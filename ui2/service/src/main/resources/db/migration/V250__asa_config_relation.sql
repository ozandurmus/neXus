-- V250 -- the derived running-vs-startup relation of each Cisco ASA backup (docs/design/
-- OPERATIONS_POLICY_INSTALL_P1_MONITORING_CONTRACT_2026_11_18.md section 3.3, amendment 2026-11-18).
--
-- The backup holds two reads of different form (more system:running-config with keys in clear, show startup-config
-- with keys masked and its own header lines), so their digests never match. While both texts are in memory the worker
-- normalises and compares them and stores only this result: a verdict and three counts. No configuration text, no
-- line, no key is stored here. One row per backup artefact (the artefact id is the backup_artefact key; there is no
-- foreign key because the row is written before the manifest row).
-- No audit trigger: derived, never operator-written; the backup job row is the audited record.
-- Dry-run in BEGIN/ROLLBACK on the live database before rollout.
CREATE TABLE asa_config_relation (
    artefact_id          TEXT        PRIMARY KEY,
    device_id            TEXT        NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    relation             TEXT        NOT NULL CHECK (relation IN ('MATCH', 'MISMATCH', 'NOT_EVALUABLE')),
    lines_compared       INTEGER     NOT NULL CHECK (lines_compared >= 0),
    lines_differing      INTEGER     NOT NULL CHECK (lines_differing >= 0),
    lines_equal_by_mask  INTEGER     NOT NULL CHECK (lines_equal_by_mask >= 0),
    derived_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asa_config_relation_device ON asa_config_relation (device_id, derived_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON asa_config_relation TO ui2_app;

-- Module roles (V246 style): the writer is the backup worker; the reader is the service's policy installation view.
-- Roles are created outside the migrations; a role that does not exist is skipped.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE'),
        ('ui2_backup', 'SELECT, INSERT, UPDATE'),
        ('ui2_worker_general', 'SELECT, INSERT, UPDATE')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.asa_config_relation TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
