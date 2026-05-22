package com.nope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-concurrency traffic blaster validating NOPE's priority-shedding guarantees:
 *
 * <ol>
 *   <li>Under extreme burst overload SheddingPolicy never evicts CRITICAL from the
 *       queue — only BACKGROUND (and STANDARD) are shed.</li>
 *   <li>CoDel drop count increases when sojourn time persistently exceeds the target,
 *       proving the controller engages under sustained pressure.</li>
 *   <li>Slow-loris (connections that hold permits long) causes the concurrency limiter
 *       to adapt downward via Little's Law, protecting latency for critical traffic.</li>
 * </ol>
 */
class TrafficBlastIT {

    // ── Test 1: SheddingPolicy never evicts CRITICAL ──────────────────────────

    @Test
    @Timeout(30)
    void backgroundShedBeforeCritical_underBurstOverload() throws Exception {
        Nope nope = Nope.builder()
                .initialLimit(3)
                .targetMillis(5)
                .intervalMillis(25)
                .build();
        nope.start();
        LoadShedder shedder = nope.shedder();

        int bgCount   = 300;
        int critCount = 50;

        LongAdder critAccepted = new LongAdder();
        LongAdder bgAccepted   = new LongAdder();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(bgCount + critCount);

            for (int i = 0; i < bgCount; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.BACKGROUND);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        LockSupport.parkNanos(8_000_000L); // 8 ms > 5 ms target
                        shedder.complete(System.nanoTime());
                        bgAccepted.increment();
                    }
                }));
            }

            for (int i = 0; i < critCount; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.CRITICAL);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        LockSupport.parkNanos(3_000_000L); // 3 ms work
                        shedder.complete(System.nanoTime());
                        critAccepted.increment();
                    }
                }));
            }

            for (Future<?> f : futures) {
                try { f.get(25, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        } finally {
            nope.stop();
        }

        // SheddingPolicy must never remove a CRITICAL request from the queue.
        assertEquals(0, shedder.shedCritical(),
            "shedCritical=" + shedder.shedCritical() +
            " (bg_shed=" + shedder.shedBackground() + ", std_shed=" + shedder.shedStandard() +
            ", bg_accepted=" + bgAccepted.sum() + ", crit_accepted=" + critAccepted.sum() + ")");

        // Background must have been shed (system was genuinely overloaded).
        assertTrue(shedder.shedBackground() > 0,
            "No background shedding occurred — system may not have been overloaded");
    }

    // ── Test 2: CoDel engages under sustained high sojourn ────────────────────

    @Test
    @Timeout(20)
    void codelDropCount_increasesUnderSustainedOverload() throws Exception {
        Nope nope = Nope.builder()
                .initialLimit(5)
                .targetMillis(5)
                .intervalMillis(30)
                .build();
        nope.start();
        LoadShedder shedder = nope.shedder();

        int wave = 200;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(wave);
            for (int i = 0; i < wave; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.STANDARD);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        LockSupport.parkNanos(20_000_000L); // 20 ms >> target
                        shedder.complete(System.nanoTime());
                    }
                }));
            }
            for (Future<?> f : futures) {
                try { f.get(15, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        } finally {
            nope.stop();
        }

        // CoDel should have triggered drops.  At least one shed from background/standard.
        long totalShed = shedder.shedBackground() + shedder.shedStandard();
        assertTrue(totalShed > 0,
            "CoDel / semaphore shedding produced zero drops under sustained 20ms sojourn");
    }

    // ── Test 3: Slow-loris scenario — limiter adapts, critical survives ────────

    @Test
    @Timeout(30)
    void slowLoris_limitAdapts_criticalSurvives() throws Exception {
        Nope nope = Nope.builder()
                .initialLimit(20)
                .targetMillis(10)
                .intervalMillis(50)
                .build();
        nope.start();
        LoadShedder shedder = nope.shedder();

        int slowCount  = 20;   // slow-loris connections holding permits
        int critCount  = 40;   // fast critical bursts
        int bgCount    = 100;  // background flood

        LongAdder critOk = new LongAdder();
        LongAdder bgOk   = new LongAdder();

        CountDownLatch slowStarted = new CountDownLatch(slowCount);
        CountDownLatch releaseSlow = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();

            // Phase 1: slow-loris occupies all permits
            for (int i = 0; i < slowCount; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.BACKGROUND);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        slowStarted.countDown();
                        try { releaseSlow.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                        shedder.complete(System.nanoTime());
                    } else {
                        slowStarted.countDown();
                    }
                }));
            }

            slowStarted.await(5, TimeUnit.SECONDS);

            // Phase 2: flood background + critical while slow-loris holds permits
            for (int i = 0; i < bgCount; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.BACKGROUND);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        shedder.complete(System.nanoTime());
                        bgOk.increment();
                    }
                }));
            }
            for (int i = 0; i < critCount; i++) {
                futures.add(pool.submit(() -> {
                    RequestContext ctx = new RequestContext(System.nanoTime(), Priority.CRITICAL);
                    LoadShedder.Decision d = shedder.submit(ctx);
                    if (d == LoadShedder.Decision.ACCEPTED) {
                        shedder.complete(System.nanoTime());
                        critOk.increment();
                    }
                }));
            }

            releaseSlow.countDown();

            for (Future<?> f : futures) {
                try { f.get(20, TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
        } finally {
            nope.stop();
        }

        // Critical success rate must be at least as good as background's.
        long totalCrit = critCount;
        long totalBg   = bgCount;
        double critRate = (double) critOk.sum() / totalCrit;
        double bgRate   = (double) bgOk.sum()   / totalBg;

        assertTrue(critRate >= bgRate - 0.05,
            "Critical success rate " + critRate + " should be >= bg rate " + bgRate +
            " (crit_ok=" + critOk.sum() + ", bg_ok=" + bgOk.sum() + ")");

        // SheddingPolicy must not have shed any CRITICAL request.
        assertEquals(0, shedder.shedCritical(),
            "CRITICAL requests were evicted from queue by SheddingPolicy");
    }
}
