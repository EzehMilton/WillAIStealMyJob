package com.chikere.jobai.model;

public record AssessmentProcessingResult(
        JobRiskAssessment assessment,
        String resolvedDetails,
        String subject,
        JourneyType journeyType,
        String mode
) {
}
