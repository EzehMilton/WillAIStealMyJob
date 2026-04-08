package com.chikere.jobai.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Locale;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerationMetrics {

    private String reportType;
    private String model;
    private long durationMs;
    private int promptTokens;
    private int completionTokens;
    private int totalTokens;
    private double estimatedCostUsd;

    public String getDurationLabel() {
        if (durationMs < 1000) {
            return durationMs + " ms";
        }
        return String.format(Locale.US, "%.2f s", durationMs / 1000.0);
    }

    public String getEstimatedCostUsdLabel() {
        return String.format(Locale.US, "$%.4f", estimatedCostUsd);
    }
}
