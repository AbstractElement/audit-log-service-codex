# Audit query by actor/resource — tasks

Decomposition optimised for **safe, reversible commits**: each task is a
single PR that leaves `master` green (build + unit + integration + ArchUnit
green), is independently revertible, and changes one concern. New code is
added *alongside* old code; the old code is removed only after every caller
has migrated. The Flyway story is split the same way — additive migration
first, destructive migration last.

References point to specific anchors in
[requirements.md](./requirements.md) and [design.md](./design.md).

Branch: `feature/audit-query-by-actor-resource` (already cut from `master`).

## Task graph

```
T1 ──┐
     ├──► T4 ──► T5 ──► T6 ──► T7 ──► T9
T2 ──┤            ▲
     ├──► T3 ─────┘
     └──► T8
```

`T1` (additive migration) and `T2` (new types) have no dependencies and can
land in either order. `T9` (perf verification) is the last gate before the
feature merges to `master`.

---

## T1 — Flyway V3: add new keyset indexes (additive only)

**Goal.** Introduce the two composite indexes that make keyset pagination
index-bounded. **No drops** in this PR.

**References.**
- requirements.md → AC-3.2 (queries must run through composite indexes,
  verified by EXPLAIN ANALYZE).
- design.md → *Indexes → New indexes (Flyway `V3__refine_query_indexes.sql`)*.

**Scope.**
- New file `src/main/resources/db/migration/V3__add_keyset_indexes.sql`
  with `CREATE INDEX CONCURRENTLY` for
  `idx_audit_events_actor_ts_id` and `idx_audit_events_resource_ts_id`.
- Configure Flyway to run this migration outside a transaction
  (`spring.flyway.transactional=false` or per-migration directive — see
  design.md note under *New indexes*).
- Update the existing `AuditEventPersistenceIntegrationTest` only if the
  Testcontainers bootstrap fails on the new migration; otherwise leave it.

**Definition of done.**
- `./gradlew flywayMigrate` (or the equivalent integration test) applies
  V3 against a fresh PG 16 Testcontainer with no errors.
- `\d+ audit_events` (verified in the integration test or one-off check)
  shows both new indexes alongside the existing three.
- All existing tests pass — `unitTest`, `integrationTest`, `archUnitTest`.

**Dependencies.** None. Can ship before any code change.

---

## T2 — Application value types: `AuditEventCursor`, `AuditEventPage`, `AuditEventQuery`

**Goal.** Add the new Application-layer types as pure records, not yet
wired into any caller.

**References.**
- requirements.md → AC-2.1 (envelope shape), AC-2.8 (opaque cursor),
  AC-5.1 (cursor + request types in Application).
- design.md → *Cursor format*, *API contract → Response*,
  *Integration with arch layers → Application layer (Records / value types)*.

**Scope.**
- `com.auditlog.application.AuditEventCursor` — record `(Instant ts, UUID id,
  String actor, String resource, Instant from, Instant to, int v)` with
  `encode()` / `decode(String)` using `Base64.getUrlEncoder().withoutPadding()`
  over a Jackson-serialised JSON envelope. Version constant `1`.
- `com.auditlog.application.AuditEventPage` — record
  `(List<AuditEventView> items, String nextCursor, boolean hasMore)`.
- `com.auditlog.application.AuditEventQuery` — record carrying `actor?`,
  `resource?`, `from`, `to`, `limit`, `cursor?`. Ctor does no validation
  yet (T3 owns validation).
- Unit test `AuditEventCursorTest` — encode/decode round-trip, malformed
  base64 → exception, version mismatch → exception.

**Definition of done.**
- New types compile under `com.auditlog.application`.
- `AuditEventCursorTest` passes.
- No production code references the new types yet (grep confirms).
- Existing tests untouched and still pass.

**Dependencies.** None.

---

## T3 — Cross-field validation for `AuditEventQuery`

**Goal.** Implement every rule in the design's *Validation rules* table on
`AuditEventQuery`, returning a typed validation error.

**References.**
- requirements.md → AC-1.3, AC-1.4, AC-1.5, AC-1.6, AC-1.7, AC-2.4, AC-2.5,
  AC-2.6, AC-2.7.
