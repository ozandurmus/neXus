package com.securityexpert.nexus.ui2.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jooq.Record;
import org.jooq.JSONB;

/** Audited storage and atomic job admission for one Check Point failover unit. */
public class JooqCpFailoverRepository {
    public record Approval(String id, String clusterRef, String vsId, Instant from, Instant until,
            String reason, String approvedBy, Instant revokedAt) {}
    public record Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
            Instant scheduledFor, String jobId, String state, String step, String outcome,
            String failedCheck, String message, String vendor) {
        public Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
                Instant scheduledFor, String jobId, String state, String step, String outcome,
                String failedCheck, String message) {
            this(id, clusterRef, vsId, approvalId, requestedBy, scheduledFor, jobId, state, step,
                outcome, failedCheck, message, "check_point");
        }
    }
    public record Check(String phase, String memberRef, String vsId, int checkNo, String status,
            String derived, Instant observedAt) {}
    public record Detail(Run run, List<Check> checks) {}
    public record Decision(String code, String runId) {}

    private final TransactionBoundary boundary;
    private final AuditedTransactionBoundary audited;

    public JooqCpFailoverRepository(TransactionBoundary boundary) {
        this.boundary = boundary;
        this.audited = new AuditedTransactionBoundary(boundary);
    }

    private static Approval approval(Record r) {
        return new Approval(r.get("approval_id", String.class), r.get("cluster_ref", String.class),
            r.get("vs_id", String.class), r.get("window_from", java.time.OffsetDateTime.class).toInstant(),
            r.get("window_until", java.time.OffsetDateTime.class).toInstant(), r.get("reason", String.class),
            r.get("approved_by", String.class),
            Optional.ofNullable(r.get("revoked_at", java.time.OffsetDateTime.class)).map(java.time.OffsetDateTime::toInstant).orElse(null));
    }
    private static Run run(Record r) {
        return new Run(r.get("run_id", String.class), r.get("cluster_ref", String.class), r.get("vs_id", String.class),
            r.get("approval_id", String.class), r.get("requested_by", String.class),
            r.get("scheduled_for", java.time.OffsetDateTime.class).toInstant(), r.get("job_id", String.class),
            r.get("state", String.class), r.get("step", String.class), r.get("outcome", String.class),
            r.get("failed_check", String.class), r.get("message", String.class), r.get("vendor", String.class));
    }

    public Approval createApproval(String cluster, String vsId, Instant from, Instant until, String reason, String actor) {
        return createApproval(cluster,vsId,from,until,reason,actor,"check_point");
    }
    public Approval createApproval(String cluster, String vsId, Instant from, Instant until, String reason, String actor,
            String vendor) {
        String id = UUID.randomUUID().toString();
        return audited.inTransaction(actor, "failover_approval_create", dsl -> approval(dsl.fetchOne(
            "insert into failover_approval(approval_id,cluster_ref,vs_id,window_from,window_until,reason,approved_by,vendor) "
            + "values({0},{1},{2},{3},{4},{5},{6},{7}) returning *", id, cluster, vsId,
            Timestamp.from(from), Timestamp.from(until), reason, actor, vendor)));
    }

    public List<Approval> approvals(String cluster, String vsId) {
        return approvals(cluster,vsId,"check_point");
    }
    public List<Approval> approvals(String cluster, String vsId, String vendor) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_approval where cluster_ref={0} "
            + "and vs_id is not distinct from {1} and vendor={2} order by window_from desc", cluster, vsId, vendor)
            .map(JooqCpFailoverRepository::approval));
    }

    public boolean revoke(String id, String actor) {
        return audited.inTransaction(actor, "failover_approval_revoke", dsl -> dsl.execute(
            "update failover_approval set revoked_at=now(),revoked_by={1} "
            + "where approval_id={0} and revoked_at is null", id, actor) == 1);
    }

    /** The active-unit unique index and the selected approval are checked in the same transaction. */
    public Decision request(String cluster, String vsId, Instant scheduledFor, String actor, String targetDeviceId,
            boolean immediate) {
        return request(cluster,vsId,scheduledFor,actor,targetDeviceId,immediate,"check_point");
    }
    public Decision request(String cluster, String vsId, Instant scheduledFor, String actor, String targetDeviceId,
            boolean immediate, String vendor) {
        return audited.inTransaction(actor, "failover_run_request", dsl -> {
            Record a = dsl.fetchOne("select * from failover_approval where cluster_ref={0} "
                + "and vs_id is not distinct from {1} and revoked_at is null "
                + "and window_from <= {2} and window_until > {2} and vendor={3} "
                + "order by approved_at desc limit 1 for share", cluster, vsId, Timestamp.from(scheduledFor), vendor);
            if (a == null) return new Decision("NO_PRE_APPROVAL", null);
            Record active = dsl.fetchOne("select run_id from failover_run where cluster_ref={0} "
                + "and vs_id is not distinct from {1} and vendor={2} and state not in ('DONE','STOPPED') limit 1",
                cluster, vsId, vendor);
            if (active != null) return new Decision("RUN_ALREADY_ACTIVE", null);
            String id = UUID.randomUUID().toString();
            dsl.execute("insert into failover_run(run_id,cluster_ref,vs_id,approval_id,requested_by,scheduled_for,state,step,vendor) "
                + "values({0},{1},{2},{3},{4},{5},'PLANNED','PLANNED',{6})", id, cluster, vsId,
                a.get("approval_id", String.class), actor, Timestamp.from(scheduledFor), vendor);
            if (immediate) admit(dsl, id, targetDeviceId, actor, vendor);
            return new Decision("ADMITTED", id);
        });
    }

    private static void admit(org.jooq.DSLContext dsl, String runId, String targetDeviceId, String actor, String vendor) {
        String jobId = UUID.randomUUID().toString();
        String kind = "palo_alto".equals(vendor) ? "pan_cluster_failover" : "cp_cluster_failover";
        dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
            + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) "
            + "values({0},{1},{1},{2},'device',{3},{4},"
            + "'operational-state-change','REQUESTED')", jobId, kind, targetDeviceId, actor, kind + ":" + runId);
        dsl.execute("update failover_run set job_id={1},started_at=now(),step='QUEUED' where run_id={0}", runId, jobId);
    }

    public List<Run> due() {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_run where state='PLANNED' "
            + "and job_id is null and scheduled_for <= now() order by scheduled_for limit 50")
            .map(JooqCpFailoverRepository::run));
    }

    public String startDue(String id, String targetDeviceId) {
        return audited.inTransaction("system:failover-scheduler", "failover_schedule_start", dsl -> {
            Record r = dsl.fetchOne("select r.*,a.window_until,a.revoked_at from failover_run r "
                + "join failover_approval a on a.approval_id=r.approval_id where r.run_id={0} for update of r", id);
            if (r == null || !"PLANNED".equals(r.get("state", String.class)) || r.get("job_id") != null) return "ALREADY_CLAIMED";
            if (r.get("revoked_at") != null || !r.get("window_until", java.time.OffsetDateTime.class).toInstant().isAfter(Instant.now())) {
                dsl.execute("update failover_run set state='STOPPED',step='PLANNED',outcome='WINDOW_EXPIRED',"
                    + "message='WINDOW_EXPIRED',finished_at=now() where run_id={0}", id);
                return "WINDOW_EXPIRED";
            }
            admit(dsl, id, targetDeviceId, r.get("requested_by", String.class), r.get("vendor", String.class));
            return "ADMITTED";
        });
    }

    public void stopPlanned(String id, String reason) {
        audited.inTransaction("system:failover-scheduler", "failover_schedule_stop", dsl -> dsl.execute(
            "update failover_run set state='STOPPED',step='PLANNED',outcome={1},message={1},finished_at=now() "
            + "where run_id={0} and state='PLANNED' and job_id is null", id, reason));
    }

    public Optional<Run> runByJob(String jobId) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_run where job_id={0}", jobId)
            .stream().findFirst().map(JooqCpFailoverRepository::run));
    }
    public boolean windowValid(String runId) {
        return boundary.inTransaction(dsl -> Boolean.TRUE.equals(dsl.fetchOne(
            "select exists(select 1 from failover_run r join failover_approval a "
            + "on a.approval_id=r.approval_id where r.run_id={0} and a.revoked_at is null "
            + "and a.window_from <= now() and a.window_until > now()) as valid", runId).get("valid",Boolean.class)));
    }
    public Optional<Detail> detail(String id) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_run where run_id={0}", id)
            .stream().findFirst().map(r -> new Detail(run(r), dsl.fetch(
                "select phase,member_ref,vs_id,check_no,status,derived,observed_at from failover_check_result "
                + "where run_id={0} order by observed_at,result_id", id).map(c -> new Check(
                    c.get("phase", String.class), c.get("member_ref", String.class), c.get("vs_id", String.class),
                    c.get("check_no", Integer.class), c.get("status", String.class),
                    c.get("derived", JSONB.class).data(), c.get("observed_at", java.time.OffsetDateTime.class).toInstant())))));
    }
    public List<Run> runs(String cluster, String vsId) {
        return runs(cluster,vsId,"check_point");
    }
    public List<Run> runs(String cluster, String vsId, String vendor) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_run where cluster_ref={0} "
            + "and vs_id is not distinct from {1} and vendor={2} order by scheduled_for desc limit 50", cluster, vsId, vendor)
            .map(JooqCpFailoverRepository::run));
    }
    public void state(String id, String state, String step, String outcome, String failedCheck, String message) {
        audited.inTransaction("system:failover-worker", "failover_state_change", id, dsl -> dsl.execute(
            "update failover_run set state={1},step={2},outcome={3},failed_check={4},message={5},"
            + "finished_at=case when {1} in ('DONE','STOPPED') then now() else null end where run_id={0}",
            id, state, step, outcome, failedCheck, message));
    }
    /** An audited pre-contact state change is the command ledger entry. */
    public void command(String id, String gateId) {
        audited.inTransaction("system:failover-worker", "failover_command_" + gateId, id,
            dsl -> dsl.execute("update failover_run set message={1} where run_id={0}", id, gateId));
    }
    public void check(String id, String phase, String member, String vsId, int no, String status, String derived) {
        audited.inTransaction("system:failover-worker", "failover_check", id, dsl -> dsl.execute(
            "insert into failover_check_result(run_id,phase,member_ref,vs_id,check_no,status,derived) "
            + "values({0},{1},{2},{3},{4},{5},{6}::jsonb)", id, phase, member, vsId, no, status, derived));
    }
}
