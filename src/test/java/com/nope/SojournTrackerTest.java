package com.nope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SojournTrackerTest {

    @Test
    void sojournNanos_fromArrival() {
        SojournTracker tracker = new SojournTracker();
        long arrival = 1_000_000L;
        long now = 1_500_000L;
        tracker.recordArrival(arrival);
        long sojourn = tracker.sojournNanos(now);
        assertEquals(500_000L, sojourn);
    }

    @Test
    void sojournNanos_fromStart() {
        SojournTracker tracker = new SojournTracker();
        long arrival = 1_000_000L;
        long start = 1_200_000L;
        long now = 1_800_000L;
        tracker.recordArrival(arrival);
        tracker.recordStart(start);
        long sojourn = tracker.sojournNanos(now);
        assertEquals(600_000L, sojourn);
    }

    @Test
    void ema_convergesToSteadyState() {
        SojournTracker tracker = new SojournTracker();
        long base = 0L;
        long delta = 50_000_000L; // 50ms

        for (int i = 0; i < 50; i++) {
            tracker.recordArrival(base);
            tracker.recordStart(base);
            tracker.recordCompletion(base + delta);
            base += 100_000_000L;
        }

        double ema = tracker.emaSojournNanos();
        // EMA should be close to 50ms after many samples
        assertTrue(ema > 40_000_000.0 && ema < 60_000_000.0,
                "EMA " + ema + " not near 50ms");
    }

    @Test
    void emaSojournSeconds_convertsCorrectly() {
        SojournTracker tracker = new SojournTracker();
        long base = 1_000_000L; // non-zero so the > 0 guard passes
        tracker.recordArrival(base);
        tracker.recordStart(base);
        tracker.recordCompletion(base + 1_000_000_000L); // 1 second sojourn
        // First sample: ema is set directly to sojourn (ema was 0.0)
        double secs = tracker.emaSojournSeconds();
        assertEquals(1.0, secs, 0.001);
    }

    @Test
    void reset_clearsState() {
        SojournTracker tracker = new SojournTracker();
        tracker.recordArrival(1000L);
        tracker.recordStart(2000L);
        tracker.reset();
        assertEquals(0L, tracker.sojournNanos(5000L));
    }
}
