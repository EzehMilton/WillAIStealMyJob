package com.chikere.jobai.controller;

import com.chikere.jobai.model.AnalyticsEventRequest;
import com.chikere.jobai.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @PostMapping("/analytics/event")
    public ResponseEntity<Void> logEvent(@RequestBody AnalyticsEventRequest request) {
        if (request.getVisitorId() == null || request.getEventType() == null) {
            return ResponseEntity.badRequest().build();
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
