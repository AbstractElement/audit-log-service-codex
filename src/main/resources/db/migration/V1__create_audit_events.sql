CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_timestamp TIMESTAMPTZ NOT NULL,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    resource TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'ERROR')),
    context JSONB NOT NULL
);

CREATE INDEX idx_audit_events_actor_timestamp ON audit_events (actor, event_timestamp DESC);
CREATE INDEX idx_audit_events_resource_timestamp ON audit_events (resource, event_timestamp DESC);
CREATE INDEX idx_audit_events_timestamp ON audit_events (event_timestamp DESC);

CREATE TABLE audit_events_archive (
    id UUID PRIMARY KEY,
    event_timestamp TIMESTAMPTZ NOT NULL,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    resource TEXT NOT NULL,
    outcome TEXT NOT NULL CHECK (outcome IN ('SUCCESS', 'DENIED', 'ERROR')),
    context JSONB NOT NULL,
    archived_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
