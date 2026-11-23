# NOPE Performance Benchmark Results

**Environment:** JDK 21.0.2 (Amazon Corretto), OpenJDK 64-Bit Server VM  
**JMH version:** 1.36  
**Configuration:** 10 warmup iterations by 1 s, 10 measurement iterations by 1 s, fork = 1  
**Modes:** AverageTime and SampleTime  
**Time unit:** nanoseconds (ns)

---

## CoDelBenchmark and shouldDrop Latency

Tests the `CoDelController.shouldDrop` path under two states: wait time below target and wait time above target.

| Benchmark | Mean (ns) | p50 (ns) | p95 (ns) | p99 (ns) | p99.9 (ns) |
|---|---:|---:|---:|---:|---:|
| `shouldDrop_aboveTarget` | 24 | 50 | 51 | 51 | 70 |
| `shouldDrop_belowTarget` | 28 | 50 | 60 | 61 | 61 |

**Observation:** The hot path runs in 24 to 28 ns mean. Tail latency stays low with zero allocation in steady state.

---

## NopeAcceptBenchmark Round Trip Latency

Tests a full accept and release cycle for critical and background priorities.

| Benchmark | Mean (ns) | p50 (ns) | p95 (ns) | p99 (ns) | p99.9 (ns) |
|---|---:|---:|---:|---:|---:|
| `acceptAndRelease_critical` | 110 | 130 | 150 | 161 | 992 |
| `acceptAndRelease_background` | 110 | 130 | 150 | 161 | 973 |

**Observation:** Critical and background priorities show identical performance at p99 (161 ns). Priority overhead is extremely small.

---

## Reproducing

```bash
./gradlew jmh
```
