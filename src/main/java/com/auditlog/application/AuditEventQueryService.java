package com.auditlog.application;

import java.util.List;

public class AuditEventQueryService {

  private final AuditEventRepository repository;

  public AuditEventQueryService(AuditEventRepository repository) {
    this.repository = repository;
  }

  public List<AuditEventView> find(AuditEventSearchCriteria criteria) {
    return repository.find(criteria).stream().map(AuditEventView::fromDomain).toList();
  }
}
