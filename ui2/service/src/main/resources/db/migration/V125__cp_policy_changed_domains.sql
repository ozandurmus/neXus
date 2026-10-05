-- PO 2026-10-05: exactly the approved per-domain published-session read.
SELECT set_config('app.actor_fingerprint', 'migration:V125_cp_policy_changed_domains', true);
SELECT set_config('app.action_id', 'gate_registry_insert_by_migration', true);
INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind, canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency, session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields, source_document_pointer)
VALUES ('cp_policy_last_published_session', 'check_point', 'cp_multi_domain_server', 'expert', 'SSH_EXEC', 'mgmt_cli -r true -d ''<DOMAIN>'' -f json show-last-published-session', 'read', 'SIGNED_OFF', 300, 'none', 'manual policy request; automatic at most once per domain per 6 hours', 'existing policy SSH sessions; bounded adaptive concurrency', 'UNKNOWN; missing or failed signal collects domain fully', 'sensitive session data; retain only uid and publish-time; no raw retention', '["outcome", "domainsReused", "domainsCollected", "rulesReused", "rulesFetched"]', 'PO 2026-10-05; cp-changed-domains; approved CLASS_0 read');

ALTER TABLE policy_collection_request ADD COLUMN mode TEXT NOT NULL DEFAULT 'CHANGED_ONLY'
    CHECK (mode IN ('CHANGED_ONLY', 'FULL'));
-- Keep the run's parsed snapshots together, independent of incomplete latest-policy checkpoints.
CREATE TABLE cp_policy_domain_run (
    job_id TEXT NOT NULL REFERENCES jobs(job_id),
    source_id TEXT NOT NULL,
    domain_ref TEXT NOT NULL,
    attempted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    status TEXT NOT NULL CHECK (status IN ('COLLECTING', 'COLLECTED', 'REUSED')),
    complete BOOLEAN NOT NULL DEFAULT false,
    signal JSONB,
    snapshots JSONB NOT NULL DEFAULT '[]' CHECK (jsonb_typeof(snapshots) = 'array'),
    rules_count INTEGER NOT NULL DEFAULT 0,
    hits_collected_at TIMESTAMPTZ,
    PRIMARY KEY (job_id, domain_ref)
);
CREATE INDEX cp_policy_domain_run_latest ON cp_policy_domain_run(source_id, domain_ref, attempted_at DESC);
GRANT SELECT, INSERT, UPDATE, DELETE ON cp_policy_domain_run TO ui2_app;
