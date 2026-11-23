# NOPE Architecture

## Problem

Standard thread pool policies are blind to request priority. Under high load, they queue everything or reject tasks at random. NOPE applies network engineering rules to HTTP requests so important work keeps running.

## Components

### CoDelController

Implements RFC 8289 CoDel. State fields are volatile longs accessed by VarHandle. Zero allocation on the hot path.

- `firstAboveTime`: set when wait time goes over target.
- `dropping`: true when dropping mode is active.
- `dropCount`: tracks total drops.
- `dropNext`: timestamp for next drop.

Defaults: target is 20 ms, interval is 100 ms.

Hot path method: `shouldDrop(sojournNanos, nowNanos)`.

### NopeSemaphore

Bounded semaphore using a VarHandle on an int field. Uses CAS loops. No `AtomicInteger` or object boxing.

### SojournTracker

Records request arrival and start times. Keeps an exponential moving average of wait time for Little's Law.

### ConcurrencyLimiter

Every second:

```
lambda = arrivals / window
W      = wait time average
L      = lambda * W
cpu    = CPU load
limit  = (cpu > 0.90) ? min(L, currentLimit) : L
limit  = clamp(limit, 2, 1000)
```

CPU check prevents limit growth when the server is busy.

### Priority Queues

Three `ConcurrentLinkedQueue` tiers (CRITICAL, STANDARD, BACKGROUND). Drops lowest tier first. Critical tasks only drop if memory is low.

### LoadShedder

Orchestrates the flow:
1. Enqueue request by priority.
2. Record arrival.
3. Check CoDel drop rule.
4. Try acquiring permit.
5. Dequeue and run.

### Nope Builder

Main entry point. Wires all parts and provides servlet filters and HTTP handlers.

## Concurrency Choices

| Requirement | Choice | Rejected |
|---|---|---|
| State fields | VarHandle on plain fields | `AtomicLong` allocates objects |
| Arrival counter | `LongAdder` | `AtomicLong` has high contention |
| Queue | `ConcurrentLinkedQueue` | Array blocking queues have locks |
| Bounded permits | `NopeSemaphore` | `Semaphore` has a deep object graph |

## Allocation Budget

Zero allocations on the hot request path. All objects are created during request enqueue time.
