package com.chikere.jobai.service;

import com.chikere.jobai.model.AnalyticsEvent;
import com.chikere.jobai.model.GenerationMetrics;
import com.chikere.jobai.repository.AnalyticsEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.Consumer;

/**
 * Records funnel/revenue events to the analytics_events table (queryable source of truth)
 * and mirrors them to the ANALYTICS logger for tailing. Persistence failures are logged
 * and swallowed — analytics must never break the user flow.
 */
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final Logger ANALYTICS = LoggerFactory.getLogger("ANALYTICS");
    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    private final AnalyticsEventRepository analyticsEventRepository;

    // ── Summary generated ─────────────────────────────────────────────────────

    public void recordSummaryGenerated(String visitorId, String profession, Double riskScore) {
        ANALYTICS.info("event=summary_generated visitorId={} profession=\"{}\" riskScore={} ts={}",
                safe(visitorId), safe(profession, "-"),
                riskScore != null ? riskScore : "-",
                Instant.now());
        persist("summary_generated", event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setProfession(truncate(profession, 300));
            event.setRiskScore(riskScore);
        });
    }

    // ── Payment completed ─────────────────────────────────────────────────────

    public void recordPaymentCompleted(String visitorId, String sessionId, Long amountCents, String currency) {
        ANALYTICS.info("event=payment_completed visitorId={} sessionId={} amount={} currency={} ts={}",
                safe(visitorId), safe(sessionId, "-"),
                amountCents != null ? amountCents : "-",
                safe(currency, "-"), Instant.now());
        persist("payment_completed", event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setAmountCents(amountCents);
            event.setCurrency(truncate(currency, 10));
            event.setDetail(truncate("sessionId=" + sessionId, 500));
        });
    }

    // ── Report delivered ──────────────────────────────────────────────────────

    public void recordReportDelivered(String visitorId, String reportId, String profession, long durationMs) {
        ANALYTICS.info("event=report_delivered visitorId={} reportId={} profession=\"{}\" durationMs={} ts={}",
                safe(visitorId), safe(reportId, "-"),
                safe(profession, "-"), durationMs, Instant.now());
        persist("report_delivered", event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setReportId(truncate(reportId, 40));
            event.setProfession(truncate(profession, 300));
            event.setDurationMs(durationMs);
        });
    }

    public void recordGenerationCompleted(String visitorId, String reportId, String profession, GenerationMetrics metrics) {
        if (metrics == null) {
            return;
        }

        ANALYTICS.info(
                "event=generation_completed visitorId={} reportType=\"{}\" reportId={} profession=\"{}\" model={} durationMs={} promptTokens={} completionTokens={} totalTokens={} estimatedCostUsd={} ts={}",
                safe(visitorId),
                safe(metrics.getReportType(), "-"),
                safe(reportId, "-"),
                safe(profession, "-"),
                safe(metrics.getModel(), "-"),
                metrics.getDurationMs(),
                metrics.getPromptTokens(),
                metrics.getCompletionTokens(),
                metrics.getTotalTokens(),
                metrics.getEstimatedCostUsdLabel(),
                Instant.now()
        );
        persist("generation_completed", event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setReportId(truncate(reportId, 40));
            event.setProfession(truncate(profession, 300));
            event.setDurationMs(metrics.getDurationMs());
            event.setDetail(truncate("reportType=" + metrics.getReportType()
                    + " model=" + metrics.getModel()
                    + " totalTokens=" + metrics.getTotalTokens()
                    + " estimatedCostUsd=" + metrics.getEstimatedCostUsdLabel(), 500));
        });
    }

    // ── Error ─────────────────────────────────────────────────────────────────

    public void recordError(String visitorId, String errorType, String reportId, String message) {
        ANALYTICS.warn("event=error visitorId={} errorType={} reportId={} message=\"{}\" ts={}",
                safe(visitorId), safe(errorType, "unknown"), safe(reportId, "-"),
                safe(message, ""), Instant.now());
        persist("error", event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setReportId(truncate(reportId, 40));
            event.setDetail(truncate(safe(errorType, "unknown") + ": " + (message != null ? message : ""), 500));
        });
    }

    // ── Generic recorder (kept for frontend-initiated events) ─────────────────

    public void record(String visitorId, String eventType, String profession, Double riskScore) {
        ANALYTICS.info("event={} visitorId={} profession=\"{}\" riskScore={} ts={}",
                safe(eventType, "unknown"), safe(visitorId), safe(profession, "-"),
                riskScore != null ? riskScore : "-", Instant.now());
        persist(safeEventType(eventType), event -> {
            event.setVisitorId(truncate(visitorId, 100));
            event.setProfession(truncate(profession, 300));
            event.setRiskScore(riskScore);
        });
    }

    public void record(String visitorId, String eventType) {
        record(visitorId, eventType, null, null);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void persist(String eventType, Consumer<AnalyticsEvent> populate) {
        try {
            AnalyticsEvent event = new AnalyticsEvent();
            event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
            event.setEventType(eventType);
            populate.accept(event);
            analyticsEventRepository.save(event);
        } catch (Exception e) {
            log.warn("Could not persist analytics event type={}: {}", eventType, e.getMessage());
        }
    }

    /** Frontend-supplied event types are constrained to a safe charset and length for the table. */
    private String safeEventType(String eventType) {
        String cleaned = safe(eventType, "unknown").replaceAll("[^a-zA-Z0-9_.-]", "_");
        return truncate(cleaned.isBlank() ? "unknown" : cleaned, 40);
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private String safe(String value) {
        return safe(value, "unknown");
    }

    private String safe(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return truncate(sanitizeForLog(value), 300);
    }

    /**
     * The ANALYTICS log lines are parsed as key=value pairs; user-supplied text must not be
     * able to forge fields (=, ") or start new lines (CR/LF, control chars).
     */
    private String sanitizeForLog(String value) {
        return value.replaceAll("[=\"\\p{Cntrl}]", "_");
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
