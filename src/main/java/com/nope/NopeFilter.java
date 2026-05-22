package com.nope;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class NopeFilter implements Filter {
    private final LoadShedder shedder;
    private final PriorityExtractor extractor;

    public NopeFilter(LoadShedder shedder, PriorityExtractor extractor) {
        this.shedder = shedder;
        this.extractor = extractor;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpResp = (HttpServletResponse) response;

        Priority priority = extractor.extract(httpReq);
        long arrival = System.nanoTime();
        RequestContext ctx = new RequestContext(arrival, priority);

        LoadShedder.Decision decision = shedder.submit(ctx);

        if (decision == LoadShedder.Decision.REJECTED) {
            httpResp.setStatus(503);
            return;
        }

        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            shedder.complete(System.nanoTime());
        }
    }

    @Override
    public void init(FilterConfig filterConfig) {}

    @Override
    public void destroy() {}
}
