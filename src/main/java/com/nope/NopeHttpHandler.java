package com.nope;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.util.function.Function;

public final class NopeHttpHandler implements HttpHandler {
    private final LoadShedder shedder;
    private final Function<HttpExchange, Priority> priorityFn;
    private final HttpHandler delegate;

    public NopeHttpHandler(LoadShedder shedder,
                           Function<HttpExchange, Priority> priorityFn,
                           HttpHandler delegate) {
        this.shedder = shedder;
        this.priorityFn = priorityFn;
        this.delegate = delegate;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Priority priority = priorityFn.apply(exchange);
        long arrival = System.nanoTime();
        RequestContext ctx = new RequestContext(arrival, priority);

        LoadShedder.Decision decision = shedder.submit(ctx);

        if (decision == LoadShedder.Decision.REJECTED) {
            byte[] body = "503 Service Unavailable".getBytes();
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
            return;
        }

        try {
            delegate.handle(exchange);
        } finally {
            shedder.complete(System.nanoTime());
        }
    }
}
