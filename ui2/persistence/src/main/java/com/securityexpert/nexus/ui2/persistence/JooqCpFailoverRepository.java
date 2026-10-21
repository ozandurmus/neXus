package com.securityexpert.nexus.ui2.persistence;

import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

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
    /**
     * Proof that the write never left neXus: the switch check before invoking any transport, or (resume only) a
     * pre-send refusal inside the send transaction ({@link #dispatchResume}); {@link #code()} is the stop code.
     */
    public static final class NotSent extends RuntimeException {
        public NotSent() { this("MUTATION_DISABLED_BEFORE_SEND"); }
        public NotSent(String code) { super(code); }
        public String code() { return getMessage(); }
    }

    private enum SendResult { NOT_SENT, REPLIED, NO_REPLY }

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
    public record Decision(String code, String runId, String dispatchRef) {
        public Decision(String code, String runId) { this(code, runId, null); }
    }
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
    private final JobWindowPolicy windows;
    private final TransactionBoundary boundary;
    private final AuditedTransactionBoundary audited;

    public JooqCpFailoverRepository(TransactionBoundary boundary) {
        this(boundary, "true".equalsIgnoreCase(System.getenv("NEXUS_FAILOVER_MUTATION_ENABLED")));
    }

    public JooqCpFailoverRepository(TransactionBoundary boundary, boolean mutationEnabled) {
        this(boundary, mutationEnabled, JobWindowPolicy.SYSTEM);
    }

    public JooqCpFailoverRepository(TransactionBoundary boundary, boolean mutationEnabled, JobWindowPolicy windows) {
        this.windows = windows;
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
        return summaryMembers(null);
    }

    /**
     * The same facts restricted to the given devices (one cluster's pair); the per-device lateral
     * subqueries then run for those devices only instead of for the whole fleet.
     */
    public List<SummaryMember> summaryMembers(java.util.Collection<String> deviceIds) {
        StringBuilder filter = new StringBuilder();
        if (deviceIds != null) {
            if (deviceIds.isEmpty()) return List.of();
            filter.append("where d.device_id in (");
            for (int i = 0; i < deviceIds.size(); i++) filter.append(i == 0 ? "{" : ",{").append(i).append("}");
            filter.append(") ");
        }
        Object[] binds = deviceIds == null ? new Object[0] : deviceIds.toArray();
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
            + "select context from device_route where run_id=r.run_id) contexts) ctx on true "
            + filter, binds)
            .map(r -> new SummaryMember(r.get("device_id", String.class),
                Boolean.TRUE.equals(r.get("readable", Boolean.class)), r.get("transport_kind", String.class),
                Boolean.TRUE.equals(r.get("inventory_present", Boolean.class)), r.get("context", String.class),
                Boolean.TRUE.equals(r.get("pan_supported", Boolean.class)))));
    }

    /** Latest run and currently valid window per vendor/cluster/VSID in one read. */
    public List<SummaryStatus> summaryStatuses() {
        return summaryStatuses(null);
    }

    /** The same read restricted to one cluster reference. */
    public List<SummaryStatus> summaryStatuses(String clusterRef) {
        String scope = clusterRef == null ? "" : " where cluster_ref={0}";
        Object[] binds = clusterRef == null ? new Object[0] : new Object[] {clusterRef};
        return boundary.inTransaction(dsl -> dsl.fetch("with unit_keys as "
            + "(select vendor,cluster_ref,vs_id from failover_approval" + scope + " union "
            + "select vendor,cluster_ref,vs_id from failover_run" + scope + ") "
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
            + "order by scheduled_for desc limit 1) r on true", binds)
            .map(r -> new SummaryStatus(r.get("cluster_ref", String.class), r.get("vs_id", String.class),
                r.get("vendor", String.class), Boolean.TRUE.equals(r.get("active_window", Boolean.class)),
                r.get("state", String.class), r.get("outcome", String.class),
                Optional.ofNullable(r.get("scheduled_for", java.time.OffsetDateTime.class))
                    .map(java.time.OffsetDateTime::toInstant).orElse(null))));
    }

    public List<ReadinessStatus> readinessStatuses() {
        return readinessStatuses(null);
    }

    /** The newest finished readiness run per unit of one cluster reference, with its pre-check rows. */
    public List<ReadinessStatus> readinessStatuses(String clusterRef) {
        String scope = clusterRef == null ? "" : " and cluster_ref={0}";
        Object[] binds = clusterRef == null ? new Object[0] : new Object[] {clusterRef};
        return boundary.inTransaction(dsl -> dsl.fetch("""
            with latest as (
                select distinct on (vendor,cluster_ref,coalesce(vs_id,''))
                    run_id,cluster_ref,vs_id,vendor,outcome,finished_at,failed_check,message
                from failover_run where run_kind='READINESS' and state in ('DONE','STOPPED')""" + scope + """

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
            """, binds)
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
        // PO 2026-10-20: an administrator's immediate start is never refused for the window (business-hours
        // acknowledgement instead); a scheduled start keeps the window at its scheduled time.
        if (immediate) windows.requireOpenFor(actor);
        else {
            windows.requireOpenUnlessAdmin(actor);
            if (scheduledFor != null) windows.requireOpen(scheduledFor);
        }
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
            Decision refusal = admissionDecision(dsl, cluster, members, null);
            if (refusal != null) return refusal;
            dsl.execute("update failover_approval set warning_confirmed_at=clock_timestamp() where approval_id={0}",requestId);
            String id = UUID.randomUUID().toString();
            dsl.execute("insert into failover_run(run_id,cluster_ref,vs_id,approval_id,requested_by,scheduled_for,state,step,vendor,target_member_ids,request_revision) "
                + "values({0},{1},{2},{3},{4},{5},'PLANNED','PLANNED',{6},jsonb_build_array({7}::text,{8}::text),1)", id, cluster, vsId,
                a.get("approval_id", String.class), actor, Timestamp.from(scheduledFor), vendor, members.get(0), members.get(1));
            if (immediate) admit(dsl, id, targetDeviceId, actor, vendor);
            return new Decision("ADMITTED", id);
        });
    }

    /** Job states after which no worker will ever record a readiness result (JobState#isTerminal). */
    static final String TERMINAL_JOB_STATES =
        "('COMPLETED','FAILED','REJECTED','CANCELLED','OUTCOME_UNKNOWN','RECONCILED')";
    /** Message on a readiness run closed because its job ended first; V162 writes the same code. */
    public static final String JOB_ENDED_WITHOUT_RESULT = "JOB_ENDED_WITHOUT_RESULT";

    /**
     * Close open readiness runs whose job is already terminal: STOPPED (the run ended before its
     * procedure finished), outcome UNKNOWN (no result), ended when the job ended. Execution runs are
     * never touched here. docs/operations/sql/2026_10_18_close_orphan_readiness_runs.sql is the same
     * statement for rows orphaned before V162.
     * A null cluster closes such runs for every unit (the sweep); otherwise only for one unit.
     */
    private static int closeOrphanReadiness(DSLContext dsl, String cluster, String vsId, String vendor) {
        return dsl.execute("update failover_run r set state='STOPPED',outcome='UNKNOWN',message={0},"
            + "finished_at=coalesce(j.finished_at,now()) from jobs j where j.job_id=r.job_id and r.run_kind='READINESS' "
            + "and r.state not in ('DONE','STOPPED') and j.state in " + TERMINAL_JOB_STATES + " "
            + "and ({1}::text is null or (r.cluster_ref={1} and r.vs_id is not distinct from {2} and r.vendor={3}))",
            JOB_ENDED_WITHOUT_RESULT, cluster, vsId, vendor);
    }

    /** Sweep for readiness runs orphaned by a terminal job; returns how many were closed. */
    public int closeOrphanReadinessRuns() {
        return audited.inTransaction("system:failover-readiness-scheduler", "failover_readiness_orphan_close",
            dsl -> closeOrphanReadiness(dsl, null, null, null));
    }

    /**
     * Admission without a failover approval; shares the active-unit uniqueness fence. A readiness run
     * whose job is terminal is closed first, in this transaction, so one orphan never blocks a unit.
     */
    public Decision requestReadiness(String cluster, String vsId, String actor, String targetDeviceId,
            String vendor) {
        windows.requireOpenFor(actor);
        return audited.inTransaction(actor, "failover_readiness_request", dsl -> {
            closeOrphanReadiness(dsl, cluster, vsId, vendor);
            Record active = dsl.fetchOne("select run_id from failover_run where cluster_ref={0} "
                + "and vs_id is not distinct from {1} and vendor={2} and state not in ('DONE','STOPPED') limit 1",
                cluster, vsId, vendor);
            if (active != null) return new Decision("RUN_ALREADY_ACTIVE", null);
            // A resumed return on this unit uses its members' sessions: no readiness battery alongside it.
            if (!dsl.fetch("select 1 from failover_resume where cluster_ref={0} and state in " + RESUME_ACTIVE_SQL,
                    cluster).isEmpty()) return new Decision("RUN_ALREADY_ACTIVE", null);
            String id = UUID.randomUUID().toString();
            dsl.execute("insert into failover_run(run_id,cluster_ref,vs_id,approval_id,requested_by,scheduled_for,state,step,vendor,run_kind) "
                + "values({0},{1},{2},null,{3},now(),'PLANNED','PLANNED',{4},'READINESS')", id, cluster, vsId, actor, vendor);
            admit(dsl, id, targetDeviceId, actor, vendor, "READINESS");
            return new Decision("ADMITTED", id);
        });
    }

    private void admit(org.jooq.DSLContext dsl, String runId, String targetDeviceId, String actor, String vendor) {
        admit(dsl, runId, targetDeviceId, actor, vendor, "FAILOVER");
    }
    private void admit(org.jooq.DSLContext dsl, String runId, String targetDeviceId, String actor,
            String vendor, String runKind) {
        windows.requireOpenFor(actor);
        String jobId = UUID.randomUUID().toString();
        String kind = "palo_alto".equals(vendor)
            ? ("READINESS".equals(runKind) ? "pan_failover_readiness" : "pan_cluster_failover")
            : ("READINESS".equals(runKind) ? "cp_failover_readiness" : "cp_cluster_failover");
        String actionClass = "READINESS".equals(runKind) ? "read" : "operational-state-change";
        dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
            + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) "
            + "values({0},{1},{1},{2},'device',{3},{4},{5},'REQUESTED')", jobId, kind, targetDeviceId, actor,
            kind + ":" + runId, actionClass);
        // The administrator's grant (and, inside business hours, the acknowledgement) is bound to this job row,
        // in this transaction; the worker rechecks it before every device write (mutationAdmission).
        com.securityexpert.nexus.ui2.persistence.jobrecords.ManualAdmissionRecord.record(dsl, jobId, actor, windows);
        dsl.execute("update failover_run set job_id={1},started_at=now(),step='QUEUED' where run_id={0}", runId, jobId);
    }

    public List<Run> due() {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from failover_run where state='PLANNED' "
            + "and job_id is null and scheduled_for <= now() order by scheduled_for limit 50")
            .map(JooqCpFailoverRepository::run));
    }

    public String startDue(String id, String targetDeviceId) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        // A scheduled start: always the window, whatever thread calls it.
        windows.requireOpen(windows.now());
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
    /**
     * PO 2026-10-20, rechecked before the run's first device write: an administrator's manual failover job (window
     * bypass) that was submitted outside business hours carries no acknowledgement; if business hours have begun
     * since, the first write is refused until an administrator starts it again with the acknowledgement. The run's
     * return write is never refused for business hours ({@code mutationAdmission}). A window-admitted job (scheduled
     * start) has no bypass role and is unaffected.
     */
    String businessHoursRefusal(DSLContext dsl, String jobId) {
        Record job = dsl.fetchOne("select window_bypass_role, business_hours_ack_at from jobs where job_id={0}", jobId);
        if (job == null || job.get("window_bypass_role") == null || job.get("business_hours_ack_at") != null) return null;
        return windows.insideBusinessHours() ? com.securityexpert.nexus.ui2.platform.ManualAdmission.CONFIRM_CODE : null;
    }

    private static void lockAdmission(DSLContext dsl) {
        dsl.execute("select pg_advisory_xact_lock(136, 1)");
    }

    private static String admissionRefusal(DSLContext dsl, String cluster, List<String> members, String runId) {
        Decision refusal = admissionDecision(dsl, cluster, members, runId);
        return refusal == null ? null : refusal.code();
    }

    private static Decision admissionDecision(DSLContext dsl, String cluster, List<String> members, String runId) {
        // The unit/member incident is the operator-actionable cause and takes precedence over
        // the fleet dispatch fence. If its own dispatch is unresolved, retain OPEN_INCIDENT
        // and attach that dispatch reference separately; never attribute an unrelated dispatch.
        Record incident = dsl.fetchOne("select i.nonce as dispatch_ref from failover_quarantine q "
            + "left join failover_dispatch_intent i on i.run_id=q.execution_id and i.observation<>'CONFIRMED' "
            + "where q.active and (q.cluster_ref={0} or q.quarantined_member_ids @> jsonb_build_array({1}::text) "
            + "or q.quarantined_member_ids @> jsonb_build_array({2}::text)) "
            + "order by i.nonce nulls last,q.execution_id limit 1", cluster, members.get(0), members.get(1));
        if (incident != null) return new Decision("OPEN_INCIDENT", null, incident.get("dispatch_ref", String.class));
        if (!dsl.fetch("select 1 from failover_dispatch_intent i where run_id is distinct from {0} "
                + "and delivery<>'NOT_SENT' and (observation='NOT_OBSERVED' or (observation='OUTCOME_UNKNOWN' and exists "
                + "(select 1 from failover_quarantine q where q.execution_id=i.run_id and q.active)))", runId).isEmpty())
            return new Decision("UNRESOLVED_DISPATCH", null);
        if (!dsl.fetch("select 1 from failover_run where run_kind='FAILOVER' "
                + "and state not in ('DONE','STOPPED') and run_id is distinct from {0}", runId).isEmpty())
            return new Decision("FLEET_MUTATION_ACTIVE", null);
        // V170: a resumed return in flight holds the same fleet fence as a run.
        if (!dsl.fetch("select 1 from failover_resume where state in " + RESUME_ACTIVE_SQL).isEmpty())
            return new Decision("FLEET_MUTATION_ACTIVE", null);
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
            // The business-hours recheck guards only the run's FIRST device write (Argon review 2026-10-20 P1-1). Once a
            // write of this run has been prepared, mutation_possible is true (the run guard never lets it fall back) and
            // the only write left is the run's own return (clusterXL_admin up / PAN functional), which restores the
            // cluster: business hours beginning between the two writes must never strand the former active member
            // down. Every other check of this fence still applies to the return write.
            if (!Boolean.TRUE.equals(r.get("mutation_possible", Boolean.class))) {
                String businessHours=businessHoursRefusal(dsl,jobId);
                if (businessHours!=null) return businessHours;
            }
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
     * transport: endpoint lease renewal uses independent transactions. Only a safe reply bit or a proven
     * pre-transport NotSent signal leaves the callback.
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
        SendResult result=audited.inTransaction("system:failover-worker", "failover_dispatch_reply", intent.runId(), dsl -> {
            if (!dispatchLock(dsl,intent.runId())) return SendResult.NO_REPLY;
            Record row=dsl.fetchOne("select * from failover_dispatch_intent where nonce={0} and run_id={1} "
                + "and job_id={2} and lease_epoch={3} and delivery='MAY_HAVE_BEEN_SENT' "
                + "and observation='NOT_OBSERVED' and dispatch_claimed for update",intent.nonce(),intent.runId(),intent.jobId(),intent.epoch());
            if (row==null || !owns(dsl,intent.jobId(),intent.epoch())) return SendResult.NO_REPLY;
            // Recheck approval and the committed owner immediately before the single send.
            Record run=dsl.fetchOne("select r.* from failover_run r join failover_approval a on a.approval_id=r.approval_id "
                    + "join jobs j on j.job_id=r.job_id where r.run_id={0} and r.state not in ('DONE','STOPPED') "
                    + "and a.revoked_at is null and a.window_from<=clock_timestamp() and a.window_until>clock_timestamp() "
                    + "and j.lease_worker_id={1} and j.lease_owner_generation={2}",intent.runId(),
                    row.get("owner_instance"),row.get("owner_generation"));
            if (run==null || !row.get("step").equals(run.get("step"))) return SendResult.NO_REPLY;
            List<String> members=JooqDeviceRepository.enrolledFailoverMembers(dsl,
                run.get("cluster_ref",String.class),run.get("vendor",String.class));
            if (validateTarget(dsl,run,members,row.get("member_ref",String.class))!=null) return SendResult.NO_REPLY;
            boolean replied;
            try { replied=Boolean.TRUE.equals(send.get()); }
            catch (NotSent notSent) {
                lockAdmission(dsl);
                requireOne(dsl.execute("update failover_dispatch_intent set delivery='NOT_SENT' where nonce={0}",intent.nonce()));
                requireOne(dsl.execute("update job_step_attempt set outcome='NOT_SENT',matched_expectation=false,"
                    + "error_class='MUTATION_DISABLED_BEFORE_SEND',mutation_boundary_crossed=false,sent_at=null "
                    + "where attempt_id={0}",row.get("attempt_id")));
                requireOne(dsl.execute("update failover_run set state='STOPPED',outcome='MUTATION_DISABLED_BEFORE_SEND',"
                    + "message='MUTATION_DISABLED_BEFORE_SEND',finished_at=now() where run_id={0}",intent.runId()));
                requireOne(dsl.execute("update jobs set state='FAILED',outcome='MUTATION_DISABLED_BEFORE_SEND',"
                    + "terminal_reason='MUTATION_DISABLED_BEFORE_SEND',finished_at=now() where job_id={0}",intent.jobId()));
                return SendResult.NOT_SENT;
            }
            catch (RuntimeException uncertain) { replied=false; }
            requireOne(dsl.execute("update failover_dispatch_intent set delivery={1} where nonce={0}",
                intent.nonce(),replied?"REPLY_RECEIVED":"UNKNOWN"));
            requireOne(dsl.execute("update job_step_attempt set outcome={1},matched_expectation=false,error_class={2} "
                + "where attempt_id={0}",row.get("attempt_id"),replied?"REPLY_RECEIVED":"OUTCOME_UNKNOWN",
                replied?null:"DELIVERY_UNCERTAIN"));
            if (!replied) unknown(dsl,intent.runId(),intent.jobId(),"DELIVERY_UNCERTAIN",null);
            return replied?SendResult.REPLIED:SendResult.NO_REPLY;
        });
        // Propagate only after the terminal state and intent have committed together.
        if (result==SendResult.NOT_SENT) throw new NotSent();
        return result==SendResult.REPLIED;
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
        // A resume's job (reconciliation after owner loss): record its own write's uncertainty; the run row stays as is.
        if (!dsl.fetch("select 1 from failover_resume where job_id={0}",job).isEmpty()) {
            unknownResume(dsl,job,reason,failedCheck);
            return;
        }
        dsl.execute("update failover_dispatch_intent set observation='OUTCOME_UNKNOWN',"
            + "delivery=case when delivery='MAY_HAVE_BEEN_SENT' then 'UNKNOWN' else delivery end "
            + "where run_id={0} and delivery<>'NOT_SENT' and observation='NOT_OBSERVED'",run);
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
                    + "where i.delivery<>'NOT_SENT' and (i.observation='NOT_OBSERVED' or r.state not in ('DONE','STOPPED')) "
                    + "order by i.run_id")) {
                String job=row.get("job_id",String.class), run=row.get("run_id",String.class);
                if (!dispatchLock(dsl,run)) continue;
                if (dsl.fetch("select job_id from jobs where job_id={0} for update skip locked",job).isEmpty()) continue;
                // Recheck inside the transaction; an unexpired lease is not evidence of a dead process.
                if (!dsl.fetch("select 1 from jobs where job_id={0} "
                        + "and state in ('CLAIMED','EXECUTING') and lease_expires_at>clock_timestamp()",job).isEmpty()) continue;
                if (dsl.fetch("select 1 from failover_dispatch_intent i join failover_run r on r.run_id=i.run_id "
                        + "where i.run_id={0} and i.delivery<>'NOT_SENT' and (i.observation='NOT_OBSERVED' or r.state not in ('DONE','STOPPED'))",run).isEmpty()) continue;
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

    /**
     * One open failover incident, or one stopped run whose possibly sent write is still unresolved (it blocks
     * every failover admission in the estate). Opaque references only; no device address or name.
     */
    public record Incident(String clusterRef, String runId, String vsId, String vendor, String reason,
            Instant openedAt, List<String> memberIds, List<String> unresolvedDispatchRefs, boolean incidentActive) {
        /** Unresolved sends of this run refuse every failover admission (UNRESOLVED_DISPATCH), not only this unit's. */
        public boolean blocksEstate() { return !unresolvedDispatchRefs.isEmpty(); }
    }
    public static final String INCIDENT_RELEASED = "RELEASED";
    public static final String INCIDENT_NOT_FOUND = "INCIDENT_NOT_FOUND";
    public static final String INCIDENT_RUN_NOT_TERMINAL = "RUN_NOT_TERMINAL";
    public static final String INCIDENT_RUN_STILL_OWNED = "RUN_STILL_OWNED";

    /** Fleet-fence predicate shared with {@link #admissionDecision}: intents that refuse every admission. */
    private static final String UNRESOLVED_SENDS = "select i.nonce from failover_dispatch_intent i where i.run_id={0} "
        + "and i.delivery<>'NOT_SENT' and (i.observation='NOT_OBSERVED' or (i.observation='OUTCOME_UNKNOWN' and exists "
        + "(select 1 from failover_quarantine q where q.execution_id=i.run_id and q.active))) order by i.nonce";

    /** Active incidents plus terminal runs whose unresolved sends hold the estate-wide fence without an incident. */
    public List<Incident> openIncidents() {
        return boundary.inTransaction(dsl -> dsl.fetch("select q.cluster_ref,q.execution_id as run_id,q.reason,"
                + "q.quarantined_at as opened_at,q.quarantined_member_ids as member_ids,r.vs_id,r.vendor,true as incident_active "
                + "from failover_quarantine q left join failover_run r on r.run_id=q.execution_id where q.active "
                + "union all select r.cluster_ref,r.run_id,'UNRESOLVED_DISPATCH',min(i.created_at),r.target_member_ids,"
                + "r.vs_id,r.vendor,false from failover_dispatch_intent i join failover_run r on r.run_id=i.run_id "
                + "where i.delivery<>'NOT_SENT' and i.observation='NOT_OBSERVED' and r.state in ('DONE','STOPPED') "
                + "and not exists(select 1 from failover_quarantine q where q.execution_id=r.run_id and q.active) "
                + "group by r.run_id order by 4,2")
            .map(r -> {
                String run=r.get("run_id",String.class);
                List<String> members=new java.util.ArrayList<>();
                try {
                    for (var member:new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(r.get("member_ids",JSONB.class).data())) members.add(member.asText());
                } catch (java.io.IOException invalid) { members.clear(); }
                return new Incident(r.get("cluster_ref",String.class),run,r.get("vs_id",String.class),
                    r.get("vendor",String.class),r.get("reason",String.class),
                    r.get("opened_at",java.time.OffsetDateTime.class).toInstant(),List.copyOf(members),
                    dsl.fetch(UNRESOLVED_SENDS,run).map(n -> n.get(0,String.class)),
                    Boolean.TRUE.equals(r.get("incident_active",Boolean.class)));
            }));
    }

    /**
     * PO decision 2026-10-20 item 1: release one run's incident and its unresolved sends in one audited
     * transaction. The actor is the server-side session (security_admin, checked by the caller); the reason
     * is recorded on the incident (V136 columns: acknowledged_by/at, review_notes; second_approver_id holds
     * the same single security_admin under the ratified single-admin policy) and in audit_log (V169); for an orphan
     * unresolved-dispatch fence with no active incident, on the run's job_reconciliation row instead (audited).
     * A still possibly sent write of the run is recorded as OUTCOME_UNKNOWN, never as confirmed: the release
     * attests that its state was established out of band, it does not claim the write's effect.
     */
    static final String ORPHAN_RELEASE_RECORD = "insert into job_reconciliation(job_id,evidence,recorded_by,reconciled_outcome) "
        + "select distinct job_id,{1},{2},'RECONCILED_STILL_UNKNOWN' from failover_dispatch_intent "
        + "where run_id={0} and job_id is not null and observation='OUTCOME_UNKNOWN' "
        + "on conflict (job_id) do update set evidence=excluded.evidence,recorded_by=excluded.recorded_by,"
        + "recorded_at=now(),reconciled_outcome=excluded.reconciled_outcome";

    /** V175: the settled fleet failover task leases bound to the run's job or its resume's job. */
    static final String RELEASE_SETTLED_LEASES = "select ui2_release_settled_failover_leases(bound.job_id) from "
        + "(select job_id from failover_run where run_id={0} union select job_id from failover_resume where run_id={0}) bound "
        + "where bound.job_id is not null";

    public String releaseIncident(String runId, String actor, String reason) {
        if (runId==null || actor==null || actor.isBlank() || reason==null || reason.isBlank())
            throw new IllegalArgumentException("INCIDENT_RELEASE_INVALID");
        return audited.inTransaction(actor,"failover_incident_release",runId,dsl -> {
            if (!dispatchLock(dsl,runId)) return INCIDENT_RUN_STILL_OWNED;
            lockAdmission(dsl);
            Record run=dsl.fetchOne("select state,job_id from failover_run where run_id={0} for update",runId);
            List<String> incidents=dsl.fetch("select cluster_ref from failover_quarantine where execution_id={0} "
                + "and active order by cluster_ref",runId).map(q -> q.get(0,String.class));
            if (run!=null && !Set.of("DONE","STOPPED").contains(run.get("state",String.class))) return INCIDENT_RUN_NOT_TERMINAL;
            if (run!=null && !dsl.fetch("select 1 from jobs where job_id={0} and state in ('CLAIMED','EXECUTING') "
                    + "and lease_expires_at>clock_timestamp()",run.get("job_id",String.class)).isEmpty())
                return INCIDENT_RUN_STILL_OWNED;
            // A resume of this run in flight still owns the incident (contract §4.3: release follows the resume).
            if (!dsl.fetch("select 1 from failover_resume s left join jobs j on j.job_id=s.job_id where s.run_id={0} "
                    + "and (s.state in "+RESUME_ACTIVE_SQL+" or (j.state in ('CLAIMED','EXECUTING') "
                    + "and j.lease_expires_at>clock_timestamp()))",runId).isEmpty())
                return INCIDENT_RUN_STILL_OWNED;
            List<String> unresolved=dsl.fetch("select nonce from failover_dispatch_intent where run_id={0} "
                + "and delivery<>'NOT_SENT' and observation='NOT_OBSERVED' for update",runId).map(n -> n.get(0,String.class));
            if (incidents.isEmpty() && unresolved.isEmpty()) return INCIDENT_NOT_FOUND;
            if (!unresolved.isEmpty()) {
                dsl.execute("update failover_dispatch_intent set observation='OUTCOME_UNKNOWN',"
                    + "delivery=case when delivery='MAY_HAVE_BEEN_SENT' then 'UNKNOWN' else delivery end "
                    + "where run_id={0} and delivery<>'NOT_SENT' and observation='NOT_OBSERVED'",runId);
                dsl.execute("update job_step_attempt set outcome='OUTCOME_UNKNOWN',error_class='OUTCOME_UNCERTAIN',"
                    + "matched_expectation=false where attempt_id in (select attempt_id from failover_dispatch_intent "
                    + "where run_id={0} and observation='OUTCOME_UNKNOWN')",runId);
            }
            if (incidents.isEmpty()) {
                // Argon review 2026-10-20 P2-3: an orphan UNRESOLVED_DISPATCH fence (the run's incident row was already
                // released, e.g. before V169) has no incident to carry the reason. Record who released it and why on
                // the run's job (job_reconciliation: audited by trg_audit_job_reconciliation, evidence redacted tier 2),
                // as RECONCILED_STILL_UNKNOWN -- the release attests the state was established out of band, never the
                // write's effect. Nothing recorded means nothing released (the transaction rolls back).
                if (dsl.execute(ORPHAN_RELEASE_RECORD,runId,reason,actor)<1)
                    throw new IllegalStateException("INCIDENT_RELEASE_REASON_NOT_RECORDED");
            }
            for (String cluster:incidents)
                if (!Boolean.TRUE.equals(dsl.fetchOne("select ui2_release_failover_incident({0},{1},{2},{3},{4}) as released",
                        cluster,runId,actor,actor,reason).get("released",Boolean.class)))
                    throw new IllegalStateException("INCIDENT_RELEASE_FENCE");
            // Pilot 2026-10-21/22: the stopped run's job kept its quarantined fleet task lease after the release and
            // its live worker blocked every module drain. Release it here, audited with this session's actor (V175),
            // once nothing about the run or its resume still needs the fence (ui2_failover_lease_settled).
            dsl.execute(RELEASE_SETTLED_LEASES,runId);
            return INCIDENT_RELEASED;
        });
    }

    // ---- Resume the return step of a stopped run (CP_FAILOVER_MEMBER_RECOVERY_CONTRACT.md, RATIFIED 2026-10-20) ----

    /** Contract §2.1 row 12 (PO Q4): a resume request lives 30 minutes from its creation to the worker start. */
    public static final java.time.Duration RESUME_REQUEST_LIFETIME = java.time.Duration.ofMinutes(30);
    public static final int RESUME_REASON_MAX = 500;
    public static final String RESUME_ADMITTED = "ADMITTED";
    public static final String RESUME_PENDING = "PENDING_APPROVAL";
    /** Contract §2.1 row 11 / §5.2: inside business hours the resume request (and a second approval) carries its own acknowledgement. */
    public static final String BUSINESS_HOURS_ACK_REQUIRED = "BUSINESS_HOURS_ACK_REQUIRED";
    /** An admitted resume that has not ended: it holds the fleet fence (V170 uq_failover_resume_fleet). */
    public static final Set<String> RESUME_ACTIVE_STATES = Set.of("QUEUED","READING_STATE","RETURNING","POST_RETURN");
    private static final String RESUME_ACTIVE_SQL = "('QUEUED','READING_STATE','RETURNING','POST_RETURN')";
    /** Contract §2.1 row 12: a request that never started and outlived its 30 minutes (V170 state EXPIRED). */
    public static final String RESUME_EXPIRED = "EXPIRED";
    /** A resume that has ended; nothing more is sent for it and it is never reopened (V170 guard). */
    public static final Set<String> RESUME_TERMINAL_STATES = Set.of("DONE","STOPPED",RESUME_EXPIRED);
    public static final String RESUME_REQUEST_EXPIRED = "RESUME_REQUEST_EXPIRED";
    /** At most this many due requests are expired per sweep transaction (the run being read is always included). */
    static final int RESUME_EXPIRY_BATCH = 100;
    private static final String RESUME_ACTOR_EXPIRY = "system:failover-resume-expiry";

    /** One resume request; opaque references only. */
    public record Resume(String id, String runId, String clusterRef, String formerActiveRef, String peerRef,
            List<String> targetMembers, String memberSetRevision, String expectedIncidentId, String policy,
            String initiatedBy, Instant requestedAt, Instant expiresAt, boolean requestedInBusinessHours,
            boolean businessHoursAcknowledged, String approvedBy, String jobId, String state, String step,
            String outcome, String failedCheck, String message, boolean mutationPossible, Instant finishedAt) {
        public boolean active() { return RESUME_ACTIVE_STATES.contains(state); }
        public boolean terminal() { return RESUME_TERMINAL_STATES.contains(state); }
    }
    private static Resume resume(Record r) {
        return new Resume(r.get("resume_id",String.class),r.get("run_id",String.class),r.get("cluster_ref",String.class),
            r.get("former_active_ref",String.class),r.get("peer_ref",String.class),
            List.of(r.get("m0",String.class),r.get("m1",String.class)),r.get("member_set_revision",String.class),
            r.get("expected_incident_id",String.class),r.get("policy",String.class),r.get("initiated_by",String.class),
            r.get("requested_at",java.time.OffsetDateTime.class).toInstant(),
            r.get("expires_at",java.time.OffsetDateTime.class).toInstant(),
            Boolean.TRUE.equals(r.get("requested_in_business_hours",Boolean.class)),
            r.get("business_hours_ack_at")!=null,r.get("approved_by",String.class),r.get("job_id",String.class),
            r.get("state",String.class),r.get("step",String.class),r.get("outcome",String.class),
            r.get("failed_check",String.class),r.get("message",String.class),
            Boolean.TRUE.equals(r.get("mutation_possible",Boolean.class)),
            Optional.ofNullable(r.get("finished_at",java.time.OffsetDateTime.class)).map(java.time.OffsetDateTime::toInstant).orElse(null));
    }
    private static final String RESUME_SELECT = "select s.*,s.target_member_ids->>0 as m0,s.target_member_ids->>1 as m1 "
        + "from failover_resume s ";

    public Optional<Resume> resumeByJob(String jobId) {
        return boundary.inTransaction(dsl -> dsl.fetch(RESUME_SELECT+"where s.job_id={0}",jobId)
            .stream().findFirst().map(JooqCpFailoverRepository::resume));
    }
    /** The run's resume as it stands now: a due, never-started request is expired first (contract §2.1 row 12). */
    public Optional<Resume> resumeForRun(String runId) {
        return audited.inTransaction(RESUME_ACTOR_EXPIRY,"failover_resume_expiry",runId,dsl -> {
            expireDue(dsl,runId);
            return dsl.fetch(RESUME_SELECT+"where s.run_id={0}",runId).stream().findFirst().map(JooqCpFailoverRepository::resume);
        });
    }

    private static final String RESUME_DUE_SQL = "state='PENDING_APPROVAL' and expires_at<=clock_timestamp()";

    /**
     * Contract §2.1 row 12 / §10 item 5: a two-person request still awaiting its second approval when its 30 minutes
     * pass becomes EXPIRED (outcome {@code RESUME_REQUEST_EXPIRED}), so no reader ever sees it as awaiting approval and
     * it is spent (row 5). Bounded: the given run's request (null: none in particular) plus at most
     * {@link #RESUME_EXPIRY_BATCH} other due requests, oldest first. Takes advisory lock 136 only when something is due.
     * A QUEUED request that outlived its 30 minutes is expired by its worker at start ({@link #resumeAdmission}).
     */
    private static int expireDue(DSLContext dsl, String runId) {
        if (dsl.fetch("select 1 from failover_resume where "+RESUME_DUE_SQL+" limit 1").isEmpty()) return 0;
        lockAdmission(dsl);
        return dsl.execute("update failover_resume set state='EXPIRED',outcome={2},message={2},finished_at=clock_timestamp() "
            + "where "+RESUME_DUE_SQL+" and (run_id={0} or resume_id in (select resume_id from failover_resume where "
            + RESUME_DUE_SQL+" order by expires_at,resume_id limit {1}))",runId,RESUME_EXPIRY_BATCH,RESUME_REQUEST_EXPIRED);
    }

    /** Bounded periodic sweep (worker reconciler, every 30 s): expire every due request awaiting its second approval. */
    public int expireResumeRequests() {
        return audited.inTransaction(RESUME_ACTOR_EXPIRY,"failover_resume_expiry",dsl -> expireDue(dsl,null));
    }

    /** The run's resume target, or the first §2.1 refusal (rows 2–9). */
    private record ResumeTarget(String refusal, String clusterRef, String formerActive, String peer, List<String> members) {
        static ResumeTarget refused(String code) { return new ResumeTarget(code,null,null,null,List.of()); }
    }

    /**
     * Contract §2.1 rows 2–9 in the caller's transaction (advisory lock 136 held by every writer). {@code ownJob} and
     * {@code ownResume} are this resume's own job and row (null before they exist): their intents and the row itself are
     * not "another" attempt, resume or dispatch. {@code expectedIncident} null skips only the mismatch test (row 6).
     */
    private static ResumeTarget resumeTarget(DSLContext dsl, String runId, String ownJob, String ownResume,
            String expectedIncident) {
        Record r=dsl.fetchOne("select run_kind,vendor,vs_id,state,cluster_ref,coalesce(started_at,scheduled_for) as started,"
            + "case when jsonb_typeof(target_member_ids)='array' then jsonb_array_length(target_member_ids)=2 else false end as bound,"
            + "target_member_ids->>0 as m0,target_member_ids->>1 as m1 from failover_run where run_id={0}",runId);
        if (r==null || !"FAILOVER".equals(r.get("run_kind",String.class)) || !"check_point".equals(r.get("vendor",String.class))
                || r.get("vs_id")!=null || !"STOPPED".equals(r.get("state",String.class))
                || !Boolean.TRUE.equals(r.get("bound",Boolean.class))) return ResumeTarget.refused("RESUME_RUN_NOT_ELIGIBLE");
        String cluster=r.get("cluster_ref",String.class);
        List<String> targets=List.of(r.get("m0",String.class),r.get("m1",String.class));
        // Row 3: neXus downed exactly this member -- the run's one DOWN intent, replied and confirmed by observation.
        List<String> downed=dsl.fetch("select member_ref from failover_dispatch_intent where run_id={0} "
            + "and step='FAILING_OVER' and delivery='REPLY_RECEIVED' and observation='CONFIRMED'",runId)
            .map(x -> x.get(0,String.class));
        if (downed.size()!=1 || !targets.contains(downed.get(0))
                || dsl.fetchOne("select count(*) from failover_dispatch_intent where run_id={0} and step='FAILING_OVER'",runId)
                    .get(0,Integer.class)!=1) return ResumeTarget.refused("RESUME_DOWN_NOT_CONFIRMED");
        // Row 4: no RETURNING intent of the run, or only one proven NOT_SENT (V140); the resume's own intent excepted.
        Record returning=dsl.fetchOne("select count(*) as total,count(*) filter (where delivery<>'NOT_SENT') as sent "
            + "from failover_dispatch_intent where run_id={0} and step='RETURNING' and job_id is distinct from {1}",runId,ownJob);
        if (returning.get("sent",Integer.class)>0 || returning.get("total",Integer.class)>1)
            return ResumeTarget.refused("RESUME_RETURN_ALREADY_ATTEMPTED");
        // Row 5: single use, in any state; an expired request is spent too and says so (§10 item 5).
        List<String> earlier=dsl.fetch("select state from failover_resume where run_id={0} and resume_id is distinct from {1}",
            runId,ownResume).map(x -> x.get(0,String.class));
        if (!earlier.isEmpty())
            return ResumeTarget.refused(earlier.contains(RESUME_EXPIRED)?RESUME_REQUEST_EXPIRED:"RESUME_ALREADY_USED");
        // Row 6: the run's own incident is open and is the one the caller saw.
        if (dsl.fetch("select 1 from failover_quarantine where execution_id={0} and cluster_ref={1} and active",runId,cluster).isEmpty())
            return ResumeTarget.refused("INCIDENT_NOT_OPEN");
        if (expectedIncident!=null && !expectedIncident.equals(runId)) return ResumeTarget.refused("INCIDENT_MISMATCH");
        // Row 7: no other incident covers the unit or either member.
        if (!dsl.fetch("select 1 from failover_quarantine q where q.active and q.execution_id<>{0} and (q.cluster_ref={1} "
                + "or q.quarantined_member_ids @> jsonb_build_array({2}::text) or q.quarantined_member_ids @> jsonb_build_array({3}::text))",
                runId,cluster,targets.get(0),targets.get(1)).isEmpty()) return ResumeTarget.refused("OPEN_INCIDENT");
        // Row 8: fleet fence, unresolved sends elsewhere, and no other run of this unit in flight.
        if (!dsl.fetch("select 1 from failover_run where run_kind='FAILOVER' and state not in ('DONE','STOPPED')").isEmpty()
                || !dsl.fetch("select 1 from failover_resume where state in "+RESUME_ACTIVE_SQL
                    + " and resume_id is distinct from {0}",ownResume).isEmpty()) return ResumeTarget.refused("FLEET_MUTATION_ACTIVE");
        if (!dsl.fetch("select 1 from failover_dispatch_intent i where i.run_id<>{0} and i.delivery<>'NOT_SENT' "
                + "and (i.observation='NOT_OBSERVED' or (i.observation='OUTCOME_UNKNOWN' and exists "
                + "(select 1 from failover_quarantine q where q.execution_id=i.run_id and q.active)))",runId).isEmpty())
            return ResumeTarget.refused("UNRESOLVED_DISPATCH");
        if (!dsl.fetch("select 1 from failover_run where cluster_ref={0} and run_id<>{1} and state not in ('DONE','STOPPED')",
                cluster,runId).isEmpty()) return ResumeTarget.refused("RUN_ALREADY_ACTIVE");
        // Row 9: the cluster's two enrolled members are the run's, and their recorded identities predate the run.
        List<String> current=JooqDeviceRepository.enrolledFailoverMembers(dsl,cluster,"check_point");
        if (current.size()!=2 || !Set.copyOf(current).equals(Set.copyOf(targets))) return ResumeTarget.refused("MEMBERSHIP_CHANGED");
        int unchanged=dsl.fetchOne("select count(*) from devices where device_id in ({0},{1}) "
            + "and recorded_identity_primary is not null and btrim(recorded_identity_primary)<>'' "
            + "and (recorded_identity_recorded_at is null or recorded_identity_recorded_at<={2})",
            targets.get(0),targets.get(1),r.get("started",java.time.OffsetDateTime.class)).get(0,Integer.class);
        if (unchanged!=2) return ResumeTarget.refused("MEMBERSHIP_CHANGED");
        String formerActive=downed.get(0);
        String peer=targets.get(0).equals(formerActive)?targets.get(1):targets.get(0);
        return new ResumeTarget(null,cluster,formerActive,peer,targets);
    }

    /** The member this run downed (its one confirmed DOWN intent), for the run view's confirm text; opaque reference. */
    public Optional<String> resumeFormerActive(String runId) {
        List<String> downed=boundary.inTransaction(dsl -> dsl.fetch("select member_ref from failover_dispatch_intent "
            + "where run_id={0} and step='FAILING_OVER' and delivery='REPLY_RECEIVED' and observation='CONFIRMED'",runId)
            .map(x -> x.get(0,String.class)));
        return downed.size()==1 ? Optional.of(downed.get(0)) : Optional.empty();
    }

    /**
     * §2.1 evaluation (rows 2–10) for the run view's {@code resumeEligible}; null when eligible. Its only write is the
     * expiry of this run's due request (row 12), so an expired request reads RESUME_REQUEST_EXPIRED, never as pending.
     */
    public String resumeEligibility(String runId) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        return audited.inTransaction(RESUME_ACTOR_EXPIRY,"failover_resume_expiry",runId,dsl -> {
            expireDue(dsl,runId);
            return resumeTarget(dsl,runId,null,null,null).refusal();
        });
    }

    /** Same columns ManualAdmissionRecord writes (V168): the job is an administrator's manual job, claimable at once. */
    private static final String RESUME_JOB_ADMISSION_SQL = "update jobs set window_bypass_role={1},"
        + "submitted_in_business_hours={2},business_hours_ack_by={3},business_hours_ack_at={4} "
        + "where job_id={0} and window_bypass_role is null";

    private String insertResumeJob(DSLContext dsl, String resumeId, String target, String actor, String role, boolean inside) {
        String jobId=UUID.randomUUID().toString();
        // The run's own job type: the same worker executor, failover module, notification route and claim rules.
        dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
            + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) "
            + "values({0},'cp_cluster_failover','cp_cluster_failover',{1},'device',{2},{3},'operational-state-change','REQUESTED')",
            jobId,target,actor,"cp_failover_resume:"+resumeId);
        requireOne(dsl.execute(RESUME_JOB_ADMISSION_SQL,jobId,role,inside,inside?actor:null,
            inside?Timestamp.from(windows.now()):null));
        return jobId;
    }

    /**
     * Contract §2.1 / §5: create the resume request in one audited transaction under advisory lock 136. A
     * security_admin's request ({@link #ADMIN_SINGLE}) is approved by its initiator and its job admitted at once; an
     * operation_admin's ({@link #TWO_PERSON}) waits for a second, distinct administrator. Inside business hours the
     * request must carry its own acknowledgement ({@code businessHoursAck}); it is stored on the request.
     */
    public Decision requestResume(String runId, String actor, String role, String policy, String reason,
            String expectedIncident, boolean businessHoursAck) {
        if (!Set.of(ADMIN_SINGLE,TWO_PERSON).contains(policy)) throw new IllegalArgumentException("INVALID_POLICY");
        if (!mutationEnabled) return new Decision("FAILOVER_MUTATION_DISABLED",null);
        return audited.inTransaction(actor,"failover_resume_request",runId,dsl -> {
            lockAdmission(dsl);
            expireDue(dsl,runId);
            ResumeTarget target=resumeTarget(dsl,runId,null,null,expectedIncident);
            if (target.refusal()!=null) return new Decision(target.refusal(),null);
            boolean inside=windows.insideBusinessHours();
            String hours=resumeBusinessHoursRefusal(inside,businessHoursAck);
            if (hours!=null) return new Decision(hours,null);
            String id=UUID.randomUUID().toString();
            boolean single=ADMIN_SINGLE.equals(policy);
            String job=single?insertResumeJob(dsl,id,target.formerActive(),actor,role,inside):null;
            requireOne(dsl.execute("insert into failover_resume(resume_id,run_id,cluster_ref,former_active_ref,peer_ref,"
                + "target_member_ids,member_set_revision,expected_incident_id,policy,initiated_by,reason,requested_at,expires_at,"
                + "requested_in_business_hours,business_hours_ack_by,business_hours_ack_at,approval_nonce,approved_by,approved_at,"
                + "job_id,state,step) select {0},{1},{2},{3},{4},jsonb_build_array({5}::text,{6}::text),{7},{8},{9},{10},{11},"
                + "t.at,t.at+make_interval(secs => {12}),{13},{14},case when {13} then t.at end,"
                + "{15},{16},case when {16}::text is null then null else t.at end,{17},{18},{18} "
                + "from (select clock_timestamp() as at) t",
                id,runId,target.clusterRef(),target.formerActive(),target.peer(),target.members().get(0),target.members().get(1),
                memberSetRevision(Set.copyOf(target.members())),runId,policy,actor,reason,
                (double)RESUME_REQUEST_LIFETIME.toSeconds(),inside,inside?actor:null,UUID.randomUUID().toString(),
                single?actor:null,job,single?"QUEUED":RESUME_PENDING));
            return new Decision(single?RESUME_ADMITTED:RESUME_PENDING,id);
        });
    }

    /** Contract §5.1: the second, distinct administrator's approval of a two-person request admits its job. */
    public Decision approveResume(String runId, String resumeId, String actor, String role, boolean businessHoursAck) {
        if (!mutationEnabled) return new Decision("FAILOVER_MUTATION_DISABLED",null);
        return audited.inTransaction(actor,"failover_resume_second_approval",runId,dsl -> {
            lockAdmission(dsl);
            Record s=dsl.fetchOne(RESUME_SELECT+"where s.resume_id={0} and s.run_id={1} for update of s",resumeId,runId);
            if (s==null) return new Decision("RESUME_NOT_FOUND",null);
            Resume resume=resume(s);
            if (!TWO_PERSON.equals(resume.policy())) return new Decision("WRONG_POLICY",null);
            // Row 12: a due request is expired here (whoever asks), and an expired one says so, not "not pending".
            if (RESUME_PENDING.equals(resume.state()) && expired(dsl,resumeId)) {
                requireOne(expire(dsl,resumeId));
                return new Decision(RESUME_REQUEST_EXPIRED,null);
            }
            if (RESUME_EXPIRED.equals(resume.state())) return new Decision(RESUME_REQUEST_EXPIRED,null);
            if (!RESUME_PENDING.equals(resume.state())) return new Decision("RESUME_NOT_PENDING",null);
            if (actor.equals(resume.initiatedBy())) return new Decision("SELF_APPROVAL",null);
            String refusal=boundRefusal(dsl,resume,null);
            if (refusal!=null) return new Decision(refusal,null);
            boolean inside=windows.insideBusinessHours();
            String hours=resumeBusinessHoursRefusal(inside,businessHoursAck);
            if (hours!=null) return new Decision(hours,null);
            String job=insertResumeJob(dsl,resumeId,resume.formerActiveRef(),actor,role,inside);
            requireOne(dsl.execute("update failover_resume set approved_by={1},approved_at=clock_timestamp(),"
                + "approved_in_business_hours={2},approval_ack_by={3},approval_ack_at=case when {2} then clock_timestamp() end,"
                + "job_id={4},state='QUEUED',step='QUEUED' where resume_id={0} and state='PENDING_APPROVAL'",
                resumeId,actor,inside,inside?actor:null,job));
            return new Decision(RESUME_ADMITTED,resumeId);
        });
    }

    /**
     * PO decision 2026-10-20 (the same predicate as every administrator's manual job, {@link JobWindowPolicy#insideBusinessHours}):
     * between 08:00 and 20:00 Europe/Istanbul a resume request, and a second approval, needs its own acknowledgement.
     * There is no job window for it. The resumed UP itself is never refused for business hours (it is a return write).
     */
    public static String resumeBusinessHoursRefusal(boolean insideBusinessHours, boolean businessHoursAck) {
        return insideBusinessHours && !businessHoursAck ? BUSINESS_HOURS_ACK_REQUIRED : null;
    }

    private static boolean expired(DSLContext dsl, String resumeId) {
        return Boolean.TRUE.equals(dsl.fetchOne("select clock_timestamp()>=expires_at as expired from failover_resume "
            + "where resume_id={0}",resumeId).get("expired",Boolean.class));
    }
    /** The one transition to EXPIRED of a request that never started (V170 guard: PENDING_APPROVAL or unstarted QUEUED). */
    private static int expire(DSLContext dsl, String resumeId) {
        return dsl.execute("update failover_resume set state='EXPIRED',outcome={1},message={1},finished_at=clock_timestamp() "
            + "where resume_id={0} and state in ('PENDING_APPROVAL','QUEUED') and started_at is null",resumeId,RESUME_REQUEST_EXPIRED);
    }

    /** §2.1 rows 2–9 against the request's own binding (members, member-set revision, former active, incident). */
    private static String boundRefusal(DSLContext dsl, Resume resume, String ownJob) {
        ResumeTarget target=resumeTarget(dsl,resume.runId(),ownJob,resume.id(),resume.expectedIncidentId());
        if (target.refusal()!=null) return target.refusal();
        if (!Set.copyOf(target.members()).equals(Set.copyOf(resume.targetMembers()))
                || !memberSetRevision(Set.copyOf(target.members())).equals(resume.memberSetRevision())
                || !target.formerActive().equals(resume.formerActiveRef()) || !target.clusterRef().equals(resume.clusterRef()))
            return "MEMBERSHIP_CHANGED";
        return null;
    }

    /**
     * Worker admission for a resume: at worker start ({@code atStart}: also the 30-minute request expiry and the start
     * time) and again before the UP intent. Rows 2–9 and 10 are re-evaluated; row 11 is the stored record (the reason,
     * and the acknowledgement recorded for a request or approval made inside business hours): the resumed UP is the
     * run's return write, so it is never refused because the clock has entered business hours since.
     */
    public String resumeAdmission(String resumeId, String jobId, long epoch, boolean atStart) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        String runId=resumeRun(resumeId);
        if (runId==null) return "RESUME_NOT_FOUND";
        return audited.inTransaction("system:failover-worker","failover_resume_admission",runId,dsl -> {
            lockAdmission(dsl);
            Record s=dsl.fetchOne(RESUME_SELECT+"where s.resume_id={0} for update of s",resumeId);
            if (s==null) return "RESUME_NOT_FOUND";
            Resume resume=resume(s);
            if (resume.terminal()) return "RESUME_TERMINAL";
            if (jobId==null || !jobId.equals(resume.jobId())) return "OWNERSHIP_LOST";
            if (dsl.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} and state='EXECUTING' "
                    + "and not cancel_requested and lease_expires_at>clock_timestamp() and ui2_job_owner_valid(job_id,lease_epoch)",
                    jobId,epoch).isEmpty()) return "OWNERSHIP_LOST";
            if (resume.approvedBy()==null) return "SECOND_APPROVAL_REQUIRED";
            String reason=s.get("reason",String.class);
            if (reason==null || reason.isBlank()) return "REASON_REQUIRED";
            if (resume.requestedInBusinessHours() && !resume.businessHoursAcknowledged()
                    || Boolean.TRUE.equals(s.get("approved_in_business_hours",Boolean.class)) && s.get("approval_ack_at")==null)
                return BUSINESS_HOURS_ACK_REQUIRED;
            if (atStart && expired(dsl,resumeId)) {
                // Row 12 at worker start: the request is EXPIRED (spent) and its job ends here, before any contact.
                requireOne(expire(dsl,resumeId));
                requireOne(dsl.execute("update jobs set state='FAILED',outcome={1},terminal_reason={1},finished_at=now() "
                    + "where job_id={0} and lease_epoch={2}",jobId,RESUME_REQUEST_EXPIRED,epoch));
                return RESUME_REQUEST_EXPIRED;
            }
            String refusal=boundRefusal(dsl,resume,jobId);
            if (refusal!=null) return refusal;
            if (atStart) dsl.execute("update failover_resume set started_at=clock_timestamp() where resume_id={0} "
                + "and started_at is null",resumeId);
            return "ADMITTED";
        });
    }

    private String resumeRun(String resumeId) {
        return boundary.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne(
            "select run_id from failover_resume where resume_id={0}",resumeId)).map(x -> x.get(0,String.class)).orElse(null));
    }

    /** The resume's command ledger entry (audit action {@code failover_command_<gate>}); the run row is never touched. */
    public void resumeCommand(String resumeId, String gateId) {
        audited.inTransaction("system:failover-worker","failover_command_"+gateId,resumeRun(resumeId),
            dsl -> dsl.execute("update failover_resume set message={1} where resume_id={0}",resumeId,gateId));
    }

    /** The run's own pre-switch check rows: the baseline the post-return checks compare against (contract §4.2). */
    public List<Check> preBaseline(String runId) {
        return boundary.inTransaction(dsl -> dsl.fetch("select phase,member_ref,vs_id,check_no,status,derived,observed_at "
            + "from failover_check_result where run_id={0} and phase='pre' order by observed_at,result_id",runId)
            .map(c -> new Check(c.get("phase",String.class),c.get("member_ref",String.class),c.get("vs_id",String.class),
                c.get("check_no",Integer.class),c.get("status",String.class),c.get("derived",JSONB.class).data(),
                c.get("observed_at",java.time.OffsetDateTime.class).toInstant())));
    }

    /**
     * Fenced resume transition (the run row stays STOPPED). A stop after the resume's write records the uncertainty of
     * its unconfirmed intent; the run's incident stays open; the job ends FAILED, or OUTCOME_UNKNOWN when its write is
     * unconfirmed (both reach the job_failure notification).
     */
    public boolean resumeState(String resumeId, long epoch, String state, String step, String outcome,
            String failedCheck, String message) {
        String runId=resumeRun(resumeId);
        if (runId==null) return false;
        return audited.inTransaction("system:failover-worker","failover_resume_state",runId,dsl -> {
            if (!dispatchLock(dsl,runId)) return false;
            lockAdmission(dsl);
            Record s=dsl.fetchOne("select job_id,mutation_possible,step from failover_resume where resume_id={0} "
                + "and state not in ('DONE','STOPPED','EXPIRED','PENDING_APPROVAL') for update",resumeId);
            if (s==null || !owns(dsl,s.get("job_id",String.class),epoch)) return false;
            String job=s.get("job_id",String.class);
            if ("STOPPED".equals(state) && Boolean.TRUE.equals(s.get("mutation_possible",Boolean.class))
                    && !dsl.fetch("select 1 from failover_dispatch_intent where job_id={0} and delivery<>'NOT_SENT' "
                        + "and observation<>'CONFIRMED'",job).isEmpty()) {
                unknownResume(dsl,job,message,failedCheck);
                return true;
            }
            if ("DONE".equals(state) && !dsl.fetch("select 1 from failover_dispatch_intent where job_id={0} "
                    + "and observation<>'CONFIRMED'",job).isEmpty()) throw new IllegalStateException("UNRESOLVED_DISPATCH");
            requireOne(dsl.execute("update failover_resume set state={1},step={2},outcome={3},failed_check={4},message={5},"
                + "finished_at=case when {1} in ('DONE','STOPPED') then clock_timestamp() else null end where resume_id={0}",
                resumeId,state,"STOPPED".equals(state)?s.get("step",String.class):step,outcome,failedCheck,message));
            if (Set.of("DONE","STOPPED").contains(state)) requireOne(dsl.execute("update jobs set state={1},"
                + "outcome={1},terminal_reason={2},finished_at=now() where job_id={0} and lease_epoch={3}",
                job,"DONE".equals(state)?"COMPLETED":"FAILED",outcome,epoch));
            return true;
        });
    }

    private static void unknownResume(DSLContext dsl, String job, String reason, String failedCheck) {
        lockAdmission(dsl);
        dsl.execute("update failover_dispatch_intent set observation='OUTCOME_UNKNOWN',"
            + "delivery=case when delivery='MAY_HAVE_BEEN_SENT' then 'UNKNOWN' else delivery end "
            + "where job_id={0} and delivery<>'NOT_SENT' and observation='NOT_OBSERVED'",job);
        dsl.execute("update job_step_attempt set outcome='OUTCOME_UNKNOWN',error_class='OUTCOME_UNCERTAIN',"
            + "matched_expectation=false where attempt_id in (select attempt_id from failover_dispatch_intent "
            + "where job_id={0} and observation='OUTCOME_UNKNOWN')",job);
        dsl.execute("update failover_resume set state='STOPPED',outcome='OUTCOME_UNKNOWN',message={1},"
            + "failed_check=coalesce({2},failed_check),finished_at=clock_timestamp() "
            + "where job_id={0} and state not in ('DONE','STOPPED','EXPIRED')",job,reason,failedCheck);
        requireOne(dsl.execute("update jobs set state='OUTCOME_UNKNOWN',outcome='OUTCOME_UNKNOWN',"
            + "terminal_reason={1},finished_at=now() where job_id={0}",job,reason));
    }

    /**
     * Commit the resume's single RETURNING intent and its attempt before the transport is invoked: the run's former
     * active only, gate {@code cp_failover_up} only, at most once per run counting the resume (V170 partial unique).
     */
    public Dispatch prepareResumeDispatch(String resumeId, long epoch, int index, String member, String gate,
            String actionClass) {
        if (!mutationEnabled) throw new IllegalStateException("FAILOVER_MUTATION_DISABLED");
        String runId=resumeRun(resumeId);
        if (runId==null) throw new IllegalStateException("RESUME_NOT_FOUND");
        return audited.inTransaction("system:failover-worker","failover_dispatch_intent",runId,dsl -> {
            if (!dispatchLock(dsl,runId)) throw new IllegalStateException("DISPATCH_BUSY");
            lockAdmission(dsl);
            Record s=dsl.fetchOne(RESUME_SELECT+"where s.resume_id={0} for update of s",resumeId);
            if (s==null) throw new IllegalStateException("RESUME_NOT_FOUND");
            Resume resume=resume(s);
            if (!"RETURNING".equals(resume.state()) || !owns(dsl,resume.jobId(),epoch))
                throw new IllegalStateException("DISPATCH_OWNER_LOST");
            if (!"cp_failover_up".equals(gate) || !resume.formerActiveRef().equals(member))
                throw new IllegalStateException("RESUME_WRITE_NOT_BOUND");
            String refusal=boundRefusal(dsl,resume,resume.jobId());
            if (refusal!=null) throw new IllegalStateException(refusal);
            if (!dsl.fetch("select 1 from failover_dispatch_intent where job_id={0}",resume.jobId()).isEmpty())
                throw new IllegalStateException("PRIOR_ATTEMPT_NOT_REPLAYED");
            String nonce=UUID.randomUUID().toString(), attempt=UUID.randomUUID().toString();
            requireOne(dsl.execute("insert into job_step_attempt(attempt_id,job_id,lease_epoch,step_index,step_kind,"
                + "attempt_number,action_class,mutation_boundary_crossed,sent_at) values({0},{1},{2},{3},{4},1,{5},true,now())",
                attempt,resume.jobId(),epoch,index,gate,actionClass));
            requireOne(dsl.execute("insert into failover_dispatch_intent(nonce,run_id,attempt_id,job_id,lease_epoch,"
                + "owner_generation,owner_instance,step,member_ref,gate_id) select {0},{1},{2},job_id,lease_epoch,"
                + "lease_owner_generation,lease_worker_id,'RETURNING',{3},{4} from jobs where job_id={5} and lease_epoch={6}",
                nonce,runId,attempt,member,gate,resume.jobId(),epoch));
            requireOne(dsl.execute("update failover_resume set mutation_possible=true,message={1} where resume_id={0}",resumeId,gate));
            return new Dispatch(nonce,runId,resume.jobId(),epoch);
        });
    }

    /**
     * The resume's single send: the same consume-once claim and reply recording as {@link #dispatch}, fenced on the
     * resume. Immediately before the send, still owning the claimed intent, it rechecks the resume's state and bound
     * member and the full §2.1 rows 2–9 against the request's binding ({@link #boundRefusal}); a refusal there ends the
     * resume with that code and records the intent NOT_SENT (the transport was never invoked), then throws
     * {@link NotSent} carrying the code. No advisory admission lock is held across the transport itself.
     */
    public boolean dispatchResume(Dispatch intent, java.util.function.Supplier<Boolean> send) {
        if (!mutationEnabled || intent==null) throw new IllegalStateException("DISPATCH_REFUSED");
        boolean claimed=audited.inTransaction("system:failover-worker","failover_dispatch_claim",intent.runId(),dsl -> {
            if (!dispatchLock(dsl,intent.runId()) || !owns(dsl,intent.jobId(),intent.epoch())) return false;
            return dsl.execute("update failover_dispatch_intent set dispatch_claimed=true where nonce={0} "
                + "and run_id={1} and job_id={2} and lease_epoch={3} and not dispatch_claimed "
                + "and observation='NOT_OBSERVED'",intent.nonce(),intent.runId(),intent.jobId(),intent.epoch())==1;
        });
        if (!claimed) return false;
        String[] refused={null};
        SendResult result=audited.inTransaction("system:failover-worker","failover_dispatch_reply",intent.runId(),dsl -> {
            if (!dispatchLock(dsl,intent.runId())) return SendResult.NO_REPLY;
            Record row=dsl.fetchOne("select * from failover_dispatch_intent where nonce={0} and run_id={1} "
                + "and job_id={2} and lease_epoch={3} and delivery='MAY_HAVE_BEEN_SENT' "
                + "and observation='NOT_OBSERVED' and dispatch_claimed for update",intent.nonce(),intent.runId(),intent.jobId(),intent.epoch());
            if (row==null || !owns(dsl,intent.jobId(),intent.epoch())) return SendResult.NO_REPLY;
            // Recheck the resume, its committed owner, the bound member and §2.1 rows 2–9 immediately before the send.
            String refusal=resumeSendRefusal(dsl,intent,row);
            if (refusal!=null) {
                notSentResume(dsl,intent,row,refusal);
                refused[0]=refusal;
                return SendResult.NOT_SENT;
            }
            boolean replied;
            try { replied=Boolean.TRUE.equals(send.get()); }
            catch (NotSent notSent) {
                notSentResume(dsl,intent,row,notSent.code());
                refused[0]=notSent.code();
                return SendResult.NOT_SENT;
            }
            catch (RuntimeException uncertain) { replied=false; }
            requireOne(dsl.execute("update failover_dispatch_intent set delivery={1} where nonce={0}",
                intent.nonce(),replied?"REPLY_RECEIVED":"UNKNOWN"));
            requireOne(dsl.execute("update job_step_attempt set outcome={1},matched_expectation=false,error_class={2} "
                + "where attempt_id={0}",row.get("attempt_id"),replied?"REPLY_RECEIVED":"OUTCOME_UNKNOWN",
                replied?null:"DELIVERY_UNCERTAIN"));
            if (!replied) unknownResume(dsl,intent.jobId(),"DELIVERY_UNCERTAIN",null);
            return replied?SendResult.REPLIED:SendResult.NO_REPLY;
        });
        // Propagate only after the terminal state and the NOT_SENT intent have committed together.
        if (result==SendResult.NOT_SENT) throw new NotSent(refused[0]);
        return result==SendResult.REPLIED;
    }

    /** The pre-send recheck of {@link #dispatchResume}, in the send transaction; null when the single UP may go. */
    private static String resumeSendRefusal(DSLContext dsl, Dispatch intent, Record row) {
        Record s=dsl.fetchOne(RESUME_SELECT+"join jobs j on j.job_id=s.job_id where s.job_id={0} and s.state='RETURNING' "
            + "and j.lease_worker_id={1} and j.lease_owner_generation={2}",intent.jobId(),row.get("owner_instance"),
            row.get("owner_generation"));
        if (s==null) return "DISPATCH_OWNER_LOST";
        Resume resume=resume(s);
        if (!resume.formerActiveRef().equals(row.get("member_ref",String.class))
                || !"cp_failover_up".equals(row.get("gate_id",String.class))) return "RESUME_WRITE_NOT_BOUND";
        return boundRefusal(dsl,resume,intent.jobId());
    }

    /**
     * The write never left neXus: the intent and its attempt are NOT_SENT, the resume stops with {@code code} and its
     * job fails, in the send transaction (the run's incident stays open; nothing is retried).
     */
    private static void notSentResume(DSLContext dsl, Dispatch intent, Record row, String code) {
        lockAdmission(dsl);
        requireOne(dsl.execute("update failover_dispatch_intent set delivery='NOT_SENT' where nonce={0}",intent.nonce()));
        requireOne(dsl.execute("update job_step_attempt set outcome='NOT_SENT',matched_expectation=false,"
            + "error_class={1},mutation_boundary_crossed=false,sent_at=null where attempt_id={0}",row.get("attempt_id"),code));
        requireOne(dsl.execute("update failover_resume set state='STOPPED',outcome={1},message={1},"
            + "finished_at=clock_timestamp() where job_id={0} and state not in ('DONE','STOPPED','EXPIRED')",intent.jobId(),code));
        requireOne(dsl.execute("update jobs set state='FAILED',outcome={1},terminal_reason={1},finished_at=now() "
            + "where job_id={0} and lease_epoch={2}",intent.jobId(),code,intent.epoch()));
    }
}
