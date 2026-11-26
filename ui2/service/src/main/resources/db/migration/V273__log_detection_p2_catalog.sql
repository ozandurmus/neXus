-- V273 -- system-log detection engine, phase P2 (layer B: template catalog, rarity and rate), behind its own switch, OFF.
-- Authority: docs/design/LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md (FROZEN 2026-11-24) sections 4 (layer B), 6.3
-- (determinism), 6.5 (catalog freeze and diff), 7.1 (a statistical finding is never CRITICAL), 8.3 (what is stored) and
-- 10 (P2 row); PO_DECISION_RECORD_2026_11_24_LOG_DETECTION_ENGINE.md. Additive only: one new settings column, six new
-- tables, new nullable columns on log_finding and three widened CHECK constraints there (every V272 row stays valid).
-- No table holds a log line, masked or not. The only text of a template is its closed-vocabulary form (every word
-- outside the technical vocabulary is <W>) and its match form (the same, with keyed one-way word tokens k- + 12 hex
-- instead of <W>), both constrained to a closed character set.
--   1. log_detection_settings.p2_enabled   the P2 switch, beside the P1 switch, off by default; same row, same audit.
--   2. log_template_catalog                one row per catalog version: manifest, digest, DRAFT -> FROZEN, diff.
--   3. log_template                        the templates of a catalog version (section 4.2 class and fleet columns).
--   4. log_template_device_window          per catalog version: a device's usable training days for one source.
--   5. log_template_device_stat            per catalog version: a device's baseline of one template (section 4.2).
--   6. log_template_index_state            what the P2 indexer did with one chunk under one catalog version.
--   7. log_template_count                  P2 evidence rows: (device, source, template, hour bucket, count, <= 3 pointers).
--   8. log_provisional_template            closed-vocabulary form of a provisional template id (section 4.1 step 6).
--   9. log_finding                         phase tag and layer-B columns (score S x R x C, z, baseline, kinds).
-- Audit (like V160/V272): the settings row is already audited (trg_audit_log_detection_settings); the catalog row is
-- operator-frozen and carries trg_audit_log_template_catalog. A FROZEN catalog row is immutable
-- (trg_log_template_catalog_frozen_guard) and so are its templates (trg_log_template_frozen_guard). The other new tables
-- are derived by the service as a system job and listed in AuditCoverageCompletenessTest.NOT_MUTATION_BEARING.
-- Every statement is repeatable (IF NOT EXISTS, ON CONFLICT DO NOTHING, drop-then-create).
SELECT set_config('app.actor_fingerprint', 'migration:V273_log_detection_p2_catalog', true);
SELECT set_config('app.action_id', 'log_detection_p2_by_migration', true);

-- 1. The P2 switch (design section 10: "each phase is behind its own setting"; rollback = switch off, rows stay).
ALTER TABLE log_detection_settings ADD COLUMN IF NOT EXISTS p2_enabled BOOLEAN NOT NULL DEFAULT false;
COMMENT ON COLUMN log_detection_settings.p2_enabled IS
    'Log detection engine P2 switch (layer B: catalog matching, rarity, rate). false (default) means nothing of P2 runs; it runs only together with enabled (P1).';

-- 2. Catalog versions (design section 6.5).
CREATE TABLE IF NOT EXISTS log_template_catalog (
    catalog_version TEXT PRIMARY KEY CHECK (catalog_version ~ '^cat-[0-9]{1,6}$'),
    state TEXT NOT NULL CHECK (state IN ('DRAFT', 'FROZEN')),
    previous_version TEXT CHECK (previous_version IS NULL OR previous_version ~ '^cat-[0-9]{1,6}$'),
    manifest JSONB NOT NULL CHECK (jsonb_typeof(manifest) = 'object'),
    digest TEXT NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    template_count INTEGER NOT NULL CHECK (template_count >= 0),
    diff JSONB NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(diff) = 'object'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by TEXT NOT NULL,
    frozen_at TIMESTAMPTZ,
    frozen_by TEXT,
    CONSTRAINT log_template_catalog_frozen CHECK ((state = 'FROZEN') = (frozen_at IS NOT NULL AND frozen_by IS NOT NULL))
);
COMMENT ON TABLE log_template_catalog IS
    'Versions of the log template catalog: manifest (normaliser, masking rule set, Drain parameters, training window, sources, marker list), digest over the sorted (template id, class) list, DRAFT -> FROZEN. Only FROZEN versions are matched online; a FROZEN version is never edited.';
