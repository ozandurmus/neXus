-- One approval and one run belong to a physical cluster or to one VSID of a VSX cluster.
CREATE TABLE failover_approval (
    approval_id TEXT PRIMARY KEY,
    cluster_ref TEXT NOT NULL,
    vs_id TEXT,
    window_from TIMESTAMPTZ NOT NULL,
    window_until TIMESTAMPTZ NOT NULL,
    reason TEXT NOT NULL,
    approved_by TEXT NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    revoked_at TIMESTAMPTZ,
    revoked_by TEXT,
    CHECK (window_until > window_from),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL))
);

CREATE TABLE failover_run (
    run_id TEXT PRIMARY KEY,
    cluster_ref TEXT NOT NULL,
    vs_id TEXT,
    approval_id TEXT NOT NULL REFERENCES failover_approval(approval_id),
    requested_by TEXT NOT NULL,
    scheduled_for TIMESTAMPTZ NOT NULL,
    job_id TEXT UNIQUE REFERENCES jobs(job_id),
    state TEXT NOT NULL CHECK (state IN ('PLANNED', 'PRECHECK', 'FAILING_OVER', 'SWITCHED',
        'POSTCHECK', 'RETURNING', 'DONE', 'STOPPED')),
    step TEXT NOT NULL,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ,
    outcome TEXT,
    failed_check TEXT,
    message TEXT
);
CREATE UNIQUE INDEX uq_failover_run_active_unit ON failover_run(cluster_ref, coalesce(vs_id, ''))
    WHERE state NOT IN ('DONE', 'STOPPED');
CREATE INDEX idx_failover_run_due ON failover_run(scheduled_for) WHERE state = 'PLANNED';

CREATE TABLE failover_check_result (
    result_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id TEXT NOT NULL REFERENCES failover_run(run_id),
    phase TEXT NOT NULL CHECK (phase IN ('pre', 'post')),
    member_ref TEXT NOT NULL,
    vs_id TEXT,
    check_no INT NOT NULL CHECK (check_no IN (1, 2, 3, 5, 6, 8)),
    status TEXT NOT NULL CHECK (status IN ('PASS', 'FAIL', 'UNKNOWN')),
    derived JSONB NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, phase, member_ref, check_no)
);

CREATE TRIGGER trg_audit_failover_approval AFTER INSERT OR UPDATE OR DELETE ON failover_approval
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('approval_id');
CREATE TRIGGER trg_audit_failover_run AFTER INSERT OR UPDATE OR DELETE ON failover_run
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('run_id');
CREATE TRIGGER trg_audit_failover_check_result AFTER INSERT OR UPDATE OR DELETE ON failover_check_result
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('result_id');

GRANT SELECT, INSERT, UPDATE, DELETE ON failover_approval TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_run TO ui2_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON failover_check_result TO ui2_app;

-- The existing HA reads are reused more often by the approved flow.
SELECT set_config('app.actor_fingerprint', 'migration:V101_cp_failover_execution', true);
SELECT set_config('app.action_id', 'gate_registry_update_by_migration', true);
UPDATE gate_registry SET max_frequency = 'pre/post once per member; cphaprob stat additionally every 3 s for at most 60 s per failover run'
WHERE gate_id IN ('cp_inventory_cphaprob_stat', 'cp_inventory_vsid_cphaprob_stat');
UPDATE gate_registry SET max_frequency = 'once pre and once post per member per failover run'
WHERE gate_id IN ('cp_inventory_cphaprob_a_m_if', 'cp_inventory_vsid_cphaprob_a_m_if');

INSERT INTO gate_registry (gate_id, vendor, platform_role_scope, shell_context, transport_kind,
    canonical_command_key, action_class, sign_off_state, timeout_s, retry_rule, max_frequency,
    session_reuse_rule, unsupported_behavior_ref, secret_output_risk, safe_telemetry_fields,
    source_document_pointer)
SELECT 'cp_failover_' || cmd.id || CASE WHEN ctx.vsx THEN '_vsid' ELSE '' END,
    'check_point', 'cp_gaia_gateway', 'expert', 'SSH_EXEC',
    CASE WHEN ctx.vsx THEN 'bash -lc ''vsenv <VSID> && ' || cmd.command || '''' ELSE cmd.command END,
    cmd.action_class, 'SIGNED_OFF', 30, 'none', cmd.frequency,
    'one SSH session per member per run; commands serial', 'UNKNOWN', 'none', '[]'::jsonb,
    'docs/design/FAILOVER_EXECUTION_CP_CONTRACT.md §4'
FROM (VALUES
    ('tablestat', 'cphaprob tablestat', 'read', 'once pre and once post per member per run'),
    ('arp', 'arp -an', 'read', 'once pre and once post per member per run'),
    ('connections', 'fw tab -t connections -s', 'read', 'once pre and once post per member per run'),
    ('traffic', 'cat /proc/net/dev', 'read', 'two samples 5 s apart pre and post per member per run'),
    ('down', 'clusterXL_admin down', 'operational-state-change', 'once on former active per run'),
    ('up', 'clusterXL_admin up', 'operational-state-change', 'once on former active per run')
) AS cmd(id, command, action_class, frequency)
CROSS JOIN (VALUES (false), (true)) AS ctx(vsx);
