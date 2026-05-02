package com.chikere.jobai.model;

public record RiskDimensions(
        double taskRepeatability,
        double digitalExecution,
        double humanInteraction,
        double creativityExecution,
        double environmentComplexity
) {
}
