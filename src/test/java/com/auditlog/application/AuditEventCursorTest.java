package com.auditlog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventCursorTest {

  private static final Instant TS = Instant.parse("2026-05-03T14:22:09.871Z");
  private static final UUID ID = UUID.fromString("5b9c0e1a-1234-4abc-9def-0123456789ab");
  private static final Instant FROM = Instant.parse("2026-04-26T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-05-03T00:00:00Z");

  @Test
  void encode_then_decode_roundTrip_preservesSingleActor() {
    AuditEventCursor original =
        new AuditEventCursor(TS, ID, List.of("svc:billing"), "invoice/4711", FROM, TO, 2);

    String token = AuditEventCursor.encode(original);
    AuditEventCursor decoded = AuditEventCursor.decode(token);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void encode_then_decode_roundTrip_preservesMultipleActors() {
    AuditEventCursor original =
        new AuditEventCursor(
            TS, ID, List.of("svc:billing", "svc:orders"), null, FROM, TO, AuditEventCursor.V);

    AuditEventCursor decoded = AuditEventCursor.decode(AuditEventCursor.encode(original));

    assertThat(decoded.actors()).containsExactly("svc:billing", "svc:orders");
    assertThat(decoded.resource()).isNull();
  }

  @Test
  void encode_then_decode_roundTrip_preservesResourceOnlyFilter() {
    AuditEventCursor original =
        new AuditEventCursor(TS, ID, null, "invoice/4711", FROM, TO, AuditEventCursor.V);

    AuditEventCursor decoded = AuditEventCursor.decode(AuditEventCursor.encode(original));

    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.actors()).isNull();
  }

  @Test
  void decode_rejectsMalformedBase64() {
    assertThatThrownBy(() -> AuditEventCursor.decode("!!not-base64!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsVersionOneScalarActorCursor() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actor\":\"svc:billing\",\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":1}";
    String token = encodeJson(json);

    assertThatThrownBy(() -> AuditEventCursor.decode(token))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsNonJsonPayload() {
    String token =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString("not json".getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> AuditEventCursor.decode(token))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsMissingAnchorTimestamp() {
    String json =
        "{\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsMissingAnchorId() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"actors\":[\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsNoPinnedFilters() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":null,\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsInvalidTimeWindow() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-05-03T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsWindowOverSevenDays() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-04-25T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsEmptyActorList() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsBlankActor() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:billing\",\" \"],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsUnsortedActors() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:orders\",\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsDuplicateActors() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actors\":[\"svc:billing\",\"svc:billing\"],\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";

    assertThatThrownBy(() -> AuditEventCursor.decode(encodeJson(json)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  private static String encodeJson(String json) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }
}
