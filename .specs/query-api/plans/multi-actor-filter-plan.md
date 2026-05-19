# T10-T13 — Multi-actor filter implementation plan

## Purpose

Implement the actor-set delta described by T10-T13 in
`.specs/query-api/tasks.md` on top of the existing scalar-actor keyset
baseline. The endpoint path and query parameter names stay unchanged:
`GET /audit-events` still accepts `actor`, `resource`, `from`, `to`,
`limit`, and `cursor`; the difference is that `actor` now supports a
canonical comma-separated actor set with one to ten values.

This plan is implementation-ready. It fixes the concrete type shape, service
flow, SQL strategy, test cases, and documentation updates needed to satisfy
the current `requirements.md` and `design.md`.

## Current baseline

- `AuditEventQuery` currently stores a scalar `String actor`.
- `AuditEventCursor` currently stores a scalar `String actor` and supports
  cursor version `1`.
- `AuditEventQueryService` decodes the cursor, injects scalar filters back
  into the query, and passes `(cursorTs, cursorId)` to the repository.
- `JpaAuditEventRepository` uses scalar SQL:
  `actor = CAST(:actor AS TEXT)`.
- `AuditEventController` already returns the paginated envelope and already
  ignores unknown query parameters such as legacy `offset`.
- The write path, domain model, Flyway migrations, and archive table are not
  part of this delta.

## Target design decisions

1. **Use an Application-layer value type for actor sets.**
   - Add `com.auditlog.application.AuditActorSet`.
   - Store canonical actors as an immutable `List<String>`.
   - Use `null` to mean "no actor filter" in `AuditEventQuery` and
     `AuditEventCursor`; do not use an empty actor set as a valid filter.

2. **Keep raw actor parsing outside the API layer.**
   - The controller continues to pass the raw `actor` query parameter string
     into `AuditEventQuery`.
   - `AuditEventQuery.validate()` canonicalizes the actor string through
     `AuditActorSet.parse(rawActor)`.
   - Production code outside Application must consume only canonical actors
     from the validated/effective query.

3. **Make cursor version `2` the only supported cursor shape.**
   - Cursor JSON field is `actors`, not `actor`.
   - Version `1` scalar-actor cursors fail as `INVALID_CURSOR`.
   - Cursor decode validates the pinned filter/window before repository
     access.

4. **Use PostgreSQL array filtering for multi-actor reads.**
   - The repository query uses `actor = ANY(:actors)` when actor filters are
     present.
   - The same query keeps resource AND filtering, `[from, to)`, keyset
     continuation, `ORDER BY event_timestamp DESC, id DESC`, and
     `LIMIT limit + 1`.
   - No new index is added in this implementation; the existing
     `idx_audit_events_actor_ts_id` is the intended path for actor-set reads.

## T10 — Application model: canonical actor sets

### Production changes

1. Add `AuditActorSet`.
   - Package: `com.auditlog.application`.
   - Suggested API:
     ```java
     public record AuditActorSet(List<String> values) {
       public static final int MAX_ACTORS = 10;
       public static AuditActorSet parse(String raw);
       public static AuditActorSet fromCanonical(List<String> values);
       public boolean isEmpty();
       public String[] toArray();
     }
     ```
   - `parse(null)` and blank strings return `null` to mean absent.
   - Parsing algorithm:
     split on comma, trim each token, reject any empty token, deduplicate,
     sort lexicographically with natural `String` ordering, and enforce
     `1 <= size <= 10`.
   - `fromCanonical(...)` is for decoded cursors. It rejects null elements,
     blank elements, empty lists, lists over ten values, duplicates, and any
     order that is not already lexicographically sorted.
   - Returned `values` must be immutable, for example via `List.copyOf`.

2. Update `ValidationError`.
   - Add:
     ```java
     public static ValidationError invalidActorSet()
     ```
   - Error code: `INVALID_ACTOR_SET`.
   - Field: `actor`.
   - Message must state that actor accepts 1-10 comma-separated non-empty
     values and that comma is reserved as the separator. For over-10 actor
     sets, the message must explicitly mention the 10-actor cap.

