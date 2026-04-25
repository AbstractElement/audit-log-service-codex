package com.auditlog.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class AuditEvent {

    private final UUID id;
    private final Instant timestamp;
    private final String actor;
    private final String action;
    private final String resource;
    private final AuditOutcome outcome;
    private final Map<String, Object> context;

    private AuditEvent(
            UUID id,
            Instant timestamp,
            String actor,
            String action,
            String resource,
            AuditOutcome outcome,
            Map<String, Object> context
    ) {
        this.id = Objects.requireNonNull(id, "id must be provided");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must be provided");
        this.actor = requireIdentity(actor);
        this.action = requireText(action, "action");
        this.resource = requireText(resource, "resource");
        this.outcome = Objects.requireNonNull(outcome, "outcome must be provided");
        this.context = context == null ? Map.of() : Map.copyOf(context);
    }

    public static AuditEvent record(
            String actor,
            String action,
            String resource,
            AuditOutcome outcome,
            Map<String, Object> context,
            Clock clock
    ) {
        return new AuditEvent(UUID.randomUUID(), Instant.now(clock), actor, action, resource, outcome, context);
    }

    public static AuditEvent rehydrate(
            UUID id,
            Instant timestamp,
            String actor,
            String action,
            String resource,
            AuditOutcome outcome,
            Map<String, Object> context
    ) {
        return new AuditEvent(id, timestamp, actor, action, resource, outcome, context);
    }

    public UUID id() {
        return id;
    }

    public Instant timestamp() {
        return timestamp;
    }

    public String actor() {
        return actor;
    }

    public String action() {
        return action;
    }

    public String resource() {
        return resource;
    }

    public AuditOutcome outcome() {
        return outcome;
    }

    public Map<String, Object> context() {
        return context;
    }

    private static String requireIdentity(String value) {
        String identity = requireText(value, "actor");
        if ("anonymous".equalsIgnoreCase(identity) || "anonymousUser".equalsIgnoreCase(identity)) {
            throw new IllegalArgumentException("actor must be an authenticated user or service identity");
        }
        return identity;
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must be provided");
        }
        return value.trim();
    }
}
