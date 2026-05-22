package com.nope;

import com.sun.net.httpserver.HttpHandler;

import java.util.function.Function;
import com.sun.net.httpserver.HttpExchange;

public final class Nope {
    private final LoadShedder shedder;
    private final NopeSemaphore semaphore;
    private final CoDelController codel;
    private final SojournTracker sojournTracker;
    private final ConcurrencyLimiter limiter;
    private final NopeMetrics metrics;

    private Nope(Builder b) {
        this.semaphore = new NopeSemaphore(b.initialLimit);
        this.codel = new CoDelController(b.targetNanos, b.intervalNanos);
        this.sojournTracker = new SojournTracker();
        this.limiter = new ConcurrencyLimiter(semaphore, sojournTracker, b.initialLimit);

        long oomGuard = (long) (Runtime.getRuntime().maxMemory() * b.oomGuardFraction);
        SheddingPolicy policy = new SheddingPolicy(oomGuard);

        this.shedder = new LoadShedder(semaphore, codel, sojournTracker, limiter, policy);
        this.metrics = new NopeMetrics(shedder, semaphore, limiter, sojournTracker, codel);
    }

    public void start() {
        limiter.start();
        metrics.register();
    }

    public void stop() {
        shedder.close();
        metrics.unregister();
    }

    public LoadShedder shedder() {
        return shedder;
    }

    public NopeMetrics metrics() {
        return metrics;
    }

    public NopeFilter filter(PriorityExtractor extractor) {
        return new NopeFilter(shedder, extractor);
    }

    public HttpHandler httpHandler(Function<HttpExchange, Priority> priorityFn, HttpHandler delegate) {
        return new NopeHttpHandler(shedder, priorityFn, delegate);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private int initialLimit = 50;
        private long targetNanos = CoDelController.TARGET_NANOS;
        private long intervalNanos = CoDelController.INTERVAL_NANOS;
        private double oomGuardFraction = 0.05;

        public Builder initialLimit(int limit) {
            if (limit <= 0) throw new IllegalArgumentException("limit must be > 0");
            this.initialLimit = limit;
            return this;
        }

        public Builder targetNanos(long nanos) {
            this.targetNanos = nanos;
            return this;
        }

        public Builder targetMillis(long millis) {
            return targetNanos(millis * 1_000_000L);
        }

        public Builder intervalNanos(long nanos) {
            this.intervalNanos = nanos;
            return this;
        }

        public Builder intervalMillis(long millis) {
            return intervalNanos(millis * 1_000_000L);
        }

        public Builder oomGuardFraction(double fraction) {
            this.oomGuardFraction = fraction;
            return this;
        }

        public Nope build() {
            return new Nope(this);
        }
    }
}
