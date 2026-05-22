package com.nope;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class SojournTracker {
    private static final VarHandle ARRIVAL;
    private static final VarHandle START;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            ARRIVAL = lookup.findVarHandle(SojournTracker.class, "arrivalNanos", long.class);
            START = lookup.findVarHandle(SojournTracker.class, "startNanos", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long arrivalNanos;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long startNanos;

    // EMA of sojourn time in nanoseconds (alpha = 0.125)
    private volatile double emaSojournNanos;
    private static final double ALPHA = 0.125;

    public SojournTracker() {
        this.arrivalNanos = 0L;
        this.startNanos = 0L;
        this.emaSojournNanos = 0.0;
    }

    public void recordArrival(long nanos) {
        ARRIVAL.setVolatile(this, nanos);
    }

    public void recordStart(long nanos) {
        START.setVolatile(this, nanos);
    }

    public long sojournNanos(long nowNanos) {
        long start = (long) START.getVolatile(this);
        long arrival = (long) ARRIVAL.getVolatile(this);
        if (start > 0) {
            return nowNanos - start;
        } else if (arrival > 0) {
            return nowNanos - arrival;
        }
        return 0L;
    }

    public void recordCompletion(long nowNanos) {
        long sojourn = sojournNanos(nowNanos);
        if (sojourn > 0) {
            double ema = emaSojournNanos;
            emaSojournNanos = (ema == 0.0) ? sojourn : ema + ALPHA * (sojourn - ema);
        }
    }

    public double emaSojournNanos() {
        return emaSojournNanos;
    }

    public double emaSojournSeconds() {
        return emaSojournNanos / 1_000_000_000.0;
    }

    public void reset() {
        ARRIVAL.setVolatile(this, 0L);
        START.setVolatile(this, 0L);
    }
}
