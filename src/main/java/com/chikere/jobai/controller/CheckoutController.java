package com.chikere.jobai.controller;

import com.chikere.jobai.model.CheckoutResponse;
import com.chikere.jobai.service.ReportService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final ReportService reportService;

    @Value("${stripe.secret-key}")
    private String stripeSecretKey;

    @Value("${stripe.price-id.profession}")
    private String professionPriceId;

    @Value("${stripe.price-id.course}")
    private String coursePriceId;

    @Value("${app.base-url}")
    private String baseUrl;

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeSecretKey;
    }

    @PostMapping("/api/report/{reportId}/checkout-session")
    public ResponseEntity<CheckoutResponse> createCheckoutSession(
            @PathVariable String reportId,
            @RequestHeader(value = "X-Visitor-Id", defaultValue = "unknown") String visitorId) {
        try {
            ReportService.StoredReportView reportView = reportService.getReportView(reportId)
                    .orElseThrow(() -> new ReportService.ReportNotFoundException(reportId));

            if (!reportView.reportLocked()) {
                return ResponseEntity.ok(new CheckoutResponse(baseUrl + "/report/" + reportId));
            }

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(baseUrl + "/report/" + reportId + "?checkout=success")
                    .setCancelUrl(baseUrl + "/report/" + reportId + "?checkout=cancelled")
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setPrice(resolvePriceId(reportView.report().getMode()))
                                    .setQuantity(1L)
                                    .build()
                    )
                    .putMetadata("visitorId", safe(visitorId))
                    .putMetadata("reportId", reportId)
                    .putMetadata("profession", truncate(reportView.profession(), 490))
                    .build();

            Session session = Session.create(params);
            reportService.attachStripeSession(reportId, session.getId());
            log.info("Checkout session created reportId={} sessionId={}", reportId, session.getId());
            return ResponseEntity.ok(new CheckoutResponse(session.getUrl()));
        } catch (ReportService.ReportNotFoundException e) {
            return ResponseEntity.notFound().build();
        } catch (StripeException e) {
            log.error("Stripe checkout session creation failed reportId={}", reportId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String resolvePriceId(String mode) {
        return "course".equals(mode) ? coursePriceId : professionPriceId;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
