-- V70 -- delete a backup whose device no longer exists (PO, 2026-09-24: "askıda kaldı bunu sil ... oraya bir ekran
-- koyalım"). The service only records the request -- it never mounts the artefact store (BK-17); the worker, which owns
-- the store, removes the encrypted file and the manifest rows, audited under the requester's fingerprint. Only an
-- orphan (no devices row) can be requested; who asked and why is kept.
SELECT set_config('app.actor_fingerprint', 'migration:V70_orphan_backup_deletion_request', true);
SELECT set_config('app.action_id', 'orphan_backup_deletion_request_by_migration', true);

CREATE TABLE backup_artefact_deletion_request (
    artefact_id                    TEXT        PRIMARY KEY,
    requested_by_actor_fingerprint TEXT        NOT NULL,
    reason                         TEXT        NOT NULL CHECK (length(reason) BETWEEN 8 AND 300),
    requested_at                   TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at                   TIMESTAMPTZ,
    outcome                        TEXT        CHECK (outcome IN ('removed', 'artefact_missing', 'device_exists', 'file_remove_failed'))
);

CREATE TRIGGER trg_audit_backup_artefact_deletion_request
    AFTER INSERT OR UPDATE OR DELETE ON backup_artefact_deletion_request
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('artefact_id');

GRANT SELECT, INSERT, UPDATE, DELETE ON backup_artefact_deletion_request TO ui2_app;
