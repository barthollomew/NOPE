package com.nope;

import jakarta.servlet.http.HttpServletRequest;

@FunctionalInterface
public interface PriorityExtractor {
    Priority extract(HttpServletRequest request);
}
