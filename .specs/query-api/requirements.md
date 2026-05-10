# Audit query by actor/resource — requirements

## Problem

Compliance officers, SREs, and security analysts need to answer the question
*"what did actor X do to resource Y last week"* against the audit log.

The current `GET /audit-events` endpoint accepts `actor`, `resource`, `from`,
`to`, `limit`, `offset`, but:

- It has no validation that constrains the query to an index-friendly shape, so
  callers can submit unbounded queries that will not meet the p95 ≤ 300ms target
  once `audit_events` reaches ~50M rows.
- It uses offset pagination, which degrades sharply at deep offsets and cannot
  hold p95 ≤ 300ms at the target row count.
- Its response is a bare list, with no signal about whether more pages exist
  and no stable continuation token across concurrent ingestion.

This feature refines `GET /audit-events` into an auditor-grade read API with
mandatory bounding inputs, keyset/cursor pagination, and a paginated response
envelope, while keeping the existing immutability and clean-architecture
guarantees of the service.

## User stories with acceptance criteria

Acceptance criteria are written in EARS style (Ubiquitous, Event-driven,
Unwanted-behaviour). Every "the system" below refers to the audit-log service
in scope — specifically `GET /audit-events`.

### US-1 — Query by actor and/or resource within a bounded time window

> As a compliance officer, I want to retrieve audit events for a given actor
> and/or resource within an explicit time window, so that I can investigate
> what actions occurred during an incident or audit period.

- **AC-1.1** (Event-driven) WHEN the client sends `GET /audit-events` with
  ISO-8601 UTC `from`, ISO-8601 UTC `to`, and at least one of `actor` or
  `resource`, THE SYSTEM SHALL return HTTP 200 with the matching events
  ordered by `event_timestamp` DESC, then `id` DESC as a deterministic
  tiebreaker.
- **AC-1.2** (Event-driven) WHEN both `actor` and `resource` are supplied,
  THE SYSTEM SHALL return only events matching both filters (logical AND).
- **AC-1.3** (Unwanted) IF `from` is missing OR `to` is missing OR both are
  missing, THEN THE SYSTEM SHALL return HTTP 400 with a body identifying the
  missing parameter(s).
- **AC-1.4** (Unwanted) IF neither `actor` nor `resource` is supplied, THEN
  THE SYSTEM SHALL return HTTP 400 with a body stating that at least one of
  `actor` or `resource` is required.
- **AC-1.5** (Unwanted) IF `from` is after `to`, THEN THE SYSTEM SHALL return
  HTTP 400.
- **AC-1.6** (Unwanted) IF `to − from` exceeds 7 days, THEN THE SYSTEM SHALL
  return HTTP 400 with a body stating the 7-day cap.
- **AC-1.7** (Unwanted) IF `from` or `to` is not a valid ISO-8601 UTC instant,
  THEN THE SYSTEM SHALL return HTTP 400.
- **AC-1.8** (Ubiquitous) THE SYSTEM SHALL treat `from` as inclusive and `to`
  as exclusive (`[from, to)`).

### US-2 — Page through results deterministically

> As a security analyst, I want to page through results with a stable cursor,
> so that I see every event exactly once even when new events are being
> ingested concurrently.

- **AC-2.1** (Ubiquitous) THE SYSTEM SHALL return a JSON envelope of the form
  `{ "items": [...], "nextCursor": <string|null>, "hasMore": <boolean> }`.
- **AC-2.2** (Ubiquitous) THE SYSTEM SHALL set `hasMore = (nextCursor != null)`.
- **AC-2.3** (Event-driven) WHEN the client sends a request with a valid
  `cursor` parameter, THE SYSTEM SHALL return the next page of items strictly
  older than the cursor's `(event_timestamp, id)` anchor, while applying the
  filter set encoded in the cursor.
- **AC-2.4** (Unwanted) IF a `cursor` is supplied together with any of
  `actor`, `resource`, `from`, or `to`, THEN THE SYSTEM SHALL return HTTP 400,
  because the cursor pins the filter set.
- **AC-2.5** (Unwanted) IF the supplied `cursor` is malformed, tampered, or
  not parseable, THEN THE SYSTEM SHALL return HTTP 400.
- **AC-2.6** (Ubiquitous) THE SYSTEM SHALL default `limit` to 100 and cap it
  at 500.
- **AC-2.7** (Unwanted) IF `limit` exceeds 500 OR is less than 1, THEN THE
  SYSTEM SHALL return HTTP 400.
- **AC-2.8** (Ubiquitous) THE SYSTEM SHALL produce an opaque, URL-safe
  `nextCursor` string (callers must not parse it).

### US-3 — Predictable performance at scale

> As an SRE, I want the query API to stay within p95 ≤ 300ms at 50M rows, so
> that downstream audit dashboards remain responsive.

