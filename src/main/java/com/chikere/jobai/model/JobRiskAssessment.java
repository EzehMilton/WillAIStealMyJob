package com.chikere.jobai.model;

import lombok.Data;

@Data
public class JobRiskAssessment {
    private double score;
    private String riskLevel;
    private String summary;
    private String assessment;
}