3. Update `AuditEventQuery`.
   - Preferred record shape:
     ```java
     public record AuditEventQuery(
         String rawActor,
         AuditActorSet actors,
         String resource,
         Instant from,
         Instant to,
         int limit,
         String cursor)
     ```
   - Keep the existing public constructor signature used by the controller:
     `new AuditEventQuery(String actor, String resource, Instant from,
     Instant to, int limit, String cursor)`. This constructor sets
     `rawActor = actor` and leaves `actors = null` until validation.
   - Add a private or package-private canonical constructor for service
     cursor rehydration:
     `AuditEventQuery.withFilters(AuditActorSet actors, String resource,
     Instant from, Instant to)`.
   - `validate()` behavior:
     - For cursor requests, keep mutual-exclusion validation and return
       without parsing raw actors.
     - For first-page requests, parse `rawActor` into `AuditActorSet`.
     - Require at least one of canonical actors or non-blank resource.
     - Keep existing `from`, `to`, window, and limit validation.
     - Return a validated query instance containing canonical actors.
   - Do not expose a scalar `actor()` accessor after this change. Update
     call sites to use `actors()`.

4. Keep Application free of Spring, JPA, and API dependencies.

### Tests

Add or update Application unit tests:

- `AuditActorSetTest`
  - `parse_nullOrBlank_returnsNull`.
  - `parse_singleActor_returnsOneItemSet`.
  - `parse_trimsDeduplicatesAndSorts`.
  - `parse_rejectsEmptyToken_betweenCommas`.
  - `parse_rejectsTrailingComma`.
  - `parse_rejectsMoreThanTenActors`.
  - `fromCanonical_acceptsSortedUniqueList`.
  - `fromCanonical_rejectsUnsortedList`.
  - `fromCanonical_rejectsDuplicates`.
- `AuditEventQueryValidationTest`
  - update existing valid scalar actor tests to assert
    `validated.actors().values()` equals a one-item list.
  - add multi-actor valid query.
  - add invalid actor-set cases and assert `INVALID_ACTOR_SET` with
    `field = "actor"`.
  - keep all existing time-window, limit, missing-filter, and cursor conflict
    tests.

## T11 — Cursor v2: pin full actor sets

### Production changes

1. Update `AuditEventCursor`.
   - New record shape:
     ```java
     public record AuditEventCursor(
         Instant ts,
         UUID id,
         List<String> actors,
         String resource,
         Instant from,
         Instant to,
         int v)
     ```
   - Set `public static final int V = 2`.
   - `encode(...)` remains base64-url JSON without padding.
   - `decode(...)` must reject all parse errors and unsupported versions with
     `IllegalArgumentException("INVALID_CURSOR")`.
   - After JSON decode, validate:
     - `ts` and `id` are present.
     - `from` and `to` are present.
     - `from < to`.
     - `to - from <= 7 days`.
     - at least one of `actors` or non-blank `resource` is present.
     - if `actors` is present, `AuditActorSet.fromCanonical(actors)` accepts
       it.
   - Normalize decoded actors by storing the immutable canonical list, not the
     mutable Jackson list.

2. Update `AuditEventQueryService`.
   - Keep cursor/filter mutual exclusion based on raw `actor`, resource,
     `from`, and `to`.
   - Catch cursor `IllegalArgumentException` and wrap as
     `ValidationException(new ValidationError("INVALID_CURSOR",
     "Cursor is invalid.", "cursor"))`, matching the existing API path.
   - On cursor requests, rehydrate an effective query with
     `AuditActorSet.fromCanonical(decoded.actors())`, decoded resource,
     decoded from/to, and the caller-supplied limit.
   - Validate limit for cursor requests before repository access; do not
     require client filters on cursor requests.

3. Update all cursor construction.
   - Repository-created `nextCursor` uses the full canonical actor set from
     `query.actors()`, not `last.actor()`.
   - Resource-only cursors encode `"actors": null`.

### Tests

Update cursor and service unit tests:

- Cursor round trips:
  - one actor.
  - multiple actors.
  - no actors with resource.
