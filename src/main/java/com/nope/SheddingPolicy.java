package com.nope;

import java.util.concurrent.ConcurrentLinkedQueue;

public final class SheddingPolicy {
    private final long oomGuardBytes;

    public SheddingPolicy(long oomGuardBytes) {
        this.oomGuardBytes = oomGuardBytes;
    }

    public RequestContext selectForShed(
            ConcurrentLinkedQueue<RequestContext> background,
            ConcurrentLinkedQueue<RequestContext> standard,
            ConcurrentLinkedQueue<RequestContext> critical) {

        boolean oomRisk = isOomRisk();

        // Shed background first
        RequestContext ctx = background.poll();
        if (ctx != null) return ctx;

        // Then standard
        ctx = standard.poll();
        if (ctx != null) return ctx;

        // Only shed critical under OOM pressure
        if (oomRisk) {
            return critical.poll();
        }

        return null;
    }

    boolean isOomRisk() {
        Runtime rt = Runtime.getRuntime();
        long free = rt.freeMemory();
        long max = rt.maxMemory();
        return free < oomGuardBytes || (max > 0 && free < max * 0.05);
    }
}