COMMENT ON COLUMN log_template_catalog.manifest IS 'Closed keys and values written by the service (versions, parameters, dates, counts); no log text.';
COMMENT ON COLUMN log_template_catalog.diff IS 'The diff against previous_version (added, removed, merged, split, class changes, id mapping), template ids and counts only.';

-- 3. Templates of a catalog version (design section 4.2; text in vocabulary form only, PO decision question 6).
CREATE TABLE IF NOT EXISTS log_template (
    catalog_version TEXT NOT NULL REFERENCES log_template_catalog(catalog_version),
    template_id TEXT NOT NULL CHECK (template_id ~ '^t[0-9a-f]{31}$'),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9_]{1,39}$'),
    template_text TEXT NOT NULL CHECK (char_length(template_text) BETWEEN 1 AND 4000
        AND template_text ~ '^[][A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]+$'),
    match_form TEXT NOT NULL CHECK (char_length(match_form) BETWEEN 1 AND 4000
        AND match_form ~ '^[][A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]+$'),
    token_count INTEGER NOT NULL CHECK (token_count BETWEEN 1 AND 64),
    marker_id TEXT CHECK (marker_id IS NULL OR marker_id ~ '^[a-z][a-z0-9_.]{1,47}$'),
    severity_weight NUMERIC(4, 2) NOT NULL CHECK (severity_weight >= 0.05 AND severity_weight <= 1),
    template_class TEXT NOT NULL CHECK (template_class IN ('MARKER', 'VENDOR_SEVERITY', 'UNCLASSED')),
    provenance TEXT NOT NULL CHECK (provenance IN ('PROVISIONAL', 'MEASURED', 'VENDOR_DOC')),
    line_count BIGINT NOT NULL CHECK (line_count >= 0),
    prevalence_devices INTEGER NOT NULL CHECK (prevalence_devices >= 0),
    cohort_devices INTEGER NOT NULL CHECK (cohort_devices >= 0),
    fleet_first_seen DATE,
    PRIMARY KEY (catalog_version, template_id)
);
CREATE INDEX IF NOT EXISTS log_template_source ON log_template(catalog_version, source_key);
COMMENT ON TABLE log_template IS
    'Templates of one catalog version: id (keyed HMAC of source, normaliser version and normalised tokens), vocabulary-form text, class, S, fleet prevalence n of N in the training window.';
COMMENT ON COLUMN log_template.match_form IS
    'The template tokens for deterministic online matching: placeholders, vocabulary words and keyed one-way word tokens (k- + 12 hex); never a word outside the vocabulary.';

-- 4. A device's usable training days per source (the denominator of its baselines).
CREATE TABLE IF NOT EXISTS log_template_device_window (
    catalog_version TEXT NOT NULL REFERENCES log_template_catalog(catalog_version),
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9_]{1,39}$'),
    usable_days INTEGER NOT NULL CHECK (usable_days >= 0),
    first_day DATE NOT NULL,
    last_day DATE NOT NULL,
    PRIMARY KEY (catalog_version, device_id, source_key),
    CONSTRAINT log_template_device_window_days CHECK (last_day >= first_day)
);

