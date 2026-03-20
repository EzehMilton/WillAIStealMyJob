package com.chikere.jobai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AnalyticsService {

    private static final Logger ANALYTICS = LoggerFactory.getLogger("ANALYTICS");
    private static final int FREE_SUMMARY_LIMIT = 3;

    private final ConcurrentHashMap<String, AtomicInteger> summaryUsage = new ConcurrentHashMap<>();

    // ── Summary generated ─────────────────────────────────────────────────────

    public void recordSummaryGenerated(String visitorId, String profession, Double riskScore, int freeUsesRemaining) {
        ANALYTICS.info("event=summary_generated visitorId={} profession=\"{}\" riskScore={} freeUsesRemaining={} ts={}",
                safe(visitorId), safe(profession, "-"),
                riskScore != null ? riskScore : "-",
                freeUsesRemaining, Instant.now());
    }

    // ── Payment completed ─────────────────────────────────────────────────────

    public void recordPaymentCompleted(String visitorId, String sessionId, Long amountCents, String currency) {
        ANALYTICS.info("event=payment_completed visitorId={} sessionId={} amount={} currency={} ts={}",
                safe(visitorId), safe(sessionId, "-"),
                amountCents != null ? amountCents : "-",
                safe(currency, "-"), Instant.now());
    }

    // ── Report delivered ──────────────────────────────────────────────────────

    public void recordReportDelivered(String visitorId, String reportId, String profession, long durationMs) {
        ANALYTICS.info("event=report_delivered visitorId={} reportId={} profession=\"{}\" durationMs={} ts={}",
                safe(visitorId), safe(reportId, "-"),
                safe(profession, "-"), durationMs, Instant.now());
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    public void recordError(String visitorId, String errorType, String reportId, String message) {
        ANALYTICS.warn("event=error visitorId={} errorType={} reportId={} message=\"{}\" ts={}",
                safe(visitorId), safe(errorType, "unknown"), safe(reportId, "-"),
                message != null ? message : "", Instant.now());
    }

    // ── Free usage tracking ───────────────────────────────────────────────────

    /** Increments this visitor's summary count and returns how many free uses are left. */
    public int incrementAndGetRemaining(String visitorId) {
        int used = summaryUsage
                .computeIfAbsent(safe(visitorId), k -> new AtomicInteger(0))
                .incrementAndGet();
        return Math.max(0, FREE_SUMMARY_LIMIT - used);
    }

    // ── Generic recorder (kept for frontend-initiated events) ─────────────────

    public void record(String visitorId, String eventType, String profession, Double riskScore) {
        ANALYTICS.info("event={} visitorId={} profession=\"{}\" riskScore={} ts={}",
                eventType, safe(visitorId), safe(profession, "-"),
                riskScore != null ? riskScore : "-", Instant.now());
    }

    public void record(String visitorId, String eventType) {
        record(visitorId, eventType, null, null);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String safe(String value) {
        return value != null && !value.isBlank() ? value.trim() : "unknown";
    }

    private String safe(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
}
