# NOPE

Adaptive load shedding library for Java. Uses CoDel and Little's Law to control HTTP request flow. Drops low priority traffic before the system gets slow. Fast decision path on every request.

## JMH Benchmark Results

| Benchmark | Mean | p99 |
|---|---|---|
| `CoDelController.shouldDrop` (above target) | 24 ns | 51 ns |
| `CoDelController.shouldDrop` (below target) | 28 ns | 61 ns |
| `acceptAndRelease` (critical) | 110 ns | 161 ns |
| `acceptAndRelease` (background) | 110 ns | 161 ns |

## How it works

- **CoDel controller**: Tracks queue wait time. When wait time goes over target (20 ms) for longer than the interval (100 ms), it starts dropping requests at a faster rate.
- **Concurrency limiter**: Every second, computes capacity using Little's Law. Lowers limit if CPU load goes over 90 percent.
- **Priority queues**: Three tiers (CRITICAL, STANDARD, BACKGROUND). Drops lowest tier first. Critical requests are never dropped unless memory is very low.
- **NopeSemaphore**: Uses VarHandle CAS on a plain int field. No boxing or extra object creation on the hot path.

## Install

Requires Java 21 or newer. No runtime dependencies.

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

Rejected requests get HTTP 503.

## Configuration

| Builder method | Default | Effect |
|---|---|---|
| `initialLimit(int)` | 50 | Starting limit |
| `targetMillis(long)` | 20 | CoDel target wait time |
| `intervalMillis(long)` | 100 | CoDel interval |
| `oomGuardFraction(double)` | 0.05 | Free heap fraction below which critical tasks drop |

## JMX

Metrics are at `com.nope:type=NopeMetrics`:

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

## Tests

```bash
./gradlew test
```

## Design notes

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details.
See [INTEGRATION.md](INTEGRATION.md) for setup guide.
