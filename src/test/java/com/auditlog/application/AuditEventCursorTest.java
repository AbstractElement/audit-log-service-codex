package com.auditlog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventCursorTest {

  @Test
  void encode_then_decode_roundTrip_preservesAllFields() {
    AuditEventCursor original =
        new AuditEventCursor(
            Instant.parse("2026-05-03T14:22:09.871Z"),
            UUID.fromString("5b9c0e1a-1234-4abc-9def-0123456789ab"),
            "svc:billing",
            "invoice/4711",
            Instant.parse("2026-04-26T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z"),
            AuditEventCursor.V);

    String token = AuditEventCursor.encode(original);
    AuditEventCursor decoded = AuditEventCursor.decode(token);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void encode_then_decode_roundTrip_preservesNullFilters() {
    AuditEventCursor original =
        new AuditEventCursor(
            Instant.parse("2026-05-03T14:22:09.871Z"),
            UUID.fromString("5b9c0e1a-1234-4abc-9def-0123456789ab"),
            null,
            null,
            Instant.parse("2026-04-26T00:00:00Z"),
            Instant.parse("2026-05-03T00:00:00Z"),
            AuditEventCursor.V);

    AuditEventCursor decoded = AuditEventCursor.decode(AuditEventCursor.encode(original));

    assertThat(decoded).isEqualTo(original);
    assertThat(decoded.actor()).isNull();
    assertThat(decoded.resource()).isNull();
  }

  @Test
  void decode_rejectsMalformedBase64() {
    assertThatThrownBy(() -> AuditEventCursor.decode("!!not-base64!!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  @Test
  void decode_rejectsVersionMismatch() {
    String json =
        "{\"ts\":\"2026-05-03T14:22:09.871Z\","
            + "\"id\":\"5b9c0e1a-1234-4abc-9def-0123456789ab\","
            + "\"actor\":null,\"resource\":null,"
            + "\"from\":\"2026-04-26T00:00:00Z\","
            + "\"to\":\"2026-05-03T00:00:00Z\","
            + "\"v\":2}";
    String token =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(json.getBytes(StandardCharsets.UTF_8));

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
}
