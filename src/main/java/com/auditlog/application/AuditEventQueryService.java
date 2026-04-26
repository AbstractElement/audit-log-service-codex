package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.util.List;

public class AuditEventQueryService {

  private final AuditEventRepository repository;

  public AuditEventQueryService(AuditEventRepository repository) {
    this.repository = repository;
  }

  public List<AuditEvent> find(AuditEventSearchCriteria criteria) {
    return repository.find(criteria);
  }
}
