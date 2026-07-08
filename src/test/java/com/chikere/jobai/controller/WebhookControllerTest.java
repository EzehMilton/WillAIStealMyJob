package com.chikere.jobai.controller;

import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.ReportService;
import com.stripe.Stripe;
import com.stripe.net.Webhook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookControllerTest {

    private static final String SECRET = "whsec_test_secret";
    private static final String REPORT_ID = "3f0f4a1c-8f4c-4a8e-9d1a-1234567890ab";
    private static final String SESSION_ID = "cs_test_123";

    private ReportService reportService;
    private AnalyticsService analyticsService;
    private WebhookController controller;

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        analyticsService = mock(AnalyticsService.class);
        controller = new WebhookController(analyticsService, reportService);
        ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
    }

    @Test
    void completedSessionWithPaidStatusMarksReportPaid() throws Exception {
        when(reportService.reportExists(REPORT_ID)).thenReturn(true);
        when(reportService.markPaidFromWebhook(REPORT_ID, SESSION_ID)).thenReturn(true);
        String payload = sessionEventPayload("checkout.session.completed", "\"paid\"");

        ResponseEntity<String> response = controller.handleWebhook(payload, signatureHeader(payload));

        assertEquals(200, response.getStatusCode().value());
        verify(reportService).markPaidFromWebhook(REPORT_ID, SESSION_ID);
        verify(analyticsService).recordPaymentCompleted("visitor-1", SESSION_ID, 900L, "gbp");
    }

    @Test
    void completedSessionWithUnpaidStatusDoesNotUnlockReport() throws Exception {
        String payload = sessionEventPayload("checkout.session.completed", "\"unpaid\"");

        ResponseEntity<String> response = controller.handleWebhook(payload, signatureHeader(payload));

        assertEquals(200, response.getStatusCode().value());
        verify(reportService, never()).markPaidFromWebhook(anyString(), anyString());
        verify(analyticsService, never()).recordPaymentCompleted(any(), any(), anyLong(), any());
    }

    @Test
    void completedSessionWithoutPaymentStatusIsTreatedAsPaid() throws Exception {
        when(reportService.reportExists(REPORT_ID)).thenReturn(true);
        when(reportService.markPaidFromWebhook(REPORT_ID, SESSION_ID)).thenReturn(true);
        String payload = sessionEventPayload("checkout.session.completed", "null");

        controller.handleWebhook(payload, signatureHeader(payload));

        verify(reportService).markPaidFromWebhook(REPORT_ID, SESSION_ID);
    }

    @Test
    void asyncPaymentSucceededMarksReportPaid() throws Exception {
        when(reportService.reportExists(REPORT_ID)).thenReturn(true);
        when(reportService.markPaidFromWebhook(REPORT_ID, SESSION_ID)).thenReturn(true);
        String payload = sessionEventPayload("checkout.session.async_payment_succeeded", "\"paid\"");

        controller.handleWebhook(payload, signatureHeader(payload));

        verify(reportService).markPaidFromWebhook(REPORT_ID, SESSION_ID);
        verify(analyticsService).recordPaymentCompleted("visitor-1", SESSION_ID, 900L, "gbp");
    }

    @Test
    void asyncPaymentFailedMarksReportFailed() throws Exception {
        String payload = sessionEventPayload("checkout.session.async_payment_failed", "\"unpaid\"");

        controller.handleWebhook(payload, signatureHeader(payload));

        verify(reportService).markFailedBySessionId(SESSION_ID);
        verify(reportService, never()).markPaidFromWebhook(anyString(), anyString());
    }

    @Test
    void sessionRejectedByReportServiceSkipsAnalytics() throws Exception {
        when(reportService.reportExists(REPORT_ID)).thenReturn(true);
        when(reportService.markPaidFromWebhook(REPORT_ID, SESSION_ID)).thenReturn(false);
        String payload = sessionEventPayload("checkout.session.completed", "\"paid\"");

        ResponseEntity<String> response = controller.handleWebhook(payload, signatureHeader(payload));

        assertEquals(200, response.getStatusCode().value());
        verify(analyticsService, never()).recordPaymentCompleted(any(), any(), anyLong(), any());
    }

    @Test
    void apiVersionMismatchFallsBackToUnsafeDeserializationAndStillMarksPaid() throws Exception {
        when(reportService.reportExists(REPORT_ID)).thenReturn(true);
        when(reportService.markPaidFromWebhook(REPORT_ID, SESSION_ID)).thenReturn(true);
        String payload = sessionEventPayload("checkout.session.completed", "\"paid\"")
                .replace(Stripe.API_VERSION, "2020-08-27");

        ResponseEntity<String> response = controller.handleWebhook(payload, signatureHeader(payload));

        assertEquals(200, response.getStatusCode().value());
        verify(reportService).markPaidFromWebhook(REPORT_ID, SESSION_ID);
    }

    @Test
    void undeserializableSessionReturns500SoStripeRetries() {
        String payload = """
                {
                  "id": "evt_test_1",
                  "object": "event",
                  "api_version": "2020-08-27",
                  "type": "checkout.session.completed",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "not_a_real_stripe_object"
                    }
                  }
                }
                """.formatted(SESSION_ID);

        ResponseEntity<String> response = controller.handleWebhook(payload, signatureHeader(payload));

        assertEquals(500, response.getStatusCode().value());
        verify(reportService, never()).markPaidFromWebhook(anyString(), anyString());
    }

    @Test
    void invalidSignatureIsRejected() {
        String payload = sessionEventPayload("checkout.session.completed", "\"paid\"");

        ResponseEntity<String> response = controller.handleWebhook(payload, "t=123,v1=bogus");

        assertEquals(400, response.getStatusCode().value());
        verify(reportService, never()).markPaidFromWebhook(anyString(), anyString());
    }

    private String sessionEventPayload(String eventType, String paymentStatusJson) {
        return """
                {
                  "id": "evt_test_1",
                  "object": "event",
                  "api_version": "%s",
                  "type": "%s",
                  "data": {
                    "object": {
                      "id": "%s",
                      "object": "checkout.session",
                      "payment_status": %s,
                      "amount_total": 900,
                      "currency": "gbp",
                      "metadata": {"reportId": "%s", "visitorId": "visitor-1"}
                    }
                  }
                }
                """.formatted(Stripe.API_VERSION, eventType, SESSION_ID, paymentStatusJson, REPORT_ID);
    }

    private String signatureHeader(String payload) {
        try {
            long timestamp = Instant.now().getEpochSecond();
            String signature = Webhook.Util.computeHmacSha256(SECRET, timestamp + "." + payload);
            return "t=" + timestamp + ",v1=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign test payload", e);
        }
    }
}
