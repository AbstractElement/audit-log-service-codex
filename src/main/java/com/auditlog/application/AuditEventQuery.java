package com.auditlog.application;

import java.time.Duration;
import java.time.Instant;

public record AuditEventQuery(
    String rawActor,
    AuditActorSet actors,
    String resource,
    Instant from,
    Instant to,
    int limit,
    String cursor) {

  private static final Duration MAX_WINDOW = Duration.ofDays(7);

  public AuditEventQuery(
      String actor, String resource, Instant from, Instant to, int limit, String cursor) {
    this(actor, null, resource, from, to, limit, cursor);
  }

  public AuditEventQuery withFilters(
      AuditActorSet actors, String resource, Instant from, Instant to) {
    return new AuditEventQuery(null, actors, resource, from, to, this.limit, null);
  }

  // INVALID_TIMESTAMP / INVALID_CURSOR are not raised here: timestamp parse
  // failures surface at the API binding boundary (GlobalExceptionHandler);
  // cursor decode failures are raised by AuditEventCursor.decode().
  public AuditEventQuery validate() {
    boolean hasCursor = cursor != null && !cursor.isBlank();
    boolean hasAnyFilter = rawActor != null || resource != null || from != null || to != null;

    if (hasCursor && hasAnyFilter) {
      throw new ValidationException(ValidationError.conflictingParameters());
    }

    if (hasCursor) {
      validateLimit();
      return this;
    }

    AuditActorSet parsedActors;
    try {
      parsedActors = actors == null ? AuditActorSet.parse(rawActor) : actors;
    } catch (IllegalArgumentException e) {
      throw new ValidationException(ValidationError.invalidActorSet());
    }

    if (parsedActors == null && (resource == null || resource.isBlank())) {
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

    validateLimit();

    return new AuditEventQuery(rawActor, parsedActors, resource, from, to, limit, cursor);
  }

  private void validateLimit() {
    if (limit < 1 || limit > 500) {
      throw new ValidationException(ValidationError.limitOutOfRange());
    }
  }
}
