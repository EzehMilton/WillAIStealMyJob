package com.chikere.jobai.controller;

import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.ReportService;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    @Value("${stripe.webhook-secret}")
    private String webhookSecret;

    private final AnalyticsService analyticsService;
    private final ReportService reportService;

    @PostMapping("/stripe/webhook")
    public ResponseEntity<String> handleWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String sigHeader) {

        Event event;
        try {
            event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (SignatureVerificationException e) {
            log.warn("Invalid Stripe webhook signature: {}", e.getMessage());
            Metrics.counter("jobai.webhook.failure", "reason", "invalid_signature").increment();
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        return switch (event.getType()) {
            case "checkout.session.completed", "checkout.session.async_payment_succeeded" ->
                    handleSessionPaid(event);
            case "checkout.session.expired", "checkout.session.async_payment_failed" ->
                    handleSessionFailed(event);
            default -> ResponseEntity.ok("received");
        };
    }

    private ResponseEntity<String> handleSessionPaid(Event event) {
        Session session = deserializeSession(event);
        if (session == null) {
            // Already logged loudly; 500 makes Stripe retry and surface the failing endpoint
            // in its dashboard instead of the payment being received but never applied.
            return ResponseEntity.internalServerError().body("event deserialization failed");
        }
        // Async payment methods (e.g. bank debits) complete the session before the money
        // settles — only async_payment_succeeded/failed decides those. Older API versions
        // omit payment_status; treat absent as paid, matching syncPaymentStatusIfNeeded.
        String paymentStatus = session.getPaymentStatus();
        if (paymentStatus != null && !"paid".equals(paymentStatus)) {
            log.info("Webhook {} with paymentStatus={} — awaiting payment settlement sessionId={}",
                    event.getType(), paymentStatus, session.getId());
            return ResponseEntity.ok("received");
        }

        Map<String, String> meta = session.getMetadata();
        String reportId = meta != null ? meta.get("reportId") : null;
        String visitorId = meta != null ? meta.get("visitorId") : null;

        if (reportId == null || reportId.isBlank()) {
            log.error("Webhook {} missing reportId in metadata — sessionId={}", event.getType(), session.getId());
            return ResponseEntity.ok("received");
        }

        if (!reportService.reportExists(reportId)) {
            log.error("Webhook references unknown or expired reportId={} sessionId={}", reportId, session.getId());
            return ResponseEntity.ok("received");
        }

        boolean marked;
        try {
            marked = reportService.markPaidFromWebhook(reportId, session.getId());
        } catch (Exception e) {
            log.error("Failed to mark report paid — reportId={} sessionId={}", reportId, session.getId(), e);
            Metrics.counter("jobai.webhook.failure", "reason", "processing_error").increment();
            return ResponseEntity.internalServerError().body("payment processing failed");
        }

        if (!marked) {
            Metrics.counter("jobai.webhook.failure", "reason", "session_mismatch").increment();
        }

        if (marked) {
            Metrics.counter("jobai.payment.completed").increment();
            if (session.getAmountTotal() != null) {
                Metrics.summary("jobai.payment.amount.cents").record(session.getAmountTotal());
            }
            try {
                analyticsService.recordPaymentCompleted(
                        visitorId,
                        session.getId(),
                        session.getAmountTotal(),
                        session.getCurrency()
                );
            } catch (Exception e) {
                log.warn("Analytics recordPaymentCompleted failed — sessionId={}", session.getId(), e);
            }
        }

        return ResponseEntity.ok("received");
    }

    private ResponseEntity<String> handleSessionFailed(Event event) {
        Session session = deserializeSession(event);
        if (session == null) {
            return ResponseEntity.internalServerError().body("event deserialization failed");
        }
        try {
            reportService.markFailedBySessionId(session.getId());
        } catch (Exception e) {
            log.error("Failed to mark report failed — sessionId={}", session.getId(), e);
            return ResponseEntity.internalServerError().body("session expiry processing failed");
        }
        return ResponseEntity.ok("received");
    }

    /**
     * Stripe's typed deserializer returns empty whenever the event's API version differs from
     * the SDK's pinned version — a silent no-op that would leave a received payment unapplied.
     * Fall back to the version-tolerant parse and log loudly if even that fails.
     */
    private Session deserializeSession(Event event) {
        StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);

        if (stripeObject == null) {
            try {
                stripeObject = event.getDataObjectDeserializer().deserializeUnsafe();
                log.warn("Stripe event {} eventId={} deserialized via fallback — event apiVersion={} differs from SDK",
                        event.getType(), event.getId(), event.getApiVersion());
            } catch (EventDataObjectDeserializationException e) {
                log.error("PAYMENT EVENT DROPPED — cannot deserialize Stripe event {} eventId={} apiVersion={}: {}",
                        event.getType(), event.getId(), event.getApiVersion(), e.getMessage());
                Metrics.counter("jobai.webhook.failure", "reason", "deserialization").increment();
                return null;
            }
        }

        if (stripeObject instanceof Session session) {
            return session;
        }
        log.error("PAYMENT EVENT DROPPED — Stripe event {} eventId={} did not contain a checkout Session (got {})",
                event.getType(), event.getId(), stripeObject.getClass().getSimpleName());
        Metrics.counter("jobai.webhook.failure", "reason", "deserialization").increment();
        return null;
    }
}
