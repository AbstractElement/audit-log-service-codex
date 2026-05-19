package com.auditlog.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public record AuditEventCursor(
    Instant ts, UUID id, List<String> actors, String resource, Instant from, Instant to, int v) {

  public static final int V = 2;
  private static final Duration MAX_WINDOW = Duration.ofDays(7);

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public AuditEventCursor {
    if (v != V
        || ts == null
        || id == null
        || from == null
        || to == null
        || !from.isBefore(to)
        || Duration.between(from, to).compareTo(MAX_WINDOW) > 0) {
      throw new IllegalArgumentException("INVALID_CURSOR");
    }
    AuditActorSet actorSet;
    try {
      actorSet = AuditActorSet.fromCanonical(actors);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("INVALID_CURSOR", e);
    }
    if (actorSet == null && (resource == null || resource.isBlank())) {
      throw new IllegalArgumentException("INVALID_CURSOR");
    }
    actors = actorSet == null ? null : actorSet.values();
  }

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