-- 5. Per-device baseline of one template in the training window (design section 4.2; segment ALL until 6.4 lands).
CREATE TABLE IF NOT EXISTS log_template_device_stat (
    catalog_version TEXT NOT NULL REFERENCES log_template_catalog(catalog_version),
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9_]{1,39}$'),
    template_id TEXT NOT NULL CHECK (template_id ~ '^t[0-9a-f]{31}$'),
    segment TEXT NOT NULL DEFAULT 'ALL' CHECK (segment ~ '^[A-Z][A-Z0-9_:-]{0,63}$'),
    first_seen DATE NOT NULL,
    last_seen DATE NOT NULL,
    line_count BIGINT NOT NULL CHECK (line_count > 0),
    presence_days INTEGER NOT NULL CHECK (presence_days >= 0),
    usable_days INTEGER NOT NULL CHECK (usable_days >= 0),
    presence_share NUMERIC(5, 4) NOT NULL CHECK (presence_share >= 0 AND presence_share <= 1),
    periodicity NUMERIC(5, 4) NOT NULL CHECK (periodicity >= 0 AND periodicity <= 1),
    baseline_median NUMERIC(12, 2) NOT NULL CHECK (baseline_median >= 0),
    baseline_mad NUMERIC(12, 2) NOT NULL CHECK (baseline_mad >= 0),
    buckets_used INTEGER NOT NULL CHECK (buckets_used >= 0),
    PRIMARY KEY (catalog_version, device_id, template_id, segment),
    CONSTRAINT log_template_device_stat_days CHECK (last_seen >= first_seen)
);
CREATE INDEX IF NOT EXISTS log_template_device_stat_device ON log_template_device_stat(catalog_version, device_id, source_key);

-- 6. P2 index state: idempotent per (chunk, catalog version, masking rule set, normaliser version) (design section 6.3).
CREATE TABLE IF NOT EXISTS log_template_index_state (
    chunk_id TEXT NOT NULL,
    catalog_version TEXT NOT NULL CHECK (catalog_version ~ '^cat-[0-9]{1,6}$'),
    masking_rule_set TEXT NOT NULL CHECK (masking_rule_set ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    normaliser_version TEXT NOT NULL CHECK (normaliser_version ~ '^[a-z0-9][a-z0-9._-]{0,31}$'),
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    job_id TEXT NOT NULL,
    log_day DATE NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('INDEXED', 'MASKING_UNAVAILABLE', 'UNREADABLE', 'PRUNED', 'LINE_TOO_LONG',
        'LINE_COUNT_MISMATCH', 'STORE_UNAVAILABLE', 'NOT_APPLICABLE', 'UNSUPPORTED')),
    lines BIGINT NOT NULL DEFAULT 0 CHECK (lines >= 0),
    matched BIGINT NOT NULL DEFAULT 0 CHECK (matched >= 0),
    provisional BIGINT NOT NULL DEFAULT 0 CHECK (provisional >= 0),
    unparsed BIGINT NOT NULL DEFAULT 0 CHECK (unparsed >= 0),
    indexed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (chunk_id, catalog_version, masking_rule_set, normaliser_version)
);
CREATE INDEX IF NOT EXISTS log_template_index_state_device_day ON log_template_index_state(device_id, source_key, log_day);
COMMENT ON TABLE log_template_index_state IS 'What the P2 indexer did with one stored chunk under one frozen catalog; counters only, never a line.';

