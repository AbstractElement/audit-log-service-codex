package com.auditlog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AuditEventQueryValidationTest {

  private static final Instant FROM = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-05-03T00:00:00Z");

  private static AuditEventQuery query(
      String actor, String resource, Instant from, Instant to, int limit, String cursor) {
    return new AuditEventQuery(actor, resource, from, to, limit, cursor);
  }

  @Test
  void validate_acceptsMinimalValidQuery_AC_1_1() {
    AuditEventQuery q = query("svc:billing", null, FROM, TO, 100, null);
    assertThat(q.validate()).isSameAs(q);
  }

  @Test
  void validate_rejectsCursorWithFilter_AC_2_4() {
    AuditEventQuery q = query("svc:billing", null, null, null, 100, "tok");
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationError err = ((ValidationException) ex).error();
              assertThat(err.code()).isEqualTo("CONFLICTING_PARAMETERS");
              assertThat(err.field()).isNull();
            });
  }

  @Test
  void validate_acceptsCursorAlone_AC_2_4() {
    AuditEventQuery q = query(null, null, null, null, 100, "tok");
    assertThat(q.validate()).isSameAs(q);
  }

  @Test
  void validate_rejectsMissingBothFilters_AC_1_4() {
    AuditEventQuery q = query(null, null, FROM, TO, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code()).isEqualTo("MISSING_FILTER"));
  }

  @Test
  void validate_rejectsBlankFilters_AC_1_4() {
    AuditEventQuery q = query("  ", "", FROM, TO, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code()).isEqualTo("MISSING_FILTER"));
  }

  @Test
  void validate_rejectsMissingFrom_AC_1_3() {
    AuditEventQuery q = query("svc:billing", null, null, TO, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationError err = ((ValidationException) ex).error();
              assertThat(err.code()).isEqualTo("MISSING_PARAMETER");
              assertThat(err.field()).isEqualTo("from");
            });
  }

  @Test
  void validate_rejectsMissingTo_AC_1_3() {
    AuditEventQuery q = query("svc:billing", null, FROM, null, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationError err = ((ValidationException) ex).error();
              assertThat(err.code()).isEqualTo("MISSING_PARAMETER");
              assertThat(err.field()).isEqualTo("to");
            });
  }

  @Test
  void validate_rejectsFromAfterTo_AC_1_5() {
    AuditEventQuery q = query("svc:billing", null, TO, FROM, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("INVALID_TIME_WINDOW"));
  }

  @Test
  void validate_rejectsFromEqualsTo_AC_1_5() {
    AuditEventQuery q = query("svc:billing", null, FROM, FROM, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("INVALID_TIME_WINDOW"));
  }

  @Test
  void validate_rejectsWindowOver7Days_AC_1_6() {
    Instant from = Instant.parse("2026-05-01T00:00:00Z");
    Instant to = from.plusSeconds(7L * 24 * 3600 + 1);
    AuditEventQuery q = query("svc:billing", null, from, to, 100, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("WINDOW_TOO_LARGE"));
  }

  @Test
  void validate_acceptsWindowExactly7Days_AC_1_6() {
    Instant from = Instant.parse("2026-05-01T00:00:00Z");
    Instant to = from.plusSeconds(7L * 24 * 3600);
    AuditEventQuery q = query("svc:billing", null, from, to, 100, null);
    assertThat(q.validate()).isSameAs(q);
  }

  @Test
  void validate_rejectsLimitZero_AC_2_7() {
    AuditEventQuery q = query("svc:billing", null, FROM, TO, 0, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationError err = ((ValidationException) ex).error();
              assertThat(err.code()).isEqualTo("LIMIT_OUT_OF_RANGE");
              assertThat(err.field()).isEqualTo("limit");
            });
  }

  @Test
  void validate_rejectsLimitOver500_AC_2_7() {
    AuditEventQuery q = query("svc:billing", null, FROM, TO, 501, null);
    assertThatThrownBy(q::validate)
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("LIMIT_OUT_OF_RANGE"));
  }

  @Test
  void validate_acceptsLimit1_AC_2_7() {
    AuditEventQuery q = query("svc:billing", null, FROM, TO, 1, null);
    assertThat(q.validate()).isSameAs(q);
  }

  @Test
  void validate_acceptsLimit500_AC_2_7() {
    AuditEventQuery q = query("svc:billing", null, FROM, TO, 500, null);
    assertThat(q.validate()).isSameAs(q);
  }
}
