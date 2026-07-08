package com.chikere.jobai.service;

import com.chikere.jobai.model.GenerationMetrics;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ObservabilityMetricsTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
        registry.close();
    }

    @Test
    void circuitOpenAndFailFastRejectionAreCounted() {
        AiCallGuard guard = new AiCallGuard("metrics-test", 1, Duration.ofMinutes(1), 10);

        assertThrows(RuntimeException.class, () -> guard.call(() -> {
            throw new RuntimeException("AI down");
        }));
        assertThrows(AiCallGuard.CircuitOpenException.class, () -> guard.call(() -> "unreachable"));

        assertEquals(1.0, registry.counter("jobai.ai.circuit.opened", "client", "metrics-test").count());
        assertEquals(1.0, registry.counter("jobai.ai.circuit.rejected",
                "client", "metrics-test", "reason", "open").count());
    }

    @Test
    void aiCostLoggingFeedsLatencyTokenAndCostMetrics() {
        GenerationMetricsService service =
                new GenerationMetricsService("gpt-5.4", "gpt-5.4-mini", 2.5, 10.0, 0.75, 4.50, 0.79);
        GenerationMetrics metrics = GenerationMetrics.builder()
                .reportType("Full Report")
                .model("gpt-5.4-mini")
                .durationMs(1200)
                .promptTokens(100)
                .completionTokens(50)
                .totalTokens(150)
                .estimatedCostUsd(0.0125)
                .build();

        service.logAiCost("profession", "r1", metrics);

        Timer timer = registry.find("jobai.ai.generation")
                .tag("reportType", "Full Report")
                .tag("model", "gpt-5.4-mini")
                .timer();
        assertNotNull(timer);
        assertEquals(1, timer.count());
        assertEquals(100.0, registry.counter("jobai.ai.tokens",
                "reportType", "Full Report", "type", "prompt").count());
        assertEquals(50.0, registry.counter("jobai.ai.tokens",
                "reportType", "Full Report", "type", "completion").count());
        assertEquals(0.0125, registry.counter("jobai.ai.cost.usd",
                "reportType", "Full Report").count(), 1e-9);
    }
}
