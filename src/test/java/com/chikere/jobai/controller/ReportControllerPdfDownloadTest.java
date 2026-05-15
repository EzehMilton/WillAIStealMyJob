package com.chikere.jobai.controller;

import com.chikere.jobai.model.PremiumReport;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.PdfService;
import com.chikere.jobai.service.ReportRateLimiterService;
import com.chikere.jobai.service.ReportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ReportControllerPdfDownloadTest {

    @Test
    void downloadReportSchedulesPdfRenderingOffRequestThread() throws Exception {
        ReportService reportService = mock(ReportService.class);
        PdfService pdfService = mock(PdfService.class);
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        ReportRateLimiterService rateLimiter = mock(ReportRateLimiterService.class);
        CapturingExecutor pdfExecutor = new CapturingExecutor();

        String reportId = "8f4f10fa-cfff-4657-83bb-9d87f9e33513";
        PremiumReport report = PremiumReport.builder()
                .reportId(reportId)
                .profession("Java Developer")
                .build();
        byte[] pdfBytes = "pdf".getBytes();

        when(reportService.getUnlockedReport(reportId)).thenReturn(Optional.of(report));
        when(pdfService.generateReportPdf(report)).thenReturn(pdfBytes);

        ReportController controller = new ReportController(
                reportService,
                pdfService,
                analyticsService,
                rateLimiter,
                pdfExecutor
        );

        CompletableFuture<ResponseEntity<byte[]>> responseFuture = controller.downloadReport(reportId);

        assertFalse(responseFuture.isDone());
        assertNotNull(pdfExecutor.command());
        verifyNoInteractions(pdfService);

        pdfExecutor.command().run();

        assertTrue(responseFuture.isDone());
        ResponseEntity<byte[]> response = responseFuture.get();
        assertArrayEquals(pdfBytes, response.getBody());
        verify(pdfService).generateReportPdf(report);
    }

    private static class CapturingExecutor implements Executor {
        private final AtomicReference<Runnable> command = new AtomicReference<>();

        @Override
        public void execute(Runnable command) {
            this.command.set(command);
        }

        Runnable command() {
            return command.get();
        }
    }
}
