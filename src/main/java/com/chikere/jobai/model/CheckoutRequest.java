package com.chikere.jobai.model;

import lombok.Data;

@Data
public class CheckoutRequest {
    private String mode;
    private String profession;
    private double score;
    private String riskLevel;
    private String summary;
    private String assessment;
}