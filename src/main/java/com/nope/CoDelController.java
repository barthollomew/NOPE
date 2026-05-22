package com.nope;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class CoDelController {
    // RFC 8289 defaults
    public static final long TARGET_NANOS = 20_000_000L;   // 20 ms
    public static final long INTERVAL_NANOS = 100_000_000L; // 100 ms

    private static final VarHandle DROPPING;
    private static final VarHandle DROP_COUNT;
    private static final VarHandle DROP_NEXT;
    private static final VarHandle FIRST_ABOVE_TIME;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            DROPPING = lookup.findVarHandle(CoDelController.class, "dropping", boolean.class);
            DROP_COUNT = lookup.findVarHandle(CoDelController.class, "dropCount", int.class);
            DROP_NEXT = lookup.findVarHandle(CoDelController.class, "dropNext", long.class);
            FIRST_ABOVE_TIME = lookup.findVarHandle(CoDelController.class, "firstAboveTime", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long firstAboveTime; // 0 = not tracking

    @SuppressWarnings("FieldMayBeFinal")
    private volatile boolean dropping;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile int dropCount;

    @SuppressWarnings("FieldMayBeFinal")
    private volatile long dropNext;

    private final long targetNanos;
    private final long intervalNanos;

    public CoDelController() {
        this(TARGET_NANOS, INTERVAL_NANOS);
    }

    public CoDelController(long targetNanos, long intervalNanos) {
        this.targetNanos = targetNanos;
        this.intervalNanos = intervalNanos;
        this.firstAboveTime = 0L;
        this.dropping = false;
        this.dropCount = 0;
        this.dropNext = 0L;
    }

    public boolean shouldDrop(long sojournNanos, long nowNanos) {
        boolean okToDrop = updateFirstAboveTime(sojournNanos, nowNanos);

        boolean isDropping = (boolean) DROPPING.getVolatile(this);

        if (!okToDrop) {
            if (isDropping) {
                DROPPING.setVolatile(this, false);
            }
            return false;
        }

        if (!isDropping) {
            // Transition into dropping state
            long next = (long) DROP_NEXT.getVolatile(this);
            if (next == 0L || nowNanos - next < intervalNanos) {
                // Not in a previous drop interval; start fresh
                DROPPING.setVolatile(this, true);
                int count = (int) DROP_COUNT.getAndAdd(this, 1);
                DROP_NEXT.setVolatile(this, nowNanos + dropInterval(count + 1));
                return true;
            }
        } else {
            // Already dropping; check if we've reached the next drop time
            long next = (long) DROP_NEXT.getVolatile(this);
            if (nowNanos >= next) {
                int count = (int) DROP_COUNT.getAndAdd(this, 1);
                DROP_NEXT.setVolatile(this, next + dropInterval(count + 1));
                return true;
            }
        }

        return false;
    }

    private boolean updateFirstAboveTime(long sojournNanos, long nowNanos) {
        if (sojournNanos < targetNanos) {
            // Below target: reset tracking
            FIRST_ABOVE_TIME.setVolatile(this, 0L);
            return false;
        }

        long fat = (long) FIRST_ABOVE_TIME.getVolatile(this);
        if (fat == 0L) {
            // First time exceeding target: set the deadline
            FIRST_ABOVE_TIME.setVolatile(this, nowNanos + intervalNanos);
            return false;
        }

        return nowNanos >= fat;
    }

    private long dropInterval(int count) {
        // interval / sqrt(count)
        if (count <= 0) count = 1;
        return (long) (intervalNanos / Math.sqrt(count));
    }

    public void reset() {
        FIRST_ABOVE_TIME.setVolatile(this, 0L);
        DROPPING.setVolatile(this, false);
        DROP_COUNT.setVolatile(this, 0);
        DROP_NEXT.setVolatile(this, 0L);
    }

    public boolean isDropping() {
        return (boolean) DROPPING.getVolatile(this);
    }

    public int dropCount() {
        return (int) DROP_COUNT.getVolatile(this);
    }

    public long targetNanos() {
        return targetNanos;
    }

    public long intervalNanos() {
        return intervalNanos;
    }
}