- **AC-3.1** (Ubiquitous) THE SYSTEM SHALL hold p95 ≤ 300ms for any compliant
  request when `audit_events` contains 50M rows, measured server-side.
- **AC-3.2** (Ubiquitous) THE SYSTEM SHALL execute queries through the
  existing composite indexes `idx_audit_events_actor_timestamp` and
  `idx_audit_events_resource_timestamp`, verified with `EXPLAIN ANALYZE` on a
  50M-row dataset.
- **AC-3.3** (Ubiquitous) THE SYSTEM SHALL be side-effect free: a query
  request SHALL NOT write any rows to `audit_events`, mutate existing rows,
  or modify any other persistent state.

### US-4 — Response shape

> As an auditor, I want every event in the response to carry the full context
> needed for investigation, so that I do not need a second round-trip per row.

- **AC-4.1** (Ubiquitous) THE SYSTEM SHALL include `id` (UUID), `timestamp`
  (ISO-8601 UTC string), `actor`, `action`, `resource`, `outcome`
  (`SUCCESS|DENIED|ERROR`), and `context` (JSON object) on every item.
- **AC-4.2** (Ubiquitous) THE SYSTEM SHALL serialize `timestamp` as the same
  instant the event was recorded with, in UTC.
- **AC-4.3** (Ubiquitous) THE SYSTEM SHALL NOT include rows from
  `audit_events_archive`.

### US-5 — Architectural conformance

> As the maintainer of this service, I want the new query path to follow the
> existing clean-architecture rules, so that ArchUnit tests keep passing and
> the system remains testable.

- **AC-5.1** (Ubiquitous) THE SYSTEM SHALL define cursor and request types in
  the Application layer; the API layer SHALL only translate HTTP
  ↔ Application DTOs.
- **AC-5.2** (Ubiquitous) THE SYSTEM SHALL keep all JPA/Hibernate access
  inside the Infrastructure layer, behind the existing
  `AuditEventRepository` port.
- **AC-5.3** (Ubiquitous) THE DOMAIN layer SHALL remain free of Spring, JPA,
  and any framework dependency.
- **AC-5.4** (Ubiquitous) ALL existing unit, integration, and ArchUnit tests
  SHALL continue to pass; new ACs SHALL be covered by:
  - unit tests for cursor encoding/decoding and validation rules,
  - a Testcontainers integration test that exercises pagination across at
    least 3 pages with concurrent inserts,
  - an ArchUnit assertion confirming the new request/response types live in
    the correct layer.

## Out of scope

- Authentication and authorization (no auditor role, no JWT, no mTLS); the
  endpoint stays unauthenticated as today.
- Querying `audit_events_archive` or any cross-table UNION.
- Filters beyond `actor`, `resource`, and time window — no `action`,
  `outcome`, or JSONB context search in v1.
- Calendar-week semantics; the API never infers "last week" — clients always
  supply explicit `from`/`to`.
- Time windows wider than 7 days.
- Mutation, deletion, or correction of audit events (already prevented by
  the V2 Flyway trigger).
- Streaming exports, CSV/Parquet downloads, or bulk extraction APIs.
- Multi-tenancy; there is no tenant column today and none is added here.
- Rate limiting, quotas, and per-caller throttling.
- Backwards-compatibility shim for the current offset/limit pagination —
  callers will migrate to cursor pagination as part of this change.
- Logging/auditing the audit-query calls themselves ("audit the auditors").

## Open questions

1. **Audit-the-auditors** — Should reads of `GET /audit-events` themselves be
   recorded somewhere (e.g. as new audit events with `action="audit.read"`)?
   Compliance teams sometimes require this. Defaulting to "no" for v1.
2. **Cursor TTL / signing** — Should `nextCursor` be HMAC-signed and/or
   expire after N hours, or is an opaque base64 of `(timestamp, id, filters)`
   sufficient? Signing prevents tampering; TTL prevents stale-snapshot abuse.
3. **Error body format** — RFC 7807 `application/problem+json` or a
   project-specific shape? The repo has no precedent yet.
4. **Inclusive/exclusive `to`** — Confirm `[from, to)` semantics are
   acceptable to auditors (proposed default in AC-1.8).
5. **Offset/limit deprecation timing** — Switch to cursor immediately and
   remove offset, or run both for one release? AGENTS.md prefers minimal
   change, but offset cannot meet p95.
6. **Total count** — Some auditor UIs want a `total` field. Cursor
   pagination usually omits it because counting 50M rows would itself breach
   p95. Defaulting to "omit"; confirm.
7. **Future auth handoff** — Which team owns adding Spring Security and the
   `auditor` role, and on what timeline? This feature ships unauthenticated
   on the assumption that network-level controls gate the service.
8. **Performance verification environment** — Do we have a 50M-row dataset
   (synthetic or anonymised) available for the EXPLAIN ANALYZE / p95
   measurement required by AC-3.1 and AC-3.2, or does generating one fall
   within this feature?
