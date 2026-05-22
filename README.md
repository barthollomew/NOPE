# NOPE

Adaptive load-shedding library for Java 21+. Applies CoDel (RFC 8289) and
Little's Law to HTTP request handling. Sheds low-priority traffic before the
system saturates. Sub-microsecond accept/reject decision on the hot path.

## How it works

- **CoDel controller**: tracks queue sojourn time. When sojourn exceeds the
  target (default 20 ms) for longer than the interval (default 100 ms), it
  enters dropping state and sheds at an accelerating rate.
- **Concurrency limiter**: every 1 s, computes `L = lambda * W` (Little's Law)
  from a `LongAdder` arrival counter and an EMA of sojourn time. Clamps the
  limit if CPU load exceeds 90%.
- **Priority queues**: three `ConcurrentLinkedQueue` tiers (CRITICAL, STANDARD,
  BACKGROUND). Shedding always pulls from the lowest non-empty tier. CRITICAL
  is never shed unless the JVM is within 5% of OOM.
- **NopeSemaphore**: VarHandle CAS on a plain `int` field. No `AtomicInteger`,
  no boxing, zero allocation on the hot path.

## Install

Requires Java 21+. No runtime dependencies beyond the JDK.

```bash
./gradlew clean build
```

## Usage

### JDK HttpServer

```java
Nope nope = Nope.builder()
        .initialLimit(50)
        .targetMillis(20)
        .build();
nope.start();

HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
server.createContext("/", nope.httpHandler(
        exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/admin")) return Priority.CRITICAL;
            if (path.startsWith("/batch")) return Priority.BACKGROUND;
            return Priority.STANDARD;
        },
        exchange -> {
            byte[] body = "ok".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        }
));
server.start();

// On shutdown
nope.stop();
```

### Jakarta Servlet filter

```java
Nope nope = Nope.builder().initialLimit(100).build();
nope.start();

PriorityExtractor extractor = req -> {
    String tier = req.getHeader("X-Priority");
    if ("critical".equals(tier)) return Priority.CRITICAL;
    if ("background".equals(tier)) return Priority.BACKGROUND;
    return Priority.STANDARD;
};

FilterRegistration.Dynamic reg =
        servletContext.addFilter("nope", nope.filter(extractor));
reg.addMappingForUrlPatterns(null, false, "/*");
```

Rejected requests receive HTTP 503.

## Configuration

| Builder method | Default | Effect |
|---|---|---|
| `initialLimit(int)` | 50 | Starting concurrency limit |
| `targetMillis(long)` | 20 | CoDel target sojourn time |
| `intervalMillis(long)` | 100 | CoDel interval |
| `oomGuardFraction(double)` | 0.05 | Free-heap fraction below which CRITICAL sheds |

## JMX

Metrics registered at `com.nope:type=NopeMetrics`:

```
Accepted, Rejected
ShedBackground, ShedStandard, ShedCritical
CurrentLimit, AvailablePermits
EmaSojournMillis
CoDelDropping, CoDelDropCount
```

## Benchmarks

```bash
./gradlew jmh
```

End-to-end accept/reject decision targets below 1 us/op (`NopeAcceptBenchmark`).
`CoDelBenchmark` isolates the `shouldDrop()` call.

## Tests

```bash
./gradlew test
```

25 tests. Includes a chaos integration test (`LoadShedderIntegrationTest`) that
fires 5x overload at a JDK HttpServer and asserts the server stays alive with
background traffic shed first.

## Design notes

See [ARCHITECTURE.md](ARCHITECTURE.md) for concurrency choices and data flow.
See [INTEGRATION.md](INTEGRATION.md) for full configuration reference.