-- 7. P2 evidence rows (design section 8.3 log_event_count, kept apart from the P1 marker rows so neither phase reads the other's).
CREATE TABLE IF NOT EXISTS log_template_count (
    count_id TEXT PRIMARY KEY CHECK (count_id ~ '^[0-9a-f]{32}$'),
    chunk_id TEXT NOT NULL,
    catalog_version TEXT NOT NULL CHECK (catalog_version ~ '^cat-[0-9]{1,6}$'),
    device_id TEXT NOT NULL,
    source_key TEXT NOT NULL,
    template_id TEXT NOT NULL CHECK (template_id ~ '^[tp][0-9a-f]{31}$'),
    provisional BOOLEAN NOT NULL,
    bucket_start TIMESTAMP NOT NULL,
    time_basis TEXT NOT NULL DEFAULT 'DEVICE_LOCAL' CHECK (time_basis = 'DEVICE_LOCAL'),
    severity_weight NUMERIC(4, 2) NOT NULL CHECK (severity_weight >= 0.05 AND severity_weight <= 1),
    marker_id TEXT CHECK (marker_id IS NULL OR marker_id ~ '^[a-z][a-z0-9_.]{1,47}$'),
    line_count INTEGER NOT NULL CHECK (line_count > 0),
    first_line_time TIMESTAMP NOT NULL,
    last_line_time TIMESTAMP NOT NULL,
    pointers JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(pointers) = 'array' AND jsonb_array_length(pointers) <= 3),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT log_template_count_unique UNIQUE (chunk_id, catalog_version, template_id, bucket_start),
    CONSTRAINT log_template_count_times CHECK (last_line_time >= first_line_time),
    CONSTRAINT log_template_count_provisional CHECK (provisional = (template_id LIKE 'p%'))
);
CREATE INDEX IF NOT EXISTS log_template_count_device_day ON log_template_count(device_id, source_key, bucket_start);
CREATE INDEX IF NOT EXISTS log_template_count_template_day ON log_template_count(source_key, template_id, bucket_start);
COMMENT ON TABLE log_template_count IS
    'P2 evidence rows: a counted occurrence of a catalog or provisional template in a device-local hour, with up to three pointers. Not a finding by itself.';

-- 8. Provisional templates: a line that matched no frozen template (design section 4.1 step 6).
CREATE TABLE IF NOT EXISTS log_provisional_template (
    template_id TEXT PRIMARY KEY CHECK (template_id ~ '^p[0-9a-f]{31}$'),
    source_key TEXT NOT NULL CHECK (source_key ~ '^[a-z][a-z0-9_]{1,39}$'),
    template_text TEXT NOT NULL CHECK (char_length(template_text) BETWEEN 1 AND 4000
        AND template_text ~ '^[][A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]+$'),
    first_seen_day DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
COMMENT ON TABLE log_provisional_template IS 'Vocabulary form of provisional template ids; folded into a catalog template at the next build.';

-- 9. log_finding: phase tag and the layer-B part of the finding record (design section 7.2).
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS phase TEXT NOT NULL DEFAULT 'P1';
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS template_id TEXT;
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS template_text TEXT;
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS catalog_version TEXT;
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS normaliser_version TEXT;
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS kinds TEXT[] NOT NULL DEFAULT '{}'::text[];
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS rarity NUMERIC(5, 4);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS change_factor NUMERIC(5, 4);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS score NUMERIC(6, 2);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS z_score NUMERIC(12, 2);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS baseline_median NUMERIC(12, 2);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS baseline_mad NUMERIC(12, 2);
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS device_first_seen DATE;
ALTER TABLE log_finding ADD COLUMN IF NOT EXISTS fleet_first_seen DATE;

-- Widened and new CHECKs on log_finding (the V266/V270 pattern, MigrationAdditiveFenceTest): the V272 checks are
-- replaced by supersets inside one DO block, added NOT VALID and validated below. Every V272 row satisfies them, and an
-- old (P1-only) image writes only rows they accept, so a rollback by image digest keeps working.
DO $widen$
DECLARE
    v_name TEXT;
BEGIN
    FOREACH v_name IN ARRAY ARRAY['log_finding_phase_check', 'log_finding_layer_check', 'log_finding_kind_check', 'log_finding_labels_check', 'log_finding_kinds_check', 'log_finding_event_count_check', 'log_finding_peak_bucket_count_check', 'log_finding_layer_b_never_critical', 'log_finding_phase_layer', 'log_finding_layer_b_columns'] LOOP
        IF EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'public.log_finding'::regclass AND conname = v_name) THEN
            EXECUTE format('ALTER TABLE log_finding DROP CONSTRAINT %I', v_name);
        END IF;
    END LOOP;
