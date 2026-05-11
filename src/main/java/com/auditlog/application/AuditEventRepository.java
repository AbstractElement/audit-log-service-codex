package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository {

  AuditEvent save(AuditEvent event);

  AuditEventPage findPage(AuditEventQuery query, Instant cursorTs, UUID cursorId);
}
