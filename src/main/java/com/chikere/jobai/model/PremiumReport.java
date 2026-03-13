package com.chikere.jobai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class PremiumReport {

    private String reportId;
    private String profession;
    private String mode;
    private double score;
    private String riskLevel;
    private LocalDateTime generatedAt;

    // At-a-glance KPIs
    private String overallExposure;
    private String mostExposedArea;
    private String safestDirection;
    private String coreAdvice;

    // Narrative sections
    private String executiveSummary;
    private String positioningAdvice;

    // Table sections
    private List<TaskRow> taskExposureMap;
    private List<CareerLevelRow> careerLevelAnalysis;
    private List<TrackRow> trackComparison;
    private List<TransitionRow> adjacentRoles;
    private List<ResilienceRow> resilienceScorecard;

    // List sections
    private List<String> aiStrengths;
    private List<String> humanAdvantages;
    private List<String> warningSigns;

    // Action plan
    private List<String> thirtyDayPlan;
    private List<String> ninetyDayPlan;
    private List<String> yearPlan;

    @Data
    @AllArgsConstructor
    public static class TaskRow {
        private String area;
        private String exposure;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    public static class CareerLevelRow {
        private String level;
        private String risk;
        private String commentary;
    }

    @Data
    @AllArgsConstructor
    public static class TrackRow {
        private String track;
        private String exposure;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    public static class TransitionRow {
        private String targetRole;
        private String salaryBand;
        private String aiResilience;
        private String transitionDifficulty;
        private String reason;
    }

    @Data
    @AllArgsConstructor
    public static class ResilienceRow {
        private String factor;
        private String signal;
    }
}