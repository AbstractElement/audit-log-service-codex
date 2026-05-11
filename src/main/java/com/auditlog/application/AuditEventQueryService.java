package com.auditlog.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public class AuditEventQueryService {

  private final AuditEventRepository repository;

  public AuditEventQueryService(AuditEventRepository repository) {
    this.repository = repository;
  }

  public AuditEventPage queryPage(AuditEventQuery query) {
    Objects.requireNonNull(query, "query");

    boolean hasCursor = query.cursor() != null && !query.cursor().isBlank();
    Instant cursorTs = null;
    UUID cursorId = null;
    AuditEventQuery effective = query;

    if (hasCursor) {
      if (query.actor() != null
          || query.resource() != null
          || query.from() != null
          || query.to() != null) {
        throw new ValidationException(ValidationError.conflictingParameters());
      }
      AuditEventCursor decoded;
      try {
        decoded = AuditEventCursor.decode(query.cursor());
      } catch (IllegalArgumentException e) {
        throw new ValidationException(
            new ValidationError("INVALID_CURSOR", "Cursor is invalid.", "cursor"));
      }
      effective =
          query.withFilters(decoded.actor(), decoded.resource(), decoded.from(), decoded.to());
      cursorTs = decoded.ts();
      cursorId = decoded.id();
    } else {
      effective.validate();
    }

    return repository.findPage(effective, cursorTs, cursorId);
  }
}
