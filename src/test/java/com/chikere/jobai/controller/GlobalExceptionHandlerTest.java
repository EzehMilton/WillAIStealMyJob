package com.chikere.jobai.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.ModelAndView;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void browserRequestGetsErrorPage() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/report/some-id");
        request.addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");

        Object result = handler.handleUnexpected(new IllegalStateException("boom"), request);

        ModelAndView view = assertInstanceOf(ModelAndView.class, result);
        assertEquals("error", view.getViewName());
        assertEquals(500, view.getStatus().value());
    }

    @Test
    void fetchRequestKeepsJsonErrorBody() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/generate-report");
        request.addHeader("Accept", "*/*");

        Object result = handler.handleUnexpected(new IllegalStateException("boom"), request);

        ResponseEntity<?> response = assertInstanceOf(ResponseEntity.class, result);
        assertEquals(500, response.getStatusCode().value());
        assertTrue(response.getBody().toString().contains("An unexpected error occurred"));
    }

    @Test
    void missingAcceptHeaderDefaultsToJson() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/analytics/event");

        Object result = handler.handleUnexpected(new RuntimeException("boom"), request);

        assertInstanceOf(ResponseEntity.class, result);
    }
}
