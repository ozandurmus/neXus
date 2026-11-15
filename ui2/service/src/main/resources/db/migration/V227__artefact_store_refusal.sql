-- Audit trail of artefact-store writes refused by a byte quota (backlog backup_screen_backbox_model, follow-up).
-- A refused write already fails its job or report run with the artefact_store_quota_exceeded reason; this table
-- gives every refusal its own append-only, audited row so the refusal is visible in the audit log even where no
-- job row exists (the pre-check, the report writer). ui2_app cannot insert into audit_log directly, so the
-- row is written here and the audit trigger copies it, exactly like the SSH host-key refusal observation.
-- Only sizes and names: a store name, the writer, an opaque device id and job id; never artefact content.

CREATE TABLE IF NOT EXISTS artefact_store_refusal (
    refusal_id TEXT PRIMARY KEY,
    store_name TEXT,
    scope TEXT NOT NULL CHECK (scope IN ('store', 'global')),
    used_bytes BIGINT NOT NULL CHECK (used_bytes >= 0),
    quota_bytes BIGINT NOT NULL CHECK (quota_bytes > 0),
    attempted_bytes BIGINT NOT NULL CHECK (attempted_bytes >= 0),
    writer TEXT NOT NULL,
    device_id TEXT,
    job_id TEXT,
    refused_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE artefact_store_refusal IS
    'One row per artefact-store write refused by the global or a per-store byte quota. Append-only; audited by trigger.';

GRANT SELECT, INSERT ON artefact_store_refusal TO ui2_app;

DROP TRIGGER IF EXISTS trg_audit_artefact_store_refusal ON artefact_store_refusal;
CREATE TRIGGER trg_audit_artefact_store_refusal
    AFTER INSERT ON artefact_store_refusal
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('refusal_id');
