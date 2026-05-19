package com.auditlog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuditActorSetTest {

  @Test
  void parse_nullOrBlank_returnsNull() {
    assertThat(AuditActorSet.parse(null)).isNull();
    assertThat(AuditActorSet.parse("   ")).isNull();
  }

  @Test
  void parse_singleActor_returnsOneItemSet() {
    AuditActorSet actors = AuditActorSet.parse("svc:billing");

    assertThat(actors.values()).containsExactly("svc:billing");
  }

  @Test
  void parse_trimsDeduplicatesAndSorts() {
    AuditActorSet actors = AuditActorSet.parse(" svc:orders,svc:billing,svc:orders ");

    assertThat(actors.values()).containsExactly("svc:billing", "svc:orders");
  }

  @Test
  void parse_rejectsEmptyToken_betweenCommas() {
    assertThatThrownBy(() -> AuditActorSet.parse("svc:billing,,svc:orders"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ACTOR_SET");
  }

  @Test
  void parse_rejectsTrailingComma() {
    assertThatThrownBy(() -> AuditActorSet.parse("svc:billing,"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ACTOR_SET");
  }

  @Test
  void parse_rejectsMoreThanTenActors() {
    assertThatThrownBy(() -> AuditActorSet.parse("a01,a02,a03,a04,a05,a06,a07,a08,a09,a10,a11"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ACTOR_SET");
  }

  @Test
  void fromCanonical_acceptsSortedUniqueList() {
    AuditActorSet actors = AuditActorSet.fromCanonical(List.of("svc:billing", "svc:orders"));

    assertThat(actors.values()).containsExactly("svc:billing", "svc:orders");
  }

  @Test
  void fromCanonical_rejectsUnsortedList() {
    assertThatThrownBy(() -> AuditActorSet.fromCanonical(List.of("svc:orders", "svc:billing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ACTOR_SET");
  }

  @Test
  void fromCanonical_rejectsDuplicates() {
    assertThatThrownBy(() -> AuditActorSet.fromCanonical(List.of("svc:billing", "svc:billing")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ACTOR_SET");
  }

  @Test
  void values_areImmutable() {
    AuditActorSet actors = AuditActorSet.parse("svc:billing");

    assertThatThrownBy(() -> actors.values().add("svc:orders"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
