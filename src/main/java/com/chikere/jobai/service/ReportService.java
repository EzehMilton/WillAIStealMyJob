package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.model.PremiumReport.*;
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

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReportService {

    private final PremiumReportAiService premiumReportAiService;
    private final GenerationMetricsService generationMetricsService;
    private final ReportRequestRepository reportRequestRepository;
    private final ReportPreviewService reportPreviewService;
    private final ObjectMapper objectMapper;

    @Value("${app.ai.use-dummy:false}")
    private boolean useDummyMode;

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
        log.info("Report generated and stored reportId={} profession={} expiresAt={}",
                fullReport.getReportId(), fullReport.getProfession(), stored.getExpiresAt());

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
        long generationStart = System.nanoTime();
        PremiumReport report;
        if (useDummyMode) {
            report = buildMockReport(UUID.randomUUID().toString(), request);
            report.setGenerationMetrics(generationMetricsService.forLocalGeneration(
                    "Full Report",
                    (System.nanoTime() - generationStart) / 1_000_000
            ));
        } else {
            report = premiumReportAiService.generate(request);
        }
        return report;
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

    // -------------------------------------------------------------------------
    // Mock builder — retained for local dummy mode
    // -------------------------------------------------------------------------

    private PremiumReport buildMockReport(String reportId, GenerateReportRequest req) {
        String p = normalise(req.getProfession());
        String rl = normalise(req.getRiskLevel());
        boolean high = "High".equalsIgnoreCase(rl);
        boolean low  = "Low".equalsIgnoreCase(rl);

        return PremiumReport.builder()
                .reportId(reportId)
                .profession(p)
                .mode(req.getMode())
                .score(req.getScore())
                .riskLevel(rl.isEmpty() ? "Moderate" : rl)
                .generatedAt(LocalDateTime.now())
                .overallExposure(rl.isEmpty() ? "Moderate" : rl)
                .mostExposedArea(high ? "Routine and repetitive tasks" : "Data processing and reporting")
                .safestDirection(low ? "Strategic and leadership roles" : "Specialisation and leadership")
                .coreAdvice(high ? "Upskill urgently and reposition" : low ? "Stay current and deepen expertise" : "Move up the value chain")
                .executiveSummary(executiveSummary(p, rl, req.getScore()))
                .taskExposureMap(taskRows(rl))
                .aiStrengths(aiStrengths())
                .humanAdvantages(humanAdvantages())
                .careerLevelAnalysis(careerLevels())
                .trackComparison(tracks())
                .adjacentRoles(adjacentRoles())
                .thirtyDayPlan(thirtyDayPlan())
                .ninetyDayPlan(ninetyDayPlan())
                .yearPlan(yearPlan())
                .warningSigns(warningSigns())
                .resilienceScorecard(resilienceScorecard())
                .positioningAdvice(positioningAdvice(p, rl))
                .build();
    }

    private String executiveSummary(String profession, String riskLevel, double score) {
        String opening = switch (riskLevel.toLowerCase()) {
            case "high" -> profession + " faces significant AI exposure. A substantial portion of day-to-day work involves predictable, rule-based tasks that AI systems are increasingly capable of handling at scale.";
            case "low"  -> profession + " carries relatively low AI exposure. The core activities of this role rely heavily on human judgement, contextual reasoning, and interpersonal skills that remain difficult to automate.";
            default     -> profession + " faces moderate AI exposure. Some routine tasks are under automation pressure, while higher-value work involving judgement, creativity, and stakeholder relationships remains resilient.";
        };

        String advice = switch (riskLevel.toLowerCase()) {
            case "high" -> " Professionals in this field should act now: move toward higher-complexity work, develop specialist depth, and treat AI tools as productivity amplifiers rather than threats.";
            case "low"  -> " The priority is to stay current with AI tools that enhance output, build on specialist and strategic depth, and maintain the human-centric skills that define this role's long-term value.";
            default     -> " The opportunity lies in repositioning away from routine delivery and toward ownership, strategy, and the kind of judgment-rich work that AI struggles to replicate consistently.";
        };

        return opening
                + " The AI risk score for this role is " + String.format("%.1f", score) + " out of 10."
                + advice;
    }

    private List<TaskRow> taskRows(String riskLevel) {
        boolean high = "High".equalsIgnoreCase(riskLevel);
        boolean low  = "Low".equalsIgnoreCase(riskLevel);
        return List.of(
                new TaskRow("Routine and repetitive tasks", high ? 84 : low ? 26 : 61, high ? "High" : low ? "Low" : "Moderate", high ? "Now" : low ? "3–6 yrs" : "1–3 yrs", "Predictable, rule-based workflows are the first area AI tools compress at scale."),
                new TaskRow("Data processing and reporting", high ? 76 : low ? 30 : 56, high ? "High" : low ? "Low" : "Moderate", high ? "Now" : low ? "3–6 yrs" : "1–3 yrs", "AI excels at aggregating structured data and drafting standard reports."),
                new TaskRow("Research and information gathering", high ? 63 : low ? 28 : 48, high ? "Moderate" : low ? "Low" : "Moderate", high ? "1–3 yrs" : low ? "3–6 yrs" : "1–3 yrs", "AI can surface and summarise information quickly, reducing manual search time."),
                new TaskRow("Analysis and problem-solving", high ? 52 : low ? 25 : 43, high ? "Moderate" : low ? "Low" : "Moderate", high ? "1–3 yrs" : low ? "5–10 yrs" : "3–6 yrs", "Pattern recognition assists analysis, but complex judgement still requires human experience."),
                new TaskRow("Communication and stakeholder work", high ? 34 : low ? 14 : 29, high ? "Low" : low ? "Very Low" : "Low", high ? "3–6 yrs" : low ? "10+ yrs" : "5–10 yrs", "Relationship management, negotiation, and nuanced communication remain human-led."),
                new TaskRow("Strategic planning and decisions", high ? 24 : low ? 12 : 20, high ? "Low" : low ? "Very Low" : "Low", high ? "5–10 yrs" : low ? "10+ yrs" : "5–10 yrs", "High-stakes decisions with incomplete information continue to rely on human experience."),
                new TaskRow("Leadership and team management", 10, "Very Low", "10+ yrs", "People leadership, coaching, and organisational influence are not automation targets.")
        );
    }

    private List<String> aiStrengths() {
        return List.of(
                "Generate first drafts of standard documents, summaries, and structured outputs.",
                "Process and cross-reference large volumes of data faster than any human team.",
                "Identify patterns in historical data to surface predictions and anomalies.",
                "Automate repetitive multi-step workflows with high consistency and zero fatigue.",
                "Translate between formats, languages, and information structures at speed."
        );
    }

    private List<String> humanAdvantages() {
        return List.of(
                "Owning the consequences of decisions over the long term.",
                "Understanding hidden organisational context, undocumented assumptions, and political dynamics.",
                "Balancing competing priorities across stakeholders with differing goals.",
                "Making judgement calls when information is ambiguous, incomplete, or emotionally charged.",
                "Building trust, influence, and professional relationships that compound over a career."
        );
    }

    private List<CareerLevelRow> careerLevels() {
        return List.of(
                new CareerLevelRow("Early-career / Junior", "Moderate–High", "Most exposed when work is task-based, closely specified, and repetitive."),
                new CareerLevelRow("Mid-level", "Moderate", "Safer when moving beyond delivery into ownership, analysis, and independent judgement."),
                new CareerLevelRow("Senior", "Low–Moderate", "Resilient when responsible for strategy, trade-offs, stakeholder alignment, and quality standards."),
                new CareerLevelRow("Lead / Principal / Specialist", "Low", "Work is rooted in deep expertise, influence, and long-term accountability."),
                new CareerLevelRow("Manager / Director", "Low", "Leadership, prioritisation, people development, and delivery coordination remain human-dominant.")
        );
    }

    private List<TrackRow> tracks() {
        return List.of(
                new TrackRow("High-volume transactional work", "High", "Large portions of routine throughput can be compressed or partially automated."),
                new TrackRow("Reporting and compliance", "Moderate–High", "AI can generate standard reports and flag compliance issues from structured data."),
                new TrackRow("Client-facing advisory roles", "Moderate", "Still valuable, but commodity advice is increasingly assisted by AI tools."),
                new TrackRow("Operations and process management", "Moderate", "Routine coordination is pressured; complex operational judgement remains valuable."),
                new TrackRow("Specialist / expert tracks", "Low", "Deep domain expertise and independent judgement are resilient to automation."),
                new TrackRow("Strategic and leadership tracks", "Low", "Direction-setting, resource allocation, and people leadership are not automation targets."),
                new TrackRow("AI-adjacent roles", "Low", "Roles that leverage AI as a core tool are likely to grow in demand.")
        );
    }

    private List<TransitionRow> adjacentRoles() {
        return List.of(
                new TransitionRow("AI Strategy Specialist", "Higher than baseline", "High", "Medium", "Works at the intersection of AI capability and business application — a growing category.", List.of("AI workflow design", "Change leadership", "Business strategy")),
                new TransitionRow("Operations Director", "Higher than baseline", "High", "Medium–High", "Oversees systems, people, and delivery at a level that requires human leadership.", List.of("Process optimisation", "Stakeholder management", "Decision-making")),
                new TransitionRow("Senior Consultant", "Higher than baseline", "High", "Medium", "Advice-driven roles at senior level rely on trust, context, and judgement.", List.of("Client advisory", "Domain expertise", "Problem structuring")),
                new TransitionRow("Product or Programme Lead", "Higher than baseline", "High", "Medium", "Ownership of complex, multi-stakeholder initiatives is strongly human-led.", List.of("Roadmapping", "Cross-functional leadership", "Delivery governance")),
                new TransitionRow("People and Org Lead", "Higher than baseline", "Very High", "Medium", "Talent development, culture, and leadership remain very difficult to automate.", List.of("Coaching", "Organisational design", "Talent strategy"))
        );
    }

    private List<String> thirtyDayPlan() {
        return List.of(
                "Audit your current work. Estimate what percentage is routine and rule-based versus strategic, interpersonal, or judgement-heavy.",
                "Start using AI tools deliberately in your daily workflow — document where they genuinely save time and where they still need heavy oversight.",
                "Update your CV and professional profile to emphasise outcomes, ownership, and problem-solving — not just tasks completed."
        );
    }

    private List<String> ninetyDayPlan() {
        return List.of(
                "Deepen one defensible area: specialist domain knowledge, stakeholder influence, AI tool fluency, or strategic planning.",
                "Build a small portfolio of work that proves higher-value capability beyond routine delivery.",
                "Identify a mentor, manager, or peer group that can help reposition your role toward more resilient work."
        );
    }

    private List<String> yearPlan() {
        return List.of(
                "Shift your trajectory toward roles that compound judgement, influence, and accountability.",
                "Treat AI literacy as core career infrastructure rather than an optional extra.",
                "Review your positioning every quarter and keep moving away from commodity work."
        );
    }

    private List<String> warningSigns() {
        return List.of(
                "Your role is evaluated mostly on volume, speed, and throughput.",
                "Large portions of your week are repetitive, rules-based, or template-driven.",
                "Your value is described more in tasks than outcomes, judgement, or ownership."
        );
    }

    private List<ResilienceRow> resilienceScorecard() {
        return List.of(
                new ResilienceRow("Judgement", "Medium"),
                new ResilienceRow("Stakeholder complexity", "High"),
                new ResilienceRow("Routine workload", "High")
        );
    }

    private String positioningAdvice(String profession, String riskLevel) {
        return profession + " professionals should reduce dependency on repeatable execution and build leverage around judgement, domain context, and stakeholder trust. Current risk level: " + riskLevel + ".";
    }

    private String normalise(String value) {
        return value == null ? "" : value.trim();
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
                                   OffsetDateTime expiresAt) {
    }

    public static class ReportNotFoundException extends RuntimeException {
        public ReportNotFoundException(String reportId) {
            super("Report not found: " + reportId);
        }
    }
}
