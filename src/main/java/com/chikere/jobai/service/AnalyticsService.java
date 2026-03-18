package com.chikere.jobai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Service
public class AnalyticsService {

    private static final Logger ANALYTICS = LoggerFactory.getLogger("ANALYTICS");

    public void record(String visitorId, String eventType, String profession, Double riskScore) {
        String id    = visitorId  != null && !visitorId.isBlank()  ? visitorId  : "unknown";
        String prof  = profession != null && !profession.isBlank() ? profession : "-";
        String score = riskScore  != null                          ? String.valueOf(riskScore) : "-";

        ANALYTICS.info("event={} visitorId={} profession=\"{}\" riskScore={} ts={}",
                eventType, id, prof, score, LocalDateTime.now(ZoneOffset.UTC));
    }

    public void record(String visitorId, String eventType) {
        record(visitorId, eventType, null, null);
    }
}
