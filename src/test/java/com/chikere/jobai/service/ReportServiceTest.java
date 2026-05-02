package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.PaymentStatus;
import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.model.ReportRequest;
import com.chikere.jobai.repository.ReportRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReportServiceTest {

    private PremiumReportAiService premiumReportAiService;
    private ReportRequestRepository repository;
    private ReportService reportService;

    @BeforeEach
    void setUp() {
        premiumReportAiService = mock(PremiumReportAiService.class);
        repository = mock(ReportRequestRepository.class);
        reportService = new ReportService(
                premiumReportAiService,
                repository,
                new ReportPreviewService(),
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(reportService, "expiryHours", 24L);
    }

    @Test
    void generateAndStoreReportStoresReportJsonAndLeavesPaymentPending() {
        UUID reportId = UUID.randomUUID();
        PremiumReport report = report(reportId.toString());
        AtomicReference<ReportRequest> saved = new AtomicReference<>();

        when(premiumReportAiService.generate(any())).thenReturn(report);
        when(repository.save(any())).thenAnswer(invocation -> {
            ReportRequest entity = invocation.getArgument(0);
            saved.set(entity);
            return entity;
        });

        ReportService.StoredReport stored = reportService.generateAndStoreReport(request());

        assertEquals(reportId.toString(), stored.reportId());
        assertEquals(PaymentStatus.PENDING, stored.paymentStatus());
        assertNotNull(saved.get().getReportJson());
        assertTrue(saved.get().getReportJson().contains("Detailed premium rationale."));
        assertEquals(PaymentStatus.PENDING, saved.get().getPaymentStatus());
        verify(premiumReportAiService).generate(any());
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
