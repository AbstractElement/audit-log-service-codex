package com.auditlog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record AuditEventCursor(
    Instant ts, UUID id, String actor, String resource, Instant from, Instant to, int v) {

  public static final int V = 1;

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public static String encode(AuditEventCursor cursor) {
    try {
      byte[] json = MAPPER.writeValueAsBytes(cursor);
      return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("cursor encode failed", e);
    }
  }

  public static AuditEventCursor decode(String token) {
    byte[] json;
    try {
      json = Base64.getUrlDecoder().decode(token);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CURSOR", e);
    }
    AuditEventCursor cursor;
    try {
      cursor = MAPPER.readValue(json, AuditEventCursor.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("INVALID_CURSOR", e);
    }
    if (cursor.v() != V) {
      throw new IllegalArgumentException("INVALID_CURSOR");
    }
    return cursor;
  }
}