- Cursor rejection:
  - malformed base64.
  - non-JSON payload.
  - version `1`.
  - missing `ts`, missing `id`, missing `from`, missing `to`.
  - no pinned actors and no resource.
  - invalid window and over-seven-day window.
  - empty actor list, blank actor, over-ten actors, duplicate actors,
    unsorted actors.
- Query service:
  - cursor + raw actor conflict.
  - cursor + resource/from/to conflict.
  - malformed cursor maps to `INVALID_CURSOR`.
  - cursor rehydrates canonical actors/resource/window and passes anchor
    `(ts, id)` to repository.
  - cursor request rejects `limit = 0` and `limit = 501`.

## T12 — Infrastructure: multi-actor keyset query path

### Production changes

1. Update repository consumers for canonical actors.
   - `AuditEventRepository.findPage(AuditEventQuery query, Instant cursorTs,
     UUID cursorId)` can keep its current signature.
   - `JpaAuditEventRepository` reads `AuditActorSet actors = query.actors()`.

2. Update native SQL.
   - Use a PostgreSQL array predicate:
     ```sql
     AND (
           CAST(:actors AS TEXT[]) IS NULL
           OR actor = ANY(CAST(:actors AS TEXT[]))
         )
     ```
   - Preserve the existing clauses:
     ```sql
     event_timestamp >= :from
     event_timestamp < :to
     resource filter as logical AND
     cursor timestamp/id continuation
     ORDER BY event_timestamp DESC, id DESC
     LIMIT :limit_plus_one
     ```
   - Continue selecting only from `audit_events`.

3. Bind actor arrays safely.
   - Preferred implementation: unwrap the Hibernate session and create a JDBC
     `java.sql.Array` with type `text` when actors are present:
     `connection.createArrayOf("text", actors.toArray())`.
   - Bind `null` for `:actors` when there is no actor filter.
   - Keep the existing UTC `OffsetDateTime` conversion for timestamp
     parameters.
   - If Hibernate rejects the casted array parameter in local testing, use
     one of these fallbacks in order:
     - dynamically emit two static SQL strings, one with `actor = ANY(:actors)`
       for actor filters and one without the actor clause for resource-only
       queries;
     - use `entityManager.unwrap(Session.class).doReturningWork(...)` and a
       `PreparedStatement` for this single native query.
   - Do not use string-concatenated actor values in SQL.

4. Preserve page assembly.
   - Fetch `limit + 1`.
   - Drop the extra row from response items.
   - Build `hasMore` from `rows.size() > limit`.
   - Build `nextCursor` from the last kept row's `(timestamp, id)` and the
     query's full canonical actors/resource/window.

### Tests

Update `AuditEventQueryRepositoryIntegrationTest`:

- Existing single-actor pagination still returns 250 unique ids over three
  pages.
- New multi-actor pagination:
  - seed at least two actors with interleaved timestamps.
  - query `actor=svc:billing,svc:orders`.
  - assert global `(timestamp DESC, id DESC)` ordering across actors.
  - assert every returned actor is in the requested set.
  - assert no duplicate ids across pages.
- Actor-set + resource:
  - seed matching and non-matching actors/resources.
  - assert only rows matching both actor set and resource are returned.
- Exact case sensitivity:
  - seed `svc:billing` and `Svc:Billing`.
  - query one and assert the other is absent.
- Cursor behavior:
  - decode page-one cursor and assert it contains the full canonical actor
    set.
  - insert a newer in-window row after page one and assert cursor page two
    does not visit it.
- Explain checks:
  - keep single-actor actor-index check.
  - add multi-actor actor-index check.
  - keep resource-index check.
  - use test-scale `EXPLAIN (ANALYZE, BUFFERS)` only as a smoke check; T13
    owns 50M-row performance documentation.

## T13 — API, docs, and verification

### Production/API changes

1. Keep `AuditEventController` method parameters unchanged.
   - Continue accepting raw `String actor`.
   - Continue defaulting missing `limit` to `100`.
   - Continue not binding `offset`, so legacy `offset` remains ignored as an
     unknown query parameter.
   - Do not import `AuditEventCursor` into the API layer.

