-- V45 -- device-side archive ledger for Check Point backups (backlog
-- cp_backup_stale_device_archives_cleanup, Product Owner P1 2026-09-22).
--
-- Measured live 2026-09-22: three requeued runs each ran `add backup local`
-- on the gateway and never reached `delete backup`, leaving ~150 MB archives
-- under /var/log/CPbackup/backups each time. The executor now records every
-- archive name a run learns (from the submit or from `show backup status`)
-- before it does anything else with it, marks it deleted when its own
-- `delete backup` succeeds, and at the start of the next run lists the
-- device's backups (`show backups`, gate cp_backup_show_backups) and deletes
-- exactly the names this ledger still holds open -- never a pattern, never
-- an archive the product did not create (an administrator's own backup keeps
-- the same naming scheme and is left alone).
--
-- No audit trigger: this is the product's own bookkeeping about device-side
-- files it created, not an operator act; the run's job row is the audited record.
CREATE TABLE cp_device_archive (
    device_id     TEXT        NOT NULL REFERENCES devices(device_id),
    archive_name  TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at    TIMESTAMPTZ,
    PRIMARY KEY (device_id, archive_name)
);

CREATE INDEX idx_cp_device_archive_open ON cp_device_archive(device_id) WHERE deleted_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON cp_device_archive TO ui2_app;
