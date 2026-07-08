package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.model.ReportStatus;
import com.chikere.jobai.model.ReportStatusResponse;
import com.chikere.jobai.model.RiskScoringResult;
import com.chikere.jobai.repository.ReportRequestRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final PremiumReportAiService premiumReportAiService;
    private final ReportRequestRepository reportRequestRepository;
    private final ReportPreviewService reportPreviewService;
    private final RiskScoringService riskScoringService;
    private final AnalyticsService analyticsService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor reportTaskExecutor;

    @Value("${app.report.expiry-hours:24}")
    private long expiryHours;

    @Value("${app.report.dedupe-window-minutes:30}")
    private long dedupeWindowMinutes;

    /**
     * Creates the report row in GENERATING state and hands the long AI call to the report
     * executor, so the servlet thread returns immediately. The caller gets the reportId to
     * poll via {@link #getGenerationStatus}. Identical requests within the dedupe window are
     * answered with the existing (possibly still generating) report instead of a new AI call.
     *
     * Deliberately not @Transactional: the row must be committed before the async task starts,
     * and the AI call must never run inside a transaction (it can take minutes).
     */
    public SubmittedReport startReportGeneration(GenerateReportRequest request, String visitorId) {
        normaliseRequest(request);
        String requestHash = requestHash(visitorId, request);
        Optional<SubmittedReport> reusable = findReusableReport(requestHash);
        if (reusable.isPresent()) {
            return reusable.get();
        }

        applyServerSideScore(request);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        UUID reportId = UUID.randomUUID();

        ReportRequest stored = new ReportRequest();
        stored.setId(reportId);
        stored.setProfession(request.getProfession());
        stored.setMode(request.getMode());
        stored.setGeneratedAt(now);
        stored.setRiskScore(request.getScore());
        stored.setRiskLevel(request.getRiskLevel());
        stored.setPaymentStatus(PaymentStatus.PENDING);
        stored.setGenerationStatus(ReportStatus.GENERATING);
        stored.setExpiresAt(now.plusHours(expiryHours));
        stored.setRequestHash(requestHash);
        reportRequestRepository.save(stored);

        try {
            reportTaskExecutor.execute(() -> runGeneration(reportId, request, visitorId));
        } catch (TaskRejectedException e) {
            reportRequestRepository.deleteById(reportId);
            log.warn("Report generation rejected — executor saturated (visitorId={})", visitorId);
            throw new GenerationCapacityException();
        }

        log.info("Report generation enqueued reportId={}", reportId);
        return new SubmittedReport(reportId.toString(), false);
    }

    /**
     * Runs on the report executor. No transaction is held across the AI call; the result is
     * saved in a short write at the end.
     */
    void runGeneration(UUID reportId, GenerateReportRequest request, String visitorId) {
        long start = System.nanoTime();
        try {
            PremiumReport fullReport = premiumReportAiService.generate(request, reportId.toString());
            completeGeneration(reportId, fullReport);
            long durationMs = (System.nanoTime() - start) / 1_000_000;
            Metrics.counter("jobai.report.generation", "outcome", "completed").increment();
            analyticsService.recordReportDelivered(visitorId, reportId.toString(), request.getProfession(), durationMs);
            analyticsService.recordGenerationCompleted(visitorId, reportId.toString(), request.getProfession(),
                    fullReport.getGenerationMetrics());
        } catch (Exception e) {
            log.error("Async report generation failed reportId={}", reportId, e);
            markGenerationFailed(reportId);
            Metrics.counter("jobai.report.generation", "outcome", "failed").increment();
            analyticsService.recordError(visitorId, "report_generation_error", reportId.toString(), e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<ReportStatusResponse> getGenerationStatus(String reportId) {
        return reportRequestRepository.findById(parseUuid(reportId))
                .map(report -> new ReportStatusResponse(report.getPaymentStatus(), effectiveStatus(report)));
    }

    /** Rows created before the generationStatus column existed are COMPLETED iff they hold a report. */
    private ReportStatus effectiveStatus(ReportRequest report) {
        if (report.getGenerationStatus() != null) {
            return report.getGenerationStatus();
        }
        boolean hasReport = report.getReportJson() != null && !report.getReportJson().isBlank();
        return hasReport ? ReportStatus.COMPLETED : ReportStatus.FAILED;
    }

    private void completeGeneration(UUID reportId, PremiumReport fullReport) {
        ReportRequest stored = reportRequestRepository.findById(reportId)
                .orElseThrow(() -> new ReportNotFoundException(reportId.toString()));
        stored.setRiskScore(fullReport.getScore());
        stored.setRiskLevel(fullReport.getRiskLevel());
        stored.setGeneratedAt(OffsetDateTime.now(ZoneOffset.UTC));
        stored.setReportJson(writeReport(fullReport));
        stored.setGenerationStatus(ReportStatus.COMPLETED);
        reportRequestRepository.save(stored);
        log.info("Report generated and stored reportId={} expiresAt={}", reportId, stored.getExpiresAt());
    }

    private void markGenerationFailed(UUID reportId) {
        try {
            reportRequestRepository.findById(reportId).ifPresent(report -> {
                report.setGenerationStatus(ReportStatus.FAILED);
                reportRequestRepository.save(report);
            });
        } catch (Exception e) {
            log.error("Could not mark report generation failed reportId={}", reportId, e);
        }
    }

    /**
     * A page refresh (or back/forward) on the generating page replays the exact same request.
     * Reuse the report generated (or still generating) moments ago instead of burning a second
     * premium AI call.
     */
    private Optional<SubmittedReport> findReusableReport(String requestHash) {
        OffsetDateTime cutoff = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(dedupeWindowMinutes);
        return reportRequestRepository
                .findFirstByRequestHashAndPaymentStatusNotOrderByGeneratedAtDesc(requestHash, PaymentStatus.FAILED)
                .filter(report -> effectiveStatus(report) != ReportStatus.FAILED)
                .filter(report -> report.getGeneratedAt() != null && report.getGeneratedAt().isAfter(cutoff))
                .filter(this::isAccessible)
                .map(entity -> {
                    log.info("Reusing report for identical request within {}min window — reportId={} (no AI call)",
                            dedupeWindowMinutes, entity.getId());
                    return new SubmittedReport(entity.getId().toString(), true);
                });
    }

    private void normaliseRequest(GenerateReportRequest request) {
        request.setMode(JourneyType.fromMode(request.getMode()).legacyMode());
        request.setProfession(request.getProfession() == null || request.getProfession().isBlank()
                ? "Unknown" : request.getProfession().trim());
        request.setDescription(request.getDescription() == null ? "" : request.getDescription().trim());
    }

    private String requestHash(String visitorId, GenerateReportRequest request) {
        String material = String.join("|",
                visitorId == null ? "" : visitorId.trim(),
                JourneyType.fromMode(request.getMode()).name(),
                request.getProfession() == null ? "" : request.getProfession().trim(),
                request.getDescription() == null ? "" : request.getDescription().trim());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
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

    /**
     * Marks the report paid, but only if the webhook's session is the one our checkout flow
     * attached to the report — a signed event for a session created outside this flow (with
     * forged metadata pointing at the report) must not unlock it.
     *
     * @return true if the report was marked paid
     */
    @Transactional
    public boolean markPaidFromWebhook(String reportId, String stripeSessionId) {
        return reportRequestRepository.findById(parseUuid(reportId))
                .map(report -> {
                    String attachedSessionId = report.getStripeSessionId();
                    if (attachedSessionId != null && !attachedSessionId.equals(stripeSessionId)) {
                        log.error("Webhook session does not match the session attached at checkout — "
                                        + "reportId={} attachedSessionId={} webhookSessionId={}; not unlocking",
                                reportId, attachedSessionId, stripeSessionId);
                        return false;
                    }
                    if (attachedSessionId == null) {
                        log.warn("Report had no attached checkout session — accepting webhook session reportId={} sessionId={}",
                                reportId, stripeSessionId);
                    }
                    report.setPaymentStatus(PaymentStatus.PAID);
                    report.setStripeSessionId(stripeSessionId);
                    reportRequestRepository.save(report);
                    log.info("Payment confirmed by webhook reportId={} sessionId={}", reportId, stripeSessionId);
                    return true;
                })
                .orElse(false);
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

    @Transactional(readOnly = true)
    public boolean reportExists(String reportId) {
        try {
            return reportRequestRepository.findById(parseUuid(reportId)).isPresent();
        } catch (ReportNotFoundException e) {
            return false;
        }
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

    /**
     * The score and risk level in the request come from the browser and cannot be trusted.
     * Recompute them from the same inputs the free assessment used (journey, subject, details)
     * so the paid report always carries the server's own scoring.
     */
    private void applyServerSideScore(GenerateReportRequest request) {
        String profession = request.getProfession() == null || request.getProfession().isBlank()
                ? "Unknown" : request.getProfession().trim();
        String details = request.getDescription() == null ? "" : request.getDescription().trim();

        RiskScoringResult result = riskScoringService.score(
                JourneyType.fromMode(request.getMode()),
                profession,
                details,
                null
        );

        if (request.getScore() != result.score() || !result.riskLevel().equals(request.getRiskLevel())) {
            log.warn("Client-supplied score/riskLevel ({}/{}) differ from server recomputation ({}/{}) — using server values",
                    request.getScore(), request.getRiskLevel(), result.score(), result.riskLevel());
        }
        request.setScore(result.score());
        request.setRiskLevel(result.riskLevel());
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

    /** Result of submitting a generation request; reused=true means no new AI call was started. */
    public record SubmittedReport(String reportId, boolean reused) {
    }

    /** Thrown when the report executor is saturated — the global budget on concurrent generations. */
    public static class GenerationCapacityException extends RuntimeException {
        public GenerationCapacityException() {
            super("Report generation is at capacity, try again shortly");
        }
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
