package com.auditlog.application;

import java.util.Map;

public record RecordAuditEventCommand(
    String actor, String action, String resource, String outcome, Map<String, Object> context) {}
