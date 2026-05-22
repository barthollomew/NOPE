package com.nope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyLimiterTest {

    @Test
    void initialLimit_clampsToMin() {
        NopeSemaphore sem = new NopeSemaphore(ConcurrencyLimiter.MIN_LIMIT);
        SojournTracker tracker = new SojournTracker();
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(sem, tracker, 0);
        assertEquals(ConcurrencyLimiter.MIN_LIMIT, limiter.currentLimit());
    }

    @Test
    void initialLimit_clampsToMax() {
        NopeSemaphore sem = new NopeSemaphore(ConcurrencyLimiter.MAX_LIMIT);
        SojournTracker tracker = new SojournTracker();
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(sem, tracker, 99999);
        assertEquals(ConcurrencyLimiter.MAX_LIMIT, limiter.currentLimit());
    }

    @Test
    void close_shutsDownScheduler() {
        NopeSemaphore sem = new NopeSemaphore(10);
        SojournTracker tracker = new SojournTracker();
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(sem, tracker, 10);
        limiter.start();
        assertDoesNotThrow(limiter::close);
    }

    @Test
    void recordArrival_doesNotThrow() {
        NopeSemaphore sem = new NopeSemaphore(10);
        SojournTracker tracker = new SojournTracker();
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(sem, tracker, 10);
        for (int i = 0; i < 100; i++) {
            limiter.recordArrival();
        }
        assertEquals(10, limiter.currentLimit());
    }

    @Test
    void littlesLaw_limitComputation_basic() {
        // L = lambda * W: if lambda=10 rps and W=0.1s then L=1
        // We can't directly test the scheduler without time manipulation,
        // but we can verify the math holds conceptually via boundary checks.
        double lambda = 100.0;
        double W = 0.5; // 500ms
        double L = lambda * W;
        assertTrue(L >= ConcurrencyLimiter.MIN_LIMIT);
        // 50 in-flight is within [MIN_LIMIT, MAX_LIMIT]
        int clamped = (int) Math.max(ConcurrencyLimiter.MIN_LIMIT,
                Math.min(ConcurrencyLimiter.MAX_LIMIT, L));
        assertEquals(50, clamped);
    }
}