2. Ensure API error mapping covers `INVALID_ACTOR_SET`.
   - Existing `GlobalExceptionHandler` should work if it maps all
     `ValidationException` values generically.
   - Add MVC assertions for `error = "INVALID_ACTOR_SET"` and
     `field = "actor"`.

3. Update `README.md`.
   - Query examples include a multi-actor first page:
     `actor=service:billing,service:orders`.
   - Supported query parameter docs state:
     actor is a comma-separated exact-match set with 1-10 values; surrounding
     whitespace is trimmed; duplicates are deduplicated; order is
     insignificant; commas inside actor values are unsupported.
   - Cursor docs mention cursor version is opaque and callers must not parse
     or edit it.
   - Error list includes `INVALID_ACTOR_SET`.

4. Update `NOTES.md` after implementation lands.
   - Add a dated entry summarizing T10-T13 implementation and verification
     results.

5. Performance verification artifact.
   - Add or update a `.specs/query-api/` verification document after running
     the synthetic 50M-row checks.
   - Required query shapes:
     - single-actor first page and cursor page.
     - multi-actor first page and cursor page.
     - resource-only first page and cursor page.
     - actor-set + resource first page and cursor page.
   - Required limits: `100` and `500`.
   - Required plan evidence:
     `idx_audit_events_actor_ts_id` or `idx_audit_events_resource_ts_id`,
     selecting only from `audit_events`.
   - If actor-set + resource misses p95 or chooses an unacceptable plan, do
     not add an index silently. Write a follow-up task/spec for a specialized
     index and keep this implementation limited to the current design.

### Tests

Update API tests:

- `AuditEventControllerMvcTest`
  - happy path accepts `actor=svc:billing,svc:orders`.
  - invalid actor set returns `INVALID_ACTOR_SET`.
  - cursor + actor conflict still returns `CONFLICTING_PARAMETERS`.
  - malformed timestamp still returns `INVALID_TIMESTAMP`.
  - `offset` remains ignored; verify the service is still called and no error
    is emitted.
- `AuditEventControllerIntegrationTest`
  - first page with multi-actor query returns only requested actors.
  - actor-set + resource query returns intersection.
  - cursor-v2 continuation works without resupplying filters.

## Architecture and compatibility guardrails

- Do not touch `com.auditlog.domain`.
- Do not add Spring, JPA, Hibernate, servlet, or API imports to
  `com.auditlog.application`.
- Do not expose persistence entities outside Infrastructure.
- Do not read from `audit_events_archive`.
- Do not add UPDATE or DELETE behavior.
- Do not reintroduce offset pagination.
- Do not add a Flyway migration for this delta unless the performance task
  proves a new index is required and a follow-up task is accepted.
- Keep `AuditEventPage` response shape unchanged:
  `{ "items": [...], "nextCursor": <string|null>, "hasMore": <boolean> }`.
- Existing version-1 cursors are intentionally incompatible and must return
  HTTP 400.

## Suggested implementation order

1. Add `AuditActorSet` and its tests.
2. Update `ValidationError` and `AuditEventQuery`; fix validation tests.
3. Update `AuditEventCursor` to version 2; fix cursor tests.
4. Update `AuditEventQueryService`; fix service tests.
5. Update `JpaAuditEventRepository`; fix repository integration tests.
6. Update API MVC/integration tests.
7. Update `README.md` and `NOTES.md`.
8. Run unit, architecture, integration, and full checks.
9. Run or document the 50M-row performance verification.
10. Run spec self-evaluation for `.specs/query-api` and save the new report.

## Verification commands

Run these commands before considering implementation complete:

```bash
rtk ./gradlew unitTest archUnitTest
rtk ./gradlew integrationTest
rtk ./gradlew check
```

If Docker/Testcontainers is unavailable, record the exact failing
Testcontainers initializers in the final notes and keep the unit/ArchUnit
results explicit. Do not claim integration coverage passed unless the
PostgreSQL containers actually started.
