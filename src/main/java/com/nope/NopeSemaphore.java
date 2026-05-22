package com.nope;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class NopeSemaphore {
    private static final VarHandle PERMITS;
    private static final VarHandle MAX_PERMITS;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            PERMITS = lookup.findVarHandle(NopeSemaphore.class, "permits", int.class);
            MAX_PERMITS = lookup.findVarHandle(NopeSemaphore.class, "maxPermits", int.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int permits;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int maxPermits;

    public NopeSemaphore(int maxPermits) {
        if (maxPermits <= 0) throw new IllegalArgumentException("maxPermits must be > 0");
        this.maxPermits = maxPermits;
        this.permits = maxPermits;
    }

    public boolean tryAcquire() {
        int current;
        do {
            current = (int) PERMITS.getVolatile(this);
            if (current <= 0) return false;
        } while (!PERMITS.compareAndSet(this, current, current - 1));
        return true;
    }

    public void release() {
        int current;
        int max;
        do {
            current = (int) PERMITS.getVolatile(this);
            max = (int) MAX_PERMITS.getVolatile(this);
            if (current >= max) return;
        } while (!PERMITS.compareAndSet(this, current, current + 1));
    }

    public void updateLimit(int newMax) {
        if (newMax <= 0) newMax = 1;
        int oldMax = (int) MAX_PERMITS.getAndSet(this, newMax);
        int delta = newMax - oldMax;
        if (delta > 0) {
            PERMITS.getAndAdd(this, delta);
        }
        // If limit shrinks, in-flight requests naturally drain below the new max.
    }

    public int available() {
        return Math.max(0, (int) PERMITS.getVolatile(this));
    }

    public int maxPermits() {
        return (int) MAX_PERMITS.getVolatile(this);
    }
}
