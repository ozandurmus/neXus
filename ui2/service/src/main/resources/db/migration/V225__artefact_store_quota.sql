-- Per-store storage quota for the artefact store (backlog backup_screen_backbox_model, PO directive 2026-09-22).
-- A "store" is the first directory level under the artefact root (the backup vendor, or the writer's own name).
-- NO ROW MEANS NO PER-STORE QUOTA: nothing changes until an administrator sets one. The global byte quota
-- (UI2_ARTEFACT_STORE_QUOTA_BYTES) keeps applying on top of every per-store quota.

CREATE TABLE IF NOT EXISTS artefact_store_quota (
    store_name TEXT PRIMARY KEY CHECK (store_name ~ '^[A-Za-z0-9._-]{1,64}$'),
    quota_bytes BIGINT NOT NULL CHECK (quota_bytes > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by TEXT
);
COMMENT ON TABLE artefact_store_quota IS
    'Optional byte quota per artefact store (first directory level under the artefact root). No row = unlimited (the global quota still applies).';

GRANT SELECT, INSERT, UPDATE, DELETE ON artefact_store_quota TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_artefact_store_quota ON artefact_store_quota;
CREATE TRIGGER trg_audit_artefact_store_quota
    AFTER INSERT OR UPDATE OR DELETE ON artefact_store_quota
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('store_name');
