package com.auditlog.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditEventTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void createsEventWithServerTimestamp() {
        AuditEvent event = AuditEvent.record(
                "service:billing",
                "invoice.created",
                "invoice/123",
                AuditOutcome.SUCCESS,
                Map.of("traceId", "abc"),
                clock
        );

        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-04-25T00:00:00Z"));
        assertThat(event.context()).containsEntry("traceId", "abc");
    }

    @Test
    void rejectsAnonymousActor() {
        assertThatThrownBy(() -> AuditEvent.record(
                "anonymous",
                "invoice.created",
                "invoice/123",
                AuditOutcome.SUCCESS,
                Map.of(),
                clock
        )).isInstanceOf(IllegalArgumentException.class);
    }
}
