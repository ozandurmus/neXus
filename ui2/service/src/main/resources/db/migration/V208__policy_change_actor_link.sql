-- Palo Alto change actor from the Panorama config log (PO decision record 2026-11-12,
-- docs/design/PO_DECISION_RECORD_2026_11_12_PAN_CHANGE_ACTOR_FROM_CONFIG_LOG.md; design
-- docs/design/PAN_CHANGE_ACTOR_FROM_PANORAMA_CONFIG_LOG_2026_11_11.md, DRAFT).
--   1. policy_change_evidence.admin_name: the administrator name of a configuration-CHANGE entry only (add, clone, delete,
--      edit, move, rename, set; result Succeeded; with a rule reference). Commits and every other entry keep admin_ref only.
--   2. policy_change_evidence.rule_ref_hmac: HMAC of the rule the entry's path names (container, phase, rulebase kind, rule
--      name), computed in memory by the worker; no rule or device-group name is stored.
--   3. policy_change_actor_link: one row per policy_rule_history row the resolver looked at: MATCHED, AMBIGUOUS or UNKNOWN
--      with a fixed reason code. The candidate names live here, so they are deleted with the history row (ON DELETE CASCADE),
--      which is PO decision D5 ("as long as the policy history"). policy_rule_history itself is not modified.
-- Every statement is repeatable so the dry run can run the file twice inside one rolled-back transaction.
SELECT set_config('app.actor_fingerprint', 'migration:V208_policy_change_actor_link', true);
SELECT set_config('app.action_id', 'policy_change_actor_link_by_migration', true);

ALTER TABLE policy_change_evidence ADD COLUMN IF NOT EXISTS admin_name TEXT;
ALTER TABLE policy_change_evidence ADD COLUMN IF NOT EXISTS rule_ref_hmac TEXT;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'policy_change_evidence_admin_name_check') THEN
        ALTER TABLE policy_change_evidence ADD CONSTRAINT policy_change_evidence_admin_name_check
            CHECK (admin_name IS NULL OR (char_length(admin_name) BETWEEN 1 AND 128 AND admin_name !~ '[[:cntrl:]]'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'policy_change_evidence_rule_ref_hmac_check') THEN
        ALTER TABLE policy_change_evidence ADD CONSTRAINT policy_change_evidence_rule_ref_hmac_check
            CHECK (rule_ref_hmac IS NULL OR rule_ref_hmac ~ '^[0-9a-f]{64}$');
    END IF;
    -- A name is only ever stored for a change entry that names a rule (decision D2); never for a commit or a Check Point row.
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'policy_change_evidence_admin_name_scope_check') THEN
        ALTER TABLE policy_change_evidence ADD CONSTRAINT policy_change_evidence_admin_name_scope_check
            CHECK (admin_name IS NULL OR (evidence_kind = 'PAN_CONFIG_LOG' AND rule_ref_hmac IS NOT NULL
                AND command_class IN ('add', 'clone', 'delete', 'edit', 'move', 'rename', 'set') AND result_class = 'Succeeded'));
    END IF;
END $$;
CREATE INDEX IF NOT EXISTS policy_change_evidence_rule_ref
    ON policy_change_evidence(source_id, rule_ref_hmac, change_time) WHERE rule_ref_hmac IS NOT NULL;
CREATE INDEX IF NOT EXISTS policy_change_evidence_commit_time
    ON policy_change_evidence(source_id, change_time) WHERE command_class = 'commit';
COMMENT ON TABLE policy_change_evidence IS
    'Who changed what, when: redacted Panorama config-log entries and Check Point show-changes summaries (plan section 9). No raw secret, no change detail, no object or rule names; the one administrator name allowed is admin_name (V208).';
COMMENT ON COLUMN policy_change_evidence.admin_name IS
    'PAN_CONFIG_LOG only: the administrator name of a successful configuration-change entry that names a rule (PO D2, 2026-11-12). NULL for commits and everything else. Cleared by the resolver 30 days after the entry; a link row keeps its own copy of the candidate names for as long as its history row exists (PO D5). Masked for aiview as a last-modifier user name.';
COMMENT ON COLUMN policy_change_evidence.rule_ref_hmac IS
    'HMAC-SHA256 under the product fingerprint key of container | phase | rulebase kind | rule name taken from the entry path; NULL when the path names no rule.';

CREATE TABLE IF NOT EXISTS policy_change_actor_link (
    revision_id BIGINT NOT NULL CONSTRAINT policy_change_actor_link_pkey PRIMARY KEY
        CONSTRAINT policy_change_actor_link_revision_fk REFERENCES policy_rule_history(revision_id) ON DELETE CASCADE,
    state TEXT NOT NULL CONSTRAINT policy_change_actor_link_state_check CHECK (state IN ('MATCHED', 'AMBIGUOUS', 'UNKNOWN')),
    reason TEXT CONSTRAINT policy_change_actor_link_reason_check CHECK (reason IS NULL OR reason ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    admin_count INTEGER NOT NULL DEFAULT 0 CONSTRAINT policy_change_actor_link_admin_count_check CHECK (admin_count >= 0),
    candidate_names JSONB NOT NULL DEFAULT '[]'::jsonb CONSTRAINT policy_change_actor_link_candidates_check CHECK (jsonb_typeof(candidate_names) = 'array'),
    resolved_job_id TEXT,
    resolved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT policy_change_actor_link_reason_pairing_check CHECK ((state = 'UNKNOWN') = (reason IS NOT NULL)),
    CONSTRAINT policy_change_actor_link_count_state_check CHECK (
        (state = 'MATCHED' AND admin_count = 1 AND jsonb_array_length(candidate_names) = 1)
        OR (state = 'AMBIGUOUS' AND admin_count >= 2 AND jsonb_array_length(candidate_names) = admin_count)
        OR (state = 'UNKNOWN' AND admin_count = 0 AND jsonb_array_length(candidate_names) = 0))
);
COMMENT ON TABLE policy_change_actor_link IS
    'Result of joining one policy_rule_history row to Panorama config-log entries (design section 4.2): MATCHED, AMBIGUOUS or UNKNOWN. Candidate names are deleted with the history row (PO D5).';
COMMENT ON COLUMN policy_change_actor_link.candidate_names IS 'Administrator names of the candidate entries: one for MATCHED, two or more for AMBIGUOUS, none for UNKNOWN. Never shown on the list; masked like a last-modifier name.';

GRANT SELECT, INSERT, UPDATE, DELETE ON policy_change_actor_link TO ui2_app;
