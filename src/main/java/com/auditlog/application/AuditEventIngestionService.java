package com.auditlog.application;

import com.auditlog.domain.AuditEvent;
import java.time.Clock;

public class AuditEventIngestionService {

  private final AuditEventRepository repository;
  private final Clock clock;

  public AuditEventIngestionService(AuditEventRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  public AuditEvent record(RecordAuditEventCommand command) {
    AuditEvent event =
        AuditEvent.record(
            command.actor(),
            command.action(),
            command.resource(),
            command.outcome(),
            command.context(),
            clock);
    return repository.save(event);
  }
}