- design.md → *Validation rules* (table).

**Scope.**
- Introduce `com.auditlog.application.ValidationError` (record with
  `code`, `message`, `field?`).
- Add `AuditEventQuery.validate()` returning either the same query or a
  `ValidationError`. Use a small sealed `Result<T,E>` if needed; otherwise
  throw a checked-style domain exception. Pick whichever matches the
  existing project idiom (verify by reading
  `com.auditlog.application.AuditEventQueryService` first).
- Unit test `AuditEventQueryValidationTest` — one test per row of the
  validation rules table, plus the cursor/filter mutual-exclusion case.

**Definition of done.**
- Every row of the *Validation rules* table is covered by a passing unit
  test asserting the correct error code and field.
- `AuditEventQuery.validate()` is referenced by no production caller yet
  (added in T5).

**Dependencies.** T2 (uses `AuditEventQuery`, `AuditEventCursor`).

---

## T4 — Repository port: add `findPage(AuditEventQuery)` and JPA implementation

**Goal.** Extend the `AuditEventRepository` port with a keyset-pagination
method and implement it in the existing infrastructure adapter, alongside
the current `find(AuditEventSearchCriteria)` (which stays for now).

**References.**
- requirements.md → AC-1.1 (ordering), AC-1.2 (AND filter), AC-1.8
  (`[from, to)` semantics), AC-2.3 (cursor compare), AC-3.2 (index usage),
  AC-4.1, AC-4.2, AC-4.3 (response shape, hot table only), AC-5.2
  (JPA isolated to Infrastructure).
- design.md → *Pagination strategy → Continuation predicate*, *Sort &
  determinism*, *Integration with arch layers → Infrastructure layer*,
  *Indexes → New indexes*.

**Scope.**
- Add `AuditEventPage findPage(AuditEventQuery query)` to
  `com.auditlog.application.AuditEventRepository`.
- Implement in `com.auditlog.infrastructure.persistence.JpaAuditEventRepository`
  using a native query that matches the SQL in design.md *Continuation
  predicate*, with `LIMIT :limit + 1` and slicing logic in the adapter
  (NOT in the service — the adapter knows it fetched `limit+1`).
- The adapter MUST select **only** from `audit_events` (AC-4.3); never
  `audit_events_archive`.
- Map results to `AuditEventView` using the existing entity-to-view mapping.
- Annotate with `@Transactional(readOnly = true)`.
- Integration test `AuditEventQueryRepositoryIntegrationTest`
  (Testcontainers) — seed ≥ 250 rows, fetch 3+ pages with limit=100, assert
  no duplicate ids, assert ordering `(timestamp desc, id desc)`,
  interleave inserts at the head between pages and assert they are not
  visited.
- Add `EXPLAIN (ANALYZE, BUFFERS)` assertion (or capture into a log) showing
  one of the new V3 indexes is used, fulfilling AC-3.2 at unit-of-test
  scale.

**Definition of done.**
- `JpaAuditEventRepository.findPage` exists and the new integration test
  passes.
- Old `find(...)` and the existing
  `AuditEventPersistenceIntegrationTest` are unchanged.
- `archUnitTest` still passes (no new package, no new framework leak).

**Dependencies.** T1 (indexes must exist for the SQL to use them),
T2 (uses `AuditEventQuery`, `AuditEventPage`).

---

## T5 — Application service: `AuditEventQueryService.queryPage(...)`

**Goal.** Add a new orchestration method on the existing
`AuditEventQueryService` that validates, decodes/encodes cursors, and
calls `findPage`. Old `query(AuditEventSearchCriteria)` stays.

**References.**
- requirements.md → AC-2.1, AC-2.2, AC-2.3, AC-2.4, AC-2.5, AC-2.8,
  AC-3.3 (side-effect free), AC-5.1.
- design.md → *Cursor format*, *Pagination strategy → Page assembly*,
  *Integration with arch layers → Application layer*.

