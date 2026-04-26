package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.util.List;

public interface AuditEventRepository {

  AuditEvent save(AuditEvent event);

  List<AuditEvent> find(AuditEventSearchCriteria criteria);
}
