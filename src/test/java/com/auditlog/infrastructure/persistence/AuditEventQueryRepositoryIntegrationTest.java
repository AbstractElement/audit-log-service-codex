package com.auditlog.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.auditlog.application.AuditEventCursor;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQuery;
import com.auditlog.application.AuditEventRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class AuditEventQueryRepositoryIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private AuditEventRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static final Instant WINDOW_FROM = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant WINDOW_TO = Instant.parse("2026-05-08T00:00:00Z");

  @BeforeEach
  void truncate() {
    jdbcTemplate.update("TRUNCATE audit_events");
  }

  @Test
  void findPage_seedsAndPagesThreeFullPagesWithoutDuplicatesOrSkips() {
    seedRows("svc:billing", "invoice/4711", 250, WINDOW_FROM);

    AuditEventQuery firstQuery =
        new AuditEventQuery("svc:billing", null, WINDOW_FROM, WINDOW_TO, 100, null);
    AuditEventPage page1 = repository.findPage(firstQuery, null, null);

    assertThat(page1.items()).hasSize(100);
    assertThat(page1.hasMore()).isTrue();
    assertThat(page1.nextCursor()).isNotNull();
    assertDescending(page1);

    AuditEventCursor cursor2 = AuditEventCursor.decode(page1.nextCursor());
    AuditEventPage page2 =
        repository.findPage(
            new AuditEventQuery(
                cursor2.actor(), cursor2.resource(), cursor2.from(), cursor2.to(), 100, null),
            cursor2.ts(),
            cursor2.id());
    assertThat(page2.items()).hasSize(100);
    assertThat(page2.hasMore()).isTrue();
    assertDescending(page2);

    AuditEventCursor cursor3 = AuditEventCursor.decode(page2.nextCursor());
    AuditEventPage page3 =
        repository.findPage(
            new AuditEventQuery(
                cursor3.actor(), cursor3.resource(), cursor3.from(), cursor3.to(), 100, null),
            cursor3.ts(),
            cursor3.id());
    assertThat(page3.items()).hasSize(50);
    assertThat(page3.hasMore()).isFalse();
    assertThat(page3.nextCursor()).isNull();
    assertDescending(page3);

    Set<UUID> all = new HashSet<>();
    page1.items().forEach(v -> all.add(v.id()));
    page2.items().forEach(v -> all.add(v.id()));
    page3.items().forEach(v -> all.add(v.id()));
    assertThat(all).hasSize(250);
  }

  @Test
  void findPage_sameInstantRowsAreOrderedByIdDesc() {
    Instant ts = Instant.parse("2026-05-02T12:00:00Z");
    UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    insertRow(idA, ts, "svc:billing", "invoice/1");
    insertRow(idB, ts, "svc:billing", "invoice/1");

    AuditEventPage page =
        repository.findPage(
            new AuditEventQuery("svc:billing", null, WINDOW_FROM, WINDOW_TO, 10, null), null, null);

    assertThat(page.items()).extracting("id").containsExactly(idB, idA);
  }

  @Test
  void findPage_concurrentInsertAtHeadIsNotVisitedByCursor() {
    seedRows("svc:billing", "invoice/4711", 200, WINDOW_FROM);

    AuditEventPage page1 =
        repository.findPage(
            new AuditEventQuery("svc:billing", null, WINDOW_FROM, WINDOW_TO, 100, null),
            null,
            null);
    assertThat(page1.items()).hasSize(100);
    AuditEventCursor cursor = AuditEventCursor.decode(page1.nextCursor());

    // Insert a row strictly newer than any seeded row but still within the window.
    UUID newId = UUID.randomUUID();
    Instant newTs = WINDOW_FROM.plusSeconds(60L * 60 * 24 * 6);
    insertRow(newId, newTs, "svc:billing", "invoice/4711");

    AuditEventPage page2 =
        repository.findPage(
            new AuditEventQuery(
                cursor.actor(), cursor.resource(), cursor.from(), cursor.to(), 100, null),
            cursor.ts(),
            cursor.id());

    assertThat(page2.items()).extracting("id").doesNotContain(newId);
  }

  @Test
  void findPage_combinedActorAndResourceFilter_returnsIntersection() {
    Instant base = WINDOW_FROM;
    insertRow(UUID.randomUUID(), base, "svc:billing", "invoice/1");
    insertRow(UUID.randomUUID(), base.plusSeconds(1), "svc:billing", "invoice/2");
    insertRow(UUID.randomUUID(), base.plusSeconds(2), "svc:payments", "invoice/1");
    insertRow(UUID.randomUUID(), base.plusSeconds(3), "svc:payments", "invoice/2");

    AuditEventPage page =
        repository.findPage(
            new AuditEventQuery("svc:billing", "invoice/1", WINDOW_FROM, WINDOW_TO, 10, null),
            null,
            null);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).actor()).isEqualTo("svc:billing");
    assertThat(page.items().get(0).resource()).isEqualTo("invoice/1");
  }

  @Test
  void findPage_explainAnalyzeUsesNewActorIndex() {
    seedRows("svc:billing", "invoice/4711", 5000, WINDOW_FROM);

    String plan = explain("svc:billing", null);
    assertThat(plan).contains("idx_audit_events_actor_ts_id");
  }

  @Test
  void findPage_explainAnalyzeUsesNewResourceIndex() {
    seedRows("svc:billing", "invoice/4711", 5000, WINDOW_FROM);

    String plan = explain(null, "invoice/4711");
    assertThat(plan).contains("idx_audit_events_resource_ts_id");
  }

  private void seedRows(String actor, String resource, int count, Instant start) {
    List<Object[]> batch = new ArrayList<>(count);
    IntStream.range(0, count)
        .forEach(
            i ->
                batch.add(
                    new Object[] {
                      UUID.randomUUID(),
                      Timestamp.from(start.plusSeconds(i)),
                      actor,
                      "invoice.created",
                      resource,
                      "SUCCESS",
                      "{}"
                    }));
    jdbcTemplate.batchUpdate(
        "INSERT INTO audit_events (id, event_timestamp, actor, action, resource, outcome, context)"
            + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))",
        batch);
  }

  private void insertRow(UUID id, Instant ts, String actor, String resource) {
    jdbcTemplate.update(
        "INSERT INTO audit_events (id, event_timestamp, actor, action, resource, outcome, context)"
            + " VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSONB))",
        id,
        Timestamp.from(ts),
        actor,
        "invoice.created",
        resource,
        "SUCCESS",
        "{}");
  }

  private String explain(String actor, String resource) {
    List<String> lines =
        jdbcTemplate.queryForList(
            "EXPLAIN (ANALYZE, BUFFERS)"
                + " SELECT id, event_timestamp, actor, action, resource, outcome, context"
                + "   FROM audit_events"
                + "  WHERE event_timestamp >= ?"
                + "    AND event_timestamp <  ?"
                + "    AND (CAST(? AS TEXT) IS NULL OR actor = CAST(? AS TEXT))"
                + "    AND (CAST(? AS TEXT) IS NULL OR resource = CAST(? AS TEXT))"
                + "  ORDER BY event_timestamp DESC, id DESC"
                + "  LIMIT 101",
            String.class,
            Timestamp.from(WINDOW_FROM),
            Timestamp.from(WINDOW_TO),
            actor,
            actor,
            resource,
            resource);
    return String.join("\n", lines);
  }

  private static void assertDescending(AuditEventPage page) {
    var items = page.items();
    for (int i = 1; i < items.size(); i++) {
      Instant prev = items.get(i - 1).timestamp();
      Instant curr = items.get(i).timestamp();
      assertThat(
              prev.isAfter(curr)
                  || (prev.equals(curr) && items.get(i - 1).id().compareTo(items.get(i).id()) > 0))
          .as("page must be ordered by (timestamp DESC, id DESC)")
          .isTrue();
    }
  }
}
