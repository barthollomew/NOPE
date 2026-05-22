package com.nope;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NopeSemaphoreTest {

    @Test
    void acquireAndRelease_basic() {
        NopeSemaphore sem = new NopeSemaphore(3);
        assertEquals(3, sem.available());
        assertTrue(sem.tryAcquire());
        assertEquals(2, sem.available());
        sem.release();
        assertEquals(3, sem.available());
    }

    @Test
    void acquireBeyondLimit_returnsFalse() {
        NopeSemaphore sem = new NopeSemaphore(2);
        assertTrue(sem.tryAcquire());
        assertTrue(sem.tryAcquire());
        assertFalse(sem.tryAcquire());
    }

    @Test
    void overRelease_doesNotExceedMax() {
        NopeSemaphore sem = new NopeSemaphore(2);
        sem.release();
        sem.release();
        assertEquals(2, sem.available());
    }

    @Test
    void updateLimit_expandsPermits() {
        NopeSemaphore sem = new NopeSemaphore(5);
        assertTrue(sem.tryAcquire());
        assertTrue(sem.tryAcquire());
        sem.updateLimit(10);
        assertEquals(10, sem.maxPermits());
        // 8 permits should be available (10 - 2 in flight)
        assertEquals(8, sem.available());
    }

    @Test
    void concurrentAcquireRelease_neverExceedsLimit() throws InterruptedException {
        int limit = 10;
        int threads = 50;
        NopeSemaphore sem = new NopeSemaphore(limit);
        AtomicInteger maxInFlight = new AtomicInteger(0);
        AtomicInteger inFlight = new AtomicInteger(0);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 100; j++) {
                        if (sem.tryAcquire()) {
                            int cur = inFlight.incrementAndGet();
                            int max = maxInFlight.get();
                            while (cur > max && !maxInFlight.compareAndSet(max, cur)) {
                                max = maxInFlight.get();
                            }
                            Thread.yield();
                            inFlight.decrementAndGet();
                            sem.release();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        done.await();
        assertTrue(maxInFlight.get() <= limit,
                "max in-flight " + maxInFlight.get() + " exceeded limit " + limit);
    }
}
