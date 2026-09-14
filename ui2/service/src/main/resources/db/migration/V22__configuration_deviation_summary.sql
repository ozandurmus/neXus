-- V22 -- structural deviation summary for changed configuration runs (14I DV-2)
-- Implements AC-1..AC-6 of NXS-LOCAL-0190. Two tables: one summary row per
-- changed run (status = COMPUTED or NOT_COMPUTABLE), and one entry row per
-- section that gained, lost, or altered its entry count.
--
-- Invariant: section names and counts only -- no configuration value is stored
-- here or in any column this migration creates (AGENTS.md raw-evidence law,
-- DV-2 AC-6). The source column from device_configuration_index intentionally
-- does not appear here: grouping is by (context, section), and individual
-- source-level rows are summed into old_count/new_count before persistence.

SELECT set_config('app.actor_fingerprint', 'migration:V22_deviation_summary', true);
SELECT set_config('app.action_id', 'deviation_summary_creation_by_migration', true);

-- ---------------------------------------------------------------------
-- 1. configuration_run_deviation_summary -- one row per changed run
--    that has a computable or not-computable deviation summary.
-- ---------------------------------------------------------------------

CREATE TABLE configuration_run_deviation_summary (
    summary_id  TEXT        PRIMARY KEY,
    run_id       TEXT        NOT NULL REFERENCES device_configuration_run(run_id),
    status        TEXT        NOT NULL
        CHECK (status IN ('COMPUTED', 'NOT_COMPUTABLE')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_deviation_summary_run_id ON configuration_run_deviation_summary(run_id);

-- ---------------------------------------------------------------------
-- 2. configuration_run_deviation_entry -- one row per section that
--    gained, lost, or altered entries. Section name and counts only.
-- ---------------------------------------------------------------------

CREATE TABLE configuration_run_deviation_entry (
    entry_id    TEXT        PRIMARY KEY,
    summary_id   TEXT        NOT NULL REFERENCES configuration_run_deviation_summary(summary_id),
    context       TEXT        NOT NULL,
    section        TEXT        NOT NULL,
    kind            TEXT        NOT NULL
        CHECK (kind IN ('ADDED', 'REMOVED', 'RECOUNTED')),
    old_count        INTEGER     NOT NULL,
    new_count         INTEGER     NOT NULL
);

CREATE INDEX idx_deviation_entry_summary_id ON configuration_run_deviation_entry(summary_id);

-- ---------------------------------------------------------------------
-- 3. Audit triggers (C1 section 3.5).
-- ---------------------------------------------------------------------

CREATE TRIGGER trg_audit_configuration_run_deviation_summary
    AFTER INSERT OR UPDATE OR DELETE ON configuration_run_deviation_summary
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('summary_id');

CREATE TRIGGER trg_audit_configuration_run_deviation_entry
    AFTER INSERT OR UPDATE OR DELETE ON configuration_run_deviation_entry
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('entry_id');

-- ---------------------------------------------------------------------
-- 4. Grants.
-- ---------------------------------------------------------------------

GRANT SELECT, INSERT, UPDATE, DELETE
  ON configuration_run_deviation_summary, configuration_run_deviation_entry
  TO ui2_app;
