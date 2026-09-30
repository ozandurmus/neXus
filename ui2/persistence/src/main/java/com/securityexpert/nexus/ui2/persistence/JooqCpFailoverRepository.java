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
            String failedCheck, String message, String vendor, String kind) {
        public Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
                Instant scheduledFor, String jobId, String state, String step, String outcome,
                String failedCheck, String message) {
            this(id, clusterRef, vsId, approvalId, requestedBy, scheduledFor, jobId, state, step,
                outcome, failedCheck, message, "check_point", "FAILOVER");
        }
    }
    public record Check(String phase, String memberRef, String vsId, int checkNo, String status,
            String derived, Instant observedAt) {}
    public record Detail(Run run, List<Check> checks) {}
    public record Decision(String code, String runId) {}
    public record SummaryMember(String deviceId, boolean readable, String transportKind,
            boolean inventoryPresent, String context, boolean panSupported) {}
    public record SummaryStatus(String clusterRef, String vsId, String vendor, boolean activeWindow,
            String state, String outcome, Instant scheduledFor) {}
    public record ReadinessStatus(String clusterRef, String vsId, String vendor, String outcome,
            Instant observedAt, String failedCheck, String checks) {}

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
            r.get("failed_check", String.class), r.get("message", String.class), r.get("vendor", String.class),
            r.get("run_kind", String.class));
    }

    /** Bulk eligibility facts; no device addresses or raw observations leave this projection. */
    public List<SummaryMember> summaryMembers() {
        return boundary.inTransaction(dsl -> dsl.fetch("select d.device_id, "
            + "(not d.disabled and d.enrollment_state in ('ENROLLED','DEGRADED')) as readable, "
            + "ep.transport_kind, r.run_id is not null as inventory_present, ctx.context, "
            + "exists(select 1 from device_inventory_ha h where h.run_id=r.run_id "
            + "and h.source='pan_high_availability_state' and lower(h.cluster_mode)='active-passive') as pan_supported "
            + "from devices d left join lateral "
            + "(select transport_kind from endpoints where device_id=d.device_id order by created_at limit 1) ep on true "
            + "left join lateral (select run_id from device_inventory_run where device_id=d.device_id "
            + "order by collected_at desc limit 1) r on true "
            + "left join lateral (select distinct context from "
            + "(select context from device_interface where run_id=r.run_id union all "
            + "select context from device_route where run_id=r.run_id) contexts) ctx on true")
            .map(r -> new SummaryMember(r.get("device_id", String.class),
                Boolean.TRUE.equals(r.get("readable", Boolean.class)), r.get("transport_kind", String.class),
                Boolean.TRUE.equals(r.get("inventory_present", Boolean.class)), r.get("context", String.class),
                Boolean.TRUE.equals(r.get("pan_supported", Boolean.class)))));
    }

    /** Latest run and currently valid window per vendor/cluster/VSID in one read. */
    public List<SummaryStatus> summaryStatuses() {
        return boundary.inTransaction(dsl -> dsl.fetch("with unit_keys as "
            + "(select vendor,cluster_ref,vs_id from failover_approval union "
            + "select vendor,cluster_ref,vs_id from failover_run) "
            + "select k.vendor,k.cluster_ref,k.vs_id, "
            + "exists(select 1 from failover_approval a where a.vendor=k.vendor and a.cluster_ref=k.cluster_ref "
            + "and a.vs_id is not distinct from k.vs_id and a.revoked_at is null "
            + "and a.window_from<=now() and now()<a.window_until) as active_window, "
            + "r.state,r.outcome,r.scheduled_for from unit_keys k left join lateral "
            + "(select state,outcome,scheduled_for from failover_run r where r.vendor=k.vendor "
            + "and r.cluster_ref=k.cluster_ref and r.vs_id is not distinct from k.vs_id "
            + "order by scheduled_for desc limit 1) r on true")
            .map(r -> new SummaryStatus(r.get("cluster_ref", String.class), r.get("vs_id", String.class),
                r.get("vendor", String.class), Boolean.TRUE.equals(r.get("active_window", Boolean.class)),
                r.get("state", String.class), r.get("outcome", String.class),
                Optional.ofNullable(r.get("scheduled_for", java.time.OffsetDateTime.class))
                    .map(java.time.OffsetDateTime::toInstant).orElse(null))));
    }

    public List<ReadinessStatus> readinessStatuses() {
        return boundary.inTransaction(dsl -> dsl.fetch("select r.cluster_ref,r.vs_id,r.vendor,r.outcome,"
            + "r.finished_at,r.failed_check,coalesce((select jsonb_agg(jsonb_build_object("
            + "'checkNo',c.check_no,'status',c.status,'derived',c.derived) order by c.check_no,c.member_ref) "
            + "from failover_check_result c where c.run_id=r.run_id and c.phase='pre'),'[]'::jsonb) checks "
            + "from failover_run r where r.run_kind='READINESS' and r.run_id in (select distinct on "
            + "(vendor,cluster_ref,coalesce(vs_id,'')) run_id from failover_run where run_kind='READINESS' "
            + "and state in ('DONE','STOPPED') order by vendor,cluster_ref,coalesce(vs_id,''),finished_at desc))")
            .map(r -> new ReadinessStatus(r.get("cluster_ref", String.class), r.get("vs_id", String.class),
                r.get("vendor", String.class), r.get("outcome", String.class),
                Optional.ofNullable(r.get("finished_at", java.time.OffsetDateTime.class))
                    .map(java.time.OffsetDateTime::toInstant).orElse(null), r.get("failed_check", String.class),
                r.get("checks", JSONB.class).data())));
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

    /** Admission without a failover approval; shares the active-unit uniqueness fence. */
    public Decision requestReadiness(String cluster, String vsId, String actor, String targetDeviceId,
            String vendor) {
        return audited.inTransaction(actor, "failover_readiness_request", dsl -> {
            Record active = dsl.fetchOne("select run_id from failover_run where cluster_ref={0} "
                + "and vs_id is not distinct from {1} and vendor={2} and state not in ('DONE','STOPPED') limit 1",
                cluster, vsId, vendor);
            if (active != null) return new Decision("RUN_ALREADY_ACTIVE", null);
            String id = UUID.randomUUID().toString();
            dsl.execute("insert into failover_run(run_id,cluster_ref,vs_id,approval_id,requested_by,scheduled_for,state,step,vendor,run_kind) "
                + "values({0},{1},{2},null,{3},now(),'PLANNED','PLANNED',{4},'READINESS')", id, cluster, vsId, actor, vendor);
            admit(dsl, id, targetDeviceId, actor, vendor, "READINESS");
            return new Decision("ADMITTED", id);
        });
    }

    private static void admit(org.jooq.DSLContext dsl, String runId, String targetDeviceId, String actor, String vendor) {
        admit(dsl, runId, targetDeviceId, actor, vendor, "FAILOVER");
    }
    private static void admit(org.jooq.DSLContext dsl, String runId, String targetDeviceId, String actor,
            String vendor, String runKind) {
        String jobId = UUID.randomUUID().toString();
        String kind = "palo_alto".equals(vendor)
            ? ("READINESS".equals(runKind) ? "pan_failover_readiness" : "pan_cluster_failover")
            : ("READINESS".equals(runKind) ? "cp_failover_readiness" : "cp_cluster_failover");
        String actionClass = "READINESS".equals(runKind) ? "read" : "operational-state-change";
        dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
            + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) "
            + "values({0},{1},{1},{2},'device',{3},{4},{5},'REQUESTED')", jobId, kind, targetDeviceId, actor,
            kind + ":" + runId, actionClass);
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
    public boolean finished(String runId) {
        return boundary.inTransaction(dsl -> Boolean.TRUE.equals(dsl.fetchOne(
            "select state in ('DONE','STOPPED') as finished from failover_run where run_id={0}", runId)
            .get("finished", Boolean.class)));
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
