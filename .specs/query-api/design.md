# Audit query by actor/resource — design

Companion to [requirements.md](./requirements.md). The requirements document
fixes the *what*; this document fixes the *how*.

## API contract

### Endpoint

```
GET /audit-events
```

### Request — first page (no cursor)

| Param      | In    | Type            | Required             | Notes                                                |
|------------|-------|-----------------|----------------------|------------------------------------------------------|
| `actor`    | query | string          | One of actor/resource required | Exact match; case-sensitive.                  |
| `resource` | query | string          | One of actor/resource required | Exact match; case-sensitive.                  |
| `from`     | query | ISO-8601 UTC    | yes                  | Inclusive lower bound on `event_timestamp`.          |
| `to`       | query | ISO-8601 UTC    | yes                  | Exclusive upper bound on `event_timestamp`.          |
| `limit`    | query | integer         | no (default 100)     | `1 ≤ limit ≤ 500`.                                   |

### Request — subsequent page (cursor)

| Param    | In    | Type    | Required | Notes                                              |
|----------|-------|---------|----------|----------------------------------------------------|
| `cursor` | query | string  | yes      | Opaque token from a previous response.             |
| `limit`  | query | integer | no       | May change between pages; subject to same bounds.  |

When `cursor` is present, **none of** `actor`, `resource`, `from`, `to` may be
supplied — the cursor pins the filter set (AC-2.4). Mixing returns `400`.

### Response — `200 OK`

```jsonc
{
  "items": [
    {
      "id":        "5b9c…-uuid",
      "timestamp": "2026-05-03T14:22:09.871Z",
      "actor":     "svc:billing",
      "action":    "invoice.created",
      "resource":  "invoice/4711",
      "outcome":   "SUCCESS",
      "context":   { "amount": 199.0, "currency": "EUR" }
    }
  ],
  "nextCursor": "eyJ0cyI6Ij…", // null when hasMore == false
  "hasMore":    true
}
```

`hasMore == (nextCursor != null)` (AC-2.2). Items are ordered as defined in
*Sort & determinism* below.

### Errors — `400 Bad Request`

A single shape, returned by a `@RestControllerAdvice` handler:

```json
{ "error": "INVALID_REQUEST", "message": "from must be before to", "field": "from" }
```

