package com.chikere.jobai.service;

import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RiskAdjustmentService {

    public double protectiveAdjustment(String subject, String details) {
        String text = ((subject == null ? "" : subject) + " " + (details == null ? "" : details))
                .toLowerCase(Locale.ROOT);

        double adjustment = 0.0;
        adjustment += factor(text, 1.1, "live performance", "performer", "singer", "choir", "audience");
        adjustment += factor(text, 0.9, "physical presence", "hands-on", "site", "field work", "patient", "ward", "electrician");
        adjustment += factor(text, 0.8, "real-time", "coordination", "stakeholder", "team leadership", "leadership", "ceo");
        adjustment += factor(text, 0.9, "emotional intelligence", "empathy", "safeguarding", "care", "nurse", "nursing", "counselling", "counseling");
        adjustment += factor(text, 0.8, "unpredictable", "chaotic", "emergency", "home visit", "real-world");

        if (containsAny(text, "call center", "call centre", "scripted support")) {
            adjustment = Math.min(adjustment, 0.6);
        }
        if (containsAny(text, "nurse", "nursing", "clinical")) {
            adjustment = Math.min(adjustment, 1.0);
        }

        return round(Math.min(3.5, adjustment));
    }

    private double factor(String text, double value, String... keywords) {
        return containsAny(text, keywords) ? value : 0.0;
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private double round(double score) {
        return Math.round(score * 10.0) / 10.0;
    }
}
