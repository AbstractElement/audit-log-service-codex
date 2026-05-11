package com.auditlog.application;

import java.util.List;

public record AuditEventPage(List<AuditEventView> items, String nextCursor, boolean hasMore) {

  public AuditEventPage {
    items = List.copyOf(items);
    if ((nextCursor != null) != hasMore) {
      throw new IllegalArgumentException("hasMore must equal (nextCursor != null)");
    }
  }
}
