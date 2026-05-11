# Audit query API — spec evaluation report

**Date:** 2026-05-11
**Scope reviewed:** `.specs/query-api/requirements.md`, `.specs/query-api/design.md`, `.specs/query-api/tasks.md`, `.specs/query-api/plans/T1.md` (sampled)
**Rubric:** `.specs/_eval-checklist.md`

## Scores

| Category | Score | Why |
|---|---|---|
| **Business Context** | **4** | Problem statement names stakeholders (compliance officers, SREs, security analysts) and a concrete driver question ("what did actor X do to resource Y last week"). Measurable goal (p95 ≤ 300ms at 50M rows). Missing: explicit ownership (which team owns the endpoint post-merge — only flagged as open question #7) and broader KPIs beyond latency. |
| **Functional Completeness** | **5** | Five user stories with EARS-style ACs covering main flow, AND-filter semantics, missing/invalid params, window cap, inclusive/exclusive bounds, cursor conflicts, malformed cursors, limit bounds, ordering determinism, immutability. Validation rules tabulated with source ACs. Tiebreaker reasoning and concurrent-ingest semantics are explicit. |
| **Data Definition** | **4** | Response item schema (id, timestamp, actor, action, resource, outcome enum, context) and cursor envelope (`ts`, `id`, `actor`, `resource`, `from`, `to`, `v`) are fully typed. Index columns/orderings specified. Missing: explicit constraint/default/ownership table for `audit_events` columns themselves — relies on the reader knowing V1 schema. |
| **Integration & Interfaces** | **4** | Full request/response contract, example JSON, error body shape with code/field, mutual-exclusion rule, error code per validation rule, drop-`offset` behavior pinned. Authentication explicitly out-of-scope. Missing: no client timeout/retry guidance, no explicit content-type/charset, no rate-limit headers (rate limiting de-scoped but unaddressed for clients). |
| **Non-Functional Requirements** | **3** | Performance is measurable (p95 ≤ 300ms, verification plan in T9 with EXPLAIN ANALYZE + 1k-request p95). Side-effect-freedom asserted. Missing: availability SLO, error-budget, logging/observability targets, security posture beyond "unauthenticated by design", capacity headroom. |
| **User Experience** | **4** | API-only feature, so "UX" = API DX: contract is precise, error shape is consistent, opaque cursor is documented. No UI exists; roles/permissions de-scoped. Reasonable ceiling for this feature type — only docked because client-facing migration guidance (offset→cursor) for downstream consumers isn't drafted. |
| **Testing Readiness** | **5** | Every AC traceable to a test: cursor unit tests, validation rules table → 1:1 unit tests, Testcontainers integration with ≥3 pages + concurrent inserts, ArchUnit rule for cursor location, perf harness for p95. Negative paths enumerated. |
| **Deployment & Operations** | **4** | Two-stage Flyway plan (V3 additive, V4 destructive), per-task rollback table, suggested PR order, branch already cut. T9 produces evidence appended to NOTES.md. Missing: monitoring/alerting changes, dashboard updates, runbook entries for the new error codes. |
| **Governance & Maintainability** | **4** | Eight open questions captured with default positions, assumptions called out (network-level access control, no audit-the-auditors v1), rationale-bearing design (why keyset, why not combined index, why `LIMIT n+1`). Missing: formal approvals/sign-off list, spec version history, ADR-style decision records as separate artifacts. |

## Average

(4 + 5 + 4 + 4 + 3 + 4 + 5 + 4 + 4) / 9 = **37 / 9 ≈ 4.11** — between "Good" and "Excellent", closer to Good.

## Summary

Strongest on **Functional Completeness** and **Testing Readiness**, with clean traceability between requirements → design → tasks → per-task plans. Weakest on **Non-Functional Requirements** (NFR coverage beyond latency is thin) and on operational concerns (monitoring/runbooks).
