# NOPE

Adaptive load-shedding library for Java 21+. Applies CoDel (RFC 8289) and Little's Law to HTTP request handling with priority-aware shedding.

- Sub-microsecond accept/reject decision on the happy path
- Zero allocations on the hot path
- VarHandle lock-free semaphore and state machine
- Three priority tiers: CRITICAL, STANDARD, BACKGROUND
- Dynamic concurrency limit via Little's Law + CPU clamp
- Jakarta Servlet and JDK HttpServer adapters

## Build

```
./gradlew clean build
./gradlew test
./gradlew jmh
```

See [ARCHITECTURE.md](ARCHITECTURE.md) for design and [INTEGRATION.md](INTEGRATION.md) for usage.
