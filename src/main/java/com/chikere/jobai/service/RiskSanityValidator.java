package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class RiskSanityValidator {

    public String riskLevel(double score) {
        if (score <= 3.4) {
            return "Low";
        }
        if (score <= 6.9) {
            return "Moderate";
        }
        return "High";
    }

    public String alignedSummary(
            JourneyType journeyType,
            String subject,
            String details,
            String riskLevel,
            String keyDriver,
            String protectiveDriver
    ) {
        String subjectText = subject == null || subject.isBlank() ? "this path" : subject.trim();
        String impactText = switch (riskLevel) {
            case "Low" -> "low AI impact";
            case "High" -> "high AI impact";
            default -> "moderate AI impact";
        };

        String prefix = switch (journeyType) {
            case PROFESSIONAL -> "Your " + subjectText + " role";
            case UNIVERSITY_STUDENT -> "Your " + subjectText + " path";
            case A_LEVEL_UNDECIDED -> "Your interest in " + subjectText;
        };

        String reason = keyDriver == null || keyDriver.isBlank()
                ? "the work combines automatable and human-led elements"
                : keyDriver;
        String protection = protectiveDriver == null || protectiveDriver.isBlank()
                ? ""
                : " " + protectiveDriver + " keeps part of the path more human-led.";
        String teaser = switch (journeyType) {
            case PROFESSIONAL -> "The premium report shows which tasks are most exposed and where to reposition next.";
            case UNIVERSITY_STUDENT -> "The premium report breaks down the career-path risks, safer specialisations, and study strategy.";
            case A_LEVEL_UNDECIDED -> "The premium report shows which subject combinations keep the widest options open and what to do next.";
        };

        return prefix + " shows " + impactText + " because " + reason + "." + protection + " " + teaser;
    }

    public boolean contradicts(String summary, String riskLevel) {
        if (summary == null || summary.isBlank()) {
            return true;
        }

        String text = summary.toLowerCase(Locale.ROOT);
        boolean impliesLow = containsAny(text,
                "low impact", "low exposure", "lower impact", "lower exposure", "minimal impact", "limited impact");
        boolean impliesHigh = containsAny(text,
                "high impact", "high exposure", "severe impact", "major displacement", "major automation");

        return (impliesLow && !"Low".equals(riskLevel)) || (impliesHigh && !"High".equals(riskLevel));
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
