# NOPE

Adaptive load shedding library for Java. When traffic spikes 5x, standard Java thread pools treat every request the same. Slow report exports and batch syncs clog the queue while login and checkout time out. NOPE solves this problem for Java backend systems.

## What NOPE Does

You wrap your HTTP handler or Servlet filter with three priority tiers: `CRITICAL`, `STANDARD`, and `BACKGROUND`. 

When queue wait time rises above 20 milliseconds, NOPE automatically drops `BACKGROUND` and `STANDARD` requests with HTTP `503` errors. This keeps `CRITICAL` requests running fast.

## JMH Benchmark Results

| Benchmark | Mean | p99 |
|---|---|---|
| `CoDelController.shouldDrop` (above target) | 24 ns | 51 ns |
| `CoDelController.shouldDrop` (below target) | 28 ns | 61 ns |
| `acceptAndRelease` (critical) | 110 ns | 161 ns |
| `acceptAndRelease` (background) | 110 ns | 161 ns |

## How It Works

- **CoDel controller**: Tracks queue wait time. When wait time goes over 20 ms for longer than 100 ms, it starts dropping requests.
- **Concurrency limiter**: Every second, computes capacity using Little's Law. Lowers limits if CPU load goes over 90 percent.
- **Priority queues**: Three tiers using concurrent queues. Drops lowest tier first. Critical tasks only drop if memory is low.
- **NopeSemaphore**: Uses VarHandle CAS on a plain int field. Zero allocation on the hot path.

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

28 tests pass successfully.

## Design Notes

See [ARCHITECTURE.md](ARCHITECTURE.md) for design details.
See [INTEGRATION.md](INTEGRATION.md) for setup guide.
