package com.auditlog.application;

import java.time.Duration;
import java.time.Instant;

public record AuditEventQuery(
    String actor, String resource, Instant from, Instant to, int limit, String cursor) {

  private static final Duration MAX_WINDOW = Duration.ofDays(7);

  public AuditEventQuery withFilters(String actor, String resource, Instant from, Instant to) {
    return new AuditEventQuery(actor, resource, from, to, this.limit, this.cursor);
  }

  // INVALID_TIMESTAMP / INVALID_CURSOR are not raised here: timestamp parse
  // failures surface at the API binding boundary (GlobalExceptionHandler);
  // cursor decode failures are raised by AuditEventCursor.decode().
  public AuditEventQuery validate() {
    boolean hasCursor = cursor != null && !cursor.isBlank();
    boolean hasAnyFilter = actor != null || resource != null || from != null || to != null;

    if (hasCursor && hasAnyFilter) {
      throw new ValidationException(ValidationError.conflictingParameters());
    }

    if (hasCursor) {
      return this;
    }

    if ((actor == null || actor.isBlank()) && (resource == null || resource.isBlank())) {
      throw new ValidationException(ValidationError.missingFilter());
    }

    if (from == null) {
      throw new ValidationException(ValidationError.missingParameter("from"));
    }
    if (to == null) {
      throw new ValidationException(ValidationError.missingParameter("to"));
    }

    if (!from.isBefore(to)) {
      throw new ValidationException(ValidationError.invalidTimeWindow());
    }

    if (Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
      throw new ValidationException(ValidationError.windowTooLarge());
    }

    if (limit < 1 || limit > 500) {
      throw new ValidationException(ValidationError.limitOutOfRange());
    }

    return this;
  }
}
