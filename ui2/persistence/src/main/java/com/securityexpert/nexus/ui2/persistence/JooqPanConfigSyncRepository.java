package com.securityexpert.nexus.ui2.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.jobrecords.ManualAdmissionRecord;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;

/**
 * Audited storage and admission for one Palo Alto HA configuration sync (V178;
 * docs/design/PAN_CONFIG_SYNC_REMEDIATION_2026_10_24.md, PO decision 2026-10-24 RATIFIED). Every decision is taken in
 * one audited transaction under the failover admission lock (advisory lock 136), so it serializes with failover and
 * readiness admission. Opaque references only; no device output is stored.
 */
public class JooqPanConfigSyncRepository {
    /** Design §3.1 R8: the readiness the request relies on ended at most this long ago. */
    public static final Duration READINESS_MAX_AGE = Duration.ofMinutes(60);
    /** Design §3.2 W2: a request the worker has not started within this time is never started. */
    public static final Duration REQUEST_LIFETIME = Duration.ofMinutes(30);
    public static final String JOB_TYPE = "pan_failover_config_sync";
    public static final String ADMITTED = "ADMITTED";
    public static final Set<String> OPEN_STATES = Set.of("QUEUED", "READING_STATE", "SYNCING");
    public static final Set<String> TERMINAL_STATES = Set.of("DONE", "STOPPED");
    public static final String REQUESTED = "CONFIG_SYNC_REQUESTED";
    public static final String DEVICE_REPORTED_ERROR = "DEVICE_REPORTED_ERROR";
    public static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    public static final String EXPIRED = "CONFIG_SYNC_REQUEST_EXPIRED";
    /** P1-3: the write's attempt was committed but the switch was off, or the transport refused before any contact. */
    public static final String MUTATION_DISABLED_BEFORE_SEND = "MUTATION_DISABLED_BEFORE_SEND";
    public static final String NOT_SENT_BEFORE_CONTACT = "NOT_SENT_BEFORE_CONTACT";
    public static final Set<String> NOT_SENT = Set.of(MUTATION_DISABLED_BEFORE_SEND, NOT_SENT_BEFORE_CONTACT);
    /** The follow-up readiness of an administrator's admitted sync acknowledged by the job itself (Argon P0-3). */
    public static final String FOLLOW_UP_ACTOR = "system:pan-config-sync-followup";
    private static final String WORKER = "system:failover-worker";

    /** One request and its end; opaque references only. */
    public record ConfigSync(String id, String clusterRef, String sourceRef, String targetRef, String readinessRunId,
            String initiatedBy, String initiatorRole, Instant requestedAt, boolean requestedInBusinessHours,
            boolean businessHoursAcknowledged, String jobId, String state, String outcome, String deviceStatus,
            boolean mutationPossible, String followupReadinessRunId, String followupRefusal, Instant startedAt,
            Instant finishedAt) {
        public boolean open() { return OPEN_STATES.contains(state); }
    }
    /** The outcome of the write's preparation: {@code ADMITTED} with the committed attempt, or a refusal code. */
    public record Prepared(String code, String attemptId) {}

    private final boolean mutationEnabled;
    private final JobWindowPolicy windows;
    private final TransactionBoundary boundary;
    private final AuditedTransactionBoundary audited;
    private final JooqCpFailoverRepository failover;

    public JooqPanConfigSyncRepository(TransactionBoundary boundary) {
        this(boundary, "true".equalsIgnoreCase(System.getenv("NEXUS_FAILOVER_MUTATION_ENABLED")), JobWindowPolicy.SYSTEM);
    }

    public JooqPanConfigSyncRepository(TransactionBoundary boundary, boolean mutationEnabled, JobWindowPolicy windows) {
        this.boundary = boundary;
        this.mutationEnabled = mutationEnabled;
        this.windows = windows;
        this.audited = new AuditedTransactionBoundary(boundary);
        this.failover = new JooqCpFailoverRepository(boundary, mutationEnabled, windows);
    }

