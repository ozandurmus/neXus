-- V163 -- policy collection completeness (PO decision 2026-10-18,
-- docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md; plan docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md
-- section 6 and section 9). Numbering: V162 is being taken tonight by another lane (readiness), so this migration is V163.
-- Schema and seed data only; the readers are off by default (see the plan's section 9).
--   1. The seven approved read commands P-1, P-2, P-3, P-4, C-1, C-2, C-3 as SIGNED_OFF CLASS_0 gate rows. Command text,
--      timeout, retry, frequency, session, unsupported-behavior, secret-risk and telemetry terms are copied from the
--      plan's section 6 tables (markdown emphasis removed). C-3 is approved but conditional: its row is registered and no
--      collector uses it until the Check Point lane shows the NAT timeout or size evidence. The retry literal C-3 names
--      (limit 50) is a further canonical key; it gets its own row when C-3 is first used.
--      P-2 carries the same text as the approved firewall row pan_syslog_collect_config_enqueue (V160). C4 section 3.3
--      step 6 makes two rows with one (vendor, scope, shell, transport, command) key a hard AmbiguousGate error, so P-2,
--      P-3 and P-4 stay distinct keys through platform_role_scope = 'panorama', as plan section 6 says. P-4 repeats the
--      text of the V151/V160 log job-get rows under the panorama scope. No shell_context narrowing is needed.
--   2. policy_revision: one row per collection per container (device group or domain), stating CHANGED, UNCHANGED or
--      UNKNOWN. A NULL count is UNKNOWN, never zero.
--   3. policy_change_evidence and policy_change_cursor: "who changed what, when". Panorama config-log entries (after the
--      PanLogRedactor rule set) and Check Point show-changes summaries. Counts, opaque ids, an admin identity as an HMAC or
--      a masked form, and timestamps. No raw secret, no before/after change detail, no object or rule names.
-- Every statement is repeatable (IF NOT EXISTS, ON CONFLICT DO NOTHING), so the dry run can run the file twice inside one
-- rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V163_policy_completeness', true);
SELECT set_config('app.action_id', 'policy_completeness_by_migration', true);

-- ---------------------------------------------------------------------
-- 1. Gate rows (plan section 6; PO approval 2026-10-18, all seven as written).
-- ---------------------------------------------------------------------
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
VALUES
    ('pan_policy_nat_rule_hit_count', 'palo_alto', 'pan_firewall', 'not_applicable', 'PAN_XML_API', 'type=op&cmd=<show><rule-hit-count><vsys><vsys-name><entry name=''<VSYS>''><rule-base><entry name=''nat''><rules><all/></rules></entry></rule-base></entry></vsys-name></vsys></rule-hit-count></show>', 'read', 'SIGNED_OFF', 60, 'none',
     'automatic at most once per firewall and VSYS per 6 hours (shares the existing policy_collection_domain throttle key family); manual admin request',
     'the per-member API key already generated for the security hit read in the same job; serial',
     'API error, missing rule-base/entry[@name=''nat''] or context mismatch → NAT hits UNKNOWN; policy retained',
     'rule names (sensitive identities); parse in memory; counters only persisted',
     '["outcome", "ruleCount"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (P-1); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): the exact XML element rule-base/entry[@name=''nat''] is a substitution into the documented security form, confirmed only by one PO-approved measurement call; the NAT response size per VSYS'),
    ('pan_policy_config_log_enqueue', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=config&nlogs=<n>&dir=forward&query=(receive_time geq ''<ts>'')', 'read', 'SIGNED_OFF', 30, 'none',
     '≤ 4 pages per Panorama per job window; <n> digits 1–5000; <ts> matches ^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$ (stored cursor); max 8 MiB per page',
     'the policy job''s existing Panorama API key; serial',
     'API error or unfinished job → UNKNOWN; cursor unchanged; collection proceeds without the marker',
     'admin user names, xpaths with DG/object names, before/after values; keep only time, seqno, opaque DG ref and command class; redact on write; masked for aiview',
     '["entries", "resultBytes", "enqueueToFinMs", "pages"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (P-2); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): Panorama config-log field content for device-group changes; any API rate limit'),
    ('pan_policy_config_log_seed', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'type=log&log-type=config&nlogs=<n>', 'read', 'SIGNED_OFF', 30, 'none',
     'once per Panorama (first run only); <n> 1–5000; max 8 MiB',
     'existing policy-job API key',
     'API error → UNKNOWN; no cursor stored',
     'as P-2',
     '["entries", "resultBytes", "enqueueToFinMs"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (P-3); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): Panorama config-log field content for device-group changes'),
    ('pan_policy_log_job_get', 'palo_alto', 'panorama', 'not_applicable', 'PAN_XML_API', 'type=log&action=get&job-id=<id>', 'read', 'SIGNED_OFF', 30, 'none',
     'every 2 s until FIN, at most 30 polls per enqueued job; <id> digits-only from the enqueue answer; max 8 MiB',
     'existing policy-job API key; serial',
     'not FIN within the bound → UNKNOWN',
     'as P-2',
     '["jobStatus", "entries", "resultBytes"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (P-4); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): Panorama config-log field content for device-group changes'),
    ('cp_policy_show_changes', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-changes from-date ''<FROM>'' to-date ''<TO>'' limit 10 offset ''<N>''', 'read', 'SIGNED_OFF', 60, 'none',
     'only when show-last-published-session changed; once per domain per 6 hours; ≤ 20 offsets per run',
     'existing policy SSH sessions (≤ 4, adaptive)',
     'error or no task id → domain collected fully (as today)',
     'none in the immediate reply (task id only)',
     '["outcome", "seconds"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (C-1); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): whether show-changes opens a session or lock on the MDS; exact API version on the MDS'),
    ('cp_policy_show_task', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-task task-id ''<UID>'' details-level full', 'read', 'SIGNED_OFF', 60, 'none',
     'poll every 5 s until status is succeeded/failed, ≤ 24 polls per task',
     'same session as C-1',
     'failed/unfinished task or unknown shape → domain collected fully; revision recorded without change list',
     'object names and admin user-name; keep only uid, type, operation and publish-time; drop names and user names in memory',
     '["outcome", "added", "modified", "deleted", "polls"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (C-2); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); UNKNOWN (plan section 8): whether a modified rule''s diff names its layer'),
    ('cp_policy_nat_rulebase_page100', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-nat-rulebase package ''<PKG>'' limit 100 offset ''<N>'' details-level standard use-object-dictionary true', 'read', 'SIGNED_OFF', 300, 'once on timeout with limit 50 (that literal is a further key; it is approved together with this row)',
     'as cp_policy_nat_rulebase; ≤ 200 pages per package',
     'existing policy SSH sessions',
     'failed page → NAT gap for that package; access rules retained',
     'sensitive policy data; parse in memory',
     '["outcome", "ruleCount", "pages"]'::jsonb, 'docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6 (C-3); PO approval 2026-10-18 (docs/design/PO_DECISION_RECORD_2026_10_18_POLICY_COMPLETENESS.md, all seven commands APPROVED as written); CONDITIONAL (decision record section 2): registered, no collector uses this row until the Check Point lane shows the NAT timeout or size evidence; UNKNOWN (plan section 8): the maximum limit of show-nat-rulebase')
ON CONFLICT (gate_id) DO NOTHING;

-- ---------------------------------------------------------------------
-- 2. Revision record (plan section 4b, as the PO's partial-first approval narrows it): one row per (job, container).
--   * change_state is CHANGED when the container's marker differs from the previous revision's, UNCHANGED when it is
--     equal, UNKNOWN when either marker is missing or the read failed (unknown_reason names why). The first observation
--     of a container has no previous marker and is UNKNOWN (NO_PREVIOUS_MARKER): it establishes revision 1.
--   * revision_no rises only on CHANGED. UNCHANGED and UNKNOWN rows repeat the previous number. A container the product
--     never recorded before starts at revision 1; when the Check Point collector already held an earlier published
--     session, the first row can be CHANGED (revision 2) against it.
--   * marker is an opaque token: Check Point {uid, publish_time, posix} of show-last-published-session; Palo Alto
--     {content_version}, the SHA-256 of the lineage XML (the shared XML and each device group from the root down to this
--     one). A different token means that lineage XML changed, not that a rule changed; rule counts come from
--     policy_rule_history. It is not the within-job resume token of PanoramaPolicyCollector.contentVersion.
--   * every count is NULL (UNKNOWN) unless measured: rules_added/modified/removed from policy_rule_history (initial
--     captures excluded), objects_* from a Check Point show-task diff, nat_rules stays NULL until a NAT parse exists.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_revision (
    revision_id BIGINT GENERATED ALWAYS AS IDENTITY CONSTRAINT policy_revision_pkey PRIMARY KEY,
    source_id TEXT NOT NULL,
    vendor TEXT NOT NULL CONSTRAINT policy_revision_vendor_check CHECK (vendor IN ('palo_alto', 'check_point')),
    container_kind TEXT NOT NULL CONSTRAINT policy_revision_container_kind_check CHECK (container_kind IN ('device_group', 'domain')),
    container_ref TEXT NOT NULL,
    job_id TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revision_no INTEGER NOT NULL CONSTRAINT policy_revision_revision_no_check CHECK (revision_no >= 1),
    change_state TEXT NOT NULL CONSTRAINT policy_revision_change_state_check CHECK (change_state IN ('CHANGED', 'UNCHANGED', 'UNKNOWN')),
    unknown_reason TEXT CONSTRAINT policy_revision_unknown_reason_check CHECK (unknown_reason IS NULL OR unknown_reason ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    marker_kind TEXT CONSTRAINT policy_revision_marker_kind_check CHECK (marker_kind IS NULL OR marker_kind IN ('CP_PUBLISHED_SESSION', 'PAN_CONTENT_VERSION')),
    marker JSONB NOT NULL DEFAULT '{}'::jsonb CONSTRAINT policy_revision_marker_check CHECK (jsonb_typeof(marker) = 'object'
        AND marker - ARRAY['uid', 'publish_time', 'posix', 'content_version'] = '{}'::jsonb),
    complete BOOLEAN NOT NULL,
    rules_total INTEGER CONSTRAINT policy_revision_rules_total_check CHECK (rules_total IS NULL OR rules_total >= 0),
    nat_rules INTEGER CONSTRAINT policy_revision_nat_rules_check CHECK (nat_rules IS NULL OR nat_rules >= 0),
    rules_added INTEGER CONSTRAINT policy_revision_rules_added_check CHECK (rules_added IS NULL OR rules_added >= 0),
    rules_modified INTEGER CONSTRAINT policy_revision_rules_modified_check CHECK (rules_modified IS NULL OR rules_modified >= 0),
    rules_removed INTEGER CONSTRAINT policy_revision_rules_removed_check CHECK (rules_removed IS NULL OR rules_removed >= 0),
    objects_added INTEGER CONSTRAINT policy_revision_objects_added_check CHECK (objects_added IS NULL OR objects_added >= 0),
    objects_modified INTEGER CONSTRAINT policy_revision_objects_modified_check CHECK (objects_modified IS NULL OR objects_modified >= 0),
    objects_removed INTEGER CONSTRAINT policy_revision_objects_removed_check CHECK (objects_removed IS NULL OR objects_removed >= 0),
    evidence_count INTEGER NOT NULL DEFAULT 0 CONSTRAINT policy_revision_evidence_count_check CHECK (evidence_count >= 0),
    CONSTRAINT policy_revision_job_container UNIQUE (job_id, container_ref),
    CONSTRAINT policy_revision_unknown_reason_pairing_check CHECK ((change_state = 'UNKNOWN') = (unknown_reason IS NOT NULL)),
    CONSTRAINT policy_revision_vendor_kind_check CHECK ((vendor = 'palo_alto') = (container_kind = 'device_group'))
);
CREATE INDEX IF NOT EXISTS policy_revision_latest ON policy_revision(source_id, container_ref, observed_at DESC, revision_id DESC);
COMMENT ON TABLE policy_revision IS
    'One row per collection per container: CHANGED, UNCHANGED or UNKNOWN against the previous marker (plan section 9). NULL counts are UNKNOWN.';
COMMENT ON COLUMN policy_revision.container_ref IS 'Opaque container reference as policy_snapshot.metadata containerId: a device-group or domain ref, never a name.';
COMMENT ON COLUMN policy_revision.revision_no IS 'Rises only when change_state is CHANGED; the first observation is revision 1.';
COMMENT ON COLUMN policy_revision.marker IS 'Opaque change marker: CP published-session uid and publish time, or PAN lineage content version.';

-- ---------------------------------------------------------------------
-- 3. Change evidence ("who changed what, when"). Evidence rows are written only by the reader of the trial target.
--   * PAN_CONFIG_LOG rows: one per config-log entry, after PanLogRedactor (rule set pan-config-v2). Kept: the seqno as
--     entry_ref, receive_time (device-local wall clock, never converted), the admin as an HMAC (or masked form when no
--     fingerprint key is mounted), the command class, the result class, an HMAC of the path and the opaque device-group
--     ref when the path names one. before-/after-change-detail is never stored. Field content for Panorama is UNKNOWN
--     until measured, so every vendor-supplied token is shape-checked and otherwise kept as NULL.
--   * CP_SHOW_CHANGES rows: one per published session of a show-task diff: the session uid as entry_ref, the publish
--     time (UTC), the admin as an HMAC or masked form, added/modified/deleted counts and a capped list of
--     {uid, type, op}. Object and rule names are dropped in memory.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS policy_change_evidence (
    evidence_id TEXT CONSTRAINT policy_change_evidence_pkey PRIMARY KEY,
    source_id TEXT NOT NULL,
    vendor TEXT NOT NULL CONSTRAINT policy_change_evidence_vendor_check CHECK (vendor IN ('palo_alto', 'check_point')),
    evidence_kind TEXT NOT NULL CONSTRAINT policy_change_evidence_evidence_kind_check CHECK (evidence_kind IN ('PAN_CONFIG_LOG', 'CP_SHOW_CHANGES')),
    container_ref TEXT,
    job_id TEXT NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    entry_ref TEXT NOT NULL CONSTRAINT policy_change_evidence_entry_ref_check CHECK (entry_ref ~ '^[0-9A-Za-z-]{1,64}$'),
    change_time TIMESTAMP,
    time_basis TEXT CONSTRAINT policy_change_evidence_time_basis_check CHECK (time_basis IS NULL OR time_basis IN ('DEVICE_LOCAL', 'UTC')),
    admin_ref TEXT NOT NULL CONSTRAINT policy_change_evidence_admin_ref_check CHECK (admin_ref ~ '^([0-9a-f]{64}|masked:[0-9]{1,3}|UNKNOWN)$'),
    command_class TEXT CONSTRAINT policy_change_evidence_command_class_check CHECK (command_class IS NULL OR command_class ~ '^[a-z][a-z0-9_-]{0,31}$'),
    result_class TEXT CONSTRAINT policy_change_evidence_result_class_check CHECK (result_class IS NULL OR result_class ~ '^[A-Za-z][A-Za-z0-9_-]{0,31}$'),
    path_hmac TEXT CONSTRAINT policy_change_evidence_path_hmac_check CHECK (path_hmac IS NULL OR path_hmac ~ '^[0-9a-f]{64}$'),
    redaction_count INTEGER NOT NULL DEFAULT 0 CONSTRAINT policy_change_evidence_redaction_count_check CHECK (redaction_count >= 0),
    added_count INTEGER CONSTRAINT policy_change_evidence_added_count_check CHECK (added_count IS NULL OR added_count >= 0),
    modified_count INTEGER CONSTRAINT policy_change_evidence_modified_count_check CHECK (modified_count IS NULL OR modified_count >= 0),
    deleted_count INTEGER CONSTRAINT policy_change_evidence_deleted_count_check CHECK (deleted_count IS NULL OR deleted_count >= 0),
    object_changes JSONB CONSTRAINT policy_change_evidence_object_changes_check CHECK (object_changes IS NULL OR jsonb_typeof(object_changes) = 'array'),
    object_changes_truncated BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT policy_change_evidence_vendor_kind_check CHECK ((vendor = 'palo_alto') = (evidence_kind = 'PAN_CONFIG_LOG')),
    CONSTRAINT policy_change_evidence_time_basis_pairing_check CHECK ((change_time IS NULL) = (time_basis IS NULL))
);
-- Re-reading the boundary second or a repeated show-changes window must not duplicate a row.
CREATE UNIQUE INDEX IF NOT EXISTS policy_change_evidence_identity
    ON policy_change_evidence(source_id, evidence_kind, entry_ref, coalesce(container_ref, ''));
CREATE INDEX IF NOT EXISTS policy_change_evidence_container_time
    ON policy_change_evidence(source_id, container_ref, change_time DESC);
CREATE INDEX IF NOT EXISTS policy_change_evidence_job ON policy_change_evidence(job_id);
COMMENT ON TABLE policy_change_evidence IS
    'Who changed what, when: redacted Panorama config-log entries and Check Point show-changes summaries (plan section 9). No raw secret, no change detail, no names.';
COMMENT ON COLUMN policy_change_evidence.entry_ref IS 'PAN: the log seqno (opaque digits). CP: the published-session uid.';
COMMENT ON COLUMN policy_change_evidence.admin_ref IS 'HMAC-SHA256 of the admin identity under the product fingerprint key, or masked:<length>, or UNKNOWN.';
COMMENT ON COLUMN policy_change_evidence.change_time IS 'PAN: receive_time as device-local wall clock. CP: publish time in UTC (time_basis says which).';

-- The Panorama config-log cursor (P-2/P-3): the newest receive_time seen and the seqnos stored at exactly that second.
CREATE TABLE IF NOT EXISTS policy_change_cursor (
    source_id TEXT NOT NULL,
    change_source TEXT NOT NULL CONSTRAINT policy_change_cursor_change_source_check CHECK (change_source IN ('pan_config_log')),
    last_receive_time TEXT NOT NULL CONSTRAINT policy_change_cursor_last_receive_time_check CHECK (last_receive_time ~ '^[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}$'),
    boundary_seqnos JSONB NOT NULL DEFAULT '[]'::jsonb CONSTRAINT policy_change_cursor_boundary_seqnos_check CHECK (jsonb_typeof(boundary_seqnos) = 'array'),
    updated_job_id TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT policy_change_cursor_pkey PRIMARY KEY (source_id, change_source)
);
COMMENT ON TABLE policy_change_cursor IS 'Resume point of the Panorama config-log reader; advanced only in the transaction that stores the evidence of the page.';

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_revision TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_change_evidence TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON policy_change_cursor TO ui2_app;
