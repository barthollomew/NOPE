package com.nope;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode({Mode.AverageTime, Mode.SampleTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@Fork(1)
public class CoDelBenchmark {
    private CoDelController codel;
    private long sojournBelowTarget;
    private long sojournAboveTarget;

    @Setup
    public void setup() {
        codel = new CoDelController();
        sojournBelowTarget = CoDelController.TARGET_NANOS / 2;
        sojournAboveTarget = CoDelController.TARGET_NANOS * 3;
    }

    @Benchmark
    public boolean shouldDrop_belowTarget() {
        return codel.shouldDrop(sojournBelowTarget, System.nanoTime());
    }

    @Benchmark
    public boolean shouldDrop_aboveTarget() {
        return codel.shouldDrop(sojournAboveTarget, System.nanoTime());
    }
}