    private static Instant instant(Record r, String field) {
        return Optional.ofNullable(r.get(field, OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null);
    }
    private static ConfigSync sync(Record r) {
        return new ConfigSync(r.get("sync_id", String.class), r.get("cluster_ref", String.class),
            r.get("source_member_ref", String.class), r.get("target_member_ref", String.class),
            r.get("readiness_run_id", String.class), r.get("initiated_by", String.class),
            r.get("initiator_role", String.class), instant(r, "requested_at"),
            Boolean.TRUE.equals(r.get("requested_in_business_hours", Boolean.class)), r.get("business_hours_ack_at") != null,
            r.get("job_id", String.class), r.get("state", String.class), r.get("outcome", String.class),
            r.get("device_status", String.class), Boolean.TRUE.equals(r.get("mutation_possible", Boolean.class)),
            r.get("followup_readiness_run_id", String.class), r.get("followup_refusal", String.class),
            instant(r, "started_at"), instant(r, "finished_at"));
    }

    public Optional<ConfigSync> find(String syncId) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from pan_config_sync where sync_id={0}", syncId)
            .stream().findFirst().map(JooqPanConfigSyncRepository::sync));
    }
    public Optional<ConfigSync> byJob(String jobId) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from pan_config_sync where job_id={0}", jobId)
            .stream().findFirst().map(JooqPanConfigSyncRepository::sync));
    }
    /** The newest request of one cluster, open or ended. */
    public Optional<ConfigSync> latest(String clusterRef) {
        return boundary.inTransaction(dsl -> dsl.fetch("select * from pan_config_sync where cluster_ref={0} "
            + "order by requested_at desc, sync_id limit 1", clusterRef).stream().findFirst().map(JooqPanConfigSyncRepository::sync));
    }

    /**
     * Design §3.1 R7, R11, R12 (and W3 in the job): the cluster's two enrolled Palo Alto members are the bound pair; no
     * open run or resume of the unit; no other open config sync; no open incident and no unresolved failover write on
     * the cluster or a member (Argon P1-2). Lock 136 held.
     */
    private static String unitRefusal(DSLContext dsl, String cluster, String source, String target, String ownSync) {
        List<String> members = JooqDeviceRepository.enrolledFailoverMembers(dsl, cluster, "palo_alto");
        if (members.size() != 2 || !Set.copyOf(members).equals(Set.of(source, target))) return "MEMBER_SET_CHANGED";
        String busy = busy(dsl, cluster, source, target, ownSync);
        if (busy != null) return busy;
        if (!dsl.fetch("select 1 from failover_quarantine where active and (cluster_ref={0} "
                + "or quarantined_member_ids @> jsonb_build_array({1}::text) "
                + "or quarantined_member_ids @> jsonb_build_array({2}::text)) limit 1", cluster, source, target).isEmpty())
            return "OPEN_INCIDENT";
        return null;
    }

    /** Open run, resume or other sync of the unit, or an unresolved failover write on it; null when none. */
    private static String busy(DSLContext dsl, String cluster, String source, String target, String ownSync) {
        if (!dsl.fetch("select 1 from failover_run where cluster_ref={0} and state not in ('DONE','STOPPED') limit 1",
                cluster).isEmpty()) return "RUN_ALREADY_ACTIVE";
        if (!dsl.fetch("select 1 from failover_resume where cluster_ref={0} and state in "
                + "('PENDING_APPROVAL','QUEUED','READING_STATE','RETURNING','POST_RETURN') limit 1", cluster).isEmpty())
            return "RUN_ALREADY_ACTIVE";
        if (!dsl.fetch("select 1 from failover_dispatch_intent i join failover_run r on r.run_id=i.run_id "
                + "where i.delivery<>'NOT_SENT' and i.observation='NOT_OBSERVED' "
                + "and (r.cluster_ref={0} or i.member_ref in ({1},{2})) limit 1", cluster, source, target).isEmpty())
            return "UNRESOLVED_DISPATCH";
        if (!dsl.fetch("select 1 from pan_config_sync where cluster_ref={0} and state in "
                + JooqCpFailoverRepository.CONFIG_SYNC_OPEN_SQL + " and sync_id is distinct from {1} limit 1",
                cluster, ownSync).isEmpty()) return "CONFIG_SYNC_ALREADY_ACTIVE";
        return null;
    }

    /** For the screens (Argon P1-2): what would refuse a request on this unit now, besides the readiness evidence. */
    public String unitBusy(String cluster, String source, String target) {
        return boundary.inTransaction(dsl -> busy(dsl, cluster, source, target, null));
    }

    /** The unit's newest finished readiness run and what it recorded for checks 1 (roles) and 4 (configuration sync). */
    static final String EVIDENCE_SQL = "with latest as (select run_id,finished_at from failover_run "
        + "where run_kind='READINESS' and state in ('DONE','STOPPED') and vendor='palo_alto' and cluster_ref={0} "
        + "and vs_id is null order by finished_at desc nulls last limit 1) "
        + "select l.run_id, l.finished_at>clock_timestamp()-make_interval(secs => {3}) as fresh, "
        + "exists(select 1 from failover_check_result c where c.run_id=l.run_id and c.phase='pre' and c.check_no=4 "
        + "and c.status='FAIL') as sync_failed, "
        // Argon P1-1: a role counts only from a check 1 row that PASSED (mode, reciprocal identity and roles).
        + "(select c.derived->>'role' from failover_check_result c where c.run_id=l.run_id and c.phase='pre' "
        + "and c.check_no=1 and c.status='PASS' and c.member_ref={1} order by c.observed_at desc,c.result_id desc limit 1) as source_role, "
        + "(select c.derived->>'role' from failover_check_result c where c.run_id=l.run_id and c.phase='pre' "
        + "and c.check_no=1 and c.status='PASS' and c.member_ref={2} order by c.observed_at desc,c.result_id desc limit 1) as target_role "
        + "from latest l";

    /**
     * Design §3.1: create the request and its job in one audited transaction. The actor is the session; the role and
     * the business-hours acknowledgement come from the administrator's manual-admission grant open on this thread (the
     * service interceptor), never from a request field. Without a grant for {@code actor} nothing is created.
     */
    public JooqCpFailoverRepository.Decision request(String cluster, String source, String target, String actor) {
        if (!mutationEnabled) return new JooqCpFailoverRepository.Decision("FAILOVER_MUTATION_DISABLED", null);
        var grant = ManualAdmission.currentFor(actor);
        if (grant.isEmpty()) return new JooqCpFailoverRepository.Decision("WRONG_ROLE", null);
        if (cluster == null || source == null || target == null || source.equals(target))
            return new JooqCpFailoverRepository.Decision("MEMBER_SET_CHANGED", null);
        windows.requireOpenFor(actor);
        return audited.inTransaction(actor, "pan_config_sync_request", dsl -> {
            JooqCpFailoverRepository.lockAdmission(dsl);
            String refusal = unitRefusal(dsl, cluster, source, target, null);
            if (refusal != null) return new JooqCpFailoverRepository.Decision(refusal, null);
            Record evidence = dsl.fetchOne(EVIDENCE_SQL, cluster, source, target, (double) READINESS_MAX_AGE.toSeconds());
            if (evidence == null) return new JooqCpFailoverRepository.Decision("READINESS_REQUIRED", null);
            if (!Boolean.TRUE.equals(evidence.get("fresh", Boolean.class)))
                return new JooqCpFailoverRepository.Decision("READINESS_NOT_FRESH", null);
            if (!Boolean.TRUE.equals(evidence.get("sync_failed", Boolean.class)))
                return new JooqCpFailoverRepository.Decision("CONFIG_SYNC_NOT_FAILED", null);
            if (!"active".equals(evidence.get("source_role", String.class))
                    || !"passive".equals(evidence.get("target_role", String.class)))
                return new JooqCpFailoverRepository.Decision("ROLES_CHANGED", null);
            String syncId = UUID.randomUUID().toString(), jobId = UUID.randomUUID().toString();
            dsl.execute("insert into jobs(job_id,job_type,capability_id,target_device_id,target_kind,"
                + "submitted_by_actor_fingerprint,idempotency_key,action_class,state) "
                + "values({0},{1},{1},{2},'device',{3},{4},'configuration-write','REQUESTED')",
                jobId, JOB_TYPE, source, actor, JOB_TYPE + ":" + syncId);
            // The grant (role, business hours, acknowledgement) is bound to the job row in this transaction; the worker
            // rechecks business hours before the write (design §3.2 W4).
            ManualAdmissionRecord.record(dsl, jobId, actor, windows);
            Record job = dsl.fetchOne("select coalesce(submitted_in_business_hours,false) as inside "
                + "from jobs where job_id={0}", jobId);
            // Argon P1-4: the administrator's acknowledgement is kept on the record whenever it was given, also just
            // before 08:00 (the job row records it only inside business hours), so the write is not refused at 08:00.
            OffsetDateTime acknowledged = grant.get().acknowledged() ? OffsetDateTime.now(java.time.ZoneOffset.UTC) : null;
            JooqCpFailoverRepository.requireOne(dsl.execute("insert into pan_config_sync(sync_id,cluster_ref,"
                + "source_member_ref,target_member_ref,member_set_revision,readiness_run_id,initiated_by,initiator_role,"
                + "requested_in_business_hours,business_hours_ack_at,job_id,state) "
                + "values({0},{1},{2},{3},{4},{5},{6},{7},{8},{9},{10},'QUEUED')",
                syncId, cluster, source, target, JooqCpFailoverRepository.memberSetRevision(Set.of(source, target)),
                evidence.get("run_id", String.class), actor, grant.get().role(), job.get("inside", Boolean.class),
                acknowledged, jobId));
            return new JooqCpFailoverRepository.Decision(ADMITTED, syncId);
        });
    }

    /** Lease, cancel and module-owner check of the record's own job, in the caller's transaction. */
    private static boolean jobOwned(DSLContext dsl, String jobId, long epoch) {
        return !dsl.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} and state='EXECUTING' "
            + "and not cancel_requested and lease_expires_at>clock_timestamp() and ui2_job_owner_valid(job_id,lease_epoch)",
            jobId, epoch).isEmpty();
    }

    /** Design §3.2 W1-W4 in the caller's transaction (lock 136 held); null when the job may go on. */
    private String admissionRefusal(DSLContext dsl, ConfigSync sync, String jobId, long epoch) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        if (!sync.open()) return "CONFIG_SYNC_TERMINAL";
        if (jobId == null || !jobId.equals(sync.jobId()) || !jobOwned(dsl, jobId, epoch)) return "OWNERSHIP_LOST";
        String unit = unitRefusal(dsl, sync.clusterRef(), sync.sourceRef(), sync.targetRef(), sync.id());
        if (unit != null) return unit;
        // Design §3.2 W4 against the record's own acknowledgement (Argon P1-4), not the job row's.
        return windows.insideBusinessHours() && !sync.businessHoursAcknowledged() ? ManualAdmission.CONFIRM_CODE : null;
    }

    /**
     * After the write (Argon P0-2): the job still runs under this epoch. Neither a cancel request nor the lease clock
     * can hide or rewrite the outcome of a write that may have left neXus.
     */
    private static boolean jobHeld(DSLContext dsl, String jobId, long epoch) {
        return !dsl.fetch("select job_id from jobs where job_id={0} and lease_epoch={1} and state='EXECUTING'",
            jobId, epoch).isEmpty();
    }

    /**
     * Worker start (design §3.2): the request's lifetime, then W1-W4. Admitted, the record moves to READING_STATE; the
     * job's reads follow. A refusal leaves the record as it is: the worker ends it with that code ({@link #finish}).
     */
    public String admission(String syncId, String jobId, long epoch) {
        if (!mutationEnabled) return "FAILOVER_MUTATION_DISABLED";
        return audited.inTransaction(WORKER, "pan_config_sync_admission", syncId, dsl -> {
            JooqCpFailoverRepository.lockAdmission(dsl);
            Record r = dsl.fetchOne("select *,requested_at<=clock_timestamp()-make_interval(secs => {1}) as expired "
                + "from pan_config_sync where sync_id={0} for update", syncId, (double) REQUEST_LIFETIME.toSeconds());
            if (r == null) return "CONFIG_SYNC_NOT_FOUND";
            ConfigSync sync = sync(r);
            if (!"QUEUED".equals(sync.state())) return sync.open() ? "PRIOR_ATTEMPT_NOT_REPLAYED" : "CONFIG_SYNC_TERMINAL";
            if (Boolean.TRUE.equals(r.get("expired", Boolean.class))) return EXPIRED;
            String refusal = admissionRefusal(dsl, sync, jobId, epoch);
            if (refusal != null) return refusal;
            JooqCpFailoverRepository.requireOne(dsl.execute("update pan_config_sync set state='READING_STATE',"
                + "started_at=clock_timestamp() where sync_id={0} and state='QUEUED'", syncId));
            return ADMITTED;
        });
    }

    /**
     * Immediately before the write (design §3.3): W1-W4 again, the bound source member and gate only, then
     * {@code mutation_possible} and the write's step attempt are committed together ({@code mutation_boundary_crossed}).
     * The transport is invoked only after this transaction commits, and only once.
     */
    public Prepared prepareSend(String syncId, String jobId, long epoch, int stepIndex, String member, String gateId,
            String actionClass) {
        if (!mutationEnabled) return new Prepared("FAILOVER_MUTATION_DISABLED", null);
        return audited.inTransaction(WORKER, "pan_config_sync_send", syncId, dsl -> {
            JooqCpFailoverRepository.lockAdmission(dsl);
            Record r = dsl.fetchOne("select * from pan_config_sync where sync_id={0} for update", syncId);
            if (r == null) return new Prepared("CONFIG_SYNC_NOT_FOUND", null);
            ConfigSync sync = sync(r);
            if (!"READING_STATE".equals(sync.state()) || sync.mutationPossible())
                return new Prepared("PRIOR_ATTEMPT_NOT_REPLAYED", null);
            if (!sync.sourceRef().equals(member) || !"pan_failover_config_sync".equals(gateId)
                    || !"configuration-write".equals(actionClass)) return new Prepared("WRITE_NOT_BOUND", null);
            String refusal = admissionRefusal(dsl, sync, jobId, epoch);
            if (refusal != null) return new Prepared(refusal, null);
            if (!JooqCpFailoverRepository.owns(dsl, jobId, epoch)) return new Prepared("OWNERSHIP_LOST", null);
            String attempt = UUID.randomUUID().toString();
            JooqCpFailoverRepository.requireOne(dsl.execute("insert into job_step_attempt(attempt_id,job_id,lease_epoch,"
                + "step_index,step_kind,attempt_number,action_class,mutation_boundary_crossed,sent_at) "
                + "values({0},{1},{2},{3},{4},1,{5},true,now())", attempt, jobId, epoch, stepIndex, gateId, actionClass));
            JooqCpFailoverRepository.requireOne(dsl.execute("update pan_config_sync set state='SYNCING',"
                + "mutation_possible=true where sync_id={0} and state='READING_STATE'", syncId));
            return new Prepared(ADMITTED, attempt);
        });
    }

    /**
     * The write's reply as soon as it is known (design §4), before the settle pause: {@code deviceStatus} is
     * {@code success}, {@code error} or null (no usable reply). {@code attemptOutcome} goes on the write's attempt
     * ({@code NOT_SENT} also clears its boundary: the transport was never invoked).
     */
    public boolean recordReply(String syncId, String jobId, long epoch, String attemptId, String deviceStatus,
            String attemptOutcome) {
        // Argon P0-2: recorded whatever the job's cancel flag or lease says; only the attempt's own job and epoch bind it.
        return audited.inTransaction(WORKER, "pan_config_sync_reply", syncId, dsl -> {
            boolean notSent = "NOT_SENT".equals(attemptOutcome);
            int attempts = dsl.execute("update job_step_attempt set outcome={1},"
                + "matched_expectation={2},error_class={3},mutation_boundary_crossed=case when {4} then false "
                + "else mutation_boundary_crossed end,sent_at=case when {4} then null else sent_at end "
                + "where attempt_id={0} and job_id={5} and lease_epoch={6} and outcome is null", attemptId, attemptOutcome,
                "success".equals(deviceStatus), "success".equals(deviceStatus) ? null : notSent ? "NOT_SENT"
                    : deviceStatus == null ? "DELIVERY_UNCERTAIN" : "DEVICE_REPORTED_ERROR", notSent, jobId, epoch);
            int record = deviceStatus == null ? 0 : dsl.execute("update pan_config_sync set device_status={1} "
                + "where sync_id={0} and job_id={2} and state='SYNCING' and device_status is null", syncId, deviceStatus, jobId);
            return attempts == 1 && (deviceStatus == null || record == 1);
        });
    }

    /**
     * Ends the record and its job in one audited transaction (design §4): DONE / COMPLETED for a recorded device
     * success, OUTCOME_UNKNOWN for a write that may have been sent without a usable reply, STOPPED / FAILED otherwise.
     * A not-sent outcome ({@link #NOT_SENT}) also clears {@code mutation_possible}. Argon P0-2: bound to the job's epoch
     * while it is EXECUTING, not to its cancel flag. Returns false when the record or the job had already ended.
     */
    public boolean finish(String syncId, String jobId, long epoch, String outcome) {
        return audited.inTransaction(WORKER, "pan_config_sync_end", syncId, dsl -> {
            JooqCpFailoverRepository.lockAdmission(dsl);
            Record r = dsl.fetchOne("select * from pan_config_sync where sync_id={0} and job_id={1} "
                + "and state in " + JooqCpFailoverRepository.CONFIG_SYNC_OPEN_SQL + " for update", syncId, jobId);
            if (r == null || !jobHeld(dsl, jobId, epoch)) return false;
            ConfigSync sync = sync(r);
            boolean notSent = NOT_SENT.contains(outcome) && "SYNCING".equals(sync.state()) && sync.deviceStatus() == null;
            boolean done = REQUESTED.equals(outcome) && "success".equals(sync.deviceStatus());
            // A write that may have left neXus ends either as the device's recorded answer or as OUTCOME_UNKNOWN.
            String recordOutcome;
            if (done) recordOutcome = REQUESTED;
            else if (notSent || !sync.mutationPossible()) recordOutcome = outcome;
            else if (DEVICE_REPORTED_ERROR.equals(outcome) && "error".equals(sync.deviceStatus())) recordOutcome = DEVICE_REPORTED_ERROR;
            else recordOutcome = OUTCOME_UNKNOWN;
            String jobState = done ? "COMPLETED" : OUTCOME_UNKNOWN.equals(recordOutcome) ? "OUTCOME_UNKNOWN" : "FAILED";
            JooqCpFailoverRepository.requireOne(dsl.execute("update pan_config_sync set state={1},outcome={2},"
                + "mutation_possible=case when {3} then false else mutation_possible end,"
                + "finished_at=clock_timestamp() where sync_id={0}", syncId, done ? "DONE" : "STOPPED", recordOutcome, notSent));
            JooqCpFailoverRepository.requireOne(dsl.execute("update jobs set state={1},outcome={1},terminal_reason={2},"
                + "finished_at=now() where job_id={0} and lease_epoch={3}", jobId, jobState, recordOutcome, epoch));
            return true;
        });
    }

    /**
     * Design §4 (Argon P0-3): the unit's readiness re-run, after the record has ended and in a transaction of its own,
     * so nothing here can roll back or rewrite the outcome. It is the initiator's run, under the initiator's grant (the
     * recorded role), and it is never refused for business hours: inside them it carries the initiator's
     * acknowledgement when the request had one, else the follow-up's own ({@link #FOLLOW_UP_ACTOR}). Returns the
     * readiness run id, or null with the refusal kept on the record.
     */
    public String followUp(String syncId) {
        return audited.inTransaction(WORKER, "pan_config_sync_followup", syncId, dsl -> {
            JooqCpFailoverRepository.lockAdmission(dsl);
            Record r = dsl.fetchOne("select * from pan_config_sync where sync_id={0} for update", syncId);
            if (r == null) return null;
            ConfigSync sync = sync(r);
            if (sync.open() || sync.followupReadinessRunId() != null || sync.followupRefusal() != null) return null;
            var grant = new ManualAdmission.Grant(sync.initiatedBy(), sync.initiatorRole(), true);
            String member = sync.sourceRef().compareTo(sync.targetRef()) < 0 ? sync.sourceRef() : sync.targetRef();
            var decision = ManualAdmission.under(grant, () ->
                failover.readinessIn(dsl, sync.clusterRef(), null, sync.initiatedBy(), member, "palo_alto"));
            String runId = ADMITTED.equals(decision.code()) ? decision.runId() : null;
            if (runId != null && !sync.businessHoursAcknowledged()) dsl.execute("update jobs set business_hours_ack_by={1} "
                + "where job_id=(select job_id from failover_run where run_id={0}) and business_hours_ack_by is not null",
                runId, FOLLOW_UP_ACTOR);
            JooqCpFailoverRepository.requireOne(dsl.execute("update pan_config_sync set followup_readiness_run_id={1},"
                + "followup_refusal={2} where sync_id={0}", syncId, runId, runId == null ? decision.code() : null));
            return runId;
        });
    }

    /** A follow-up that failed for another reason keeps that code on the ended record (never the outcome). */
    public void followUpFailed(String syncId, String code) {
        audited.inTransaction(WORKER, "pan_config_sync_followup", syncId, dsl -> dsl.execute(
            "update pan_config_sync set followup_refusal={1} where sync_id={0} and state in ('DONE','STOPPED') "
            + "and followup_readiness_run_id is null and followup_refusal is null", syncId, code));
    }
}
