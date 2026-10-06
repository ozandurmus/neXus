-- PR 7 allocation; may be renumbered when the parallel lanes are integrated.
-- Existing window-only approvals remain readable but cannot authorize new execution.
ALTER TABLE failover_approval
 ADD COLUMN request_revision BIGINT,
 ADD COLUMN unit_ref TEXT,
 ADD COLUMN member_set_revision TEXT,
 ADD COLUMN target_member_ids JSONB,
 ADD COLUMN operation TEXT,
 ADD COLUMN policy TEXT,
 ADD COLUMN policy_version INTEGER,
 ADD COLUMN initiated_by TEXT,
 ADD COLUMN execution_nonce TEXT UNIQUE,
 ADD COLUMN warning_confirmed_at TIMESTAMPTZ;
ALTER TABLE failover_approval ALTER COLUMN approved_by DROP NOT NULL;
ALTER TABLE failover_approval ALTER COLUMN approved_at DROP NOT NULL;
ALTER TABLE failover_approval ADD CONSTRAINT failover_request_binding CHECK (
 request_revision IS NULL OR (
  request_revision=1 AND unit_ref IS NOT NULL AND member_set_revision IS NOT NULL
  AND target_member_ids IS NOT NULL AND jsonb_array_length(target_member_ids)=2
  AND operation IS NOT NULL AND operation='FAILOVER' AND policy IS NOT NULL AND policy IN ('ADMIN_SINGLE','OPERATION_ADMIN_TWO_PERSON')
  AND policy_version IS NOT NULL AND policy_version=1 AND initiated_by IS NOT NULL AND execution_nonce IS NOT NULL
  AND (approved_by IS NULL OR policy='ADMIN_SINGLE' AND approved_by=initiated_by
       OR policy='OPERATION_ADMIN_TWO_PERSON' AND approved_by<>initiated_by)));
ALTER TABLE failover_run ADD COLUMN request_revision BIGINT;
CREATE UNIQUE INDEX uq_failover_consumed_approval ON failover_run(approval_id)
 WHERE request_revision IS NOT NULL;

-- The same lock orders admission, revocation, incidents and dispatch authorization.
-- Requests are immutable revision 1: changing target, membership, window or policy
-- requires a new request and fresh confirmation/approval, never an in-place edit.
CREATE FUNCTION ui2_failover_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM pg_advisory_xact_lock(136, 1);
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'APPROVAL_HISTORY_IMMUTABLE'; END IF;
 IF TG_OP='UPDATE' AND (
  ROW(NEW.approval_id,NEW.cluster_ref,NEW.vs_id,NEW.vendor,NEW.window_from,NEW.window_until,
      NEW.reason,NEW.request_revision,NEW.unit_ref,NEW.member_set_revision,NEW.target_member_ids,
      NEW.operation,NEW.policy,NEW.policy_version,NEW.initiated_by,NEW.execution_nonce)
  IS DISTINCT FROM
  ROW(OLD.approval_id,OLD.cluster_ref,OLD.vs_id,OLD.vendor,OLD.window_from,OLD.window_until,
      OLD.reason,OLD.request_revision,OLD.unit_ref,OLD.member_set_revision,OLD.target_member_ids,
      OLD.operation,OLD.policy,OLD.policy_version,OLD.initiated_by,OLD.execution_nonce)
  OR (OLD.approved_by IS NOT NULL AND ROW(NEW.approved_by,NEW.approved_at) IS DISTINCT FROM ROW(OLD.approved_by,OLD.approved_at))
  OR (OLD.revoked_at IS NOT NULL AND ROW(NEW.revoked_at,NEW.revoked_by) IS DISTINCT FROM ROW(OLD.revoked_at,OLD.revoked_by))
  OR (OLD.warning_confirmed_at IS NOT NULL AND NEW.warning_confirmed_at IS DISTINCT FROM OLD.warning_confirmed_at))
 THEN RAISE EXCEPTION 'APPROVAL_IMMUTABLE'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_approval_guard BEFORE INSERT OR UPDATE OR DELETE ON failover_approval
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_approval_guard();

INSERT INTO rbac_roles(id,name,token_string,description,is_system)
 VALUES(gen_random_uuid(),'Operation Admin','role:operation_admin',
 'Failover initiation and independent second approval; security_admin maps to ADMIN_SINGLE',true);

CREATE FUNCTION ui2_failover_run_approval_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.approval_id,NEW.request_revision,NEW.requested_by,NEW.scheduled_for)
    IS DISTINCT FROM ROW(OLD.approval_id,OLD.request_revision,OLD.requested_by,OLD.scheduled_for)
 THEN RAISE EXCEPTION 'FAILOVER_AUTHORIZATION_IMMUTABLE'; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_run_approval_guard BEFORE UPDATE ON failover_run
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_run_approval_guard();

-- A membership change invalidates approval even if the previous pair is restored later.
CREATE FUNCTION ui2_failover_membership_approval_invalidate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.cluster_member_ref,NEW.vendor_hint,NEW.role,NEW.disabled,NEW.enrollment_state,NEW.discovery_match_key,NEW.recorded_identity_primary)
    IS NOT DISTINCT FROM ROW(OLD.cluster_member_ref,OLD.vendor_hint,OLD.role,OLD.disabled,OLD.enrollment_state,OLD.discovery_match_key,OLD.recorded_identity_primary)
 THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock(136, 1);
 UPDATE failover_approval SET revoked_at=clock_timestamp(),revoked_by='system:membership-change'
  WHERE request_revision IS NOT NULL AND revoked_at IS NULL
   AND (cluster_ref=NEW.cluster_member_ref OR cluster_ref=OLD.cluster_member_ref
        OR target_member_ids @> jsonb_build_array(NEW.device_id)
        OR target_member_ids @> jsonb_build_array(OLD.device_id));
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_membership_approval_invalidate AFTER INSERT OR DELETE OR UPDATE OF
 cluster_member_ref,vendor_hint,role,disabled,enrollment_state,discovery_match_key,recorded_identity_primary ON devices
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_membership_approval_invalidate();

-- Discovery also supplies the effective unit/member mapping. Any change to that
-- mapping invalidates outstanding requests conservatively, including an ABA change.
CREATE FUNCTION ui2_failover_discovery_approval_invalidate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='UPDATE' AND ROW(NEW.vendor,NEW.owning_domain,NEW.stable_identifier,NEW.parent_candidate_id,
      NEW.cluster_reference,NEW.display_name) IS NOT DISTINCT FROM
      ROW(OLD.vendor,OLD.owning_domain,OLD.stable_identifier,OLD.parent_candidate_id,
      OLD.cluster_reference,OLD.display_name) THEN RETURN NEW; END IF;
 PERFORM pg_advisory_xact_lock(136, 1);
 UPDATE failover_approval SET revoked_at=clock_timestamp(),revoked_by='system:membership-change'
  WHERE request_revision IS NOT NULL AND revoked_at IS NULL;
 IF TG_OP='DELETE' THEN RETURN OLD; END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER failover_discovery_approval_invalidate AFTER INSERT OR DELETE OR UPDATE OF
 vendor,owning_domain,stable_identifier,parent_candidate_id,cluster_reference,display_name ON discovery_candidate
 FOR EACH ROW EXECUTE FUNCTION ui2_failover_discovery_approval_invalidate();
