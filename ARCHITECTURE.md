# NOPE Architecture

## Problem

Standard thread-pool rejection policies (AbortPolicy, CallerRunsPolicy) are binary and unaware of request priority. Under flash load, they either queue without bound or reject uniformly, dropping critical traffic alongside background noise. NOPE applies network-engineering principles to HTTP request handling.

## Components

### CoDelController

Implements RFC 8289 (CoDel). State machine fields are plain volatile longs on VarHandle - no AtomicLong objects, zero allocation on the hot path.

- `firstAboveTime`: set to `now + INTERVAL` the first time sojourn exceeds TARGET; reset to 0 when sojourn drops below TARGET.
- `dropping`: flip to true when `now >= firstAboveTime`.
- `dropCount`: incremented on each drop; drop interval = `INTERVAL / sqrt(dropCount)`.
- `dropNext`: nanos timestamp of next forced drop.

Defaults: TARGET = 20 ms, INTERVAL = 100 ms (per RFC 8289).

Hot path: `shouldDrop(sojournNanos, nowNanos)` - one method, no allocations.

### NopeSemaphore

Bounded semaphore using a VarHandle on a plain `int permits` field. CAS loop in `tryAcquire()`, fetch-and-add in `release()`. No `AtomicInteger`, no boxing. Supports `updateLimit()` for dynamic resize by ConcurrencyLimiter.

### SojournTracker

Records arrival and service-start nanos. Maintains an EMA (alpha=0.125) of observed sojourn for the Little's Law computation. Exposed via JMX.

### ConcurrencyLimiter

Every 1 s:

```
lambda = arrivals.sumThenReset() / window_seconds   // LongAdder
W      = ema of observed sojourn seconds             // SojournTracker
L      = lambda * W                                  // Little's Law
cpu    = OperatingSystemMXBean.getProcessCpuLoad()
limit  = (cpu > 0.90) ? min(L, currentLimit) : L
limit  = clamp(limit, MIN=2, MAX=1000)
```

CPU clamp prevents limit growth during saturation. `OperatingSystemMXBean` is a zero-dependency JDK API.

### Priority Queues

Three `ConcurrentLinkedQueue<RequestContext>` instances (CRITICAL, STANDARD, BACKGROUND). Dequeue order: CRITICAL first, then STANDARD, then BACKGROUND. One global `CoDelController` measures sojourn across all tiers. When shedding, `SheddingPolicy` selects from the lowest non-empty priority queue. CRITICAL is never dropped unless `Runtime.freeMemory()` falls below the OOM guard threshold (5% of max heap).

### LoadShedder

Orchestrates all components:
1. Enqueue incoming `RequestContext` by priority.
2. Call `limiter.recordArrival()`.
3. Check `codel.shouldDrop()` and shed if true.
4. `semaphore.tryAcquire()` - reject if full, shedding a lower-priority request first.
5. Dequeue in CRITICAL -> STANDARD -> BACKGROUND order and mark start.

### Nope (Builder)

Single entry point. Fluent builder wires all components. Provides `filter(PriorityExtractor)` for Jakarta Servlet containers and `httpHandler(priorityFn, delegate)` for JDK HttpServer.

## Concurrency Guarantees

| Need | Tool | Alternative rejected |
|------|------|---------------------|
| State machine fields | VarHandle on plain fields | AtomicLong allocates an object |
| Arrival rate counter | LongAdder | AtomicLong contends under high write load |
| Per-request queue | ConcurrentLinkedQueue | Allocation at enqueue, not on shed decision |
| Bounded in-flight | NopeSemaphore (VarHandle CAS) | j.u.c.Semaphore has deeper object graph |

## Allocation Budget

Happy-path allocation: zero. All allocations occur at request enqueue time (RequestContext), not during the admit/reject decision.
