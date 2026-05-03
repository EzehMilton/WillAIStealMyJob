package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.Locale;

@Service
@Slf4j
public class GenerationMetricsService {

    private final String premiumModelName;
    private final String miniModelName;
    private final double premiumInputCostPer1M;
    private final double premiumOutputCostPer1M;
    private final double miniInputCostPer1M;
    private final double miniOutputCostPer1M;
    private final double usdToGbpRate;

    public GenerationMetricsService(@Value("${app.ai.model.premium}") String premiumModelName,
                                    @Value("${app.ai.model.mini}") String miniModelName,
                                    @Value("${app.ai.cost.premium.input-per-1m}") double premiumInputCostPer1M,
                                    @Value("${app.ai.cost.premium.output-per-1m}") double premiumOutputCostPer1M,
                                    @Value("${app.ai.cost.mini.input-per-1m}") double miniInputCostPer1M,
                                    @Value("${app.ai.cost.mini.output-per-1m}") double miniOutputCostPer1M,
                                    @Value("${app.ai.cost.usd-to-gbp-rate:0.79}") double usdToGbpRate) {
        this.premiumModelName = premiumModelName;
        this.miniModelName = miniModelName;
        this.premiumInputCostPer1M = premiumInputCostPer1M;
        this.premiumOutputCostPer1M = premiumOutputCostPer1M;
        this.miniInputCostPer1M = miniInputCostPer1M;
        this.miniOutputCostPer1M = miniOutputCostPer1M;
        this.usdToGbpRate = usdToGbpRate;
    }

    public GenerationMetrics fromChatResponse(String reportType, String fallbackModelName, long durationMs, ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse != null ? chatResponse.getMetadata() : null;
        Usage usage = metadata != null ? metadata.getUsage() : null;

        int promptTokens = valueOrZero(usage != null ? usage.getPromptTokens() : null);
        int completionTokens = valueOrZero(usage != null ? usage.getCompletionTokens() : null);
        int totalTokens = valueOrZero(usage != null ? usage.getTotalTokens() : null);
        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }

        String modelName = metadata != null && metadata.getModel() != null && !metadata.getModel().isBlank()
                ? metadata.getModel().trim()
                : fallbackModelName;

        Pricing pricing = pricingForModel(modelName);
        double inputCostUsd = costUsd(promptTokens, pricing.inputCostPer1M());
        double outputCostUsd = costUsd(completionTokens, pricing.outputCostPer1M());
        double estimatedCostUsd = inputCostUsd + outputCostUsd;

        return GenerationMetrics.builder()
                .reportType(reportType)
                .model(modelName)
                .durationMs(durationMs)
                .promptTokens(promptTokens)
                .completionTokens(completionTokens)
                .totalTokens(totalTokens)
                .inputCostUsd(inputCostUsd)
                .outputCostUsd(outputCostUsd)
                .estimatedCostUsd(estimatedCostUsd)
                .estimatedCostPence(estimatedCostPence(estimatedCostUsd))
                .build();
    }

    private double costUsd(int tokens, double pricePer1M) {
        return (tokens / 1_000_000.0) * pricePer1M;
    }

    private Pricing pricingForModel(String modelName) {
        String normalized = modelName == null ? "" : modelName.trim();
        String normalizedLower = normalized.toLowerCase(Locale.ROOT);
        if (normalized.equalsIgnoreCase(miniModelName)
                || normalizedLower.startsWith(miniModelName.toLowerCase(Locale.ROOT))
                || normalizedLower.contains("mini")) {
            return new Pricing(miniInputCostPer1M, miniOutputCostPer1M);
        }
        if (!normalized.equalsIgnoreCase(premiumModelName)
                && !normalizedLower.startsWith(premiumModelName.toLowerCase(Locale.ROOT))) {
            log.warn("Unknown AI model pricing for model='{}'. Falling back to premium pricing config.", modelName);
        }
        return new Pricing(premiumInputCostPer1M, premiumOutputCostPer1M);
    }

    private double estimatedCostPence(double estimatedCostUsd) {
        return estimatedCostUsd * usdToGbpRate * 100.0;
    }

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private record Pricing(double inputCostPer1M, double outputCostPer1M) {
    }
}
