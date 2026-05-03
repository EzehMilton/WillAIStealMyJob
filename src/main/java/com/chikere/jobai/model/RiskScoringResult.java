package com.chikere.jobai.model;

public record RiskScoringResult(
        double score,
        String riskLevel,
        String summary,
        RiskDimensions dimensions,
        double baseScore,
        double protectiveAdjustment
) {
}
