# NOPE Integration Guide

## Build

```
./gradlew clean build
```

Requires Java 21+. No runtime dependencies beyond the JDK.

## Jakarta Servlet (Spring Boot, Tomcat, Jetty)

```java
Nope nope = Nope.builder()
        .initialLimit(50)
        .targetMillis(20)
        .build();
nope.start();

// Register as a filter
PriorityExtractor extractor = req -> {
    String tier = req.getHeader("X-Priority");
    if ("critical".equals(tier)) return Priority.CRITICAL;
    if ("background".equals(tier)) return Priority.BACKGROUND;
    return Priority.STANDARD;
};

FilterRegistration.Dynamic reg = servletContext.addFilter("nope", nope.filter(extractor));
reg.addMappingForUrlPatterns(null, false, "/*");
```

## JDK HttpServer

```java
Nope nope = Nope.builder().initialLimit(50).build();
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
            // your handler logic
        }
));
server.start();
```

## Shutdown

```java
nope.stop(); // cancels limiter thread, unregisters JMX
```

## JMX Monitoring

Metrics are registered at `com.nope:type=NopeMetrics`:

- `Accepted` / `Rejected` - total request counts
- `ShedBackground` / `ShedStandard` / `ShedCritical` - shed counts by tier
- `CurrentLimit` - active concurrency limit from Little's Law
- `AvailablePermits` - semaphore permits available
- `EmaSojournMillis` - exponential moving average of sojourn time
- `CoDelDropping` - whether CoDel is in dropping state
- `CoDelDropCount` - total CoDel drops

## Configuration Reference

| Method | Default | Description |
|--------|---------|-------------|
| `initialLimit(int)` | 50 | Starting concurrency limit |
| `targetMillis(long)` | 20 | CoDel target sojourn (RFC 8289) |
| `intervalMillis(long)` | 100 | CoDel interval (RFC 8289) |
| `oomGuardFraction(double)` | 0.05 | Free-heap fraction below which CRITICAL sheds |

## Priority Selection

Assign CRITICAL to health-check, payment, or auth endpoints. Assign BACKGROUND to batch jobs, analytics, or cache-warmup paths. Default is STANDARD. CRITICAL is never shed unless the JVM is within 5% of OOM.

## Performance Notes

The accept/reject decision (NopeSemaphore + CoDelController) benchmarks below 1 us/op on a single core under JMH. The ConcurrencyLimiter updates its limit every 1 s on a daemon thread and does not touch the hot path.
