package com.auditlog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.auditlog.domain.AuditEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuditEventQueryServiceTest {

  private static final Instant FROM = Instant.parse("2026-05-01T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-05-03T00:00:00Z");

  private final FakeRepository repository = new FakeRepository();
  private final AuditEventQueryService service = new AuditEventQueryService(repository);

  @Test
  void queryPage_happyPath_firstPage_returnsAdapterResult() {
    AuditEventPage expected = new AuditEventPage(List.of(), null, false);
    repository.response = expected;

    AuditEventPage result =
        service.queryPage(new AuditEventQuery("svc:billing", null, FROM, TO, 100, null));

    assertThat(result).isSameAs(expected);
    assertThat(repository.lastCursorTs).isNull();
    assertThat(repository.lastCursorId).isNull();
    assertThat(repository.lastQuery.actor()).isEqualTo("svc:billing");
    assertThat(repository.lastQuery.from()).isEqualTo(FROM);
    assertThat(repository.lastQuery.to()).isEqualTo(TO);
  }

  @Test
  void queryPage_happyPath_multiPage_propagatesNextCursor() {
    repository.response = new AuditEventPage(List.of(), "encoded-token", true);

    AuditEventPage result =
        service.queryPage(new AuditEventQuery("svc:billing", null, FROM, TO, 100, null));

    assertThat(result.hasMore()).isTrue();
    assertThat(result.nextCursor()).isEqualTo("encoded-token");
  }

  @Test
  void queryPage_rejectsCursorWithActor_throwsConflictingParameters() {
    AuditEventQuery q = new AuditEventQuery("svc:billing", null, null, null, 100, "tok");

    assertThatThrownBy(() -> service.queryPage(q))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("CONFLICTING_PARAMETERS"));
    assertThat(repository.lastQuery).isNull();
  }

  @Test
  void queryPage_rejectsCursorWithFrom_throwsConflictingParameters() {
    AuditEventQuery q = new AuditEventQuery(null, null, FROM, null, 100, "tok");

    assertThatThrownBy(() -> service.queryPage(q))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("CONFLICTING_PARAMETERS"));
    assertThat(repository.lastQuery).isNull();
  }

  @Test
  void queryPage_rejectsMalformedCursor_throwsInvalidCursor() {
    AuditEventQuery q = new AuditEventQuery(null, null, null, null, 100, "!!not-base64!!");

    assertThatThrownBy(() -> service.queryPage(q))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex -> {
              ValidationError err = ((ValidationException) ex).error();
              assertThat(err.code()).isEqualTo("INVALID_CURSOR");
              assertThat(err.field()).isEqualTo("cursor");
            });
    assertThat(repository.lastQuery).isNull();
  }

  @Test
  void queryPage_decodesCursorAndAppliesFiltersOntoEffectiveQuery() {
    UUID anchorId = UUID.fromString("5b9c0e1a-1234-4abc-9def-0123456789ab");
    Instant anchorTs = Instant.parse("2026-05-02T12:00:00Z");
    String token =
        AuditEventCursor.encode(
            new AuditEventCursor(
                anchorTs, anchorId, "svc:billing", "invoice/4711", FROM, TO, AuditEventCursor.V));

    service.queryPage(new AuditEventQuery(null, null, null, null, 100, token));

    assertThat(repository.lastQuery.actor()).isEqualTo("svc:billing");
    assertThat(repository.lastQuery.resource()).isEqualTo("invoice/4711");
    assertThat(repository.lastQuery.from()).isEqualTo(FROM);
    assertThat(repository.lastQuery.to()).isEqualTo(TO);
    assertThat(repository.lastCursorTs).isEqualTo(anchorTs);
    assertThat(repository.lastCursorId).isEqualTo(anchorId);
  }

  @Test
  void queryPage_forwardsValidationFailures_missingFilter() {
    AuditEventQuery q = new AuditEventQuery(null, null, FROM, TO, 100, null);

    assertThatThrownBy(() -> service.queryPage(q))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code()).isEqualTo("MISSING_FILTER"));
    assertThat(repository.lastQuery).isNull();
  }

  @Test
  void queryPage_forwardsValidationFailures_windowTooLarge() {
    Instant wideTo = FROM.plusSeconds(8L * 24 * 3600);
    AuditEventQuery q = new AuditEventQuery("svc:billing", null, FROM, wideTo, 100, null);

    assertThatThrownBy(() -> service.queryPage(q))
        .isInstanceOf(ValidationException.class)
        .satisfies(
            ex ->
                assertThat(((ValidationException) ex).error().code())
                    .isEqualTo("WINDOW_TOO_LARGE"));
    assertThat(repository.lastQuery).isNull();
  }

  @Test
  void queryPage_cursorPath_skipsFreshValidation() {
    // Cursor anchor still inside an originally-valid window; ensure the cursor
    // branch reaches the repository without re-running validate(). Using a
    // token whose embedded window is fine; the test guarantees the cursor
    // branch is the one taken.
    Instant anchorTs = Instant.parse("2026-05-02T12:00:00Z");
    String token =
        AuditEventCursor.encode(
            new AuditEventCursor(
                anchorTs, UUID.randomUUID(), "svc:billing", null, FROM, TO, AuditEventCursor.V));

    service.queryPage(new AuditEventQuery(null, null, null, null, 100, token));

    assertThat(repository.lastQuery).isNotNull();
  }

  static final class FakeRepository implements AuditEventRepository {
    AuditEventPage response = new AuditEventPage(List.of(), null, false);
    AuditEventQuery lastQuery;
    Instant lastCursorTs;
    UUID lastCursorId;

    @Override
    public AuditEvent save(AuditEvent event) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<AuditEvent> find(AuditEventSearchCriteria criteria) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AuditEventPage findPage(AuditEventQuery query, Instant cursorTs, UUID cursorId) {
      this.lastQuery = query;
      this.lastCursorTs = cursorTs;
      this.lastCursorId = cursorId;
      return response;
    }
  }
}