END
$widen$;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_phase_check CHECK (phase IN ('P1', 'P2')) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_layer_check CHECK (layer IN ('A', 'B')) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_kind_check CHECK (kind IN ('MARKER', 'VENDOR_SEVERITY', 'NEW_ON_DEVICE',
    'FLEET_RARE', 'RATE_SPIKE', 'SILENCE', 'BOOT_BURST')) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_labels_check CHECK (labels <@ ARRAY['HISTORY_UNDER_7_DAYS', 'CHRONIC',
    'FLEET_BACKGROUND', 'PROVISIONAL_CAP', 'BASELINE_LEARNING', 'PROVISIONAL_TEMPLATE', 'CATALOG_BASELINE',
    'CATALOG_PREVALENCE', 'HA_ROLE_UNKNOWN', 'STATISTICAL_FLOOR']::text[]) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_kinds_check CHECK (kinds <@ ARRAY['NEW_ON_DEVICE', 'FLEET_RARE', 'RATE_SPIKE',
    'SILENCE', 'BOOT_BURST']::text[]) NOT VALID;
-- A SILENCE finding counts an absence: its line and peak counts are 0 (the V272 "> 0" checks stay for every other row).
ALTER TABLE log_finding ADD CONSTRAINT log_finding_event_count_check CHECK (event_count > 0
    OR (layer = 'B' AND kind = 'SILENCE' AND event_count = 0)) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_peak_bucket_count_check CHECK (peak_bucket_count > 0
    OR (layer = 'B' AND kind = 'SILENCE' AND peak_bucket_count = 0)) NOT VALID;
-- Design section 7.1: "a statistical finding alone is never CRITICAL" -- held by the database as well as by the evaluator.
ALTER TABLE log_finding ADD CONSTRAINT log_finding_layer_b_never_critical CHECK (layer <> 'B' OR severity <> 'CRITICAL') NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_phase_layer CHECK ((phase = 'P1' AND layer = 'A') OR (phase = 'P2' AND layer = 'B')) NOT VALID;
ALTER TABLE log_finding ADD CONSTRAINT log_finding_layer_b_columns CHECK (
    (template_id IS NULL OR template_id ~ '^[tp][0-9a-f]{31}$')
    AND (template_text IS NULL OR (char_length(template_text) BETWEEN 1 AND 4000
        AND template_text ~ '^[][A-Za-z0-9 <>_.:/#()=,*+%&|!?@~$^{}-]+$'))
    AND (catalog_version IS NULL OR catalog_version ~ '^cat-[0-9]{1,6}$')
    AND (normaliser_version IS NULL OR normaliser_version ~ '^[a-z0-9][a-z0-9._-]{0,31}$')
    AND (rarity IS NULL OR (rarity >= 0 AND rarity <= 1))
    AND (change_factor IS NULL OR (change_factor >= 0 AND change_factor <= 1))
    AND (score IS NULL OR (score >= 0 AND score <= 100))
    AND (baseline_median IS NULL OR baseline_median >= 0)
    AND (baseline_mad IS NULL OR baseline_mad >= 0)
    AND (layer <> 'B' OR (template_id IS NOT NULL AND catalog_version IS NOT NULL AND rarity IS NOT NULL
        AND change_factor IS NOT NULL AND score IS NOT NULL AND cardinality(kinds) > 0))) NOT VALID;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_phase_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_layer_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_kind_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_labels_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_kinds_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_event_count_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_peak_bucket_count_check;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_layer_b_never_critical;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_phase_layer;
ALTER TABLE log_finding VALIDATE CONSTRAINT log_finding_layer_b_columns;
CREATE INDEX IF NOT EXISTS log_finding_phase_day ON log_finding(phase, window_day DESC);
COMMENT ON COLUMN log_finding.phase IS 'P1 = layer A (severity and markers), P2 = layer B (catalog, rarity and rate).';
COMMENT ON COLUMN log_finding.score IS 'Layer B: 100 x S x R x C, for ordering only; never sets severity (design section 4.4).';

