package com.nope;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class NopeAcceptBenchmark {
    private LoadShedder shedder;
    private Nope nope;

    @Setup
    public void setup() {
        nope = Nope.builder()
                .initialLimit(100)
                .build();
        nope.start();
        shedder = nope.shedder();
    }

    @TearDown
    public void teardown() {
        nope.stop();
    }

    @Benchmark
    public LoadShedder.Decision acceptAndRelease_critical() {
        long arrival = System.nanoTime();
        RequestContext ctx = new RequestContext(arrival, Priority.CRITICAL);
        LoadShedder.Decision d = shedder.submit(ctx);
        if (d == LoadShedder.Decision.ACCEPTED) {
            shedder.complete(System.nanoTime());
        }
        return d;
    }

    @Benchmark
    public LoadShedder.Decision acceptAndRelease_background() {
        long arrival = System.nanoTime();
        RequestContext ctx = new RequestContext(arrival, Priority.BACKGROUND);
        LoadShedder.Decision d = shedder.submit(ctx);
        if (d == LoadShedder.Decision.ACCEPTED) {
            shedder.complete(System.nanoTime());
        }
        return d;
    }
}
