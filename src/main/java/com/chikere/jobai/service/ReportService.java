package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.repository.ReportRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final PremiumReportAiService premiumReportAiService;
    private final ReportRequestRepository reportRequestRepository;
    private final ReportPreviewService reportPreviewService;
    private final ObjectMapper objectMapper;

    @Value("${app.report.expiry-hours:24}")
    private long expiryHours;

    @Transactional
    public StoredReport generateAndStoreReport(GenerateReportRequest request) {
        PremiumReport fullReport = generateFullReport(request);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        ReportRequest stored = new ReportRequest();
        stored.setId(UUID.fromString(fullReport.getReportId()));
        stored.setProfession(fullReport.getProfession());
        stored.setMode(fullReport.getMode());
        stored.setGeneratedAt(now);
        stored.setRiskScore(fullReport.getScore());
        stored.setRiskLevel(fullReport.getRiskLevel());
        stored.setPaymentStatus(PaymentStatus.PENDING);
        stored.setExpiresAt(now.plusHours(expiryHours));
        stored.setReportJson(writeReport(fullReport));

        reportRequestRepository.save(stored);
        log.info("Report generated and stored reportId={} expiresAt={}",
                fullReport.getReportId(), stored.getExpiresAt());

        return StoredReport.builder()
                .reportId(fullReport.getReportId())
                .profession(fullReport.getProfession())
                .paymentStatus(stored.getPaymentStatus())
                .report(fullReport)
                .expiresAt(stored.getExpiresAt())
                .build();
    }

    @Transactional(readOnly = true)
    public Optional<StoredReportView> getReportView(String reportId) {
        return reportRequestRepository.findById(parseUuid(reportId))
                .filter(report -> report.getReportJson() != null && !report.getReportJson().isBlank())
                .filter(this::isAccessible)
                .map(entity -> {
                    PremiumReport fullReport = readReport(entity.getReportJson());
                    boolean unlocked = entity.getPaymentStatus() == PaymentStatus.PAID;
                    PremiumReport renderedReport = unlocked
                            ? fullReport
                            : reportPreviewService.buildLockedPreview(fullReport);

                    return StoredReportView.builder()
                            .reportId(entity.getId().toString())
                            .profession(entity.getProfession())
                            .paymentStatus(entity.getPaymentStatus())
                            .reportLocked(!unlocked)
                            .report(renderedReport)
                            .fullReport(unlocked ? fullReport : null)
                            .expiresAt(entity.getExpiresAt())
                            .stripeSessionId(entity.getStripeSessionId())
                            .build();
                });
    }

    @Transactional(readOnly = true)
    public Optional<PremiumReport> getUnlockedReport(String reportId) {
        return getReportView(reportId)
                .filter(view -> !view.reportLocked())
                .map(StoredReportView::fullReport);
    }

    @Transactional
    public void attachStripeSession(String reportId, String stripeSessionId) {
        ReportRequest report = reportRequestRepository.findById(parseUuid(reportId))
                .filter(this::isAccessible)
                .orElseThrow(() -> new ReportNotFoundException(reportId));
        report.setStripeSessionId(stripeSessionId);
        reportRequestRepository.save(report);
    }

    @Transactional
    public void markPaidFromWebhook(String reportId, String stripeSessionId) {
        reportRequestRepository.findById(parseUuid(reportId))
                .ifPresent(report -> {
                    report.setPaymentStatus(PaymentStatus.PAID);
                    report.setStripeSessionId(stripeSessionId);
                    reportRequestRepository.save(report);
                    log.info("Payment confirmed by webhook reportId={} sessionId={}", reportId, stripeSessionId);
                });
    }

    /**
     * Called on the checkout=success redirect. If the report is still PENDING and has a Stripe
     * session attached, verify the session status directly with Stripe and mark as PAID immediately
     * rather than waiting for the webhook to arrive.
     */
    @Transactional
    public void syncPaymentStatusIfNeeded(String reportId) {
        reportRequestRepository.findById(parseUuid(reportId)).ifPresent(report -> {
            if (report.getPaymentStatus() != PaymentStatus.PENDING) return;
            if (report.getStripeSessionId() == null) return;
            try {
                Session session = Session.retrieve(report.getStripeSessionId());
                if ("paid".equals(session.getPaymentStatus())) {
                    report.setPaymentStatus(PaymentStatus.PAID);
                    reportRequestRepository.save(report);
                    log.info("Payment confirmed via direct Stripe lookup reportId={} sessionId={}",
                            reportId, report.getStripeSessionId());
                }
            } catch (StripeException e) {
                log.warn("Could not verify Stripe session for reportId={} sessionId={}: {}",
                        reportId, report.getStripeSessionId(), e.getMessage());
            }
        });
    }

    @Transactional
    public void markFailedBySessionId(String stripeSessionId) {
        reportRequestRepository.findByStripeSessionId(stripeSessionId)
                .ifPresent(report -> {
                    report.setPaymentStatus(PaymentStatus.FAILED);
                    reportRequestRepository.save(report);
                });
    }

    @Transactional
    public void purgeExpiredUnpaidReports() {
        reportRequestRepository.deleteByPaymentStatusNotAndExpiresAtBefore(
                PaymentStatus.PAID,
                OffsetDateTime.now(ZoneOffset.UTC)
        );
    }

    private boolean isAccessible(ReportRequest report) {
        return report.getPaymentStatus() == PaymentStatus.PAID
                || report.getExpiresAt() == null
                || report.getExpiresAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    }

    private PremiumReport generateFullReport(GenerateReportRequest request) {
        return premiumReportAiService.generate(request);
    }

    private String writeReport(PremiumReport report) {
        try {
            return objectMapper.writeValueAsString(report);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to persist report JSON", e);
        }
    }

    private PremiumReport readReport(String reportJson) {
        try {
            return objectMapper.readValue(reportJson, PremiumReport.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unable to read stored report JSON", e);
        }
    }

    private UUID parseUuid(String reportId) {
        try {
            return UUID.fromString(reportId);
        } catch (IllegalArgumentException ex) {
            throw new ReportNotFoundException(reportId);
        }
    }

    @Builder
    public record StoredReport(String reportId,
                               String profession,
                               PaymentStatus paymentStatus,
                               PremiumReport report,
                               OffsetDateTime expiresAt) {
    }

    @Builder
    public record StoredReportView(String reportId,
                                   String profession,
                                   PaymentStatus paymentStatus,
                                   boolean reportLocked,
                                   PremiumReport report,
                                   PremiumReport fullReport,
                                   OffsetDateTime expiresAt,
                                   String stripeSessionId) {
    }

    public static class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(String reportId) {
            super("Report not found: " + reportId);
        }
    }
}
