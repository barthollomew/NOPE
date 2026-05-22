package com.nope;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoDelControllerTest {

    private static final long TARGET = CoDelController.TARGET_NANOS;
    private static final long INTERVAL = CoDelController.INTERVAL_NANOS;

    @Test
    void belowTarget_neverDrops() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET - 1;
        long now = INTERVAL * 2;
        for (int i = 0; i < 200; i++) {
            assertFalse(codel.shouldDrop(sojourn, now + i * 1_000_000L));
        }
    }

    @Test
    void aboveTarget_noDropBeforeInterval() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 2;
        long now = 0L;
        // First call: sets firstAboveTime = now + INTERVAL, returns false
        assertFalse(codel.shouldDrop(sojourn, now));
    }

    @Test
    void aboveTarget_dropsAfterInterval() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 2;
        long start = 0L;

        // Warm up firstAboveTime
        codel.shouldDrop(sojourn, start);

        // Advance past interval
        long afterInterval = start + INTERVAL + 1;
        boolean dropped = codel.shouldDrop(sojourn, afterInterval);
        assertTrue(dropped, "Should drop after interval elapses");
    }

    @Test
    void dropIntervalDecreases_withDropCount() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 3;
        long now = 0L;

        // Seed firstAboveTime
        codel.shouldDrop(sojourn, now);
        now += INTERVAL + 1;

        // First drop: dropNext is set to now + INTERVAL/sqrt(1) = now + INTERVAL
        codel.shouldDrop(sojourn, now);
        int firstCount = codel.dropCount();
        assertTrue(firstCount > 0, "First drop should have occurred");

        // Advance past the full first drop interval to reach dropNext
        now += INTERVAL + 1;
        codel.shouldDrop(sojourn, now);
        int secondCount = codel.dropCount();

        assertTrue(secondCount > firstCount, "Drop count should increase on second drop");
    }

    @Test
    void exitDropping_whenBelowTarget() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 2;
        long now = 0L;

        codel.shouldDrop(sojourn, now);
        now += INTERVAL + 1;
        codel.shouldDrop(sojourn, now);
        assertTrue(codel.isDropping());

        // Now sojourn drops below target
        now += 10_000_000L;
        codel.shouldDrop(TARGET / 2, now);
        assertFalse(codel.isDropping());
    }

    @Test
    void reset_clearsAllState() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 2;
        codel.shouldDrop(sojourn, 0L);
        codel.shouldDrop(sojourn, INTERVAL + 1);
        codel.reset();

        assertFalse(codel.isDropping());
        assertEquals(0, codel.dropCount());
        // After reset, should behave as fresh
        assertFalse(codel.shouldDrop(sojourn, 0L));
    }

    @Test
    void dropInterval_formula_matchesSpec() {
        // drop_interval = INTERVAL / sqrt(drop_count)
        // Validate against known values
        double interval1 = INTERVAL / Math.sqrt(1);
        double interval4 = INTERVAL / Math.sqrt(4);
        assertEquals(INTERVAL, (long) interval1);
        assertEquals(INTERVAL / 2, (long) interval4);
    }

    @Test
    void highSojourn_manyDrops_dropCountGrows() {
        CoDelController codel = new CoDelController();
        long sojourn = TARGET * 10;
        long now = 0L;

        // Seed
        codel.shouldDrop(sojourn, now);
        now += INTERVAL + 1;

        // Simulate many drop cycles
        for (int i = 0; i < 10; i++) {
            codel.shouldDrop(sojourn, now);
            now += (long) (INTERVAL / Math.sqrt(codel.dropCount() + 1)) + 1;
        }

        assertTrue(codel.dropCount() >= 5, "Expected at least 5 drops, got " + codel.dropCount());
    }
}
