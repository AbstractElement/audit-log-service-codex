package com.auditlog.application;

import com.auditlog.domain.AuditOutcome;
import java.util.Map;

public record RecordAuditEventCommand(
    String actor,
    String action,
    String resource,
    AuditOutcome outcome,
    Map<String, Object> context) {}