The exact body format (e.g. RFC 7807) is still
[Open Question #3](./requirements.md#open-questions); this design uses the
ad-hoc shape above to unblock implementation. Switching later is a
controller-layer change only.

## Sort & determinism

- Final ORDER BY for every query: `event_timestamp DESC, id DESC`.
  - Primary key — `event_timestamp` — matches the auditor reading model
    (newest events first within an investigation window).
  - Secondary key — `id` (UUID v4, random) — guarantees a strict total order
    across rows that share a millisecond. Without it, two events emitted in
    the same instant could be visited in an indeterminate order across pages,
    breaking the keyset invariant.
- The table is immutable (Flyway V2 trigger blocks UPDATEs), so the
  `(timestamp, id)` ordering of any row is fixed for the lifetime of the row.
  This is what makes keyset pagination correct here: a cursor anchored on
  `(ts, id)` always partitions the table into "seen" and "unseen" sets, even
  while new events are being ingested at the head.
- New events ingested *after* a cursor is issued sort *before* the cursor
  (newer timestamp, DESC order) and therefore are not visited by that
  cursor's continuation — a snapshot-like read without any explicit
  transaction snapshot. This is the desired behaviour for an auditor working
  through a window: the result set does not silently grow underneath them.

## Pagination strategy with reasoning

### Choice — keyset over `(event_timestamp DESC, id DESC)`

#### Why not offset

At ~50M rows the offset/limit pattern fails the p95 ≤ 300ms target the moment
auditors page deep:

- `OFFSET k` forces PostgreSQL to scan and discard `k` rows on every request;
  cost grows linearly with depth.
- It is also unstable under concurrent ingest — every new event ahead of the
  current cursor effectively shifts subsequent offsets, so a row may appear
  twice or be skipped entirely as the user pages.

The current `GET /audit-events` uses offset; this design replaces it
(per the *Out of scope* clause in requirements: no backwards-compatibility
shim for offset).

#### Why keyset

- Cost is `O(log n + page)` regardless of how deep the user has paged,
  because the index lets PG seek directly to the `(ts, id)` anchor.
- Stable under concurrent ingest (see *Sort & determinism* above).
- Plays naturally with the immutable, append-only model already in place.

### Cursor format

Opaque base64-url of a small JSON envelope:

```jsonc
{
  "ts":       "2026-05-03T14:22:09.871Z",
  "id":       "5b9c…-uuid",
  "actor":    "svc:billing",      // null if not in original filter
  "resource": null,
  "from":     "2026-04-26T00:00:00Z",
  "to":       "2026-05-03T00:00:00Z",
  "v":        1                    // schema version for forward compat
}
```

- **Encoding**: `Base64.getUrlEncoder().withoutPadding().encodeToString(json)`.
- **Decoding**: `400` on any parse error or unknown `v` (AC-2.5).
- **Tampering**: not signed in v1. The endpoint is read-only and currently
  unauthenticated, so a tampered cursor can only return rows that the same
  caller could ask for directly via filter params. HMAC signing is parked in
  [Open Question #2](./requirements.md#open-questions).
- **Pinned filters**: every cursor request decodes the envelope and reapplies
  `actor`, `resource`, `from`, `to` from inside it; client-supplied filter
  params alongside `cursor` cause a `400` (AC-2.4).

### Page assembly — `LIMIT n+1`

Each page runs **one** query with `LIMIT limit + 1`. The (limit+1)-th row, if
present, is dropped from `items` and used solely to set `hasMore = true` and
to source the next `nextCursor`.

Why: a separate `COUNT(*)` over 50M rows would itself breach the p95 target
and is therefore unacceptable. A trailing "exists 1 row" probe would add a
second round-trip with no upside. `LIMIT n+1` gives `hasMore` for free at
the cost of fetching one extra row. `nextCursor` is built from the last
**kept** row (`items[limit-1]`); the dropped row is not exposed.

### Continuation predicate

Adding the keyset compare to the WHERE clause:

```sql
WHERE  event_timestamp >= :from
  AND  event_timestamp <  :to
  AND  (:actor    IS NULL OR actor    = :actor)
  AND  (:resource IS NULL OR resource = :resource)
  AND  (
        :cursor_ts IS NULL
        OR event_timestamp <  :cursor_ts
        OR (event_timestamp = :cursor_ts AND id < :cursor_id)
      )
ORDER  BY event_timestamp DESC, id DESC
LIMIT  :limit + 1;
```

Written as `(event_timestamp, id) < (:cursor_ts, :cursor_id)` in row-form
for clarity; the expanded form above is what we'll emit because PostgreSQL
plans it more reliably against the composite index.

## Indexes

### New indexes (Flyway `V3__refine_query_indexes.sql`)

```sql
CREATE INDEX idx_audit_events_actor_ts_id
  ON audit_events (actor, event_timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_resource_ts_id
  ON audit_events (resource, event_timestamp DESC, id DESC);
```

Including `id` as the trailing key column lets the keyset compare be served
entirely from the index without a heap visit per row to resolve same-instant
ties — the dominant cost driver for AC-3.1.

### Indexes to drop

```sql
DROP INDEX idx_audit_events_actor_timestamp;
DROP INDEX idx_audit_events_resource_timestamp;
```

These are strict key-prefixes of the new indexes; PG will choose the new
indexes for any query the old ones served, so retaining them only doubles
the write amplification on every ingest.

### Index kept

`idx_audit_events_timestamp ON (event_timestamp DESC)` is **kept** untouched.
The new query endpoint never uses it (AC-1.4 forbids time-only queries), but
it remains available for ad-hoc operational reads. Removing it is a
follow-up if and when ad-hoc usage is confirmed absent.

### Combined `actor + resource` queries

No combined `(actor, resource, event_timestamp, id)` index. PostgreSQL is
expected to pick the more selective single-column index — typically `actor`
— and filter `resource` from the heap. Two reasons to defer:

1. Combined queries are rare relative to single-filter ones; an extra index
   adds write amplification on every ingest.
2. Selectivity of `actor` at 50M rows is high enough in expected workloads
   to keep the heap-filter cost well under p95.

This **must be verified** with `EXPLAIN ANALYZE` against a 50M-row dataset
before sign-off (AC-3.2). If selectivity proves insufficient for some hot
actor, add a partial index targeting that actor rather than a global
combined index.

## Validation rules

Validation runs in the Application layer, not the controller, so it is
covered by unit tests independent of Spring MVC.

| Rule | Source AC | Failure mode |
|------|-----------|--------------|
| Either `actor` or `resource` non-blank (when no cursor)        | AC-1.4 | `400` `MISSING_FILTER` |
| `from` and `to` both supplied (when no cursor)                 | AC-1.3 | `400` `MISSING_PARAMETER`, `field` set |
| `from`, `to` parse as ISO-8601 UTC                             | AC-1.7 | `400` `INVALID_TIMESTAMP` |
| `from < to`                                                    | AC-1.5 | `400` `INVALID_TIME_WINDOW` |
| `to − from ≤ Duration.ofDays(7)`                               | AC-1.6 | `400` `WINDOW_TOO_LARGE` |
| `1 ≤ limit ≤ 500`                                              | AC-2.6, AC-2.7 | `400` `LIMIT_OUT_OF_RANGE` |
| `cursor` parses as base64-url JSON, version 1                  | AC-2.5 | `400` `INVALID_CURSOR` |
| Mutual exclusion: `cursor` ⇔ none of `actor`/`resource`/`from`/`to` | AC-2.4 | `400` `CONFLICTING_PARAMETERS` |

Bean Validation handles single-field shapes (`@Min`, `@Max` on `limit`,
`@Pattern` on the timestamp formats). Cross-field rules are enforced inside
the Application service via a dedicated `AuditEventQuery.validate()` step
that returns a `Result<AuditEventQuery, ValidationError>` — keeping the
domain expression-oriented and easy to unit-test without Spring.

## Integration with arch layers

```
HTTP request
   │
   ▼
┌─────────────────────────────────────────────────────────┐
│ API layer  (com.auditlog.api)                           │
│  AuditEventController                                   │
│   - @GetMapping("/audit-events")                        │
│   - parses query string into AuditEventQueryRequest     │
│     (API DTO, package-private)                          │
│   - calls AuditEventQueryService.query(...)             │
│   - maps AuditEventPage → AuditEventPageResponse        │
│   - @RestControllerAdvice maps ValidationError → 400    │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│ Application layer  (com.auditlog.application)           │
│  Records / value types:                                 │
│   AuditEventQuery       (validated request)             │
│   AuditEventCursor      (ts, id, filters, version)      │
│   AuditEventPage        (items, nextCursor, hasMore)    │
│  Service:                                               │
│   AuditEventQueryService                                │
│     · validates request                                 │
│     · decodes cursor (if any) and reapplies filters     │
│     · calls AuditEventRepository.findPage(query)        │
│     · slices `limit+1`, builds nextCursor               │
│  Port:                                                  │
│   AuditEventRepository.findPage(AuditEventQuery)        │
└──────────────────────┬──────────────────────────────────┘
                       ▼
┌─────────────────────────────────────────────────────────┐
│ Infrastructure layer  (com.auditlog.infrastructure)     │
│  JpaAuditEventRepository implements AuditEventRepository│
│   - emits the SQL shown in *Pagination strategy*        │
│   - @Transactional(readOnly = true)                     │
│   - maps AuditEventEntity → AuditEvent (domain)         │
│  Flyway: V3__refine_query_indexes.sql                   │
└─────────────────────────────────────────────────────────┘

Domain layer (com.auditlog.domain) is untouched:
  AuditEvent and AuditOutcome stay framework-free; no change.
```

### Type changes summary

- `AuditEventSearchCriteria` (existing) is **replaced** by
  `AuditEventQuery`. The new type carries the cursor and drops `offset`.
  Replacing rather than evolving avoids the temptation of a hybrid
  cursor/offset shape, which would violate AC-2.4.
- `AuditEventRepository.find(...)` is **replaced** by
  `AuditEventRepository.findPage(...)` returning `AuditEventPage`. Single
  port method change, single integration test rewrite.
- `AuditEventQueryService.query(...)` returns `AuditEventPage` instead of
  `List<AuditEventView>`. Wiring change only inside the Application layer.

### Test coverage map (AC-5.4)

- Unit tests
  - `AuditEventCursorTest` — round-trip encode/decode, version mismatch,
    malformed input.
  - `AuditEventQueryValidationTest` — every row of the *Validation rules*
    table.
- Integration test
  - `AuditEventQueryIntegrationTest` (Testcontainers PG 16) — seed N rows,
    page through with cursor across ≥ 3 pages, interleave concurrent inserts
    at the head, assert no row is seen twice and no row is skipped.
- ArchUnit
  - Existing rules already enforce that `AuditEventQuery`,
    `AuditEventCursor`, `AuditEventPage` live under
    `com.auditlog.application`. No new rule needed unless a new sub-package
    is introduced.

## Alignment with AGENTS.md

- **DDD-first** — Domain layer is not touched. `AuditEventQuery` and
  `AuditEventCursor` are Application-layer value types; `AuditEvent` itself
  remains framework-free.
- **Clean architecture layering** — Dependencies still point inward only.
  The new repository port `AuditEventRepository.findPage` is defined in
  Application; its sole implementation lives in Infrastructure; the API
  layer talks only to `AuditEventQueryService`.
- **Persistence rules** — Schema changes are limited to the new Flyway
  migration `V3__refine_query_indexes.sql`. No JPA/Hibernate access leaks
  out of Infrastructure.
- **Testing strategy** — Unit tests cover validation and cursor logic with
  zero Spring context. The Testcontainers integration test exercises the
  real keyset SQL against PG 16. ArchUnit keeps the layering invariants
  green.
- **Layer boundaries** — API does not import any Infrastructure or JPA
  type. Persistence entities are not exposed past Infrastructure (we map to
  `AuditEvent`/`AuditEventView` at the boundary as today).
- **Ports & adapters** — `AuditEventRepository` stays the single port for
  audit-log persistence; we evolve its method signature, not its location.
- **Architecture enforcement** — The existing
  `ArchitectureRulesTest` continues to enforce
  *API ↛ Infrastructure*, *Application ↛ Infrastructure impls*,
  *Domain ↛ Spring/JPA*. No new rule is required; one assertion will be
  added so that `AuditEventCursor` lives under `com.auditlog.application`
  to keep cursor encoding out of the controller.
- **Smallest safe change** — One refined endpoint (no parallel `/v2`), one
  Flyway migration, one port-method evolution, one new pagination value
  type. Offset support is removed rather than maintained, in line with the
  *Out of scope* clause in requirements.
- **Branching** — Work lands on `feature/audit-query-by-actor-resource`
  (already cut from `master`). NOTES.md will be appended after the feature
  merges.
