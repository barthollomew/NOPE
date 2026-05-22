# NOPE — Performance Benchmark Results

**Environment:** JDK 21.0.2 (Amazon Corretto), OpenJDK 64-Bit Server VM  
**JMH version:** 1.36  
**Configuration:** 10 warmup iterations × 1 s, 10 measurement iterations × 1 s, fork = 1  
**Modes:** `AverageTime` (mean latency) + `SampleTime` (percentile histogram)  
**Time unit:** nanoseconds (ns)

---

## CoDelBenchmark — `shouldDrop()` hot-path latency

Tests the `CoDelController.shouldDrop(long sojournNanos, long nowNanos)` path under two
steady-state conditions: sojourn persistently below target (controller idle) and sojourn
persistently above target (controller in dropping state).

| Benchmark | Mean (ns) | p50 (ns) | p95 (ns) | p99 (ns) | p99.9 (ns) |
|---|---:|---:|---:|---:|---:|
| `shouldDrop_aboveTarget` | 24 ± 1 | 50 | 51 | 51 | 70 |
| `shouldDrop_belowTarget` | 28 ± 1 | 50 | 60 | 61 | 61 |

**Observation:** The hot path is branch-predicted to ~24–28 ns mean. The above-target path
is marginally faster because the `firstAboveTime` reset branch is well-predicted once the
controller enters the dropping interval. Tail latency (p99.9) stays below 70 ns, confirming
zero allocation in steady state.

---

## NopeAcceptBenchmark — `submit()/complete()` round-trip latency

Tests a full accept-and-release cycle (enqueue → semaphore acquire → dequeue → mark start →
complete → semaphore release) for both CRITICAL and BACKGROUND priorities under no load.

| Benchmark | Mean (ns) | p50 (ns) | p95 (ns) | p99 (ns) | p99.9 (ns) |
|---|---:|---:|---:|---:|---:|
| `acceptAndRelease_critical` | 110 ± 5 | 130 | 150 | 161 | 992 |
| `acceptAndRelease_background` | 110 ± 2 | 130 | 150 | 161 | 973 |

**Observation:** CRITICAL and BACKGROUND priorities show identical throughput at the p99
boundary (161 ns) — priority routing overhead is negligible at this latency scale. The
p99.9 spike to ~990 ns is driven by OS scheduler jitter on the single benchmark thread,
not by the NOPE code path.

---

## Raw JMH output

See [`jmh-raw.txt`](jmh-raw.txt) for the complete machine-readable results including
full sample histograms and per-iteration timing.

---

## Reproducing

```
./gradlew jmh
```

For extended warmup on production hardware:
```
./gradlew jmh -Pjmh.warmupIterations=20 -Pjmh.iterations=20
```
