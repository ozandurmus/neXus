package com.securityexpert.nexus.ui2.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.Set;
import org.jooq.DSLContext;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;

import org.jooq.Record;
import org.jooq.JSONB;

/** Audited storage and atomic job admission for one Check Point failover unit. */
public class JooqCpFailoverRepository {
    public record Approval(String id, String clusterRef, String vsId, Instant from, Instant until,
            String reason, String approvedBy, Instant revokedAt) {}
    public static final int APPROVAL_POLICY_VERSION = 1;
    public static final String ADMIN_SINGLE = "ADMIN_SINGLE";
    public static final String TWO_PERSON = "OPERATION_ADMIN_TWO_PERSON";
    public record RequestApproval(Approval approval, long revision, String unitRef,
            String memberSetRevision, String policy, int policyVersion, String initiatedBy,
            String executionNonce) {}

    /** Opaque content revision, framed to avoid ambiguous member identifier concatenation. */
    public static String memberSetRevision(Set<String> members) {
        String framed=members.stream().sorted().map(m -> m.length()+":"+m).collect(java.util.stream.Collectors.joining());
        return UUID.nameUUIDFromBytes(framed.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private static RequestApproval requestApproval(Record r) {
        return new RequestApproval(approval(r),r.get("request_revision",Long.class),r.get("unit_ref",String.class),
            r.get("member_set_revision",String.class),r.get("policy",String.class),r.get("policy_version",Integer.class),
            r.get("initiated_by",String.class),r.get("execution_nonce",String.class));
    }
    public record Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
            Instant scheduledFor, String jobId, String state, String step, String outcome,
            String failedCheck, String message, String vendor, String kind) {
        public Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
                Instant scheduledFor, String jobId, String state, String step, String outcome,
                String failedCheck, String message) {
            this(id, clusterRef, vsId, approvalId, requestedBy, scheduledFor, jobId, state, step,
                outcome, failedCheck, message, "check_point", "FAILOVER");
        }
        public Run(String id, String clusterRef, String vsId, String approvalId, String requestedBy,
                Instant scheduledFor, String jobId, String state, String step, String outcome,
                String failedCheck, String message, String vendor) {
            this(id, clusterRef, vsId, approvalId, requestedBy, scheduledFor, jobId, state, step,
                outcome, failedCheck, message, vendor, "FAILOVER");
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
            Instant observedAt, String failedCheck, String checks, String stopCode) {
        public ReadinessStatus(String clusterRef,String vsId,String vendor,String outcome,
                Instant observedAt,String failedCheck,String checks) {
            this(clusterRef,vsId,vendor,outcome,observedAt,failedCheck,checks,null);
        }
    }

    private final boolean mutationEnabled;
    private final TransactionBoundary boundary;
    private final AuditedTransactionBoundary audited;

    public JooqCpFailoverRepository(TransactionBoundary boundary) {
        this(boundary, "true".equalsIgnoreCase(System.getenv("NEXUS_FAILOVER_MUTATION_ENABLED")));
    }

    public JooqCpFailoverRepository(TransactionBoundary boundary, boolean mutationEnabled) {
        this.mutationEnabled = mutationEnabled;
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
            + "and a.request_revision is not null and a.approved_by is not null "
            + "and a.policy_version=" + APPROVAL_POLICY_VERSION + " "
            + "and not exists(select 1 from failover_run used where used.approval_id=a.approval_id) "
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
        return boundary.inTransaction(dsl -> dsl.fetch("""
            with latest as (
                select distinct on (vendor,cluster_ref,coalesce(vs_id,''))
                    run_id,cluster_ref,vs_id,vendor,outcome,finished_at,failed_check,message
                from failover_run where run_kind='READINESS' and state in ('DONE','STOPPED')
                order by vendor,cluster_ref,coalesce(vs_id,''),finished_at desc
            ), checks as (
                select c.run_id,jsonb_agg(jsonb_build_object(
                    'memberRef',c.member_ref,'observedAt',c.observed_at,'checkNo',c.check_no,
                    'status',c.status,'derived',c.derived) order by c.check_no,c.member_ref) as checks
                from failover_check_result c join latest l on l.run_id=c.run_id
                where c.phase='pre' group by c.run_id
            )
            select r.cluster_ref,r.vs_id,r.vendor,r.outcome,r.finished_at,r.failed_check,r.message,
                coalesce(c.checks,'[]'::jsonb) as checks
            from latest r left join checks c on c.run_id=r.run_id
            """)
            .map(r -> new ReadinessStatus(r.get("cluster_ref", String.class), r.get("vs_id", String.class),
                r.get("vendor", String.class), r.get("outcome", String.class),
                Optional.ofNullable(r.get("finished_at", java.time.OffsetDateTime.class))
                    .map(java.time.OffsetDateTime::toInstant).orElse(null), r.get("failed_check", String.class),
                r.get("checks", JSONB.class).data(),r.get("message", String.class))));
    }

    /** Last stored readiness counters; never borrow another member, unit or VS baseline. */
    public Optional<Check> previousReadinessSync(String currentRun,String member,String vsId) {
        return boundary.inTransaction(dsl -> dsl.fetch(
            "select c.* from failover_check_result c join failover_run r on r.run_id=c.run_id "
            + "join failover_run current on current.run_id={0} where r.run_id<>current.run_id "
            + "and r.vendor=current.vendor and r.cluster_ref=current.cluster_ref "
            + "and r.vs_id is not distinct from current.vs_id and r.run_kind='READINESS' "
            + "and r.state in ('DONE','STOPPED') and r.finished_at<=current.started_at "
            + "and c.member_ref={1} and c.vs_id is not distinct from {2} and c.check_no=9 and c.phase='pre' "
            + "and jsonb_typeof(c.derived->'lostUpdates')='number' "
            + "and jsonb_typeof(c.derived->'lostBulkUpdateEvents')='number' "
            + "order by c.observed_at desc,c.result_id desc limit 1",currentRun,member,vsId)
            .stream().findFirst().map(c -> new Check(c.get("phase",String.class),c.get("member_ref",String.class),
                c.get("vs_id",String.class),c.get("check_no",Integer.class),c.get("status",String.class),
                c.get("derived",JSONB.class).data(),c.get("observed_at",java.time.OffsetDateTime.class).toInstant())));
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

    public Optional<RequestApproval> requestApproval(String id) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_approval where approval_id={0} "
            + "and request_revision is not null",id).stream().findFirst().map(JooqCpFailoverRepository::requestApproval));
    }

    public RequestApproval createRequestApproval(String id,String cluster,String vsId,String unitRef,
            Set<String> members,Instant from,Instant until,String reason,String actor,String vendor,String policy) {
        if (!Set.of(ADMIN_SINGLE,TWO_PERSON).contains(policy)) throw new IllegalArgumentException("INVALID_POLICY");
        return audited.inTransaction(actor,"failover_request_create",dsl -> {
            lockAdmission(dsl);
            List<String> current=JooqDeviceRepository.enrolledFailoverMembers(dsl,cluster,vendor);
            if (current.size()!=2 || !Set.copyOf(current).equals(members)) throw new IllegalArgumentException("MEMBER_SET_CHANGED");
            String revision=memberSetRevision(members);
            Record existing=dsl.fetchOne("select * from failover_approval where approval_id={0}",id);
            if (existing!=null) {
                if (!java.util.Objects.equals(existing.get("request_revision",Long.class),1L)
                        || !cluster.equals(existing.get("cluster_ref",String.class))
                        || !java.util.Objects.equals(vsId,existing.get("vs_id",String.class))
                        || !unitRef.equals(existing.get("unit_ref",String.class))
                        || !revision.equals(existing.get("member_set_revision",String.class))
                        || !policy.equals(existing.get("policy",String.class))
                        || !Integer.valueOf(APPROVAL_POLICY_VERSION).equals(existing.get("policy_version",Integer.class))
                        || !actor.equals(existing.get("initiated_by",String.class))
                        || !vendor.equals(existing.get("vendor",String.class))
                        || !from.equals(existing.get("window_from",java.time.OffsetDateTime.class).toInstant())
                        || !until.equals(existing.get("window_until",java.time.OffsetDateTime.class).toInstant())
                        || !reason.equals(existing.get("reason",String.class))) throw new IllegalArgumentException("REQUEST_CHANGED");
                return requestApproval(existing);
            }
            return requestApproval(dsl.fetchOne("insert into failover_approval(approval_id,cluster_ref,vs_id,"
                + "window_from,window_until,reason,approved_by,approved_at,vendor,request_revision,unit_ref,"
                + "member_set_revision,target_member_ids,operation,policy,policy_version,initiated_by,execution_nonce) "
                + "values({0},{1},{2},{3},{4},{5},{6},case when {6}::text is null then null else clock_timestamp() end,"
                + "{7},1,{8},{9},jsonb_build_array({10}::text,{11}::text),'FAILOVER',{12},{13},{14},{15}) returning *",
                id,cluster,vsId,Timestamp.from(from),Timestamp.from(until),reason,ADMIN_SINGLE.equals(policy)?actor:null,
                vendor,unitRef,revision,current.get(0),current.get(1),policy,APPROVAL_POLICY_VERSION,actor,UUID.randomUUID().toString()));
        });
    }

    public String approveRequest(String id,long revision,String actor,String vendor,Set<String> members) {
        return audited.inTransaction(actor,"failover_request_second_approval",dsl -> {
            lockAdmission(dsl);
            Record a=dsl.fetchOne("select * from failover_approval where approval_id={0} for update",id);
            if (a==null || !vendor.equals(a.get("vendor",String.class))
                    || !Long.valueOf(revision).equals(a.get("request_revision",Long.class))) return "REQUEST_CHANGED";
            if (!TWO_PERSON.equals(a.get("policy",String.class))) return "WRONG_POLICY";
            if (actor.equals(a.get("initiated_by",String.class))) return "SELF_APPROVAL";
            if (!memberSetRevision(members).equals(a.get("member_set_revision",String.class))
                    || !Set.copyOf(JooqDeviceRepository.enrolledFailoverMembers(dsl,a.get("cluster_ref",String.class),vendor))
                        .equals(members)) return "MEMBER_SET_CHANGED";
            String refusal=approvalRefusal(dsl,a,false);
            if (refusal!=null) return refusal;
            if (a.get("approved_by")!=null) return actor.equals(a.get("approved_by"))?"APPROVED":"ALREADY_APPROVED";
            dsl.execute("update failover_approval set approved_by={1},approved_at=clock_timestamp() where approval_id={0}",id,actor);
            return "APPROVED";
        });
    }

    private static String approvalRefusal(DSLContext dsl,Record a,boolean requireApproval) {
        if (a==null || a.get("request_revision")==null) return "REQUEST_BINDING_REQUIRED";
        if (!Integer.valueOf(APPROVAL_POLICY_VERSION).equals(a.get("policy_version",Integer.class))) return "POLICY_CHANGED";
        if (a.get("revoked_at")!=null) return "APPROVAL_REVOKED";
        // PostgreSQL now() is transaction-start time and is stale after waiting for a lock.
        Instant now=dsl.fetchOne("select clock_timestamp() as checked_at").get("checked_at",java.time.OffsetDateTime.class).toInstant();
        if (!a.get("window_until",java.time.OffsetDateTime.class).toInstant().isAfter(now)) return "WINDOW_EXPIRED";
        if (requireApproval && a.get("approved_by")==null) return "SECOND_APPROVAL_REQUIRED";
        return null;
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
        return audited.inTransaction(actor, "failover_approval_revoke", dsl -> {
            lockAdmission(dsl);
            return dsl.execute("update failover_approval set revoked_at=clock_timestamp(),revoked_by={1} "
                + "where approval_id={0} and revoked_at is null", id, actor) == 1;
        });
    }

    /** The active-unit unique index and the selected approval are checked in the same transaction. */
    public Decision request(String cluster, String vsId, Instant scheduledFor, String actor, String targetDeviceId,
            boolean immediate) {
        return request(cluster,vsId,scheduledFor,actor,targetDeviceId,immediate,"check_point");
    }
    public Decision request(String cluster, String vsId, Instant scheduledFor, String actor, String targetDeviceId,
            boolean immediate, String vendor) {
        return requestBound(cluster, vsId, scheduledFor, actor, targetDeviceId, immediate, vendor, null);
    }

    public Decision requestBound(String cluster, String vsId, Instant scheduledFor, String actor, String targetDeviceId,
            boolean immediate, String vendor, Set<String> expectedMembers) {
        if (!mutationEnabled) return new Decision("FAILOVER_MUTATION_DISABLED", null);
        return new Decision("REQUEST_BINDING_REQUIRED",null);
    }

    public Decision requestBound(String cluster,String vsId,Instant scheduledFor,String actor,String targetDeviceId,
            boolean immediate,String vendor,Set<String> expectedMembers,String requestId,long revision,
            String nonce,boolean warningConfirmed,String policy) {
        if (!mutationEnabled) return new Decision("FAILOVER_MUTATION_DISABLED",null);
        Set<String> binding=Set.copyOf(expectedMembers);
        return audited.inTransaction(actor,"failover_run_request",dsl -> {
            lockAdmission(dsl);
            Record a=dsl.fetchOne("select * from failover_approval where approval_id={0} for update",requestId);
            if (a==null || !cluster.equals(a.get("cluster_ref",String.class))
                    || !java.util.Objects.equals(vsId,a.get("vs_id",String.class))
                    || !vendor.equals(a.get("vendor",String.class))
                    || !Long.valueOf(revision).equals(a.get("request_revision",Long.class))) return new Decision("REQUEST_CHANGED",null);
            if (!actor.equals(a.get("initiated_by",String.class))) return new Decision("WRONG_INITIATOR",null);
            if (!java.util.Objects.equals(nonce,a.get("execution_nonce",String.class))) return new Decision("NONCE_MISMATCH",null);
            if (!warningConfirmed) return new Decision("WARNING_CONFIRMATION_REQUIRED",null);
            if (!policy.equals(a.get("policy",String.class))) return new Decision("POLICY_CHANGED",null);
            if (!memberSetRevision(binding).equals(a.get("member_set_revision",String.class))) return new Decision("MEMBER_SET_CHANGED",null);
            Record consumed=dsl.fetchOne("select run_id from failover_run where approval_id={0}",requestId);
            if (consumed!=null) return new Decision("ADMITTED",consumed.get("run_id",String.class));
            String approvalRefusal=approvalRefusal(dsl,a,true);
            if (approvalRefusal!=null) return new Decision(approvalRefusal,null);
            if (scheduledFor.isBefore(a.get("window_from",java.time.OffsetDateTime.class).toInstant())
                    || !scheduledFor.isBefore(a.get("window_until",java.time.OffsetDateTime.class).toInstant()))
                return new Decision("OUTSIDE_WINDOW",null);
            Record active = dsl.fetchOne("select run_id from failover_run where cluster_ref={0} "
                + "and vs_id is not distinct from {1} and vendor={2} and state not in ('DONE','STOPPED') limit 1",
                cluster, vsId, vendor);
            if (active != null) return new Decision("RUN_ALREADY_ACTIVE", null);
            List<String> members = JooqDeviceRepository.enrolledFailoverMembers(dsl, cluster, vendor);
            if (members.size()!=2 || !members.contains(targetDeviceId)) return new Decision("CLUSTER_NOT_ELIGIBLE", null);
            if (!Set.copyOf(members).equals(binding))
                return new Decision("MEMBER_SET_CHANGED", null);
            String refusal = admissionRefusal(dsl, cluster, members, null);
            if (refusal != null) return new Decision(refusal, null);
            dsl.execute("update failover_approval set warning_confirmed_at=clock_timestamp() where approval_id={0}",requestId);
            String id = UUID.randomUUID().toString();
            dsl.execute("insert into failover_run(run_id,cluster_ref,vs_id,approval_id,requested_by,scheduled_for,state,step,vendor,target_member_ids,request_revision) "
                + "values({0},{1},{2},{3},{4},{5},'PLANNED','PLANNED',{6},jsonb_build_array({7}::text,{8}::text),1)", id, cluster, vsId,
                a.get("approval_id", String.class), actor, Timestamp.from(scheduledFor), vendor, members.get(0), members.get(1));
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
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        return audited.inTransaction("system:failover-scheduler", "failover_schedule_start", dsl -> {
            lockAdmission(dsl);
            Record r = dsl.fetchOne("select r.*,a.window_until,a.revoked_at from failover_run r "
                + "join failover_approval a on a.approval_id=r.approval_id where r.run_id={0} for update of r", id);
            if (r == null || !"PLANNED".equals(r.get("state", String.class)) || r.get("job_id") != null) return "ALREADY_CLAIMED";
            if (r.get("revoked_at") != null || !r.get("window_until", java.time.OffsetDateTime.class).toInstant().isAfter(Instant.now())) {
                dsl.execute("update failover_run set state='STOPPED',step='PLANNED',outcome='WINDOW_EXPIRED',"
                    + "message='WINDOW_EXPIRED',finished_at=now() where run_id={0}", id);
                return "WINDOW_EXPIRED";
            }
            Record approval=dsl.fetchOne("select * from failover_approval where approval_id={0} for update",r.get("approval_id"));
            String authority=approvalRefusal(dsl,approval,true);
            if (authority!=null || approval.get("warning_confirmed_at")==null) {
                String code=authority==null?"REQUEST_BINDING_REQUIRED":authority;
                dsl.execute("update failover_run set state='STOPPED',outcome={1},message={1},finished_at=clock_timestamp() "
                    + "where run_id={0}",id,code);
                return code;
            }
            List<String> members = JooqDeviceRepository.enrolledFailoverMembers(dsl, r.get("cluster_ref", String.class), r.get("vendor", String.class));
            String refusal = validateTarget(dsl, r, members, targetDeviceId);
            if (refusal != null) {
                dsl.execute("update failover_run set state='STOPPED',outcome={1},message={1},finished_at=now() where run_id={0}", id, refusal);
                return refusal;
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
        audited.inTransaction("system:failover-worker", "failover_state_change", id, dsl -> {
            lockAdmission(dsl);
            return dsl.execute(
            "update failover_run set state={1},step={2},outcome={3},failed_check={4},message={5},"
            + "finished_at=case when {1} in ('DONE','STOPPED') then now() else null end where run_id={0}",
            id, state, step, outcome, failedCheck, message);
        });
    }
    private static void lockAdmission(DSLContext dsl) {
        dsl.execute("select pg_advisory_xact_lock(136, 1)");
    }

    private static String admissionRefusal(DSLContext dsl, String cluster, List<String> members, String runId) {
        if (!dsl.fetch("select 1 from failover_dispatch_intent i where run_id is distinct from {0} "
                + "and (observation='NOT_OBSERVED' or (observation='OUTCOME_UNKNOWN' and exists "
                + "(select 1 from failover_quarantine q where q.execution_id=i.run_id and q.active)))", runId).isEmpty())
            return "UNRESOLVED_DISPATCH";
        if (!dsl.fetch("select 1 from failover_quarantine where active and "
                + "(cluster_ref={0} or quarantined_member_ids @> jsonb_build_array({1}::text) "
                + "or quarantined_member_ids @> jsonb_build_array({2}::text))", cluster, members.get(0), members.get(1)).isEmpty())
            return "OPEN_INCIDENT";
        if (!dsl.fetch("select 1 from failover_run where run_kind='FAILOVER' "
                + "and state not in ('DONE','STOPPED') and run_id is distinct from {0}", runId).isEmpty())
            return "FLEET_MUTATION_ACTIVE";
        return null;
    }

    private static String validateTarget(DSLContext dsl, Record r, List<String> members, String target) {
        if (members.size()!=2 || !members.contains(target)) return "CLUSTER_NOT_ELIGIBLE";
        boolean unchanged = Boolean.TRUE.equals(dsl.fetchOne("select target_member_ids = "
            + "jsonb_build_array({1}::text,{2}::text) as unchanged from failover_run where run_id={0}",
            r.get("run_id", String.class), members.get(0), members.get(1)).get("unchanged", Boolean.class));
        if (!unchanged) return "MEMBER_SET_CHANGED";
        return admissionRefusal(dsl, r.get("cluster_ref", String.class), members, r.get("run_id", String.class));
    }

    /** Recheck the immutable request before any contact and again immediately before each write. */
    public String mutationAdmission(String id, String cluster, String vsId, String vendor,
            Set<String> expectedMembers, boolean possibleSend) {
        return mutationAdmission(id,cluster,vsId,vendor,expectedMembers,possibleSend,null,-1);
    }

    public String mutationAdmission(String id,String cluster,String vsId,String vendor,
            Set<String> expectedMembers,boolean possibleSend,String jobId,long epoch) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        return audited.inTransaction("system:failover-worker", "failover_mutation_admission", id, dsl -> {
            lockAdmission(dsl);
            Record r = dsl.fetchOne("select * from failover_run where run_id={0} for update", id);
            if (r==null || !"FAILOVER".equals(r.get("run_kind", String.class))
                    || !cluster.equals(r.get("cluster_ref", String.class))
                    || !java.util.Objects.equals(vsId,r.get("vs_id", String.class))
                    || !vendor.equals(r.get("vendor", String.class))) return "WRONG_UNIT";
            if (Set.of("DONE","STOPPED").contains(r.get("state", String.class))) return "RUN_TERMINAL";
            Record a=dsl.fetchOne("select * from failover_approval where approval_id={0} for update",r.get("approval_id"));
            String authority=approvalRefusal(dsl,a,true);
            if (authority!=null) return authority;
            if (!java.util.Objects.equals(r.get("request_revision",Long.class),a.get("request_revision",Long.class))
                    || !cluster.equals(a.get("cluster_ref",String.class))
                    || !java.util.Objects.equals(vsId,a.get("vs_id",String.class))
                    || !vendor.equals(a.get("vendor",String.class))) return "REQUEST_CHANGED";
            if (a.get("warning_confirmed_at")==null
                    || !r.get("requested_by").equals(a.get("initiated_by"))) return "REQUEST_BINDING_REQUIRED";
            if (!memberSetRevision(expectedMembers).equals(a.get("member_set_revision",String.class))) return "MEMBER_SET_CHANGED";
            if (!Boolean.TRUE.equals(dsl.fetchOne("select window_from<=clock_timestamp() as started "
                    + "from failover_approval where approval_id={0}",r.get("approval_id")).get("started",Boolean.class))) return "OUTSIDE_WINDOW";
            if (jobId==null || !jobId.equals(r.get("job_id"))) return "OWNERSHIP_LOST";
            Record owner=dsl.fetchOne("select job_id from jobs where job_id={0} and lease_epoch={1} "
                + "and state='EXECUTING' and not cancel_requested and lease_expires_at>clock_timestamp() "
                + "and ui2_job_owner_valid(job_id,lease_epoch) for update",jobId,epoch);
            if (owner==null) return "OWNERSHIP_LOST";
            List<String> members = JooqDeviceRepository.enrolledFailoverMembers(dsl, cluster, vendor);
            if (!Set.copyOf(members).equals(expectedMembers)) return "MEMBER_SET_CHANGED";
            String refusal = validateTarget(dsl, r, members, members.isEmpty()?null:members.get(0));
            if (refusal != null) return refusal;
            authority=approvalRefusal(dsl,a,true);
            if (authority!=null) return authority;
            // Linearization point: the conditional update below authorizes dispatch,
            // effective only after this audited transaction commits.
            // Revocation/incident changes serialize on the same advisory lock. A later
            // revocation cannot recall this dispatch, but always fences the next write.
            if (possibleSend) {
                int authorized=dsl.execute("update failover_run set mutation_possible=true where run_id={0} "
                    + "and exists(select 1 from failover_approval a where a.approval_id=failover_run.approval_id "
                    + "and a.revoked_at is null and a.window_from<=clock_timestamp() and a.window_until>clock_timestamp()) "
                    + "and exists(select 1 from jobs j where j.job_id={1} and j.lease_epoch={2} "
                    + "and j.state='EXECUTING' and not j.cancel_requested and j.lease_expires_at>clock_timestamp() "
                    + "and ui2_job_owner_valid(j.job_id,j.lease_epoch))",id,jobId,epoch);
                if (authorized!=1) return "DISPATCH_AUTHORITY_EXPIRED";
            }
            return "ADMITTED";
        });
    }

    /** An audited pre-contact state change is the command ledger entry. */
    public void command(String id, String gateId) {
        audited.inTransaction("system:failover-worker", "failover_command_" + gateId, id,
            dsl -> dsl.execute("update failover_run set message={1} where run_id={0}", id, gateId));
    }

    public record Dispatch(String nonce, String runId, String jobId, long epoch) {}

    private static boolean dispatchLock(DSLContext dsl, String run) {
        return Boolean.TRUE.equals(dsl.fetchOne("select pg_try_advisory_xact_lock(138,hashtext({0})) as acquired",run)
            .get("acquired",Boolean.class));
    }

    /** Strict owner check: legacy null module generations never authorize a mutation. */
    private static boolean owns(DSLContext dsl, String job, long epoch) {
        return !dsl.fetch("select j.job_id from jobs j join module_runtime_control m "
            + "on m.module=ui2_job_module(j.capability_id) join module_runtime_control r on r.module=m.effective_owner "
            + "where j.job_id={0} and j.lease_epoch={1} and j.state='EXECUTING' and not j.cancel_requested "
            + "and j.lease_expires_at>clock_timestamp() and j.lease_owner_generation=m.generation "
            + "and m.effective_owner=split_part(j.lease_worker_id,'-',1) "
            + "and not m.drain_requested and not r.drain_requested "
            + "and r.owner_instance=j.lease_worker_id and r.owner_heartbeat_at>clock_timestamp()-interval '60 seconds'",
            job, epoch).isEmpty();
    }

    /** Commit the attempt, immutable dispatch identity and command ledger before transport invocation. */
    public Dispatch prepareDispatch(String id, long epoch, int index, String member, String gate, String actionClass) {
        if (!mutationEnabled) throw new IllegalStateException("FAILOVER_MUTATION_DISABLED");
        return audited.inTransaction("system:failover-worker", "failover_dispatch_intent", id, dsl -> {
            if (!dispatchLock(dsl,id)) throw new IllegalStateException("DISPATCH_BUSY");
            lockAdmission(dsl);
            Record r=dsl.fetchOne("select * from failover_run where run_id={0} for update",id);
            if (r==null || !"FAILOVER".equals(r.get("run_kind",String.class)))
                throw new IllegalStateException("RUN_NOT_FOUND");
            String job=r.get("job_id",String.class), step=r.get("step",String.class);
            if (!Set.of("FAILING_OVER","RETURNING").contains(step) || !owns(dsl,job,epoch))
                throw new IllegalStateException("DISPATCH_OWNER_LOST");
            List<String> members=JooqDeviceRepository.enrolledFailoverMembers(dsl,
                r.get("cluster_ref",String.class),r.get("vendor",String.class));
            String refusal=validateTarget(dsl,r,members,member);
            if (refusal!=null) throw new IllegalStateException(refusal);
            if (dsl.fetch("select 1 from failover_approval where approval_id={0} and revoked_at is null "
                    + "and window_from<=clock_timestamp() and window_until>clock_timestamp()",r.get("approval_id")).isEmpty())
                throw new IllegalStateException("WINDOW_EXPIRED");
            if (!dsl.fetch("select 1 from failover_dispatch_intent where run_id={0} "
                    + "and (step={1} or observation<>'CONFIRMED')",id,step).isEmpty())
                throw new IllegalStateException("PRIOR_ATTEMPT_NOT_REPLAYED");
            String nonce=UUID.randomUUID().toString(), attempt=UUID.randomUUID().toString();
            requireOne(dsl.execute("insert into job_step_attempt(attempt_id,job_id,lease_epoch,step_index,step_kind,"
                + "attempt_number,action_class,mutation_boundary_crossed,sent_at) values({0},{1},{2},{3},{4},1,{5},true,now())",
                attempt,job,epoch,index,gate,actionClass));
            requireOne(dsl.execute("insert into failover_dispatch_intent(nonce,run_id,attempt_id,job_id,lease_epoch,"
                + "owner_generation,owner_instance,step,member_ref,gate_id) select {0},{1},{2},job_id,lease_epoch,"
                + "lease_owner_generation,lease_worker_id,{3},{4},{5} from jobs where job_id={6} and lease_epoch={7}",
                nonce,id,attempt,step,member,gate,job,epoch));
            requireOne(dsl.execute("update failover_run set mutation_possible=true,message={1} where run_id={0}",id,gate));
            return new Dispatch(nonce,id,job,epoch);
        });
    }

    private static void requireOne(int rows) {
        if (rows!=1) throw new IllegalStateException("FAILOVER_PERSISTENCE_FENCE");
    }

    /**
     * The locked intent is the dispatch linearization point. Reconciliation skips it until this bounded
     * invocation ends; it is never assigned to another sender. Do not hold module/admission locks across
     * transport: endpoint lease renewal uses independent transactions. Only a safe reply bit leaves the callback.
     * A rollback after send retains the already committed intent and therefore blocks fleet admission.
     */
    public boolean dispatch(Dispatch intent, java.util.function.Supplier<Boolean> send) {
        if (!mutationEnabled || intent==null) throw new IllegalStateException("DISPATCH_REFUSED");
        // Consume once in a separate committed transaction. Even a lost commit reply cannot cause replay.
        boolean claimed=audited.inTransaction("system:failover-worker","failover_dispatch_claim",intent.runId(),dsl -> {
            if (!dispatchLock(dsl,intent.runId()) || !owns(dsl,intent.jobId(),intent.epoch())) return false;
            return dsl.execute("update failover_dispatch_intent set dispatch_claimed=true where nonce={0} "
                + "and run_id={1} and job_id={2} and lease_epoch={3} and not dispatch_claimed "
                + "and observation='NOT_OBSERVED'",intent.nonce(),intent.runId(),intent.jobId(),intent.epoch())==1;
        });
        if (!claimed) return false;
        return audited.inTransaction("system:failover-worker", "failover_dispatch_reply", intent.runId(), dsl -> {
            if (!dispatchLock(dsl,intent.runId())) return false;
            Record row=dsl.fetchOne("select * from failover_dispatch_intent where nonce={0} and run_id={1} "
                + "and job_id={2} and lease_epoch={3} and delivery='MAY_HAVE_BEEN_SENT' "
                + "and observation='NOT_OBSERVED' and dispatch_claimed for update",intent.nonce(),intent.runId(),intent.jobId(),intent.epoch());
            if (row==null || !owns(dsl,intent.jobId(),intent.epoch())) return false;
            // Recheck approval and the committed owner immediately before the single send.
            Record run=dsl.fetchOne("select r.* from failover_run r join failover_approval a on a.approval_id=r.approval_id "
                    + "join jobs j on j.job_id=r.job_id where r.run_id={0} and r.state not in ('DONE','STOPPED') "
                    + "and a.revoked_at is null and a.window_from<=clock_timestamp() and a.window_until>clock_timestamp() "
                    + "and j.lease_worker_id={1} and j.lease_owner_generation={2}",intent.runId(),
                    row.get("owner_instance"),row.get("owner_generation"));
            if (run==null || !row.get("step").equals(run.get("step"))) return false;
            List<String> members=JooqDeviceRepository.enrolledFailoverMembers(dsl,
                run.get("cluster_ref",String.class),run.get("vendor",String.class));
            if (validateTarget(dsl,run,members,row.get("member_ref",String.class))!=null) return false;
            boolean replied;
            try { replied=Boolean.TRUE.equals(send.get()); }
            catch (RuntimeException uncertain) { replied=false; }
            requireOne(dsl.execute("update failover_dispatch_intent set delivery={1} where nonce={0}",
                intent.nonce(),replied?"REPLY_RECEIVED":"UNKNOWN"));
            requireOne(dsl.execute("update job_step_attempt set outcome={1},matched_expectation=false,error_class={2} "
                + "where attempt_id={0}",row.get("attempt_id"),replied?"REPLY_RECEIVED":"OUTCOME_UNKNOWN",
                replied?null:"DELIVERY_UNCERTAIN"));
            if (!replied) unknown(dsl,intent.runId(),intent.jobId(),"DELIVERY_UNCERTAIN",null);
            return replied;
        });
    }

    /** Independent role observations, never the write reply, confirm the requested effect. */
    public boolean confirmDispatch(Dispatch intent) {
        return audited.inTransaction("system:failover-worker","failover_dispatch_observed",intent.runId(),dsl -> {
            if (!dispatchLock(dsl,intent.runId()) || !owns(dsl,intent.jobId(),intent.epoch())) return false;
            return dsl.execute("update failover_dispatch_intent set observation='CONFIRMED' where nonce={0} "
                + "and lease_epoch={1} and run_id={2} and job_id={3} "
                + "and delivery='REPLY_RECEIVED' and observation='NOT_OBSERVED'",
                intent.nonce(),intent.epoch(),intent.runId(),intent.jobId())==1;
        });
    }

    /** Atomic fenced run/job termination; V136 creates the incident in this same transaction. */
    public boolean workerState(String id, long epoch, String state, String step, String outcome,
            String failedCheck, String message) {
        return audited.inTransaction("system:failover-worker","failover_worker_state",id,dsl -> {
            if (!dispatchLock(dsl,id)) return false;
            lockAdmission(dsl);
            Record r=dsl.fetchOne("select job_id,mutation_possible,step from failover_run where run_id={0} "
                + "and state not in ('DONE','STOPPED') for update",id);
            if (r==null || !owns(dsl,r.get("job_id",String.class),epoch)) return false;
            String job=r.get("job_id",String.class);
            if ("STOPPED".equals(state) && Boolean.TRUE.equals(r.get("mutation_possible",Boolean.class))) {
                unknown(dsl,id,job,message,failedCheck);
                return true;
            }
            if ("DONE".equals(state) && !dsl.fetch("select 1 from failover_dispatch_intent where run_id={0} "
                    + "and observation<>'CONFIRMED'",id).isEmpty()) throw new IllegalStateException("UNRESOLVED_DISPATCH");
            requireOne(dsl.execute("update failover_run set state={1},step={2},outcome={3},failed_check={4},message={5},"
                + "finished_at=case when {1} in ('DONE','STOPPED') then now() else null end where run_id={0}",
                id,state,"STOPPED".equals(state)?r.get("step",String.class):step,outcome,failedCheck,message));
            if (Set.of("DONE","STOPPED").contains(state)) requireOne(dsl.execute("update jobs set state={1},"
                + "outcome={1},terminal_reason={2},finished_at=now() where job_id={0} and lease_epoch={3}",
                job,"DONE".equals(state)?"COMPLETED":"FAILED",outcome,epoch));
            return true;
        });
    }

    private static void unknown(DSLContext dsl, String run, String job, String reason, String failedCheck) {
        lockAdmission(dsl);
        dsl.execute("update failover_dispatch_intent set observation='OUTCOME_UNKNOWN',"
            + "delivery=case when delivery='MAY_HAVE_BEEN_SENT' then 'UNKNOWN' else delivery end "
            + "where run_id={0} and observation='NOT_OBSERVED'",run);
        dsl.execute("update job_step_attempt set outcome='OUTCOME_UNKNOWN',error_class='OUTCOME_UNCERTAIN',"
            + "matched_expectation=false where attempt_id in (select attempt_id from failover_dispatch_intent "
            + "where run_id={0} and observation='OUTCOME_UNKNOWN')",run);
        requireOne(dsl.execute("update failover_run set state='STOPPED',outcome='OUTCOME_UNKNOWN',message={1},"
            + "failed_check=coalesce({2},failed_check),finished_at=now() where run_id={0}",run,reason,failedCheck));
        requireOne(dsl.execute("update jobs set state='OUTCOME_UNKNOWN',outcome='OUTCOME_UNKNOWN',"
            + "terminal_reason={1},finished_at=now() where job_id={0}",job,reason));
    }

    /** Startup and periodic recovery: no transport and no takeover of a locked or live-owned intent. */
    public int reconcileDispatches() {
        return audited.inTransaction("system:failover-worker","failover_dispatch_reconcile",dsl -> {
            int recovered=0;
            for (Record row:dsl.fetch("select distinct i.run_id,i.job_id from failover_dispatch_intent i join failover_run r on r.run_id=i.run_id "
                    + "where i.observation='NOT_OBSERVED' or r.state not in ('DONE','STOPPED') "
                    + "order by i.run_id")) {
                String job=row.get("job_id",String.class), run=row.get("run_id",String.class);
                if (!dispatchLock(dsl,run)) continue;
                if (dsl.fetch("select job_id from jobs where job_id={0} for update skip locked",job).isEmpty()) continue;
                // Recheck inside the transaction; an unexpired lease is not evidence of a dead process.
                if (!dsl.fetch("select 1 from jobs where job_id={0} "
                        + "and state in ('CLAIMED','EXECUTING') and lease_expires_at>clock_timestamp()",job).isEmpty()) continue;
                if (dsl.fetch("select 1 from failover_dispatch_intent i join failover_run r on r.run_id=i.run_id "
                        + "where i.run_id={0} and (i.observation='NOT_OBSERVED' or r.state not in ('DONE','STOPPED'))",run).isEmpty()) continue;
                unknown(dsl,run,job,"OWNER_LOST",null);
                recovered++;
            }
            return recovered;
        });
    }
    public void check(String id, String phase, String member, String vsId, int no, String status, String derived) {
        audited.inTransaction("system:failover-worker", "failover_check", id, dsl -> dsl.execute(
            "insert into failover_check_result(run_id,phase,member_ref,vs_id,check_no,status,derived) "
            + "values({0},{1},{2},{3},{4},{5},{6}::jsonb)", id, phase, member, vsId, no, status, derived));
    }
}
