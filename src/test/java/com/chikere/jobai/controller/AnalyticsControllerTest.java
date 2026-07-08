package com.chikere.jobai.controller;

import com.chikere.jobai.model.AnalyticsEventRequest;
import com.chikere.jobai.service.AnalyticsRateLimiterService;
import com.chikere.jobai.service.AnalyticsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyticsControllerTest {

    private AnalyticsService analyticsService;
    private AnalyticsRateLimiterService rateLimiter;
    private AnalyticsController controller;
    private MockHttpServletRequest httpRequest;

    @BeforeEach
    void setUp() {
        analyticsService = mock(AnalyticsService.class);
        rateLimiter = mock(AnalyticsRateLimiterService.class);
        when(rateLimiter.tryConsume(anyString())).thenReturn(true);
        controller = new AnalyticsController(analyticsService, rateLimiter);
        httpRequest = new MockHttpServletRequest();
        httpRequest.setRemoteAddr("203.0.113.7");
    }

    @Test
    void knownEventTypeIsRecorded() {
        ResponseEntity<Void> response = controller.logEvent(
                request("visitor-1", "result_viewed", "Nurse", 4.2), httpRequest);

        assertEquals(200, response.getStatusCode().value());
        verify(analyticsService).record("visitor-1", "result_viewed", "Nurse", 4.2);
    }

    @Test
    void unknownEventTypeIsRejected() {
        ResponseEntity<Void> response = controller.logEvent(
                request("visitor-1", "payment_completed", null, null), httpRequest);

        assertEquals(400, response.getStatusCode().value());
        verify(analyticsService, never()).record(any(), any(), any(), any());
    }

    @Test
    void overlongVisitorIdIsRejected() {
        ResponseEntity<Void> response = controller.logEvent(
                request("v".repeat(65), "visit", null, null), httpRequest);

        assertEquals(400, response.getStatusCode().value());
        verify(analyticsService, never()).record(any(), any(), any(), any());
    }

    @Test
    void overlongProfessionIsRejected() {
        ResponseEntity<Void> response = controller.logEvent(
                request("visitor-1", "result_viewed", "x".repeat(301), 4.2), httpRequest);

        assertEquals(400, response.getStatusCode().value());
    }

    @Test
    void rateLimitedClientGets429AndNothingIsRecorded() {
        when(rateLimiter.tryConsume("203.0.113.7")).thenReturn(false);

        ResponseEntity<Void> response = controller.logEvent(
                request("visitor-1", "visit", null, null), httpRequest);

        assertEquals(429, response.getStatusCode().value());
        verify(analyticsService, never()).record(any(), any(), any(), any());
    }

    @Test
    void missingFieldsAreRejected() {
        assertEquals(400, controller.logEvent(request(null, "visit", null, null), httpRequest)
                .getStatusCode().value());
        assertEquals(400, controller.logEvent(request("visitor-1", null, null, null), httpRequest)
                .getStatusCode().value());
    }

    private AnalyticsEventRequest request(String visitorId, String eventType, String profession, Double riskScore) {
        AnalyticsEventRequest request = new AnalyticsEventRequest();
        request.setVisitorId(visitorId);
        request.setEventType(eventType);
        request.setProfession(profession);
        request.setRiskScore(riskScore);
        return request;
    }
}
