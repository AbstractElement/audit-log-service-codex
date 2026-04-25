package com.auditlog.application;

import java.time.Instant;

public record AuditEventSearchCriteria(
        String actor,
        String resource,
        Instant from,
        Instant to,
        int limit,
        int offset
) {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    public AuditEventSearchCriteria {
        limit = normalizeLimit(limit);
        offset = Math.max(offset, 0);
    }

    private static int normalizeLimit(int requestedLimit) {
        if (requestedLimit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requestedLimit, MAX_LIMIT);
    }
}
