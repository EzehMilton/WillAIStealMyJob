package com.chikere.jobai.model;

import lombok.Data;

@Data
public class GenerateReportRequest {
    private String sessionId;
    private String profession;
    private String description;
    private double score;
    private String riskLevel;
    private String mode;
}