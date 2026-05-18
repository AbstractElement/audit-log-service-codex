# Query API Plan Delta

Date: 2026-05-18

Scope: compared `.specs/query-api/requirements.md` with the final plan
documents in `.specs/query-api/plans/` (`T1.md` through `T8.md`).

## Summary

The plans are not fully aligned with `requirements.md`.

Most of the happy-path query behavior, response envelope, keyset ordering,
clean-architecture split, and archive exclusion are planned. The material
deltas are around performance verification, index strategy, cursor tamper
handling, cursor-path validation, and several decisions that are still open or
absent in `requirements.md` but are locked by the plans.

## Deltas

| ID | Type | Requirement source | Plan source | Delta | Required resolution |
| --- | --- | --- | --- | --- | --- |
| D-001 | Conflict | AC-3.2 requires queries to execute through the existing indexes `idx_audit_events_actor_timestamp` and `idx_audit_events_resource_timestamp`. | `plans/T1.md` adds `idx_audit_events_actor_ts_id` and `idx_audit_events_resource_ts_id`; `plans/T4.md` asserts EXPLAIN against the new indexes; `plans/T7.md` drops the old indexes. | The plans intentionally supersede and remove the indexes named by the requirement. This cannot be treated as satisfying AC-3.2 without changing the requirement. | Either update `requirements.md` to require the new `(filter, event_timestamp DESC, id DESC)` indexes, or revise the plans to use the existing named indexes. |
| D-002 | Missing coverage | AC-3.1 requires p95 <= 300ms at 50M rows; AC-3.2 requires EXPLAIN ANALYZE on a 50M-row dataset. | `plans/T1.md` says a T9 will discharge the 50M-row part, but no `T9.md` exists in `plans/`; `plans/T4.md` uses 1,000-5,000 row EXPLAIN tests. | The final plan folder does not contain a complete performance-verification plan for AC-3.1 or the 50M-row portion of AC-3.2. | Add a final `plans/T9.md` or fold an explicit 50M-row p95 and EXPLAIN gate into an existing plan, including dataset ownership, measurement method, and pass/fail criteria. |
| D-003 | Gap / open question resolved outside requirements | AC-2.5 says malformed, tampered, or non-parseable cursors return 400. Open question 2 asks whether cursors should be signed and/or expire. | `plans/T2.md` states tampering is not signed in v1 and tests only malformed base64, version mismatch, and non-JSON payloads. | A caller can alter a base64 JSON cursor while keeping it parseable and version `1`; the plan does not detect that as tampering. | Sign cursors, for example with HMAC, or update `requirements.md` to redefine "tampered" as only parse/version failure. The safer alignment is to sign. |
| D-004 | Gap | AC-2.6 and AC-2.7 apply `limit` defaulting and bounds to the query API. | `plans/T5.md` skips `AuditEventQuery.validate()` on the cursor branch and makes the explicit cursor-path limit check only "recommended"; `plans/T6.md` delegates validation to the service. | A cursor request with `limit < 1` or `limit > 500` is not guaranteed to return 400. | Make the post-branch `limit` check mandatory in T5 and add tests for cursor requests with `limit=0` and `limit=501`. |
| D-005 | Conflict / gap | AC-1.4 requires at least one actor/resource; AC-1.6 rejects windows wider than 7 days; AC-2.5 rejects tampered cursors. | `plans/T5.md` suggests a test proving a cursor whose embedded filters would fail validation, such as a window over 7 days, still succeeds. | A parseable cursor can bypass first-page validation rules. Combined with unsigned cursors, this allows unbounded or over-wide cursor queries that the requirements reject. | Validate the decoded cursor envelope or sign cursors and only trust system-issued tokens. Remove the proposed "invalid embedded filters still succeed" test unless `requirements.md` explicitly allows it. |
| D-006 | Gap | AC-1.3 says if `from`, `to`, or both are missing, the 400 body identifies the missing parameter(s). | `plans/T3.md` validates `from` first, then `to`, and tests missing `from` and missing `to` separately. | When both are missing, the planned behavior appears to report only `from`, not both missing parameters. | Add a both-missing validation case that identifies both `from` and `to`, or update AC-1.3 to allow first-error-only responses. |
| D-007 | Extra criterion | AC-1.5 only says `from` after `to` returns 400. | `plans/T3.md` enforces `from < to` and adds a test rejecting `from == to`. | The plans reject a zero-length `[from, to)` window, but `requirements.md` does not state that equality is invalid. | Either update AC-1.5 to say `from` is equal to or after `to`, or permit equality and return an empty page. |
| D-008 | Ambiguous / potential gap | AC-1.1 and AC-1.7 require ISO-8601 UTC `from` and `to` instants. | `plans/T6.md` relies on Spring `Instant` binding and does not include an explicit test that non-UTC offsets are rejected. | If the binder accepts offset date-times and normalizes them, non-UTC timestamps may be accepted despite the UTC wording. | Clarify whether "UTC" means a `Z` timestamp only or any ISO-8601 instant normalized to UTC. Add tests for offset timestamps either way. |
| D-009 | Open question resolved outside requirements | Open question 3 says error body format is undecided. | `plans/T3.md` and `plans/T6.md` define stable error codes and the `{ error, message, field? }` response shape. | The plans lock an API contract that `requirements.md` still marks as open. | Move the chosen error shape and error-code taxonomy into `requirements.md`, or keep it out of final plans until the question is closed. |
| D-010 | Extra / inconsistent criterion | Out of scope says there is no backwards-compatibility shim for offset pagination; open question 5 asks how offset removal should work. | `plans/T6.md` recommends silently ignoring `offset` and adds an integration test named `getAuditEvents_offsetParameterIsSilentlyIgnored`, while its DoD says the endpoint has no `offset`. | Silently ignoring `offset` is a behavior decision not stated in `requirements.md`, and it is inconsistent with "no offset" unless explicitly defined as unknown-parameter handling. | Specify in `requirements.md` whether `offset` must return 400 or may be ignored. If no shim is intended, prefer a 400 for supplied `offset`. |
| D-011 | Gap | AC-5.4 requires an ArchUnit assertion confirming the new request/response types live in the correct layer. | `plans/T8.md` adds rules only for `AuditEventCursor`, while relying on existing rules for `AuditEventQuery` and `AuditEventPage`. | The planned new ArchUnit coverage is cursor-targeted and does not explicitly assert all new request/response types. | Add explicit ArchUnit assertions for `AuditEventQuery` and `AuditEventPage`, and any API response DTO placement rule needed to prove AC-5.4. |
| D-012 | Extra criterion | `requirements.md` does not mention actor/resource case sensitivity. | `plans/T6.md` states actor and resource are exact, case-sensitive matches. | This is a user-visible filter semantic not specified by the requirements. | Add case-sensitivity language to AC-1.1/AC-1.2 or remove it from the final plan. |
| D-013 | Extra operational assumption | `requirements.md` does not state that the service is pre-production or that blocking index drops are acceptable. | `plans/T7.md` uses plain `DROP INDEX`, says the service is pre-production, and treats the brief lock as fine. | The plan includes an operational deployment assumption not present in the source requirements. | Add the deployment assumption to `requirements.md` or revise T7 to use production-safe migration language. |
| D-014 | Extra behavior | `requirements.md` does not say whether `limit` may change between cursor pages. | `plans/T5.md` and `plans/T6.md` state that callers may change `limit` between pages. | This is a cursor contract decision not captured in requirements. | Add this behavior to AC-2.3/AC-2.6 or remove it from the plans. |

## Requirements With No Material Delta Found

- AC-1.1, AC-1.2, and AC-1.8 are planned through the repository SQL ordering,
  AND filtering, and `[from, to)` predicate.
- AC-2.1, AC-2.2, AC-2.3, AC-2.4, and AC-2.8 are planned for normal
  system-issued cursors, subject to the tampering and validation gaps above.
- AC-3.3 is planned through read-only query flow and `SELECT`-only repository
  access.
- AC-4.1, AC-4.2, and AC-4.3 are planned through `AuditEventView` mapping and
  selecting only from `audit_events`.
- AC-5.1, AC-5.2, and AC-5.3 are broadly planned through Application DTOs,
  Infrastructure-only JPA access, and no Domain-layer changes, subject to the
  ArchUnit coverage gap in D-011.

## Recommended Next Step

Treat D-001 through D-005 as blocking before implementation starts or before
these plans are accepted as final. They change required behavior or leave
mandatory requirements without an executable plan.
