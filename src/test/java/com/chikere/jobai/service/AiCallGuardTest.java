package com.chikere.jobai.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiCallGuardTest {

    @Test
    void opensAfterThresholdFailuresAndFailsFastWithoutCallingSupplier() {
        AiCallGuard guard = new AiCallGuard("test", 2, Duration.ofMinutes(1), 10);
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));
        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));

        assertThrows(AiCallGuard.CircuitOpenException.class, () -> guard.call(failing(invocations)));
        assertEquals(2, invocations.get());
    }

    @Test
    void successResetsConsecutiveFailureCount() {
        AiCallGuard guard = new AiCallGuard("test", 2, Duration.ofMinutes(1), 10);
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));
        assertEquals("ok", guard.call(() -> "ok"));
        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));

        // circuit still closed: the next call reaches the supplier
        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));
        assertEquals(3, invocations.get());
    }

    @Test
    void halfOpenProbeClosesCircuitAfterRecovery() throws Exception {
        AiCallGuard guard = new AiCallGuard("test", 1, Duration.ofMillis(50), 10);
        AtomicInteger invocations = new AtomicInteger();

        assertThrows(RuntimeException.class, () -> guard.call(failing(invocations)));
        assertThrows(AiCallGuard.CircuitOpenException.class, () -> guard.call(failing(invocations)));

        Thread.sleep(80);

        assertEquals("recovered", guard.call(() -> "recovered"));
        assertEquals("ok", guard.call(() -> "ok"));
        assertEquals(1, invocations.get());
    }

    @Test
    @Timeout(10)
    void bulkheadRejectsCallsBeyondConcurrencyLimit() throws Exception {
        AiCallGuard guard = new AiCallGuard("test", 3, Duration.ofSeconds(30), 1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread inFlight = new Thread(() -> guard.call(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "slow";
        }));
        inFlight.start();
        entered.await();

        AiCallGuard.CircuitOpenException ex = assertThrows(AiCallGuard.CircuitOpenException.class,
                () -> guard.call(() -> "second"));
        assertEquals("AI test service is currently at capacity", ex.getMessage());

        release.countDown();
        inFlight.join();

        assertEquals("after", guard.call(() -> "after"));
    }

    private java.util.function.Supplier<String> failing(AtomicInteger invocations) {
        return () -> {
            invocations.incrementAndGet();
            throw new RuntimeException("AI unavailable");
        };
    }
}
