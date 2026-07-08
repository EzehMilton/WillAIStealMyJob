package com.chikere.jobai.controller;

import com.chikere.jobai.model.GenerateReportRequest;
import com.chikere.jobai.model.GenerateReportResponse;
import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.PdfService;
import com.chikere.jobai.service.ReportRateLimiterService;
import com.chikere.jobai.service.ReportService;
import io.github.bucket4j.ConsumptionProbe;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportControllerRateLimitTest {

    @Test
    void subSecondRefillWaitYieldsRetryAfterOfAtLeastOneSecond() {
        ReportRateLimiterService rateLimiter = mock(ReportRateLimiterService.class);
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(500_000_000L); // 0.5s
        when(rateLimiter.tryConsume("visitor-1")).thenReturn(probe);

        ReportController controller = new ReportController(
                mock(ReportService.class),
                mock(PdfService.class),
                mock(AnalyticsService.class),
                rateLimiter,
                Runnable::run
        );

        ResponseEntity<GenerateReportResponse> response =
                controller.generateReport(new GenerateReportRequest(), "visitor-1");

        assertEquals(429, response.getStatusCode().value());
        assertEquals("1", response.getHeaders().getFirst("Retry-After"));
    }

    @Test
    void multiSecondRefillWaitRoundsUpNotDown() {
        ReportRateLimiterService rateLimiter = mock(ReportRateLimiterService.class);
        ConsumptionProbe probe = mock(ConsumptionProbe.class);
        when(probe.isConsumed()).thenReturn(false);
        when(probe.getNanosToWaitForRefill()).thenReturn(2_100_000_000L); // 2.1s
        when(rateLimiter.tryConsume("visitor-1")).thenReturn(probe);

        ReportController controller = new ReportController(
                mock(ReportService.class),
                mock(PdfService.class),
                mock(AnalyticsService.class),
                rateLimiter,
                Runnable::run
        );

        ResponseEntity<GenerateReportResponse> response =
                controller.generateReport(new GenerateReportRequest(), "visitor-1");

        assertEquals("3", response.getHeaders().getFirst("Retry-After"));
    }
}
