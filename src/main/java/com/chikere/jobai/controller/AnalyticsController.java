package com.chikere.jobai.controller;

import com.chikere.jobai.model.AnalyticsEventRequest;
import com.chikere.jobai.service.AnalyticsRateLimiterService;
import com.chikere.jobai.service.AnalyticsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    /** The funnel events the frontend actually sends; anything else is rejected. */
    static final Set<String> ALLOWED_EVENT_TYPES = Set.of(
            "visit",
            "landing_page_view",
            "summary_requested",
            "result_viewed",
            "result_exited_without_payment",
            "premium_report_generation_started"
    );

    private static final int MAX_VISITOR_ID_LENGTH = 64;   // UUIDs are 36 chars
    private static final int MAX_PROFESSION_LENGTH = 300;

    private final AnalyticsService analyticsService;
    private final AnalyticsRateLimiterService rateLimiter;

    @PostMapping("/analytics/event")
    public ResponseEntity<Void> logEvent(@RequestBody AnalyticsEventRequest request,
                                         HttpServletRequest httpRequest) {
        if (request.getVisitorId() == null || request.getEventType() == null) {
            return ResponseEntity.badRequest().build();
        }
        if (!ALLOWED_EVENT_TYPES.contains(request.getEventType())) {
            return ResponseEntity.badRequest().build();
        }
        if (request.getVisitorId().length() > MAX_VISITOR_ID_LENGTH
                || (request.getProfession() != null && request.getProfession().length() > MAX_PROFESSION_LENGTH)) {
            return ResponseEntity.badRequest().build();
        }
        if (!rateLimiter.tryConsume(httpRequest.getRemoteAddr())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }

        analyticsService.record(
                request.getVisitorId(),
                request.getEventType(),
                request.getProfession(),
                request.getRiskScore()
        );
        return ResponseEntity.ok().build();
    }
}