**Scope.**
- New method `AuditEventPage queryPage(AuditEventQuery query)` on
  `AuditEventQueryService`:
  1. If `query.cursor()` is non-null: decode it, reject if any client
     filter param is also present (AC-2.4), reapply the encoded filters.
  2. Otherwise: call `query.validate()` (T3); reject on error.
  3. Call `repository.findPage(...)`; build `nextCursor` from the last
     **kept** item; set `hasMore` accordingly.
- Unit test `AuditEventQueryServiceTest` (Mockito or hand-rolled fake of
  the port) — exercise: happy path single page, happy path multi-page,
  cursor + filter conflict, malformed cursor, validation failure.
- `AuditEventQueryService` and the new method must contain **no**
  Spring/JPA/HTTP imports (verify in CR).

**Definition of done.**
- New service method passes its unit tests.
- No call site uses the new method yet (added in T6).
- ArchUnit + existing tests still pass.

**Dependencies.** T2, T3, T4.

---

## T6 — API layer: switch `GET /audit-events` to keyset, add `@RestControllerAdvice`

**Goal.** Refactor `AuditEventController` to consume the new service
method, accept the new query parameters, and return the new envelope.
This is the user-visible breaking change.

**References.**
- requirements.md → AC-1.1, AC-1.3, AC-1.4, AC-1.5, AC-1.6, AC-1.7, AC-2.4,
  AC-2.5, AC-2.6, AC-2.7, AC-4.1, AC-4.2, AC-5.1; *Out of scope* item
  "Backwards-compatibility shim for the current offset/limit pagination".
- design.md → *API contract* (all subsections), *Errors — 400*,
  *Integration with arch layers → API layer*.

**Scope.**
- Replace the body of `GET /audit-events` to:
  - Parse query string into a package-private `AuditEventQueryRequest`
    DTO (API layer).
  - Translate to `AuditEventQuery` (Application).
  - Call `AuditEventQueryService.queryPage(...)`.
  - Map `AuditEventPage` → `AuditEventPageResponse` (API DTO with
    `items`, `nextCursor`, `hasMore`).
- Drop the existing `limit`/`offset` parameters from the request DTO.
  Add `cursor`. Keep `actor`, `resource`, `from`, `to`, `limit` per the
  contract.
- Add `com.auditlog.api.GlobalExceptionHandler`
  (`@RestControllerAdvice`) mapping `ValidationError` /
  `IllegalArgumentException` (whichever T3 chose) to the
  `{ error, message, field? }` body shape from design.md.
- Spring MVC slice test (`@WebMvcTest`) — one assertion per error path
  in the validation table, plus the happy path returning the envelope.
- Update the existing controller integration test (full Spring context +
  Testcontainers) so the golden path uses cursor pagination.

**Definition of done.**
- `./gradlew unitTest integrationTest archUnitTest` all green.
- Manual `curl` against `bootRun` returns the new envelope shape on the
  happy path and the new error shape on each validation failure.
