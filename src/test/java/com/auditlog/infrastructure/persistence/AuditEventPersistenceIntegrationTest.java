package com.auditlog.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auditlog.application.AuditEventRepository;
import com.auditlog.domain.AuditEvent;
import com.auditlog.domain.AuditOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventPersistenceIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private DataSource dataSource;

  @Autowired private AuditEventRepository repository;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void connectsToPostgreSqlDatabase() throws Exception {
    try (var connection = dataSource.getConnection()) {
      assertThat(connection.isValid(2)).isTrue();
      assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
    }

    assertThat(jdbcTemplate.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
  }

  @Test
  void persistedAuditEventCannotBeUpdated() {
    Clock clock = Clock.fixed(Instant.parse("2026-04-25T12:00:00Z"), ZoneOffset.UTC);
    AuditEvent event =
        AuditEvent.record(
            "service:billing",
            "invoice.created",
            "invoice/123",
            AuditOutcome.SUCCESS,
            Map.of("traceId", "trace-123"),
            clock);

    repository.save(event);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "UPDATE audit_events SET actor = ? WHERE id = ?",
                    "service:payments",
                    event.id()))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("audit_events records are immutable");
  }

  @Test
  void auditEventsTableHasExpectedIndexesAfterMigrations() {
    var indexes =
        Set.copyOf(
            jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'audit_events'", String.class));

    assertThat(indexes)
        .containsExactlyInAnyOrder(
            "audit_events_pkey",
            "idx_audit_events_timestamp",
            "idx_audit_events_actor_ts_id",
            "idx_audit_events_resource_ts_id");
  }
}
