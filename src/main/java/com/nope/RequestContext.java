package com.nope;

public final class RequestContext {
    private final long arrivalNanos;
    private final Priority priority;
    private volatile long startNanos;

    public RequestContext(long arrivalNanos, Priority priority) {
        this.arrivalNanos = arrivalNanos;
        this.priority = priority;
        this.startNanos = 0L;
    }

    public long arrivalNanos() {
        return arrivalNanos;
    }

    public Priority priority() {
        return priority;
    }

    public long startNanos() {
        return startNanos;
    }

    public void markStart(long nanos) {
        this.startNanos = nanos;
    }

    public long sojournNanos(long nowNanos) {
        long start = startNanos;
        return (start > 0) ? nowNanos - start : nowNanos - arrivalNanos;
    }
}
