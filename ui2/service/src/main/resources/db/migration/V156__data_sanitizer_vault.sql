-- V156 -- Data Sanitizer: jobs and the reversible mapping vault (docs/design/DATA_SANITIZER_CONTRACT.md §2
-- "Reversible on request", §3.1). The sanitized and uploaded files themselves never enter the database; these
-- tables hold job state, counts per kind, and the vault: token -> original, where the token is stored only as an
-- HMAC lookup key and the original only sealed (AES-256-GCM) under a per-job data key that is itself wrapped by the
-- artefact-store envelope key. Nothing in any row (or in audit_log) is a token or an original in clear.
--
-- Additive only: four new tables, their audit triggers and redaction classification, one ledger. No existing
-- table, constraint, module row or capability mapping changes.
SELECT set_config('app.actor_fingerprint', 'system:migration', true);
SELECT set_config('app.action_id', 'data_sanitizer_vault', true);

-- 1. Sanitize jobs. Counts per kind only (never a value); the vault's data key is wrapped, and is cleared (crypto-
-- shredded) when the vault expires -- 90 days by default, configurable (ui2.sanitizer.vault-retention-days).
CREATE TABLE sanitize_job (
    job_id            TEXT        PRIMARY KEY
                                  CHECK (job_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    state             TEXT        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    file_type         TEXT        CHECK (file_type IS NULL OR file_type IN ('pcap', 'pcapng', 'xlsx', 'json', 'csv', 'text')),
    input_bytes       BIGINT      CHECK (input_bytes IS NULL OR input_bytes >= 0),
    output_bytes      BIGINT      CHECK (output_bytes IS NULL OR output_bytes >= 0),
    counts            JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(counts) = 'object'),
    failure_reason    TEXT        CHECK (failure_reason IS NULL OR failure_reason ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    finished_at       TIMESTAMPTZ,
    -- The sanitized file is a temporary file of the service: READY until downloaded once or until it expires.
    output_state      TEXT        NOT NULL DEFAULT 'PENDING'
                                  CHECK (output_state IN ('PENDING', 'READY', 'DOWNLOADED', 'EXPIRED', 'NONE')),
    vault_entries     BIGINT      NOT NULL DEFAULT 0 CHECK (vault_entries >= 0),
    vault_expires_at  TIMESTAMPTZ NOT NULL,
    vault_pruned_at   TIMESTAMPTZ,
    wrapped_data_key  BYTEA       CHECK (wrapped_data_key IS NULL OR octet_length(wrapped_data_key) BETWEEN 28 AND 256),
    key_id            TEXT        CHECK (key_id IS NULL OR key_id ~ '^[A-Za-z0-9._-]{1,40}$'),
    CONSTRAINT chk_sanitize_job_vault_expiry CHECK (vault_expires_at > created_at),
    CONSTRAINT chk_sanitize_job_key_until_pruned CHECK (vault_pruned_at IS NOT NULL OR (wrapped_data_key IS NOT NULL
                                                                                          AND key_id IS NOT NULL))
);
CREATE INDEX idx_sanitize_job_creator ON sanitize_job(created_by, created_at DESC);
CREATE INDEX idx_sanitize_job_vault_expiry ON sanitize_job(vault_expires_at) WHERE vault_pruned_at IS NULL;

-- 2. The vault. lookup_key = HMAC-SHA256(vault sub-key of the privacy key, kind || 0x00 || canonical token);
-- sealed_original = nonce(12) || AES-256-GCM(job data key, original; AAD = job_id || lookup_key) || tag(16).
-- entry_id is a surrogate key: lookup_key is redacted, and a redacted column is never a primary key (row_pk guard).
CREATE TABLE sanitize_vault_entry (
    entry_id          BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id            TEXT        NOT NULL REFERENCES sanitize_job(job_id) ON DELETE CASCADE,
    lookup_key        BYTEA       NOT NULL CHECK (octet_length(lookup_key) = 32),
    entry_kind        TEXT        NOT NULL CHECK (entry_kind IN ('address', 'network', 'mac', 'label', 'device', 'dns_name')),
    sealed_original   BYTEA       NOT NULL CHECK (octet_length(sealed_original) BETWEEN 29 AND 4124),
    UNIQUE (job_id, lookup_key)
);
CREATE INDEX idx_sanitize_vault_entry_lookup ON sanitize_vault_entry(lookup_key);

-- 3. Revert jobs: a file that came back, reverted against the vault of the actor's own sanitize jobs (or of every
-- job, for security_admin; or of one named job).
CREATE TABLE sanitize_revert_job (
    revert_id         TEXT        PRIMARY KEY
                                  CHECK (revert_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    created_by        TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    scope             TEXT        NOT NULL CHECK (scope IN ('OWN_JOBS', 'ALL_JOBS', 'ONE_JOB')),
    source_job_id     TEXT        REFERENCES sanitize_job(job_id) ON DELETE SET NULL,
    state             TEXT        NOT NULL CHECK (state IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    file_type         TEXT        CHECK (file_type IS NULL OR file_type IN ('pcap', 'pcapng', 'xlsx', 'json', 'csv', 'text')),
    input_bytes       BIGINT      CHECK (input_bytes IS NULL OR input_bytes >= 0),
    output_bytes      BIGINT      CHECK (output_bytes IS NULL OR output_bytes >= 0),
    counts            JSONB       NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(counts) = 'object'),
    failure_reason    TEXT        CHECK (failure_reason IS NULL OR failure_reason ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    finished_at       TIMESTAMPTZ,
    output_state      TEXT        NOT NULL DEFAULT 'PENDING'
                                  CHECK (output_state IN ('PENDING', 'READY', 'DOWNLOADED', 'EXPIRED', 'NONE')),
    CONSTRAINT chk_sanitize_revert_scope CHECK (scope = 'ONE_JOB' OR source_job_id IS NULL)
);
CREATE INDEX idx_sanitize_revert_job_creator ON sanitize_revert_job(created_by, created_at DESC);

-- 4. Append-only ledger of every sanitize, revert, download and vault prune, keyed by opaque ids and counts only
-- (like artefact_retention_ledger), so it outlives the vault it describes.
CREATE TABLE sanitize_ledger (
    ledger_id          TEXT        PRIMARY KEY,
    job_id             TEXT,
    revert_id          TEXT,
    event              TEXT        NOT NULL CHECK (event IN ('SANITIZED', 'SANITIZE_FAILED', 'OUTPUT_DOWNLOADED',
                           'REVERTED', 'REVERT_FAILED', 'REVERT_DOWNLOADED', 'OUTPUT_EXPIRED', 'VAULT_PRUNED')),
    actor_fingerprint  TEXT        NOT NULL,
    entries            BIGINT      CHECK (entries IS NULL OR entries >= 0),
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sanitize_ledger_job ON sanitize_ledger(job_id, occurred_at);

-- 5. Audit (C1 §3.5): every mutation-bearing table carries trg_audit_<table>. The job tables and the ledger use the
-- generic row capture (counts, states and ids only; the wrapped key is redacted below).
CREATE TRIGGER trg_audit_sanitize_job
    AFTER INSERT OR UPDATE OR DELETE ON sanitize_job
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('job_id');
CREATE TRIGGER trg_audit_sanitize_revert_job
    AFTER INSERT OR UPDATE OR DELETE ON sanitize_revert_job
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('revert_id');
CREATE TRIGGER trg_audit_sanitize_ledger
    AFTER INSERT OR UPDATE OR DELETE ON sanitize_ledger
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('ledger_id');

-- The vault holds up to millions of rows per job: one audit row per statement and job, with the entry count, instead
-- of one per entry. Entries are immutable; the audit context is required exactly as fn_audit_capture requires it.
-- PostgreSQL allows transition tables on single-event triggers only, so INSERT (trg_audit_sanitize_vault_entry, the
-- name the coverage test requires), DELETE and the UPDATE refusal are three triggers over one function.
CREATE OR REPLACE FUNCTION fn_audit_sanitize_vault_entry() RETURNS TRIGGER
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public, pg_temp AS $$
DECLARE
    v_actor  TEXT := current_setting('app.actor_fingerprint', true);
    v_action TEXT := current_setting('app.action_id', true);
    r        RECORD;
BEGIN
    IF v_actor IS NULL OR v_actor = '' OR v_action IS NULL OR v_action = '' THEN
        RAISE EXCEPTION 'audit_context_missing on sanitize_vault_entry: actor or action not set in this transaction';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'sanitize_vault_entry_immutable';
    END IF;
    IF TG_OP = 'INSERT' THEN
        FOR r IN SELECT job_id, count(*) AS n FROM vault_new GROUP BY job_id LOOP
            INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state,
                                  correlation_run_id)
            VALUES ('sanitize_vault_entry', r.job_id, 'INSERT', v_actor, v_action, NULL,
                    jsonb_build_object('job_id', r.job_id, 'entries', r.n), current_setting('app.correlation_run_id', true));
        END LOOP;
    ELSE
        FOR r IN SELECT job_id, count(*) AS n FROM vault_old GROUP BY job_id LOOP
            INSERT INTO audit_log(table_name, row_pk, operation, actor_fingerprint, action_id, before_state, after_state,
                                  correlation_run_id)
            VALUES ('sanitize_vault_entry', r.job_id, 'DELETE', v_actor, v_action,
                    jsonb_build_object('job_id', r.job_id, 'entries', r.n), NULL, current_setting('app.correlation_run_id', true));
        END LOOP;
    END IF;
    RETURN NULL;
END;
$$;
REVOKE ALL ON FUNCTION fn_audit_sanitize_vault_entry() FROM PUBLIC;
CREATE TRIGGER trg_audit_sanitize_vault_entry
    AFTER INSERT ON sanitize_vault_entry
    REFERENCING NEW TABLE AS vault_new
    FOR EACH STATEMENT EXECUTE FUNCTION fn_audit_sanitize_vault_entry();
CREATE TRIGGER trg_audit_sanitize_vault_entry_delete
    AFTER DELETE ON sanitize_vault_entry
    REFERENCING OLD TABLE AS vault_old
    FOR EACH STATEMENT EXECUTE FUNCTION fn_audit_sanitize_vault_entry();
CREATE TRIGGER trg_audit_sanitize_vault_entry_update
    BEFORE UPDATE ON sanitize_vault_entry
    FOR EACH STATEMENT EXECUTE FUNCTION fn_audit_sanitize_vault_entry();

-- 6. Redaction classification (V5 contract): cryptographic material and the vault's two columns never reach
-- audit_log in clear. (The vault's statement trigger never copies them at all; the entries classify them anyway.)
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('sanitize_job', 'wrapped_data_key', 1, 'wrapped per-job vault data key -- cryptographic material outside its key lifecycle'),
    ('sanitize_vault_entry', 'lookup_key', 1, 'keyed HMAC of a sanitizer token; links a token to its vault row'),
    ('sanitize_vault_entry', 'sealed_original', 1, 'AES-GCM sealed original value of a sanitized token')
ON CONFLICT (table_name, column_name) DO NOTHING;

-- 7. Grants: jobs and vault entries are written by the service; the ledger is append-only for ui2_app.
GRANT SELECT, INSERT, UPDATE, DELETE ON sanitize_job TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON sanitize_revert_job TO ui2_app;
GRANT SELECT, INSERT, DELETE ON sanitize_vault_entry TO ui2_app;
REVOKE ALL ON sanitize_ledger FROM ui2_app;
GRANT SELECT, INSERT ON sanitize_ledger TO ui2_app;