- The endpoint no longer accepts `offset`; supplying `offset` returns
  `400` (caught by Spring as an unknown parameter only if strict binding
  is on — otherwise it is silently ignored; this is acceptable per
  Open Question #5 in requirements).

**Dependencies.** T5.

---

## T7 — Cleanup: remove dead code and Flyway V4 drop old indexes

**Goal.** Now that nothing references the old query path, remove it.
This is a destructive PR, intentionally separate from T6 so a regression
in T6 does not require also reverting a schema drop.

**References.**
- requirements.md → *Out of scope* item "Backwards-compatibility shim for
  the current offset/limit pagination".
- design.md → *Indexes → Indexes to drop*, *Integration with arch layers →
  Type changes summary*.

**Scope.**
- New file `src/main/resources/db/migration/V4__drop_legacy_indexes.sql`
  with `DROP INDEX CONCURRENTLY idx_audit_events_actor_timestamp;`
  and `DROP INDEX CONCURRENTLY idx_audit_events_resource_timestamp;`.
- Delete `com.auditlog.application.AuditEventSearchCriteria`.
- Delete `AuditEventRepository.find(AuditEventSearchCriteria)` and its
  JPA implementation.
- Delete `AuditEventQueryService.query(AuditEventSearchCriteria)` (the
  old method that wraps `find`).
- Delete or rewrite any test that references those types
  (`AuditEventPersistenceIntegrationTest` will need its old `find` paths
  removed — keep only what is still meaningful for the write path).

**Definition of done.**
- `grep -rn AuditEventSearchCriteria src/` returns nothing.
- V4 applies cleanly on a fresh PG 16 Testcontainer; only the three
  expected indexes remain (`idx_audit_events_actor_ts_id`,
  `idx_audit_events_resource_ts_id`, `idx_audit_events_timestamp`).
- All test tasks green.

**Dependencies.** T6 (must merge first so the old code has zero callers).

---

## T8 — ArchUnit assertion: cursor stays in Application

**Goal.** Lock in AC-5.1 with a static check so future refactors cannot
push cursor encoding into the API layer.

**References.**
- requirements.md → AC-5.1, AC-5.4.
- design.md → *Test coverage map → ArchUnit*,
  *Alignment with AGENTS.md → Architecture enforcement*.

**Scope.**
- Add to `ArchitectureRulesTest`:
  - `noClasses().that().resideInAPackage("..api..").should().dependOnClassesThat().haveSimpleName("AuditEventCursor")`
    — the API layer must not import `AuditEventCursor` directly; it sees
    only the opaque `String nextCursor`.
  - `classes().that().haveSimpleName("AuditEventCursor").should().resideInAPackage("..application..")`.
- Verify the rules fail when intentionally violated (manual one-off
  experiment in a scratch branch — do not commit the violating change).

**Definition of done.**
- New assertions pass on the current code.
- `archUnitTest` task green.

**Dependencies.** T2 (cursor type must exist).

---

## T9 — Performance verification at 50M rows

**Goal.** Discharge AC-3.1 and AC-3.2 with reproducible evidence before
the feature merges to `master`.

**References.**
- requirements.md → AC-3.1, AC-3.2; *Open Questions → Performance
  verification environment*.
- design.md → *Indexes → Combined `actor + resource` queries*
  ("must be verified with EXPLAIN ANALYZE against a 50M-row dataset").

**Scope.**
- Seed-script (one-off, not committed to `src/main`): a small Java or
  `psql` `\copy` script that loads ~50M synthetic rows into a local PG 16
  with realistic actor/resource cardinality.
- Capture `EXPLAIN (ANALYZE, BUFFERS)` for:
  - actor-only query within a 7-day window;
  - resource-only query within a 7-day window;
  - actor + resource query within a 7-day window;
  - cursor-driven page 10 of each shape.
- Capture wall-clock p95 over ≥ 1 000 requests for each shape (e.g. with
  `wrk`, `vegeta`, or a small JMH/JMeter harness).
- Append the results to `NOTES.md` (per AGENTS.md invariant: NOTES.md
  records performed actions). Cite the index actually used for each
  query shape.

**Definition of done.**
- Each query shape's p95 is documented at ≤ 300ms.
- `EXPLAIN ANALYZE` for each shape shows an Index Scan / Index Only Scan
  on `idx_audit_events_actor_ts_id` or `idx_audit_events_resource_ts_id`.
- If any shape misses the target, file a follow-up issue (do **not**
  silently merge); recommended remediation is the partial index option
  from design.md *Combined `actor + resource` queries*.

**Dependencies.** T1 (indexes), T6 (full request path live).

---

## Suggested PR order and rollback notes

| # | Task | Reversible by |
|---|------|---------------|
| 1 | T1   | `DROP INDEX CONCURRENTLY` of the two new indexes (no schema-shape change). |
| 2 | T2   | `git revert` — pure additive code. |
| 3 | T3   | `git revert` — adds tests + a method on a still-unused record. |
| 4 | T8   | `git revert` — single ArchUnit rule. |
| 5 | T4   | `git revert` — port method and adapter method are additive. |
| 6 | T5   | `git revert` — service method is additive. |
| 7 | T6   | `git revert` — controller swap; same revert restores the old offset endpoint. |
| 8 | T7   | `git revert` of the code change + Flyway `V5__restore_legacy_indexes.sql` if the drop has already shipped. |
| 9 | T9   | N/A — documentation only. |

T8 is slotted before T4–T7 because it's cheap, independent, and protects
the layer boundary the moment the cursor type exists.
