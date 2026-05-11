package com.auditlog.application;

import java.time.Instant;

public record AuditEventQuery(
    String actor, String resource, Instant from, Instant to, int limit, String cursor) {

  public AuditEventQuery withFilters(String actor, String resource, Instant from, Instant to) {
    return new AuditEventQuery(actor, resource, from, to, this.limit, this.cursor);
  }
}
