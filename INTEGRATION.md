# NOPE Integration Guide

## Build

```bash
./gradlew clean build
```

Requires Java 21 or newer. No extra runtime dependencies.

## Jakarta Servlet

```java
Nope nope = Nope.builder()
        .initialLimit(50)
        .targetMillis(20)
        .build();
nope.start();

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
            // handler logic
        }
));
server.start();
```

## Shutdown

```java
nope.stop();
```

## JMX Monitoring

Metrics are registered at `com.nope:type=NopeMetrics`:

- `Accepted` and `Rejected` counts
- Shed counts by priority tier
- `CurrentLimit` from Little's Law
- `AvailablePermits`
- `EmaSojournMillis`
- CoDel dropping state and drop count

## Configuration Reference

| Method | Default | Description |
|---|---|---|
| `initialLimit(int)` | 50 | Starting limit |
| `targetMillis(long)` | 20 | CoDel target wait time |
| `intervalMillis(long)` | 100 | CoDel interval |
| `oomGuardFraction(double)` | 0.05 | Free heap fraction below which critical tasks drop |

## Priority Selection

Use CRITICAL for health checks, payments, and auth. Use BACKGROUND for batch jobs and analytics. Default is STANDARD.

## Performance Notes

The request admission decision runs in under one microsecond. The concurrency limiter runs on a background thread every second and does not touch the request hot path.
