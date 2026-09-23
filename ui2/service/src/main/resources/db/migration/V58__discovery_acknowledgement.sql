-- V58 -- "this is not an issue, do not flag it here" for a device a management server lists but neXus does not enrol
-- (PO, 2026-09-23). Keyed by the discovery match key (the same opaque key an import writes to devices), so the
-- decision survives later discovery runs. Who decided and why is kept; the row is audited like every other write.
CREATE TABLE discovery_acknowledgement (
    discovery_match_key               TEXT        PRIMARY KEY,
    acknowledged_by_actor_fingerprint TEXT        NOT NULL,
    reason                            TEXT        NOT NULL CHECK (length(reason) BETWEEN 3 AND 300),
    acknowledged_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_audit_discovery_acknowledgement
    AFTER INSERT OR UPDATE OR DELETE ON discovery_acknowledgement
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('discovery_match_key');

GRANT SELECT, INSERT, UPDATE, DELETE ON discovery_acknowledgement TO ui2_app;
