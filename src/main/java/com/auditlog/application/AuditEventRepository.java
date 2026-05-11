package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditEventRepository {

  AuditEvent save(AuditEvent event);

  List<AuditEvent> find(AuditEventSearchCriteria criteria);

  AuditEventPage findPage(AuditEventQuery query, Instant cursorTs, UUID cursorId);
}
