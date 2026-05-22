package com.nope;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class LoadShedder implements AutoCloseable {
    private final ConcurrentLinkedQueue<RequestContext> criticalQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestContext> standardQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<RequestContext> backgroundQueue = new ConcurrentLinkedQueue<>();

    private final NopeSemaphore semaphore;
    private final CoDelController codel;
    private final SojournTracker sojournTracker;
    private final ConcurrencyLimiter limiter;
    private final SheddingPolicy sheddingPolicy;

    // Shed counters (no boxing needed, accessed via plain volatile reads)
    private volatile long shedBackground;
    private volatile long shedStandard;
    private volatile long shedCritical;
    private volatile long accepted;
    private volatile long rejected;

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
            rejected++;
            removeFromQueue(ctx);
            return Decision.REJECTED;
        }

        // Dequeue in priority order
        RequestContext next = dequeue();
        if (next == null) {
            semaphore.release();
            rejected++;
            return Decision.REJECTED;
        }

        next.markStart(nowNanos);
        sojournTracker.recordArrival(next.arrivalNanos());
        sojournTracker.recordStart(nowNanos);
        accepted++;
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
            case BACKGROUND -> shedBackground++;
            case STANDARD -> shedStandard++;
            case CRITICAL -> shedCritical++;
        }
    }

    public long shedBackground() { return shedBackground; }
    public long shedStandard() { return shedStandard; }
    public long shedCritical() { return shedCritical; }
    public long accepted() { return accepted; }
    public long rejected() { return rejected; }

    public int criticalQueueSize() { return criticalQueue.size(); }
    public int standardQueueSize() { return standardQueue.size(); }
    public int backgroundQueueSize() { return backgroundQueue.size(); }

    @Override
    public void close() {
        limiter.close();
    }
}
