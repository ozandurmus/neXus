-- V31 -- Failover Engine Stage 2: durable storage for scheduled maintenance
-- window failovers, single-use grant consumption, sticky quarantine, and the
-- hash-chained transition ledger. Replaces the Stage 1 in-memory-only stores
-- (CF-P0.5, CF-P0.7, CF-P0.20, CF-P1.4, CF-P1.14 remediation).

CREATE TABLE IF NOT EXISTS failover_schedules (
    schedule_id TEXT PRIMARY KEY,
    cluster_ref TEXT NOT NULL,
    masked_cluster_name TEXT NOT NULL,
    vendor TEXT NOT NULL,
    command_family_id TEXT NOT NULL,
    action_kind TEXT NOT NULL,
    signed_mutation_target TEXT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    max_start_delay_minutes INT NOT NULL,
    execution_deadline TIMESTAMPTZ NOT NULL,
    requester_id TEXT NOT NULL,
    approver_id TEXT NOT NULL,
    grant_id TEXT NOT NULL UNIQUE,
    baseline_digest TEXT NOT NULL,
    baseline_json JSONB NOT NULL,
    envelope_signature TEXT NOT NULL,
    status TEXT NOT NULL,
    client_nonce TEXT NOT NULL,
    scheduled_at TIMESTAMPTZ NOT NULL,
    claimed_at TIMESTAMPTZ,
    executed_at TIMESTAMPTZ,
    execution_result_id TEXT,
    abort_reason_code TEXT,
    abort_reason TEXT,
    cancelled_by TEXT,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 1
);

CREATE INDEX IF NOT EXISTS idx_failover_schedules_cluster_ref ON failover_schedules(cluster_ref);
CREATE INDEX IF NOT EXISTS idx_failover_schedules_status ON failover_schedules(status);
CREATE INDEX IF NOT EXISTS idx_failover_schedules_window ON failover_schedules(window_start, window_end);

CREATE TABLE IF NOT EXISTS failover_grant_consumption (
    grant_id TEXT PRIMARY KEY,
    schedule_id TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_failover_grant_consumption_schedule_id ON failover_grant_consumption(schedule_id);

CREATE TABLE IF NOT EXISTS failover_quarantine (
    cluster_ref TEXT PRIMARY KEY,
    execution_id TEXT NOT NULL,
    reason TEXT NOT NULL,
    -- C1 §8: a PostgreSQL array is not a named portability exception; a
    -- JSON array in an opaque JSONB payload column (no jsonb operator used
    -- in any query here) is, so the member-id list is stored that way.
    quarantined_member_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    quarantined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by TEXT,
    second_approver_id TEXT,
    review_notes TEXT,
    active BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX IF NOT EXISTS idx_failover_quarantine_active ON failover_quarantine(active);

CREATE TABLE IF NOT EXISTS failover_schedule_ledger (
    -- C1 §8: every internal surrogate key uses GENERATED ALWAYS AS IDENTITY,
    -- not SERIAL/BIGSERIAL (Oracle portability; no exception listed for it).
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_index BIGINT NOT NULL UNIQUE,
    prev_hash TEXT NOT NULL,
    entry_hash TEXT NOT NULL,
    schedule_id TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    attempt_id TEXT NOT NULL,
    actor_id TEXT NOT NULL,
    details TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_failover_schedule_ledger_schedule_id ON failover_schedule_ledger(schedule_id);
