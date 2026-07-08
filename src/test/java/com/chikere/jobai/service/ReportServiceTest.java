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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private PremiumReportAiService premiumReportAiService;
    private ReportRequestRepository repository;
    private RiskScoringService riskScoringService;
    private AnalyticsService analyticsService;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        premiumReportAiService = mock(PremiumReportAiService.class);
        repository = mock(ReportRequestRepository.class);
        riskScoringService = mock(RiskScoringService.class);
        analyticsService = mock(AnalyticsService.class);
        when(riskScoringService.score(any(), any(), any(), any()))
                .thenReturn(scoringResult(5.5, "Moderate"));
        reportService = newService(Runnable::run);
    }

    private ReportService newService(TaskExecutor executor) {
        ReportService service = new ReportService(
                premiumReportAiService,
                repository,
                new ReportPreviewService(),
                riskScoringService,
                analyticsService,
                new ObjectMapper(),
                executor
        );
        ReflectionTestUtils.setField(service, "expiryHours", 24L);
        ReflectionTestUtils.setField(service, "dedupeWindowMinutes", 30L);
        return service;
    }

    /** Stubs save/findById so the async completion step sees the row created at submit. */
    private AtomicReference<ReportRequest> trackSavedRow() {
        AtomicReference<ReportRequest> saved = new AtomicReference<>();
        when(repository.save(any())).thenAnswer(invocation -> {
            saved.set(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        when(repository.findById(any(UUID.class)))
                .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        return saved;
    }

    @Test
    void startReportGenerationStoresCompletedReportAndLeavesPaymentPending() {
        AtomicReference<ReportRequest> saved = trackSavedRow();
        when(premiumReportAiService.generate(any(), anyString()))
                .thenAnswer(invocation -> report(invocation.getArgument(1)));

        ReportService.SubmittedReport submitted =
                reportService.startReportGeneration(request(), "visitor-1");

        assertFalse(submitted.reused());
        ReportRequest row = saved.get();
        assertEquals(submitted.reportId(), row.getId().toString());
        assertEquals(ReportStatus.COMPLETED, row.getGenerationStatus());
        assertEquals(PaymentStatus.PENDING, row.getPaymentStatus());
        assertNotNull(row.getRequestHash());
        assertTrue(row.getReportJson().contains("Detailed premium rationale."));
        verify(premiumReportAiService).generate(any(), eq(submitted.reportId()));
        verify(analyticsService).recordReportDelivered(eq("visitor-1"), eq(submitted.reportId()), any(), anyLong());
    }

    @Test
    void startReportGenerationIgnoresClientSuppliedScoreAndRiskLevel() {
        trackSavedRow();
        when(riskScoringService.score(any(), any(), any(), any()))
                .thenReturn(scoringResult(3.2, "Low"));
        when(premiumReportAiService.generate(any(), anyString()))
                .thenAnswer(invocation -> report(invocation.getArgument(1)));

        GenerateReportRequest tampered = request();
        tampered.setScore(9.9);
        tampered.setRiskLevel("High");

        reportService.startReportGeneration(tampered, "visitor-1");

        ArgumentCaptor<GenerateReportRequest> sentToAi = ArgumentCaptor.forClass(GenerateReportRequest.class);
        verify(premiumReportAiService).generate(sentToAi.capture(), anyString());
        assertEquals(3.2, sentToAi.getValue().getScore());
        assertEquals("Low", sentToAi.getValue().getRiskLevel());
        verify(riskScoringService).score(
                JourneyType.PROFESSIONAL, "Java Developer", "Original detailed intake text", null);
    }

    @Test
    void failedGenerationMarksRowFailedAndRecordsError() {
        AtomicReference<ReportRequest> saved = trackSavedRow();
        when(premiumReportAiService.generate(any(), anyString()))
                .thenThrow(new RuntimeException("AI unavailable"));

        ReportService.SubmittedReport submitted =
                reportService.startReportGeneration(request(), "visitor-1");

        assertEquals(ReportStatus.FAILED, saved.get().getGenerationStatus());
        verify(analyticsService).recordError(eq("visitor-1"), eq("report_generation_error"),
                eq(submitted.reportId()), any());
    }

    @Test
    void saturatedExecutorRemovesRowAndSignalsCapacity() {
        trackSavedRow();
        ReportService saturated = newService(task -> {
            throw new TaskRejectedException("full");
        });

        assertThrows(ReportService.GenerationCapacityException.class,
                () -> saturated.startReportGeneration(request(), "visitor-1"));

        verify(repository).deleteById(any(UUID.class));
        verify(premiumReportAiService, never()).generate(any(), anyString());
    }

    @Test
    void identicalRequestWithinWindowReusesReportWithoutAiCall() {
        UUID existingId = UUID.randomUUID();
        ReportRequest existing = entity(existingId, PaymentStatus.PENDING, "{}");
        existing.setGenerationStatus(ReportStatus.GENERATING);
        existing.setGeneratedAt(java.time.OffsetDateTime.now().minusMinutes(2));
        when(repository.findFirstByRequestHashAndPaymentStatusNotOrderByGeneratedAtDesc(any(), any()))
                .thenReturn(Optional.of(existing));

        ReportService.SubmittedReport submitted =
                reportService.startReportGeneration(request(), "visitor-1");

        assertTrue(submitted.reused());
        assertEquals(existingId.toString(), submitted.reportId());
        verify(premiumReportAiService, never()).generate(any(), anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void identicalRequestOutsideWindowGeneratesFreshReport() {
        UUID staleId = UUID.randomUUID();
        ReportRequest stale = entity(staleId, PaymentStatus.PENDING, "{}");
        stale.setGeneratedAt(java.time.OffsetDateTime.now().minusMinutes(45));
        when(repository.findFirstByRequestHashAndPaymentStatusNotOrderByGeneratedAtDesc(any(), any()))
                .thenReturn(Optional.of(stale));
        trackSavedRow();
        when(premiumReportAiService.generate(any(), anyString()))
                .thenAnswer(invocation -> report(invocation.getArgument(1)));

        ReportService.SubmittedReport submitted =
                reportService.startReportGeneration(request(), "visitor-1");

        assertFalse(submitted.reused());
        verify(premiumReportAiService).generate(any(), anyString());
    }

    @Test
    void failedPreviousGenerationIsNotReused() {
        UUID failedId = UUID.randomUUID();
        ReportRequest failed = entity(failedId, PaymentStatus.PENDING, null);
        failed.setGenerationStatus(ReportStatus.FAILED);
        failed.setGeneratedAt(java.time.OffsetDateTime.now().minusMinutes(1));
        when(repository.findFirstByRequestHashAndPaymentStatusNotOrderByGeneratedAtDesc(any(), any()))
                .thenReturn(Optional.of(failed));
        trackSavedRow();
        when(premiumReportAiService.generate(any(), anyString()))
                .thenAnswer(invocation -> report(invocation.getArgument(1)));

        ReportService.SubmittedReport submitted =
                reportService.startReportGeneration(request(), "visitor-1");

        assertFalse(submitted.reused());
        verify(premiumReportAiService).generate(any(), anyString());
    }

    @Test
    void generationStatusReportsLifecycleAndLegacyRows() {
        UUID generating = UUID.randomUUID();
        ReportRequest generatingRow = entity(generating, PaymentStatus.PENDING, null);
        generatingRow.setGenerationStatus(ReportStatus.GENERATING);
        when(repository.findById(generating)).thenReturn(Optional.of(generatingRow));

        UUID legacy = UUID.randomUUID();
        ReportRequest legacyRow = entity(legacy, PaymentStatus.PAID, "{\"reportId\":\"x\"}");
        when(repository.findById(legacy)).thenReturn(Optional.of(legacyRow));

        Optional<ReportStatusResponse> generatingStatus = reportService.getGenerationStatus(generating.toString());
        Optional<ReportStatusResponse> legacyStatus = reportService.getGenerationStatus(legacy.toString());

        assertEquals(ReportStatus.GENERATING, generatingStatus.orElseThrow().getReportStatus());
        assertEquals(ReportStatus.COMPLETED, legacyStatus.orElseThrow().getReportStatus());
        assertEquals(PaymentStatus.PAID, legacyStatus.orElseThrow().getPaymentStatus());
    }

    @Test
    void lockedReportViewUsesPreviewAndPreservesPremiumScoreFields() throws Exception {
        UUID reportId = UUID.randomUUID();
        PremiumReport fullReport = report(reportId.toString());
        ReportRequest entity = entity(reportId, PaymentStatus.PENDING, new ObjectMapper().writeValueAsString(fullReport));

        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        Optional<ReportService.StoredReportView> view = reportService.getReportView(reportId.toString());

        assertTrue(view.isPresent());
        assertTrue(view.get().reportLocked());
        assertNull(view.get().fullReport());
        assertEquals(8.2, view.get().report().getPremiumScore());
        assertEquals("High", view.get().report().getPremiumRiskLevel());
        assertEquals("Detailed premium rationale.", view.get().report().getScoreRationale());
        assertEquals(PaymentStatus.PENDING, entity.getPaymentStatus());
    }

    @Test
    void unlockedReportViewReturnsFullReport() throws Exception {
        UUID reportId = UUID.randomUUID();
        PremiumReport fullReport = report(reportId.toString());
        ReportRequest entity = entity(reportId, PaymentStatus.PAID, new ObjectMapper().writeValueAsString(fullReport));

        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        Optional<ReportService.StoredReportView> view = reportService.getReportView(reportId.toString());

        assertTrue(view.isPresent());
        assertFalse(view.get().reportLocked());
        assertEquals(fullReport.getExecutiveSummary(), view.get().fullReport().getExecutiveSummary());
        assertEquals(PaymentStatus.PAID, entity.getPaymentStatus());
    }

    @Test
    void oldReportWithoutPremiumScoreStillReadsSafely() {
        UUID reportId = UUID.randomUUID();
        String oldReportJson = """
                {
                  "reportId": "%s",
                  "profession": "Accountant",
                  "mode": "profession",
                  "score": 5.5,
                  "riskLevel": "Moderate",
                  "executiveSummary": "Old report summary"
                }
                """.formatted(reportId);
        ReportRequest entity = entity(reportId, PaymentStatus.PENDING, oldReportJson);

        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        Optional<ReportService.StoredReportView> view = reportService.getReportView(reportId.toString());

        assertTrue(view.isPresent());
        assertNull(view.get().report().getPremiumScore());
        assertEquals(5.5, view.get().report().getScore());
        assertEquals("Moderate", view.get().report().getRiskLevel());
    }

    @Test
    void markPaidFromWebhookRejectsSessionNotAttachedToReport() {
        UUID reportId = UUID.randomUUID();
        ReportRequest entity = entity(reportId, PaymentStatus.PENDING, "{}");
        entity.setStripeSessionId("cs_attached");
        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        boolean marked = reportService.markPaidFromWebhook(reportId.toString(), "cs_foreign");

        assertFalse(marked);
        assertEquals(PaymentStatus.PENDING, entity.getPaymentStatus());
        assertEquals("cs_attached", entity.getStripeSessionId());
        verify(repository, never()).save(any());
    }

    @Test
    void markPaidFromWebhookAcceptsMatchingSession() {
        UUID reportId = UUID.randomUUID();
        ReportRequest entity = entity(reportId, PaymentStatus.PENDING, "{}");
        entity.setStripeSessionId("cs_attached");
        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        boolean marked = reportService.markPaidFromWebhook(reportId.toString(), "cs_attached");

        assertTrue(marked);
        assertEquals(PaymentStatus.PAID, entity.getPaymentStatus());
    }

    @Test
    void markPaidFromWebhookAcceptsWhenNoSessionWasAttached() {
        UUID reportId = UUID.randomUUID();
        ReportRequest entity = entity(reportId, PaymentStatus.PENDING, "{}");
        when(repository.findById(reportId)).thenReturn(Optional.of(entity));

        boolean marked = reportService.markPaidFromWebhook(reportId.toString(), "cs_new");

        assertTrue(marked);
        assertEquals(PaymentStatus.PAID, entity.getPaymentStatus());
        assertEquals("cs_new", entity.getStripeSessionId());
    }

    private RiskScoringResult scoringResult(double score, String riskLevel) {
        return new RiskScoringResult(score, riskLevel, "summary", null, score, 0.0);
    }

    private GenerateReportRequest request() {
        GenerateReportRequest request = new GenerateReportRequest();
        request.setMode("profession");
        request.setProfession("Java Developer");
        request.setDescription("Original detailed intake text");
        request.setScore(5.5);
        request.setRiskLevel("Moderate");
        return request;
    }

    private PremiumReport report(String reportId) {
        return PremiumReport.builder()
                .reportId(reportId)
                .profession("Java Developer")
                .mode("profession")
                .score(5.5)
                .riskLevel("Moderate")
                .premiumScore(8.2)
                .premiumRiskLevel("High")
                .scoreRationale("Detailed premium rationale.")
                .executiveSummary("Full report summary.")
                .build();
    }

    private ReportRequest entity(UUID reportId, PaymentStatus paymentStatus, String reportJson) {
        ReportRequest entity = new ReportRequest();
        entity.setId(reportId);
        entity.setProfession("Java Developer");
        entity.setMode("profession");
        entity.setRiskScore(5.5);
        entity.setRiskLevel("Moderate");
        entity.setPaymentStatus(paymentStatus);
        entity.setExpiresAt(java.time.OffsetDateTime.now().plusHours(1));
        entity.setReportJson(reportJson);
        return entity;
    }
}
