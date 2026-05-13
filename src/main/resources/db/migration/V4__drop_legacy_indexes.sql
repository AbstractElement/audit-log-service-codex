-- V4: drop the legacy two-column indexes superseded by V3's
-- (filter, event_timestamp DESC, id DESC) composites. Keeping both
-- doubles write amplification on every ingest. Plain DROP INDEX
-- (no CONCURRENTLY) -- pre-production; see design.md -> Indexes.

DROP INDEX IF EXISTS idx_audit_events_actor_timestamp;
DROP INDEX IF EXISTS idx_audit_events_resource_timestamp;
