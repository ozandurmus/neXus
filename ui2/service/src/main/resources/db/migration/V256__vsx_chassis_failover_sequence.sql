-- V256 -- VSX chassis failover (docs/design/PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md, RATIFIED; contract
-- FAILOVER_EXECUTION_CP_CONTRACT.md section 17). Schema only, no device command:
--   1. failover_check_result admits check 16 "Virtual systems moved" (option B: the VS-moved verification of a VSX
--      HA-mode chassis run).
--   2. failover_approval: operation FAILOVER_ALL_VIRTUAL_SYSTEMS (option A: the approval of a VSLS chassis unit), and
--      parent_approval_id: the per-VS approval a sequence derives from that chassis approval (same window, reason,
--      policy, initiator and approver; operation FAILOVER on one VS). The immutability guard covers the new column.
--   3. failover_sequence: one audited record per "Fail over all virtual systems" request -- the ordered VSIDs, the index
--      of the next VS, the current VS run and the end. At most one RUNNING sequence per cluster; bindings and terminal
--      states are immutable; never deleted.
--   4. failover_run.sequence_id: each VS run of a sequence names it (every run stays its own auditable record).
-- DDL uses IF NOT EXISTS / DROP ... IF EXISTS / CREATE OR REPLACE so the dry run can execute the file twice inside one
-- rolled-back transaction.

ALTER TABLE failover_check_result DROP CONSTRAINT IF EXISTS failover_check_result_check_no_check;
ALTER TABLE failover_check_result ADD CONSTRAINT failover_check_result_check_no_check
    CHECK (check_no IN (1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16));

ALTER TABLE failover_approval ADD COLUMN IF NOT EXISTS parent_approval_id TEXT REFERENCES failover_approval(approval_id);
ALTER TABLE failover_approval DROP CONSTRAINT IF EXISTS failover_request_binding;
ALTER TABLE failover_approval ADD CONSTRAINT failover_request_binding CHECK (
 request_revision IS NULL OR (
  request_revision=1 AND unit_ref IS NOT NULL AND member_set_revision IS NOT NULL
  AND target_member_ids IS NOT NULL AND jsonb_array_length(target_member_ids)=2
  AND operation IS NOT NULL AND operation IN ('FAILOVER','FAILOVER_ALL_VIRTUAL_SYSTEMS')
  AND (operation='FAILOVER' OR vs_id IS NULL AND parent_approval_id IS NULL)
  AND (parent_approval_id IS NULL OR operation='FAILOVER' AND vs_id IS NOT NULL)
  AND policy IS NOT NULL AND policy IN ('ADMIN_SINGLE','OPERATION_ADMIN_TWO_PERSON')
  AND policy_version IS NOT NULL AND policy_version=1 AND initiated_by IS NOT NULL AND execution_nonce IS NOT NULL
  AND (approved_by IS NULL OR policy='ADMIN_SINGLE' AND approved_by=initiated_by
       OR policy='OPERATION_ADMIN_TWO_PERSON' AND approved_by<>initiated_by)));

CREATE OR REPLACE FUNCTION ui2_failover_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'APPROVAL_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP='UPDATE' AND (
  ROW(NEW.approval_id,NEW.cluster_ref,NEW.vs_id,NEW.vendor,NEW.window_from,NEW.window_until,
      NEW.reason,NEW.request_revision,NEW.unit_ref,NEW.member_set_revision,NEW.target_member_ids,
      NEW.operation,NEW.policy,NEW.policy_version,NEW.initiated_by,NEW.execution_nonce,NEW.parent_approval_id)
  IS DISTINCT FROM
  ROW(OLD.approval_id,OLD.cluster_ref,OLD.vs_id,OLD.vendor,OLD.window_from,OLD.window_until,
      OLD.reason,OLD.request_revision,OLD.unit_ref,OLD.member_set_revision,OLD.target_member_ids,
      OLD.operation,OLD.policy,OLD.policy_version,OLD.initiated_by,OLD.execution_nonce,OLD.parent_approval_id)
  OR (OLD.approved_by IS NOT NULL AND ROW(NEW.approved_by,NEW.approved_at) IS DISTINCT FROM ROW(OLD.approved_by,OLD.approved_at))
  OR (OLD.revoked_at IS NOT NULL AND ROW(NEW.revoked_at,NEW.revoked_by) IS DISTINCT FROM ROW(OLD.revoked_at,OLD.revoked_by))
  OR (OLD.warning_confirmed_at IS NOT NULL AND NEW.warning_confirmed_at IS DISTINCT FROM OLD.warning_confirmed_at))
 THEN RAISE EXCEPTION 'APPROVAL_IMMUTABLE'; END IF;
 RETURN NEW;
