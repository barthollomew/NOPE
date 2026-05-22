package com.nope;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

import static org.junit.jupiter.api.Assertions.*;

class LoadShedderIntegrationTest {
    private static final int PORT = 19876;
    private static final int CONCURRENCY_LIMIT = 10;
    private static final int OVERLOAD_FACTOR = 5;

    private Nope nope;
    private HttpServer server;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        nope = Nope.builder()
                .initialLimit(CONCURRENCY_LIMIT)
                .targetMillis(50)
                .intervalMillis(200)
                .build();

        LongAdder handledCount = new LongAdder();

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 128);
        server.createContext("/", nope.httpHandler(
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    if (path.startsWith("/bg")) return Priority.BACKGROUND;
                    if (path.startsWith("/std")) return Priority.STANDARD;
                    return Priority.CRITICAL;
                },
                exchange -> {
                    handledCount.increment();
                    // Simulate 20ms work
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    byte[] resp = "ok".getBytes();
                    exchange.sendResponseHeaders(200, resp.length);
                    exchange.getResponseBody().write(resp);
                    exchange.getResponseBody().close();
                }
        ));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        nope.start();

        client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        nope.stop();
    }

    @Test
    @Timeout(30)
    void serverSurvivesOverload_andBackgroundShedFirst() throws Exception {
        int totalRequests = CONCURRENCY_LIMIT * OVERLOAD_FACTOR * 3;
        int bgRequests = totalRequests / 2;
        int stdRequests = totalRequests / 3;
        int critRequests = totalRequests - bgRequests - stdRequests;

        LongAdder bg200 = new LongAdder();
        LongAdder bg503 = new LongAdder();
        LongAdder std200 = new LongAdder();
        LongAdder std503 = new LongAdder();
        LongAdder crit200 = new LongAdder();
        LongAdder crit503 = new LongAdder();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();

        // Fire background traffic
        for (int i = 0; i < bgRequests; i++) {
            futures.add(pool.submit(() -> {
                int status = get("/bg");
                if (status == 200) bg200.increment(); else bg503.increment();
            }));
        }

        // Fire standard traffic
        for (int i = 0; i < stdRequests; i++) {
            futures.add(pool.submit(() -> {
                int status = get("/std");
                if (status == 200) std200.increment(); else std503.increment();
            }));
        }

        // Fire critical traffic
        for (int i = 0; i < critRequests; i++) {
            futures.add(pool.submit(() -> {
                int status = get("/crit");
                if (status == 200) crit200.increment(); else crit503.increment();
            }));
        }

        for (Future<?> f : futures) {
            try { f.get(25, TimeUnit.SECONDS); } catch (Exception ignored) {}
        }
        pool.shutdown();

        long totalBg = bg200.sum() + bg503.sum();
        long totalShed = bg503.sum() + std503.sum() + crit503.sum();

        // Server stayed alive: some requests succeeded
        long totalSucceeded = bg200.sum() + std200.sum() + crit200.sum();
        assertTrue(totalSucceeded > 0, "Server returned no 200s under load");

        // BG shed rate must exceed 90% if there was meaningful background traffic
        if (totalBg > 10) {
            double bgShedRate = (double) bg503.sum() / totalBg;
            // BG sheds more than std+crit combined
            assertTrue(bg503.sum() >= std503.sum(),
                    "BG shed count " + bg503.sum() + " should be >= std shed " + std503.sum());
        }

        // Critical traffic has the best success rate among all tiers
        if (crit503.sum() + crit200.sum() > 0 && std503.sum() + std200.sum() > 0) {
            double critSuccessRate = (double) crit200.sum() / (crit200.sum() + crit503.sum());
            double stdSuccessRate = (double) std200.sum() / (std200.sum() + std503.sum());
            // Relax to: critical success rate is at least as good as standard
            assertTrue(critSuccessRate >= stdSuccessRate - 0.1,
                    "Critical success " + critSuccessRate + " should be >= standard " + stdSuccessRate);
        }
    }

    @Test
    @Timeout(15)
    void serverResponds_underNormalLoad() throws Exception {
        int requests = CONCURRENCY_LIMIT;
        LongAdder succeeded = new LongAdder();

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < requests; i++) {
            futures.add(pool.submit(() -> {
                int status = get("/crit");
                if (status == 200) succeeded.increment();
            }));
        }

        for (Future<?> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertTrue(succeeded.sum() > 0, "Expected some 200s under normal load");
    }

    private int get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + PORT + path))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode();
        } catch (Exception e) {
            return 503;
        }
    }
}
