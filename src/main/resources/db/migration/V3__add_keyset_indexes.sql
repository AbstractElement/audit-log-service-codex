-- V3: add composite indexes covering keyset pagination on
-- (filter, event_timestamp DESC, id DESC). Including id as the trailing
-- key column lets PG serve the (timestamp, id) tiebreaker compare
-- entirely from the index, avoiding a heap visit per same-millisecond
-- row. See .specs/query-api/design.md -> Indexes for rationale.

CREATE INDEX idx_audit_events_actor_ts_id
  ON audit_events (actor, event_timestamp DESC, id DESC);

CREATE INDEX idx_audit_events_resource_ts_id
  ON audit_events (resource, event_timestamp DESC, id DESC);
