package com.auditlog.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.auditlog.application.AuditActorSet;
import com.auditlog.application.AuditEventCursor;
import com.auditlog.application.AuditEventPage;
import com.auditlog.application.AuditEventQuery;
import com.auditlog.application.AuditEventRepository;
import com.auditlog.application.AuditEventView;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
import org.springframework.jdbc.core.ConnectionCallback;
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

    List<AuditEventPage> pages = fetchPages(query("svc:billing", null, 100), 100);

    assertThat(pages).hasSize(3);
    assertThat(pages.get(0).items()).hasSize(100);
    assertThat(pages.get(1).items()).hasSize(100);
    assertThat(pages.get(2).items()).hasSize(50);
    assertThat(pages.get(2).hasMore()).isFalse();
    assertThat(allIds(pages)).hasSize(250);
    pages.forEach(AuditEventQueryRepositoryIntegrationTest::assertDescending);
  }

  @Test
  void findPage_multiActorPagesAreGloballyOrderedAcrossActors() {
    seedRows("svc:billing", "invoice/4711", 125, WINDOW_FROM);
    seedRows("svc:orders", "order/99", 125, WINDOW_FROM.plusSeconds(1));
    seedRows("svc:payments", "payment/77", 50, WINDOW_FROM.plusSeconds(2));

    List<AuditEventPage> pages = fetchPages(query("svc:orders,svc:billing", null, 75), 75);
    List<AuditEventView> items = pages.stream().flatMap(page -> page.items().stream()).toList();

    assertThat(pages).hasSize(4);
    assertThat(items).hasSize(250);
    assertThat(items).extracting(AuditEventView::actor).containsOnly("svc:billing", "svc:orders");
    assertThat(allIds(pages)).hasSize(250);
    pages.forEach(AuditEventQueryRepositoryIntegrationTest::assertDescending);
  }

  @Test
  void findPage_sameInstantRowsAreOrderedByIdDesc() {
    Instant ts = Instant.parse("2026-05-02T12:00:00Z");
    UUID idA = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID idB = UUID.fromString("00000000-0000-0000-0000-000000000002");
    insertRow(idA, ts, "svc:billing", "invoice/1");
    insertRow(idB, ts, "svc:billing", "invoice/1");

    AuditEventPage page = repository.findPage(query("svc:billing", null, 10), null, null);

    assertThat(page.items()).extracting("id").containsExactly(idB, idA);
  }

  @Test
  void findPage_concurrentInsertAtHeadIsNotVisitedByCursor() {
    seedRows("svc:billing", "invoice/4711", 200, WINDOW_FROM);
    seedRows("svc:orders", "order/99", 200, WINDOW_FROM.plusSeconds(1));

    AuditEventPage page1 =
        repository.findPage(query("svc:billing,svc:orders", null, 100), null, null);
    assertThat(page1.items()).hasSize(100);
    AuditEventCursor cursor = AuditEventCursor.decode(page1.nextCursor());
    assertThat(cursor.actors()).containsExactly("svc:billing", "svc:orders");

    UUID newId = UUID.randomUUID();
    Instant newTs = WINDOW_FROM.plusSeconds(60L * 60 * 24 * 6);
    insertRow(newId, newTs, "svc:orders", "order/99");

    AuditEventPage page2 =
        repository.findPage(queryFromCursor(cursor, 100), cursor.ts(), cursor.id());

    assertThat(page2.items()).extracting("id").doesNotContain(newId);
  }

  @Test
  void findPage_combinedActorSetAndResourceFilter_returnsIntersection() {
    Instant base = WINDOW_FROM;
    insertRow(UUID.randomUUID(), base, "svc:billing", "invoice/1");
    insertRow(UUID.randomUUID(), base.plusSeconds(1), "svc:orders", "invoice/1");
    insertRow(UUID.randomUUID(), base.plusSeconds(2), "svc:payments", "invoice/1");
    insertRow(UUID.randomUUID(), base.plusSeconds(3), "svc:billing", "invoice/2");

    AuditEventPage page =
        repository.findPage(query("svc:orders,svc:billing", "invoice/1", 10), null, null);

    assertThat(page.items()).hasSize(2);
    assertThat(page.items())
        .extracting(AuditEventView::actor)
        .containsOnly("svc:billing", "svc:orders");
    assertThat(page.items()).extracting(AuditEventView::resource).containsOnly("invoice/1");
  }

  @Test
  void findPage_actorMatchingIsCaseSensitive() {
    insertRow(UUID.randomUUID(), WINDOW_FROM, "svc:billing", "invoice/1");
    insertRow(UUID.randomUUID(), WINDOW_FROM.plusSeconds(1), "Svc:Billing", "invoice/1");

    AuditEventPage page = repository.findPage(query("svc:billing", null, 10), null, null);

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).actor()).isEqualTo("svc:billing");
  }

  @Test
  void findPage_explainAnalyzeUsesNewActorIndex() {
    seedRows("svc:billing", "invoice/4711", 5000, WINDOW_FROM);

    String plan = explainActors(List.of("svc:billing"), null);
    assertThat(plan).contains("idx_audit_events_actor_ts_id");
  }

  @Test
  void findPage_explainAnalyzeUsesNewActorIndexForMultiActor() {
    seedRows("svc:billing", "invoice/4711", 2500, WINDOW_FROM);
    seedRows("svc:orders", "invoice/4711", 2500, WINDOW_FROM.plusSeconds(1));

    String plan = explainActors(List.of("svc:billing", "svc:orders"), null);
    assertThat(plan).contains("idx_audit_events_actor_ts_id");
  }

  @Test
  void findPage_explainAnalyzeUsesNewResourceIndex() {
    seedRows("svc:billing", "invoice/4711", 5000, WINDOW_FROM);

    String plan = explainResource("invoice/4711");
    assertThat(plan).contains("idx_audit_events_resource_ts_id");
  }

  private List<AuditEventPage> fetchPages(AuditEventQuery firstQuery, int limit) {
    List<AuditEventPage> pages = new ArrayList<>();
    AuditEventPage page = repository.findPage(firstQuery, null, null);
    pages.add(page);
    while (page.hasMore()) {
      AuditEventCursor cursor = AuditEventCursor.decode(page.nextCursor());
      page = repository.findPage(queryFromCursor(cursor, limit), cursor.ts(), cursor.id());
      pages.add(page);
    }
    return pages;
  }

  private static AuditEventQuery query(String actors, String resource, int limit) {
    return new AuditEventQuery(actors, resource, WINDOW_FROM, WINDOW_TO, limit, null).validate();
  }

  private static AuditEventQuery queryFromCursor(AuditEventCursor cursor, int limit) {
    return new AuditEventQuery(
        null,
        AuditActorSet.fromCanonical(cursor.actors()),
        cursor.resource(),
        cursor.from(),
        cursor.to(),
        limit,
        null);
  }

  private static Set<UUID> allIds(List<AuditEventPage> pages) {
    Set<UUID> all = new HashSet<>();
    pages.forEach(page -> page.items().forEach(item -> all.add(item.id())));
    return all;
  }

  private void seedRows(String actor, String resource, int count, Instant start) {
    List<Object[]> batch = new ArrayList<>(count);
    IntStream.range(0, count)
        .forEach(
            i ->
                batch.add(
                    new Object[] {
                      UUID.randomUUID(),
                      Timestamp.from(start.plusSeconds(i * 2L)),
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

  private String explainActors(List<String> actors, String resource) {
    return jdbcTemplate.execute(
        (ConnectionCallback<String>)
            connection -> {
              java.sql.Array actorArray =
                  connection.createArrayOf("text", actors.toArray(String[]::new));
              try (PreparedStatement statement =
                  connection.prepareStatement(
                      "EXPLAIN (ANALYZE, BUFFERS)"
                          + " SELECT id, event_timestamp, actor, action, resource, outcome, context"
                          + "   FROM audit_events"
                          + "  WHERE event_timestamp >= ?"
                          + "    AND event_timestamp <  ?"
                          + "    AND actor = ANY(CAST(? AS TEXT[]))"
                          + "    AND (CAST(? AS TEXT) IS NULL OR resource = CAST(? AS TEXT))"
                          + "  ORDER BY event_timestamp DESC, id DESC"
                          + "  LIMIT 101")) {
                statement.setTimestamp(1, Timestamp.from(WINDOW_FROM));
                statement.setTimestamp(2, Timestamp.from(WINDOW_TO));
                statement.setArray(3, actorArray);
                statement.setString(4, resource);
                statement.setString(5, resource);
                try (ResultSet resultSet = statement.executeQuery()) {
                  return explainLines(resultSet);
                }
              } finally {
                actorArray.free();
              }
            });
  }

  private String explainResource(String resource) {
    List<String> lines =
        jdbcTemplate.queryForList(
            "EXPLAIN (ANALYZE, BUFFERS)"
                + " SELECT id, event_timestamp, actor, action, resource, outcome, context"
                + "   FROM audit_events"
                + "  WHERE event_timestamp >= ?"
                + "    AND event_timestamp <  ?"
                + "    AND resource = ?"
                + "  ORDER BY event_timestamp DESC, id DESC"
                + "  LIMIT 101",
            String.class,
            Timestamp.from(WINDOW_FROM),
            Timestamp.from(WINDOW_TO),
            resource);
    return String.join("\n", lines);
  }

  private static String explainLines(ResultSet resultSet) throws java.sql.SQLException {
    List<String> lines = new ArrayList<>();
    while (resultSet.next()) {
      lines.add(resultSet.getString(1));
    }
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
