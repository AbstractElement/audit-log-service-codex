package com.auditlog.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuditEventControllerIntegrationTest {

  @Container
  private static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine");

  @DynamicPropertySource
  static void configureDatabase(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  private static final Instant WINDOW_FROM = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant WINDOW_TO = Instant.parse("2026-05-08T00:00:00Z");

  @BeforeEach
  void truncate() {
    jdbcTemplate.update("TRUNCATE audit_events");
  }

  @Test
  void getAuditEvents_returnsCursorPagedEnvelope() throws Exception {
    seedRows("svc:billing", "invoice/4711", 150, WINDOW_FROM);
    seedRows("svc:orders", "order/99", 100, WINDOW_FROM.plusSeconds(1));
    seedRows("svc:payments", "payment/77", 25, WINDOW_FROM.plusSeconds(2));

    MvcResult page1 =
        mockMvc
            .perform(
                get("/audit-events")
                    .param("actor", "svc:orders,svc:billing")
                    .param("from", WINDOW_FROM.toString())
                    .param("to", WINDOW_TO.toString())
                    .param("limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andExpect(jsonPath("$.nextCursor").isString())
            .andReturn();

    JsonNode body1 = objectMapper.readTree(page1.getResponse().getContentAsString());
    assertThat(body1.get("items")).hasSize(100);
    String cursor1 = body1.get("nextCursor").asText();

    MvcResult page2 =
        mockMvc
            .perform(get("/audit-events").param("cursor", cursor1).param("limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(true))
            .andReturn();
    JsonNode body2 = objectMapper.readTree(page2.getResponse().getContentAsString());
    String cursor2 = body2.get("nextCursor").asText();

    MvcResult page3 =
        mockMvc
            .perform(get("/audit-events").param("cursor", cursor2).param("limit", "100"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextCursor").isEmpty())
            .andReturn();
    JsonNode body3 = objectMapper.readTree(page3.getResponse().getContentAsString());
    assertThat(body3.get("items")).hasSize(50);

    Set<String> ids = new HashSet<>();
    body1.get("items").forEach(item -> ids.add(item.get("id").asText()));
    body2.get("items").forEach(item -> ids.add(item.get("id").asText()));
    body3.get("items").forEach(item -> ids.add(item.get("id").asText()));
    assertThat(ids).hasSize(250);
    assertThatActorsAreOnly(body1, "svc:billing", "svc:orders");
    assertThatActorsAreOnly(body2, "svc:billing", "svc:orders");
    assertThatActorsAreOnly(body3, "svc:billing", "svc:orders");
  }

  @Test
  void getAuditEvents_actorSetAndResource_returnsIntersection() throws Exception {
    insertRow(UUID.randomUUID(), WINDOW_FROM, "svc:billing", "invoice/1");
    insertRow(UUID.randomUUID(), WINDOW_FROM.plusSeconds(1), "svc:orders", "invoice/1");
    insertRow(UUID.randomUUID(), WINDOW_FROM.plusSeconds(2), "svc:payments", "invoice/1");
    insertRow(UUID.randomUUID(), WINDOW_FROM.plusSeconds(3), "svc:billing", "invoice/2");

    MvcResult response =
        mockMvc
            .perform(
                get("/audit-events")
                    .param("actor", "svc:billing,svc:orders")
                    .param("resource", "invoice/1")
                    .param("from", WINDOW_FROM.toString())
                    .param("to", WINDOW_TO.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn();

    JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
    body.get("items")
        .forEach(
            item -> {
              assertThat(item.get("actor").asText()).isIn("svc:billing", "svc:orders");
              assertThat(item.get("resource").asText()).isEqualTo("invoice/1");
            });
  }

  @Test
  void getAuditEvents_missingFilter_returns400() throws Exception {
    mockMvc
        .perform(
            get("/audit-events")
                .param("from", WINDOW_FROM.toString())
                .param("to", WINDOW_TO.toString()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("MISSING_FILTER"));
  }

  @Test
  void getAuditEvents_offsetParameterIsSilentlyIgnored() throws Exception {
    seedRows("svc:billing", "invoice/4711", 5, WINDOW_FROM);

    mockMvc
        .perform(
            get("/audit-events")
                .param("actor", "svc:billing")
                .param("from", WINDOW_FROM.toString())
                .param("to", WINDOW_TO.toString())
                .param("offset", "999"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(5));
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

  private static void assertThatActorsAreOnly(JsonNode body, String... actors) {
    body.get("items")
        .forEach(item -> assertThat(item.get("actor").asText()).isIn((Object[]) actors));
  }
}
