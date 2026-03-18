package com.chikere.jobai.controller;

import com.chikere.jobai.model.CheckoutRequest;
import com.chikere.jobai.model.CheckoutResponse;
import com.chikere.jobai.service.AnalyticsService;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.checkout.Session;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
@Slf4j
public class CheckoutController {

    private final AnalyticsService analyticsService;

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

    @PostMapping("/create-checkout-session")
    public ResponseEntity<CheckoutResponse> createCheckoutSession(
            @RequestBody CheckoutRequest request,
            @RequestHeader(value = "X-Visitor-Id", defaultValue = "unknown") String visitorId) {
        try {
            String priceId = "course".equals(request.getMode()) ? coursePriceId : professionPriceId;

            SessionCreateParams params = SessionCreateParams.builder()
                    .setMode(SessionCreateParams.Mode.PAYMENT)
                    .setSuccessUrl(baseUrl + "/generating-report?session_id={CHECKOUT_SESSION_ID}")
                    .setCancelUrl(baseUrl + "/result")
                    .addLineItem(
                            SessionCreateParams.LineItem.builder()
                                    .setPrice(priceId)
                                    .setQuantity(1L)
                                    .build()
                    )
                    .putMetadata("visitorId", safe(visitorId))
                    .putMetadata("mode", safe(request.getMode()))
                    .putMetadata("profession", truncate(request.getProfession(), 490))
                    .putMetadata("score", String.valueOf(request.getScore()))
                    .putMetadata("risk_level", safe(request.getRiskLevel()))
                    .putMetadata("summary", truncate(request.getSummary(), 490))
                    .build();

            Session session = Session.create(params);
            return ResponseEntity.ok(new CheckoutResponse(session.getUrl()));

        } catch (StripeException e) {
            log.error("Stripe checkout session creation failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private String truncate(String value, int maxLength) {
        if (value == null) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}