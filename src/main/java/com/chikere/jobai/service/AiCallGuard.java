package com.chikere.jobai.service;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Circuit breaker + bulkhead around an AI call. After {@code failureThreshold} consecutive
 * failures the circuit opens for {@code openDuration}; while open, calls fail fast with
 * {@link CircuitOpenException}. When the open period elapses, a single half-open probe is let
 * through to test recovery. The semaphore bulkhead caps concurrent calls.
 *
 * One instance per AI client, so a failing cheap-summary path never blocks the premium path
 * and vice versa.
 */
@Slf4j
public class AiCallGuard {

    private final String name;
    private final int failureThreshold;
    private final Duration openDuration;
    private final Semaphore bulkhead;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilEpochMillis = new AtomicLong();
    private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean();

    public AiCallGuard(String name, int failureThreshold, Duration openDuration, int maxConcurrentCalls) {
        this.name = name;
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = validOpenDuration(openDuration);
        this.bulkhead = new Semaphore(Math.max(1, maxConcurrentCalls));
    }

    public <T> T call(Supplier<T> aiCall) {
        long now = System.currentTimeMillis();
        long openUntil = openUntilEpochMillis.get();

        if (openUntil > now) {
            countRejection("open");
            throw new CircuitOpenException("AI " + name + " service is temporarily unavailable");
        }

        boolean halfOpenProbe = openUntil > 0;
        if (halfOpenProbe && !halfOpenProbeInFlight.compareAndSet(false, true)) {
            countRejection("probe_in_flight");
            throw new CircuitOpenException("AI " + name + " service is recovering; retry shortly");
        }

        if (!bulkhead.tryAcquire()) {
            if (halfOpenProbe) {
                halfOpenProbeInFlight.set(false);
            }
            countRejection("capacity");
            throw new CircuitOpenException("AI " + name + " service is currently at capacity");
        }

        try {
            T result = aiCall.get();
            recordSuccess();
            return result;
        } catch (RuntimeException e) {
            recordFailure(e);
            throw e;
        } finally {
            bulkhead.release();
            if (halfOpenProbe) {
                halfOpenProbeInFlight.set(false);
            }
        }
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        openUntilEpochMillis.set(0);
    }

    private void recordFailure(RuntimeException exception) {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures < failureThreshold) {
            return;
        }

        long openUntil = System.currentTimeMillis() + openDuration.toMillis();
        openUntilEpochMillis.set(openUntil);
        consecutiveFailures.set(0);
        Metrics.counter("jobai.ai.circuit.opened", "client", name).increment();
        log.warn(
                "AI {} circuit opened for {} ms after {} consecutive failures: {}",
                name,
                openDuration.toMillis(),
                failures,
                exception.getMessage()
        );
    }

    private void countRejection(String reason) {
        Metrics.counter("jobai.ai.circuit.rejected", "client", name, "reason", reason).increment();
    }

    private static Duration validOpenDuration(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return Duration.ofSeconds(30);
        }
        return duration;
    }

    public static class CircuitOpenException extends RuntimeException {
        public CircuitOpenException(String message) {
            super(message);
        }
    }
}