-- ---------------------------------------------------------------------
-- Immutability of a frozen catalog (design section 6.5: "a version is never edited after the freeze").
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_log_template_catalog_frozen_guard() RETURNS trigger AS $fn$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.state = 'FROZEN' THEN
            RAISE EXCEPTION 'log template catalog % is FROZEN and cannot be deleted', OLD.catalog_version;
        END IF;
        RETURN OLD;
    END IF;
    IF OLD.state = 'FROZEN' THEN
        RAISE EXCEPTION 'log template catalog % is FROZEN and cannot be changed', OLD.catalog_version;
    END IF;
    RETURN NEW;
END
$fn$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_log_template_catalog_frozen_guard ON log_template_catalog;
CREATE TRIGGER trg_log_template_catalog_frozen_guard
    BEFORE UPDATE OR DELETE ON log_template_catalog
    FOR EACH ROW EXECUTE FUNCTION fn_log_template_catalog_frozen_guard();

CREATE OR REPLACE FUNCTION fn_log_template_frozen_guard() RETURNS trigger AS $fn$
DECLARE
    v_version TEXT;
BEGIN
    v_version := CASE WHEN TG_OP = 'INSERT' THEN NEW.catalog_version ELSE OLD.catalog_version END;
    IF EXISTS (SELECT 1 FROM log_template_catalog c WHERE c.catalog_version = v_version AND c.state = 'FROZEN') THEN
        RAISE EXCEPTION 'templates of FROZEN log template catalog % cannot be changed', v_version;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    RETURN NEW;
END
$fn$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_log_template_frozen_guard ON log_template;
CREATE TRIGGER trg_log_template_frozen_guard
    BEFORE INSERT OR UPDATE OR DELETE ON log_template
    FOR EACH ROW EXECUTE FUNCTION fn_log_template_frozen_guard();
DROP TRIGGER IF EXISTS trg_log_template_device_stat_frozen_guard ON log_template_device_stat;
CREATE TRIGGER trg_log_template_device_stat_frozen_guard
    BEFORE INSERT OR UPDATE OR DELETE ON log_template_device_stat
    FOR EACH ROW EXECUTE FUNCTION fn_log_template_frozen_guard();
DROP TRIGGER IF EXISTS trg_log_template_device_window_frozen_guard ON log_template_device_window;
CREATE TRIGGER trg_log_template_device_window_frozen_guard
    BEFORE INSERT OR UPDATE OR DELETE ON log_template_device_window
    FOR EACH ROW EXECUTE FUNCTION fn_log_template_frozen_guard();

-- ---------------------------------------------------------------------
-- Audit (UI2_0_B1_02A section 4): the catalog row is operator-made (build, freeze). Its actor columns are redacted.
-- ---------------------------------------------------------------------
INSERT INTO audit_redaction_policy (table_name, column_name, tier, reason) VALUES
    ('log_template_catalog', 'created_by', 2, 'actor fingerprint of the operator'),
    ('log_template_catalog', 'frozen_by', 2, 'actor fingerprint of the operator')
ON CONFLICT (table_name, column_name) DO NOTHING;

DROP TRIGGER IF EXISTS trg_audit_log_template_catalog ON log_template_catalog;
CREATE TRIGGER trg_audit_log_template_catalog
    AFTER INSERT OR UPDATE OR DELETE ON log_template_catalog
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('catalog_version');

GRANT SELECT, INSERT, UPDATE, DELETE ON log_template_catalog TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_template TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_template_device_window TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_template_device_stat TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_template_index_state TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_template_count TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON log_provisional_template TO ui2_app;

-- Module-role grants (the V242 / V261 / V272 pattern): the job, the catalog build and the read routes run in ui2-service only.
DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template_catalog'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template_device_window'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template_device_stat'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template_index_state'),
        ('ui2_service', 'SELECT, INSERT, UPDATE, DELETE', 'log_template_count'),
        ('ui2_service', 'SELECT, INSERT, UPDATE', 'log_provisional_template')
    ) AS g(role_name, privs, tbl_name)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.%I TO %I', v_rec.privs, v_rec.tbl_name, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
