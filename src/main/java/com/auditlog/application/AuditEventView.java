package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventView(
    UUID id,
    Instant timestamp,
    String actor,
    String action,
    String resource,
    String outcome,
    Map<String, Object> context) {

  public static AuditEventView fromDomain(AuditEvent event) {
    return new AuditEventView(
        event.id(),
        event.timestamp(),
        event.actor(),
        event.action(),
        event.resource(),
        event.outcome().name(),
        event.context());
  }
}
