package com.chikere.jobai.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client-IP rate limit for the public analytics endpoint, so a loop cannot fill the
 * disk (log lines) or the analytics_events table. Keyed by remote address, not the
 * client-supplied visitorId, which can be rotated freely.
 */
@Service
public class AnalyticsRateLimiterService {

    /** Hard cap on tracked clients; beyond this the map is reset rather than growing unbounded. */
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final int capacity;
    private final Duration refillPeriod;

    public AnalyticsRateLimiterService(
            @Value("${app.rate-limit.analytics.capacity:60}") int capacity,
            @Value("${app.rate-limit.analytics.refill-minutes:1}") int refillMinutes) {
        this.capacity = Math.max(1, capacity);
        this.refillPeriod = Duration.ofMinutes(Math.max(1, refillMinutes));
    }

    public boolean tryConsume(String clientKey) {
        if (buckets.size() > MAX_TRACKED_CLIENTS) {
            buckets.clear();
        }
        return buckets
                .computeIfAbsent(clientKey, key -> newBucket())
                .tryConsume(1);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.greedy(capacity, refillPeriod)))
                .build();
    }
}
