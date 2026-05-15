package com.chikere.jobai.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReportRateLimiterService {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration refillPeriod;

    public ReportRateLimiterService(
            @Value("${app.rate-limit.report.capacity:5}") int capacity,
            @Value("${app.rate-limit.report.refill-hours:1}") int refillHours) {
        this.capacity = capacity;
        this.refillPeriod = Duration.ofHours(refillHours);
    }

    public ConsumptionProbe tryConsume(String visitorId) {
        return buckets
                .computeIfAbsent(visitorId, id -> newBucket())
                .tryConsumeAndReturnRemaining(1);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, refillPeriod)))
                .build();
    }
}
