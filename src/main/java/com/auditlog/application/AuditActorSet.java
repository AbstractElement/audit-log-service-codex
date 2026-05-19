package com.auditlog.application;

import java.util.List;
import java.util.TreeSet;

public record AuditActorSet(List<String> values) {

  public static final int MAX_ACTORS = 10;

  public AuditActorSet {
    if (values == null || values.isEmpty() || values.size() > MAX_ACTORS) {
      throw new IllegalArgumentException("INVALID_ACTOR_SET");
    }
    List<String> copy = List.copyOf(values);
    String previous = null;
    for (String value : copy) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("INVALID_ACTOR_SET");
      }
      if (previous != null && previous.compareTo(value) >= 0) {
        throw new IllegalArgumentException("INVALID_ACTOR_SET");
      }
      previous = value;
    }
    values = copy;
  }

  public static AuditActorSet parse(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }

    String[] tokens = raw.split(",", -1);
    TreeSet<String> canonical = new TreeSet<>();
    for (String token : tokens) {
      String actor = token.trim();
      if (actor.isEmpty()) {
        throw new IllegalArgumentException("INVALID_ACTOR_SET");
      }
      canonical.add(actor);
    }

    return new AuditActorSet(List.copyOf(canonical));
  }

  public static AuditActorSet fromCanonical(List<String> values) {
    if (values == null) {
      return null;
    }
    return new AuditActorSet(values);
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }

  public String[] toArray() {
    return values.toArray(String[]::new);
  }
}
