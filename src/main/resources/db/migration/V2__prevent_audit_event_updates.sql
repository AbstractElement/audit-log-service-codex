CREATE FUNCTION reject_audit_events_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events records are immutable and cannot be updated';
END;
$$;

CREATE TRIGGER trg_reject_audit_events_update
BEFORE UPDATE ON audit_events
FOR EACH ROW
EXECUTE FUNCTION reject_audit_events_update();
