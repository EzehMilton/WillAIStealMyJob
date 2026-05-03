package com.chikere.jobai.service;

import com.chikere.jobai.model.JourneyType;
import com.chikere.jobai.model.RiskDimensions;
import com.chikere.jobai.model.RiskScoringResult;
import org.springframework.stereotype.Service;

@Service
public class RiskScoringService {

    private final RiskDimensionCalculator dimensionCalculator;
    private final RiskAdjustmentService adjustmentService;
    private final RiskSanityValidator sanityValidator;

    public RiskScoringService(
            RiskDimensionCalculator dimensionCalculator,
            RiskAdjustmentService adjustmentService,
            RiskSanityValidator sanityValidator
    ) {
        this.dimensionCalculator = dimensionCalculator;
        this.adjustmentService = adjustmentService;
        this.sanityValidator = sanityValidator;
    }

    public RiskScoringResult score(JourneyType journeyType, String subject, String details, String modelSummary) {
        RiskDimensions dimensions = dimensionCalculator.calculate(journeyType, subject, details);
        double baseScore = weightedBaseScore(dimensions);
        double protectiveAdjustment = adjustmentService.protectiveAdjustment(subject, details);
        double finalScore = round(clamp(baseScore - protectiveAdjustment));
        String riskLevel = sanityValidator.riskLevel(finalScore);

        String summary = sanityValidator.alignedSummary(
                journeyType,
                subject,
                details,
                riskLevel,
                keyDriver(dimensions),
                protectiveDriver(protectiveAdjustment)
        );

        return new RiskScoringResult(
                finalScore,
                riskLevel,
                summary,
                dimensions,
                round(baseScore),
                protectiveAdjustment
        );
    }

    double weightedBaseScore(RiskDimensions dimensions) {
        return round(
                (dimensions.taskRepeatability() * 0.30)
                        + (dimensions.digitalExecution() * 0.25)
                        + (dimensions.humanInteraction() * 0.20)
                        + (dimensions.creativityExecution() * 0.15)
                        + (dimensions.environmentComplexity() * 0.10)
        );
    }

    private String keyDriver(RiskDimensions dimensions) {
        double max = Math.max(
                Math.max(dimensions.taskRepeatability(), dimensions.digitalExecution()),
                Math.max(
                        Math.max(dimensions.humanInteraction(), dimensions.creativityExecution()),
                        dimensions.environmentComplexity()
                )
        );

        if (max == dimensions.taskRepeatability()) {
            return "the task mix contains repetitive or predictable work that AI tools can compress";
        }
        if (max == dimensions.digitalExecution()) {
            return "much of the work happens in digital systems where AI tools can operate directly";
        }
        if (max == dimensions.humanInteraction()) {
            return "the workflow has limited dependency on deep emotional judgement or complex human trust";
        }
        if (max == dimensions.creativityExecution()) {
            return "the work leans more toward execution than original judgement or taste";
        }
        return "the work happens in relatively structured environments with clearer inputs and outputs";
    }

    private String protectiveDriver(double protectiveAdjustment) {
        if (protectiveAdjustment >= 2.0) {
            return "Protective factors such as physical presence, live coordination, emotional judgement, or unpredictable environments reduce the overall exposure.";
        }
        if (protectiveAdjustment >= 0.8) {
            return "Some protective human or real-world elements reduce the overall exposure.";
        }
        return "";
    }

    private double clamp(double score) {
        return Math.max(0.0, Math.min(10.0, score));
    }

    private double round(double score) {
        return Math.round(score * 10.0) / 10.0;
    }
}
