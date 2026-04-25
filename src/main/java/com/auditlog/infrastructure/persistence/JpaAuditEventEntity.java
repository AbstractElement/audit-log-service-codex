package com.auditlog.infrastructure.persistence;

import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.AuditOutcome;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_events")
class JpaAuditEventEntity {

    @Id
    private UUID id;

    @Column(name = "event_timestamp", nullable = false)
    private Instant timestamp;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String action;

    @Column(nullable = false)
    private String resource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditOutcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> context;

    protected JpaAuditEventEntity() {
    }

    private JpaAuditEventEntity(
            UUID id,
            Instant timestamp,
            String actor,
            String action,
            String resource,
            AuditOutcome outcome,
            Map<String, Object> context
    ) {
        this.id = id;
        this.timestamp = timestamp;
        this.actor = actor;
        this.action = action;
        this.resource = resource;
        this.outcome = outcome;
        this.context = context;
    }

    static JpaAuditEventEntity fromDomain(AuditEvent event) {
        return new JpaAuditEventEntity(
                event.id(),
                event.timestamp(),
                event.actor(),
                event.action(),
                event.resource(),
                event.outcome(),
                event.context()
        );
    }

    AuditEvent toDomain() {
        return AuditEvent.rehydrate(id, timestamp, actor, action, resource, outcome, context);
    }

    UUID id() {
        return id;
    }

    Instant timestamp() {
        return timestamp;
    }

    String actor() {
        return actor;
    }

    String resource() {
        return resource;
    }
}
