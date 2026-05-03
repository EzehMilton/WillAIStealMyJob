package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationMetricsServiceTest {

    private final GenerationMetricsService service = new GenerationMetricsService(
            "gpt-5.4",
            "gpt-5.4-mini",
            2.50,
            10.00,
            0.75,
            4.50,
            0.79
    );

    @Test
    void calculatesMiniCostsUsingSeparateInputAndOutputPricesForDatedAlias() {
        GenerationMetrics metrics = service.fromChatResponse(
                "Summary Report",
                "gpt-5.4-mini",
                1250,
                chatResponse("gpt-5.4-mini-2026-03-17", 3848, 177)
        );

        assertEquals("gpt-5.4-mini-2026-03-17", metrics.getModel());
        assertEquals(3848, metrics.getPromptTokens());
        assertEquals(177, metrics.getCompletionTokens());
        assertEquals(4025, metrics.getTotalTokens());
        assertEquals(0.002886, metrics.getInputCostUsd(), 0.0000001);
        assertEquals(0.0007965, metrics.getOutputCostUsd(), 0.0000001);
        assertEquals(0.0036825, metrics.getEstimatedCostUsd(), 0.0000001);
        assertEquals(0.2909175, metrics.getEstimatedCostPence(), 0.0000001);
        assertEquals("$0.0037", metrics.getEstimatedCostUsdLabel());
        assertEquals("0.291p", metrics.getEstimatedCostPenceLabel());
    }

    @Test
    void zeroTokensReturnZeroCost() {
        GenerationMetrics metrics = service.fromChatResponse(
                "Summary Report",
                "gpt-5.4-mini",
                20,
                chatResponse("gpt-5.4-mini", 0, 0)
        );

        assertEquals(0, metrics.getTotalTokens());
        assertEquals(0.0, metrics.getInputCostUsd(), 0.0);
        assertEquals(0.0, metrics.getOutputCostUsd(), 0.0);
        assertEquals(0.0, metrics.getEstimatedCostUsd(), 0.0);
        assertEquals(0.0, metrics.getEstimatedCostPence(), 0.0);
    }

    @Test
    void unknownModelFallsBackToPremiumPricingConfig() {
        GenerationMetrics metrics = service.fromChatResponse(
                "Full Report",
                "unknown-model",
                50,
                chatResponse("unknown-model-2026", 1000, 500)
        );

        assertEquals(0.0025, metrics.getInputCostUsd(), 0.0000001);
        assertEquals(0.005, metrics.getOutputCostUsd(), 0.0000001);
        assertEquals(0.0075, metrics.getEstimatedCostUsd(), 0.0000001);
        assertEquals(0.5925, metrics.getEstimatedCostPence(), 0.0000001);
    }

    @Test
    void baseMiniModelMapsToMiniPricing() {
        GenerationMetrics metrics = service.fromChatResponse(
                "Summary Report",
                "gpt-5.4-mini",
                50,
                chatResponse("gpt-5.4-mini", 1000, 500)
        );

        assertEquals(0.00075, metrics.getInputCostUsd(), 0.0000001);
        assertEquals(0.00225, metrics.getOutputCostUsd(), 0.0000001);
        assertEquals(0.003, metrics.getEstimatedCostUsd(), 0.0000001);
    }

    private ChatResponse chatResponse(String model, int promptTokens, int completionTokens) {
        Usage usage = new Usage() {
            @Override
            public Integer getPromptTokens() {
                return promptTokens;
            }

            @Override
            public Integer getCompletionTokens() {
                return completionTokens;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };

        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .model(model)
                .usage(usage)
                .build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{}"))), metadata);
    }
}
