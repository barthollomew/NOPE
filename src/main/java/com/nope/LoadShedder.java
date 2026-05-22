package com.nope;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

public final class LoadShedder implements AutoCloseable {
    private final ConcurrentLinkedQueue<RequestContext> criticalQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestContext> standardQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestContext> backgroundQueue = new ConcurrentLinkedQueue<>();

    private final NopeSemaphore semaphore;
    private final CoDelController codel;
    private final SojournTracker sojournTracker;
    private final ConcurrencyLimiter limiter;
    private final SheddingPolicy sheddingPolicy;

    // Shed counters: LongAdder avoids the data-race of volatile-long++ under concurrent access.
    private final LongAdder shedBackground = new LongAdder();
    private final LongAdder shedStandard   = new LongAdder();
    private final LongAdder shedCritical   = new LongAdder();
    private final LongAdder accepted       = new LongAdder();
    private final LongAdder rejected       = new LongAdder();

    public LoadShedder(NopeSemaphore semaphore,
                       CoDelController codel,
                       SojournTracker sojournTracker,
                       ConcurrencyLimiter limiter,
                       SheddingPolicy sheddingPolicy) {
        this.semaphore = semaphore;
        this.codel = codel;
        this.sojournTracker = sojournTracker;
        this.limiter = limiter;
        this.sheddingPolicy = sheddingPolicy;
    }

    public enum Decision { ACCEPTED, REJECTED }

    public Decision submit(RequestContext ctx) {
        long nowNanos = System.nanoTime();

        // Enqueue by priority
        switch (ctx.priority()) {
            case CRITICAL -> criticalQueue.offer(ctx);
            case STANDARD -> standardQueue.offer(ctx);
            case BACKGROUND -> backgroundQueue.offer(ctx);
        }

        limiter.recordArrival();

        // Check CoDel on the queue sojourn
        long sojourn = ctx.sojournNanos(nowNanos);
        if (codel.shouldDrop(sojourn, nowNanos)) {
            shed(nowNanos);
        }

        // Try to acquire a permit
        if (!semaphore.tryAcquire()) {
            // Backpressure: shed lowest priority to make room, then reject this
            shed(nowNanos);
            rejected.increment();
            removeFromQueue(ctx);
            return Decision.REJECTED;
        }

        // Dequeue in priority order
        RequestContext next = dequeue();
        if (next == null) {
            semaphore.release();
            rejected.increment();
            return Decision.REJECTED;
        }

        next.markStart(nowNanos);
        sojournTracker.recordArrival(next.arrivalNanos());
        sojournTracker.recordStart(nowNanos);
        accepted.increment();
        return Decision.ACCEPTED;
    }

    public void complete(long nowNanos) {
        sojournTracker.recordCompletion(nowNanos);
        semaphore.release();
    }

    private RequestContext dequeue() {
        RequestContext ctx = criticalQueue.poll();
        if (ctx != null) return ctx;
        ctx = standardQueue.poll();
        if (ctx != null) return ctx;
        return backgroundQueue.poll();
    }

    private void removeFromQueue(RequestContext ctx) {
        switch (ctx.priority()) {
            case CRITICAL -> criticalQueue.remove(ctx);
            case STANDARD -> standardQueue.remove(ctx);
            case BACKGROUND -> backgroundQueue.remove(ctx);
        }
    }

    private void shed(long nowNanos) {
        RequestContext victim = sheddingPolicy.selectForShed(backgroundQueue, standardQueue, criticalQueue);
        if (victim == null) return;
        switch (victim.priority()) {
            case BACKGROUND -> shedBackground.increment();
            case STANDARD   -> shedStandard.increment();
            case CRITICAL   -> shedCritical.increment();
        }
    }

    public long shedBackground() { return shedBackground.sum(); }
    public long shedStandard()   { return shedStandard.sum(); }
    public long shedCritical()   { return shedCritical.sum(); }
    public long accepted()       { return accepted.sum(); }
    public long rejected()       { return rejected.sum(); }

    public int criticalQueueSize() { return criticalQueue.size(); }
    public int standardQueueSize() { return standardQueue.size(); }
    public int backgroundQueueSize() { return backgroundQueue.size(); }

    @Override
    public void close() {
        limiter.close();
    }
}
