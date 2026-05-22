package com.nope;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

public final class ConcurrencyLimiter implements AutoCloseable {
    private static final double CPU_THRESHOLD = 0.90;
    public static final int MIN_LIMIT = 2;
    public static final int MAX_LIMIT = 1000;
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private final NopeSemaphore semaphore;
    private final SojournTracker sojournTracker;
    private final LongAdder arrivals = new LongAdder();
    private final OperatingSystemMXBean osMBean;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> updateTask;

    private volatile int currentLimit;
    private volatile long windowStartNanos;

    public ConcurrencyLimiter(NopeSemaphore semaphore, SojournTracker sojournTracker, int initialLimit) {
        this.semaphore = semaphore;
        this.sojournTracker = sojournTracker;
        this.currentLimit = clamp(initialLimit);
        this.windowStartNanos = System.nanoTime();
        this.osMBean = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nope-limiter");
            t.setDaemon(true);
            return t;
        });
    }

    public void start() {
        updateTask = scheduler.scheduleAtFixedRate(this::updateLimit, 1, 1, TimeUnit.SECONDS);
    }

    public void recordArrival() {
        arrivals.increment();
    }

    private void updateLimit() {
        long now = System.nanoTime();
        long windowNanos = now - windowStartNanos;
        windowStartNanos = now;

        if (windowNanos <= 0) return;

        long totalArrivals = arrivals.sumThenReset();
        double lambda = (double) totalArrivals / (windowNanos / 1_000_000_000.0);
        double W = sojournTracker.emaSojournSeconds();

        if (W <= 0 || lambda <= 0) return;

        double lComputed = lambda * W;
        double cpu = osMBean.getProcessCpuLoad();

        int newLimit;
        if (cpu > CPU_THRESHOLD) {
            newLimit = (int) Math.min(lComputed, currentLimit);
        } else {
            newLimit = (int) lComputed;
        }

        newLimit = clamp(newLimit);
        currentLimit = newLimit;
        semaphore.updateLimit(newLimit);
    }

    private static int clamp(int v) {
        return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, v));
    }

    public int currentLimit() {
        return currentLimit;
    }

    @Override
    public void close() {
        if (updateTask != null) updateTask.cancel(false);
        scheduler.shutdown();
    }
}
