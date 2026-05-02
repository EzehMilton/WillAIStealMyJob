package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskDimensions;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RiskDimensionCalculator {

    public RiskDimensions calculate(JourneyType journeyType, String subject, String details) {
        String text = normalize(subject, details);

        double taskRepeatability = baseline(journeyType, 4.8, 4.3, 4.0);
        double digitalExecution = baseline(journeyType, 5.0, 4.4, 4.0);
        double humanInteraction = baseline(journeyType, 5.0, 4.5, 4.2);
        double creativityExecution = baseline(journeyType, 4.8, 4.3, 4.0);
        double environmentComplexity = baseline(journeyType, 4.7, 4.2, 4.0);

        taskRepeatability += keywordAdjustment(text, 2.6,
                "data entry", "call center", "call centre", "customer support", "admin", "administrative",
                "routine", "repetitive", "scheduling", "transcription", "invoice", "claims", "forms");
        taskRepeatability += keywordAdjustment(text, 1.4,
                "documentation", "reporting", "testing", "debugging", "processing", "standardised", "standardized");
        taskRepeatability -= keywordAdjustment(text, 2.0,
                "ceo", "chief executive", "leader", "leadership", "strategy", "strategic", "nurse",
                "electrician", "singer", "choir", "performer", "live performance", "social care");

        digitalExecution += keywordAdjustment(text, 3.0,
                "software", "developer", "java", "coding", "code", "graphic designer", "designer",
                "data entry", "call center", "call centre", "analyst", "spreadsheet", "online");
        digitalExecution -= keywordAdjustment(text, 3.0,
                "electrician", "nurse", "nursing", "care", "social care", "singer", "choir",
                "performer", "physical", "hands-on", "site", "field work", "patient", "live performance");

        humanInteraction += keywordAdjustment(text, 2.2,
                "data entry", "admin", "back office", "processing", "forms", "reporting");
        humanInteraction += keywordAdjustment(text, 1.4,
                "call center", "call centre", "scripted", "customer support");
        humanInteraction -= keywordAdjustment(text, 2.4,
                "nurse", "nursing", "care", "social care", "safeguarding", "patient", "empathy",
                "emotional", "counselling", "counseling", "teaching", "negotiation", "stakeholder",
                "ceo", "leadership", "singer", "choir", "audience", "live performance");

        creativityExecution += keywordAdjustment(text, 2.1,
                "data entry", "call center", "call centre", "scripted", "routine", "processing",
                "implementation", "testing", "debugging", "documentation");
        creativityExecution -= keywordAdjustment(text, 2.0,
                "creative", "creativity", "original", "taste", "brand", "concept", "strategy",
                "leadership", "ceo", "singer", "choir", "performer");

        environmentComplexity += keywordAdjustment(text, 2.1,
                "structured", "standardised", "standardized", "scripted", "rules-based", "rule-based",
                "data entry", "call center", "call centre", "forms", "processing");
        environmentComplexity -= keywordAdjustment(text, 2.1,
                "unpredictable", "chaotic", "real-time", "site", "field work", "emergency",
                "patient", "ward", "home visit", "live performance", "audience", "electrician",
                "nurse", "ceo", "leadership");

        if (containsAny(text, "choir singer", "singer", "choir", "vocalist")) {
            taskRepeatability = Math.min(taskRepeatability, 2.4);
            digitalExecution = Math.min(digitalExecution, 1.6);
            humanInteraction = Math.min(humanInteraction, 2.0);
            creativityExecution = Math.min(creativityExecution, 2.2);
            environmentComplexity = Math.min(environmentComplexity, 2.4);
        } else if (containsAny(text, "nurse", "nursing")) {
            taskRepeatability = Math.max(Math.min(taskRepeatability, 3.8), 3.0);
            digitalExecution = Math.max(Math.min(digitalExecution, 2.2), 1.5);
            humanInteraction = Math.max(Math.min(humanInteraction, 2.4), 1.8);
            creativityExecution = Math.max(Math.min(creativityExecution, 4.2), 3.0);
            environmentComplexity = Math.max(Math.min(environmentComplexity, 3.0), 2.4);
        } else if (containsAny(text, "electrician")) {
            taskRepeatability = Math.min(taskRepeatability, 3.4);
            digitalExecution = Math.min(digitalExecution, 1.8);
            humanInteraction = Math.min(humanInteraction, 4.0);
            environmentComplexity = Math.min(environmentComplexity, 2.8);
        } else if (containsAny(text, "ceo", "chief executive")) {
            taskRepeatability = Math.min(taskRepeatability, 2.6);
            humanInteraction = Math.min(humanInteraction, 2.2);
            creativityExecution = Math.min(creativityExecution, 2.2);
            environmentComplexity = Math.min(environmentComplexity, 2.5);
        } else if (containsAny(text, "data entry")) {
            taskRepeatability = Math.max(taskRepeatability, 9.2);
            digitalExecution = Math.max(digitalExecution, 9.0);
            humanInteraction = Math.max(humanInteraction, 8.4);
            creativityExecution = Math.max(creativityExecution, 8.6);
            environmentComplexity = Math.max(environmentComplexity, 8.8);
        } else if (containsAny(text, "call center", "call centre")) {
            taskRepeatability = Math.max(taskRepeatability, 8.4);
            digitalExecution = Math.max(digitalExecution, 8.2);
            humanInteraction = Math.max(humanInteraction, 7.2);
            creativityExecution = Math.max(creativityExecution, 7.7);
            environmentComplexity = Math.max(environmentComplexity, 8.2);
        } else if (containsAny(text,
                "transcriptionist", "transcribes", "transcription",
                "claims processor", "invoice processing clerk", "generic seo content writer",
                "seo content", "telemarketer", "appointment scheduler")) {
            taskRepeatability = Math.max(taskRepeatability, 8.4);
            digitalExecution = Math.max(digitalExecution, 8.0);
            humanInteraction = Math.max(humanInteraction, 7.4);
            creativityExecution = Math.max(creativityExecution, 7.8);
            environmentComplexity = Math.max(environmentComplexity, 8.0);
        } else if (containsAny(text, "software developer", "java developer", "developer", "software engineer")) {
            taskRepeatability = Math.min(Math.max(taskRepeatability, 5.4), 6.2);
            digitalExecution = Math.max(digitalExecution, 8.8);
            humanInteraction = Math.min(Math.max(humanInteraction, 5.0), 5.8);
            creativityExecution = Math.min(Math.max(creativityExecution, 5.2), 5.8);
            environmentComplexity = Math.min(Math.max(environmentComplexity, 5.6), 6.2);
        } else if (containsAny(text, "graphic designer", "designer")) {
            taskRepeatability = Math.max(taskRepeatability, 4.6);
            digitalExecution = Math.max(digitalExecution, 8.0);
            humanInteraction = Math.max(humanInteraction, 4.8);
            creativityExecution = Math.min(Math.max(creativityExecution, 4.4), 5.4);
            environmentComplexity = Math.max(environmentComplexity, 4.8);
        }

        return new RiskDimensions(
                round(clamp(taskRepeatability)),
                round(clamp(digitalExecution)),
                round(clamp(humanInteraction)),
                round(clamp(creativityExecution)),
                round(clamp(environmentComplexity))
        );
    }

    private double baseline(JourneyType journeyType, double professional, double universityStudent, double aLevel) {
        return switch (journeyType) {
            case PROFESSIONAL -> professional;
            case UNIVERSITY_STUDENT -> universityStudent;
            case A_LEVEL_UNDECIDED -> aLevel;
        };
    }

    private double keywordAdjustment(String text, double adjustment, String... keywords) {
        int matches = 0;
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                matches++;
            }
        }
        return matches == 0 ? 0.0 : adjustment * Math.min(2, matches);
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private String normalize(String subject, String details) {
        return ((subject == null ? "" : subject) + " " + (details == null ? "" : details))
                .toLowerCase(Locale.ROOT);
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(10.0, score));
    }

    private double round(double score) {
        return Math.round(score * 10.0) / 10.0;
    }
}
