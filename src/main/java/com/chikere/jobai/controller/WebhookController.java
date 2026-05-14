package com.chikere.jobai.controller;

import com.chikere.jobai.service.AnalyticsService;
import com.chikere.jobai.service.ReportService;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
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
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        if ("checkout.session.completed".equals(event.getType())) {
            StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);

            if (stripeObject instanceof Session session) {
                Map<String, String> meta = session.getMetadata();
                String reportId = meta != null ? meta.get("reportId") : null;
                String visitorId = meta != null ? meta.get("visitorId") : null;

                if (reportId == null || reportId.isBlank()) {
                    log.error("Webhook checkout.session.completed missing reportId in metadata — sessionId={}", session.getId());
                    return ResponseEntity.ok("received");
                }

                try {
                    reportService.markPaidFromWebhook(reportId, session.getId());
                } catch (Exception e) {
                    log.error("Failed to mark report paid — reportId={} sessionId={}", reportId, session.getId(), e);
                    return ResponseEntity.internalServerError().body("payment processing failed");
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

        } else if ("checkout.session.expired".equals(event.getType())) {
            StripeObject stripeObject = event.getDataObjectDeserializer().getObject().orElse(null);
            if (stripeObject instanceof Session session) {
                try {
                    reportService.markFailedBySessionId(session.getId());
                } catch (Exception e) {
                    log.error("Failed to mark report failed — sessionId={}", session.getId(), e);
                    return ResponseEntity.internalServerError().body("session expiry processing failed");
                }
            }
        }

        return ResponseEntity.ok("received");
    }
}
