package com.chikere.jobai.model;

import lombok.Data;

@Data
public class AnalyticsEventRequest {
    private String visitorId;
    private String eventType;
    private String profession;
    private Double riskScore;
}