END;
$$;

CREATE TABLE IF NOT EXISTS failover_sequence (
    sequence_id TEXT PRIMARY KEY,
    cluster_ref TEXT NOT NULL,
    vendor TEXT NOT NULL DEFAULT 'check_point' CHECK (vendor = 'check_point'),
    approval_id TEXT NOT NULL UNIQUE REFERENCES failover_approval(approval_id),
    initiated_by TEXT NOT NULL,
    admission_role TEXT CHECK (admission_role IS NULL OR admission_role IN ('security_admin', 'operation_admin')),
    business_hours_ack BOOLEAN NOT NULL DEFAULT false,
    vs_ids JSONB NOT NULL CHECK (jsonb_typeof(vs_ids) = 'array' AND jsonb_array_length(vs_ids) >= 1),
    target_member_ids JSONB NOT NULL CHECK (jsonb_typeof(target_member_ids) = 'array' AND jsonb_array_length(target_member_ids) = 2),
    next_index INTEGER NOT NULL DEFAULT 0 CHECK (next_index >= 0),
    current_run_id TEXT REFERENCES failover_run(run_id),
    state TEXT NOT NULL CHECK (state IN ('RUNNING', 'DONE', 'STOPPED')),
    outcome TEXT,
    failed_vs_id TEXT,
    requested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    finished_at TIMESTAMPTZ,
    CHECK ((state = 'RUNNING') = (finished_at IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_failover_sequence_running ON failover_sequence(cluster_ref) WHERE state = 'RUNNING';

ALTER TABLE failover_run ADD COLUMN IF NOT EXISTS sequence_id TEXT REFERENCES failover_sequence(sequence_id);
CREATE INDEX IF NOT EXISTS idx_failover_run_sequence ON failover_run(sequence_id) WHERE sequence_id IS NOT NULL;

CREATE OR REPLACE FUNCTION ui2_failover_sequence_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'SEQUENCE_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP='UPDATE' AND (
  ROW(NEW.sequence_id,NEW.cluster_ref,NEW.vendor,NEW.approval_id,NEW.initiated_by,NEW.admission_role,
      NEW.business_hours_ack,NEW.vs_ids,NEW.target_member_ids,NEW.requested_at)
  IS DISTINCT FROM
  ROW(OLD.sequence_id,OLD.cluster_ref,OLD.vendor,OLD.approval_id,OLD.initiated_by,OLD.admission_role,
      OLD.business_hours_ack,OLD.vs_ids,OLD.target_member_ids,OLD.requested_at)
  OR OLD.state <> 'RUNNING'
  OR NEW.next_index < OLD.next_index)
 THEN RAISE EXCEPTION 'SEQUENCE_IMMUTABLE'; END IF;
 RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS failover_sequence_guard ON failover_sequence;
CREATE TRIGGER failover_sequence_guard BEFORE UPDATE OR DELETE ON failover_sequence
    FOR EACH ROW EXECUTE FUNCTION ui2_failover_sequence_guard();
DROP TRIGGER IF EXISTS trg_audit_failover_sequence ON failover_sequence;
CREATE TRIGGER trg_audit_failover_sequence AFTER INSERT OR UPDATE OR DELETE ON failover_sequence
    FOR EACH ROW EXECUTE FUNCTION fn_audit_capture('sequence_id');

GRANT SELECT, INSERT, UPDATE ON failover_sequence TO ui2_app;

DO $grants$
DECLARE
    v_rec RECORD;
BEGIN
    FOR v_rec IN SELECT * FROM (VALUES
        ('ui2_service', 'SELECT, INSERT, UPDATE'),
        ('ui2_scheduler', 'SELECT, INSERT, UPDATE'),
        ('ui2_failover', 'SELECT'),
        ('ui2_worker_general', 'SELECT')
    ) AS g(role_name, privs)
    LOOP
        IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = v_rec.role_name) THEN
            EXECUTE format('GRANT %s ON TABLE public.failover_sequence TO %I', v_rec.privs, v_rec.role_name);
        END IF;
    END LOOP;
END
$grants$;
