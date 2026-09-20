package com.securityexpert.nexus.ui2.service.failover;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverAuthorizationRequest;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FailoverLeaseToken;
import com.securityexpert.nexus.ui2.jobs.failover.authz.FourEyesAuthorizationResult;
import com.securityexpert.nexus.ui2.jobs.failover.checks.ClockHealthCheck;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverActionKind;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionResult;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverExecutionState;
import com.securityexpert.nexus.ui2.jobs.failover.model.*;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service coordinating scheduled maintenance window failovers.
 * Invariants:
 * 1. Cryptographic schedule binding: HMAC-SHA256 over length-prefixed canonical envelope.
 * 2. Hard temporal execution deadline: min(windowEnd, windowStart + maxStartDelayMinutes).
 * 3. Atomic in-lock single-snapshot pre-mutation gate (Astra P0.1 & Claude F-P1.9).
 * 4. Storage-enforced single-use grant consumption: UNIQUE(grant_id) prevents replay/resurrection.
 * 5. Deterministic drift detection: typed dimension comparator (MATCH, MISMATCH, NOT_EVALUABLE).
 * 6. Durable signing key (CF-P0.5): the HMAC master secret is resolved through
 *    {@link FailoverKeyManagementService}, never minted fresh per process (see that class).
 * 7. Durable storage (CF-P0.7/CF-P1.4): schedule state lives in {@code failover_schedules} via
 *    {@link JdbcTemplate}, with each transition applied as a compare-and-set on the schedule's
 *    expected current status -- the minimum viable state-machine enforcement the Engine Final
 *    Review named for CF-P1.4, until a dedicated transition-legality table lands.
 * 8. Crash recovery (CF-P0.20): {@link #reconcileOrphanedAttemptsOnStartup()} forces every
 *    schedule left mid-dispatch by a prior process's crash to {@code OUTCOME_UNKNOWN} and engages
 *    sticky quarantine on its cluster.
 */
@Service
public class FailoverScheduleService {

    public record ScheduleWindowRequest(
        String clusterRef,
        FailoverActionKind actionKind,
        Instant windowStart,
        Instant windowEnd,
        int maxStartDelayMinutes,
        String requesterId,
        String approverId,
        String reason,
        String clientNonce
    ) {}

    private static final Duration PRE_ADMISSION_BUDGET = Duration.ofSeconds(30);
    private static final String RECONCILIATION_ACTOR = "SYSTEM_STARTUP_RECONCILIATION";
    private static final String RECONCILIATION_REASON_CODE = "ERR_SERVICE_RESTART_RECONCILIATION";

    private static final String SCHEDULE_COLUMNS =
        "schedule_id, cluster_ref, masked_cluster_name, vendor, command_family_id, action_kind, " +
            "signed_mutation_target, window_start, window_end, max_start_delay_minutes, execution_deadline, " +
            "requester_id, approver_id, grant_id, baseline_digest, baseline_json, envelope_signature, status, " +
            "client_nonce, scheduled_at, claimed_at, executed_at, execution_result_id, abort_reason_code, " +
            "abort_reason, cancelled_by, cancelled_at ";

    private final PreflightService preflightService;
    private final FailoverAuthorizationService authzService;
    private final FailoverExecutionService executionService;
    private final FailoverScheduleLedger scheduleLedger;
    private final FailoverBookingAdmissionControl admissionControl;
    private final FailoverKeyManagementService keyManagementService;
    private final JdbcTemplate jdbcTemplate;
    private final FailoverDriftEngine driftEngine;
    private final ClockHealthCheck clockHealthCheck;
    private final ObjectMapper baselineJsonMapper;

    // In-memory fallback store, used only when no JdbcTemplate is wired (lightweight unit tests).
    private final Map<String, FailoverScheduleRecord> memorySchedules = new ConcurrentHashMap<>();

    @Autowired
    public FailoverScheduleService(
        PreflightService preflightService,
        FailoverAuthorizationService authzService,
        FailoverExecutionService executionService,
        FailoverScheduleLedger scheduleLedger,
        FailoverBookingAdmissionControl admissionControl,
        FailoverKeyManagementService keyManagementService,
        JdbcTemplate jdbcTemplate
    ) {
        this(preflightService, authzService, executionService, scheduleLedger, admissionControl,
            keyManagementService, jdbcTemplate, new FailoverDriftEngine(), new ClockHealthCheck());
    }

    // Constructor for deterministic testing: a null jdbcTemplate falls back to an in-memory,
    // non-durable schedule store (mirrors FailoverScheduleLedger / DurableQuarantineStore).
    public FailoverScheduleService(
        PreflightService preflightService,
        FailoverAuthorizationService authzService,
        FailoverExecutionService executionService,
        FailoverScheduleLedger scheduleLedger,
        FailoverBookingAdmissionControl admissionControl,
        FailoverKeyManagementService keyManagementService,
        JdbcTemplate jdbcTemplate,
        FailoverDriftEngine driftEngine,
        ClockHealthCheck clockHealthCheck
    ) {
        this.preflightService = Objects.requireNonNull(preflightService, "preflightService must not be null");
        this.authzService = Objects.requireNonNull(authzService, "authzService must not be null");
        this.executionService = Objects.requireNonNull(executionService, "executionService must not be null");
        this.scheduleLedger = Objects.requireNonNull(scheduleLedger, "scheduleLedger must not be null");
        this.admissionControl = Objects.requireNonNull(admissionControl, "admissionControl must not be null");
        this.keyManagementService = Objects.requireNonNull(keyManagementService, "keyManagementService must not be null");
        this.jdbcTemplate = jdbcTemplate;
        this.driftEngine = Objects.requireNonNull(driftEngine, "driftEngine must not be null");
        this.clockHealthCheck = Objects.requireNonNull(clockHealthCheck, "clockHealthCheck must not be null");
        this.baselineJsonMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private boolean isDurable() {
        return jdbcTemplate != null;
    }

    public FailoverScheduleRecord scheduleMaintenanceWindow(ScheduleWindowRequest request) {
        Objects.requireNonNull(request, "request must not be null");

        // 1. Four-Eyes Principal Validation (CF-P0.14: canonicalized comparison, not raw equalsIgnoreCase)
        PrincipalCanonicalization.requireDistinctPrincipals(
            request.requesterId(), request.approverId(), "Scheduling maintenance window");
        if (request.reason() == null || request.reason().trim().length() < 8) {
            throw new IllegalArgumentException("Scheduling maintenance window requires a valid change reason (minimum 8 characters)");
        }

        // 2. Booking-Time Admission Control (Lead time <= 7d, fleet overlap, volume caps)
        admissionControl.validateBookingAdmission(
            request.windowStart(), request.windowEnd(), request.maxStartDelayMinutes(),
            request.clusterRef(), allSchedulesSnapshot()
        );

        // 3. Cluster Readiness Baseline Verification
        PreflightReport report = preflightService.getLatestReport(request.clusterRef());
        if (report.overallVerdict() != PreflightVerdict.NO_BLOCKING_CONDITIONS_OBSERVED) {
            throw new IllegalStateException("Cannot schedule failover when cluster pre-flight checks report blocking conditions (" +
                report.blockingFailureCount() + " blocking failure(s) observed)");
        }

        ClusterEvidenceSnapshot snapshot = preflightService.buildSnapshotForCluster(request.clusterRef());
        Optional<ClusterMemberEvidence> activeOpt = snapshot.activeMember();
        Optional<ClusterMemberEvidence> standbyOpt = snapshot.standbyMember();

        if (activeOpt.isEmpty() || standbyOpt.isEmpty()) {
            throw new IllegalStateException("Active or standby member identity could not be corroborated for baseline");
        }

        ClusterMemberEvidence active = activeOpt.get();
        ClusterMemberEvidence standby = standbyOpt.get();

        String vendor = snapshot.vendor().toUpperCase(Locale.ROOT);
        String commandFamilyId = vendor.contains("PAN") ? "PAN_HA_MUTATION_GATE" : "CP_CLUSTERXL_MUTATION_GATE";
        String grantId = "grant-" + UUID.randomUUID();
        String scheduleId = "sched-" + UUID.randomUUID();
        Instant now = Instant.now();

        // 4. Durable Signing Key Resolution (CF-P0.5): fail cleanly rather than sign with a key
        // that will not durably verify after a restart.
        ScheduleCryptographicService cryptoService = keyManagementService
            .resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID)
            .orElseThrow(() -> new IllegalStateException(
                "ABORTED_KEY_UNAVAILABLE: signing key '" + FailoverScheduleEnvelope.DEFAULT_KEY_ID +
                    "' is not available; a new schedule cannot be sealed"));

        // Deterministic content digest (Claude CF-P0.2): computed over the baseline's own
        // fields, not a random token, so the digest can be independently re-derived and
        // verified rather than merely asserted.
        BaselineSnapshotSummary baselineDraft = BaselineSnapshotSummary.of(
            request.clusterRef(), vendor, snapshot.haMode(), active.memberId(), standby.memberId(),
            active.softwareVersion(), active.installedPolicyHash(), active.flapCountLast24Hours(),
            "PENDING", now
        );
        String baselineDigest = baselineDraft.computeCanonicalDigest();

        BaselineSnapshotSummary baseline = BaselineSnapshotSummary.of(
            request.clusterRef(), vendor, snapshot.haMode(), active.memberId(), standby.memberId(),
            active.softwareVersion(), active.installedPolicyHash(), active.flapCountLast24Hours(),
            baselineDigest, now
        );

        Instant executionDeadline = FailoverScheduleRecord.computeExecutionDeadline(
            request.windowStart(), request.windowEnd(), request.maxStartDelayMinutes()
        );

        // 5. Sealed HMAC Envelope Binding (Claude F-P0.4 & F-P0.5)
        FailoverScheduleEnvelope envelope = new FailoverScheduleEnvelope(
            scheduleId, request.clusterRef(), vendor, commandFamilyId, request.actionKind().name(),
            active.memberId(), request.windowStart(), request.windowEnd(), request.maxStartDelayMinutes(),
            request.requesterId(), request.approverId(), grantId, baselineDigest,
            request.clientNonce(), FailoverScheduleEnvelope.DEFAULT_KEY_ID,
            FailoverScheduleEnvelope.DEFAULT_ALG_VERSION
        );

        String envelopeSignature = cryptoService.signEnvelope(envelope);

        FailoverScheduleRecord record = new FailoverScheduleRecord(
            scheduleId, request.clusterRef(), snapshot.maskedClusterName(), vendor, commandFamilyId,
            request.actionKind(), active.memberId(), request.windowStart(), request.windowEnd(),
            request.maxStartDelayMinutes(), executionDeadline, request.requesterId(), request.approverId(),
            grantId, baseline, envelopeSignature, FailoverScheduleStatus.SCHEDULED, request.clientNonce(),
            now, null, null, null, null, null, null, null
        );

        insertSchedule(record);
        scheduleLedger.recordTransition(
            scheduleId, null, FailoverScheduleStatus.SCHEDULED, null,
            request.requesterId(), "Maintenance window scheduled and sealed with HMAC-SHA256"
        );

        return record;
    }

    public synchronized FailoverScheduleRecord cancelSchedule(String scheduleId, String operatorId, String reason) {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(operatorId, "operatorId must not be null");
        Objects.requireNonNull(reason, "reason must not be null");

        FailoverScheduleRecord record = loadSchedule(scheduleId);
        if (record == null) {
            throw new IllegalArgumentException("No schedule found with ID: " + scheduleId);
        }

        if (record.status().isMutationBoundaryCrossed()) {
            throw new IllegalStateException("CANCELLATION_REJECTED_POST_BOUNDARY: Execution has already crossed the durable mutation boundary");
        }

        if (!record.status().isCancellable()) {
            throw new IllegalStateException("Schedule " + scheduleId + " is in terminal state " + record.status() + " and cannot be cancelled");
        }

        FailoverScheduleRecord cancelled = record.withCancellation(operatorId, Instant.now());
        updateScheduleCas(scheduleId, record.status(), cancelled);
        scheduleLedger.recordTransition(
            scheduleId, record.status(), FailoverScheduleStatus.CANCELLED, null,
            operatorId, "Schedule cancelled by operator: " + reason
        );
        return cancelled;
    }

    public synchronized FailoverScheduleRecord dispatchScheduledExecution(String scheduleId, String triggeringActor) {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        Objects.requireNonNull(triggeringActor, "triggeringActor must not be null");

        FailoverScheduleRecord record = loadSchedule(scheduleId);
        if (record == null) {
            throw new IllegalArgumentException("No schedule found with ID: " + scheduleId);
        }

        if (record.status() != FailoverScheduleStatus.SCHEDULED) {
            throw new IllegalStateException("Schedule " + scheduleId + " is not in SCHEDULED status (current: " + record.status() + ")");
        }

        String attemptId = "att-" + UUID.randomUUID();
        Instant now = Instant.now();

        // 1. Hard Temporal Window Gate (Claude F-P0.8)
        if (now.isBefore(record.windowStart())) {
            throw new IllegalStateException("Scheduled maintenance window is not yet open (opens at: " + record.windowStart() + ")");
        }

        if (now.isAfter(record.executionDeadline()) || now.plus(PRE_ADMISSION_BUDGET).isAfter(record.executionDeadline())) {
            FailoverScheduleRecord expired = record.withAbort(
                FailoverScheduleStatus.ABORTED_WINDOW_EXPIRED, "WINDOW_EXPIRED",
                "Execution window deadline passed before mutation dispatch (" + now + " >= deadline: " + record.executionDeadline() + ")"
            );
            updateScheduleCas(scheduleId, record.status(), expired);
            scheduleLedger.recordTransition(scheduleId, record.status(), FailoverScheduleStatus.ABORTED_WINDOW_EXPIRED, attemptId, triggeringActor, "Window expired");
            return expired;
        }

        // 2. Durable Signing Key Resolution (CF-P0.5/CF-P1.2): a key that cannot be resolved is
        // reported as ABORTED_KEY_UNAVAILABLE, never as a false ABORTED_TAMPERED.
        Optional<ScheduleCryptographicService> cryptoServiceOpt =
            keyManagementService.resolveCryptoService(FailoverScheduleEnvelope.DEFAULT_KEY_ID);
        if (cryptoServiceOpt.isEmpty()) {
            FailoverScheduleRecord keyUnavailable = record.withAbort(
                FailoverScheduleStatus.ABORTED_KEY_UNAVAILABLE, "KEY_UNAVAILABLE",
                "Signing key '" + FailoverScheduleEnvelope.DEFAULT_KEY_ID + "' could not be resolved; envelope cannot be verified"
            );
            updateScheduleCas(scheduleId, record.status(), keyUnavailable);
            scheduleLedger.recordTransition(scheduleId, record.status(), FailoverScheduleStatus.ABORTED_KEY_UNAVAILABLE, attemptId, triggeringActor, "Signing key unavailable at dispatch");
            return keyUnavailable;
        }

        // 3. Cryptographic Envelope Verification (Claude F-P0.3)
        FailoverScheduleEnvelope envelope = new FailoverScheduleEnvelope(
            record.scheduleId(), record.clusterRef(), record.vendor(), record.commandFamilyId(),
            record.actionKind().name(), record.signedMutationTarget(), record.windowStart(), record.windowEnd(),
            record.maxStartDelayMinutes(), record.requesterId(), record.approverId(),
            record.grantId(), record.baselineSummary().assessmentDigest(), record.clientNonce(),
            FailoverScheduleEnvelope.DEFAULT_KEY_ID, FailoverScheduleEnvelope.DEFAULT_ALG_VERSION
        );

        if (!cryptoServiceOpt.get().verifyEnvelope(envelope, record.envelopeSignature())) {
            FailoverScheduleRecord tampered = record.withAbort(
                FailoverScheduleStatus.ABORTED_TAMPERED, "CRYPTO_SIGNATURE_INVALID",
                "Schedule cryptographic signature verification failed: record has been tampered with"
            );
            updateScheduleCas(scheduleId, record.status(), tampered);
            scheduleLedger.recordTransition(scheduleId, record.status(), FailoverScheduleStatus.ABORTED_TAMPERED, attemptId, triggeringActor, "HMAC signature verification failed");
            return tampered;
        }

        // 4. Claim the Schedule
        FailoverScheduleRecord claimed = record.withClaim(now);
        updateScheduleCas(scheduleId, record.status(), claimed);
        scheduleLedger.recordTransition(scheduleId, record.status(), FailoverScheduleStatus.CLAIMED_VERIFYING, attemptId, triggeringActor, "Claimed for execution");

        // 5. Authorize Execution Lease for System Scheduler
        FailoverAuthorizationRequest authzReq = new FailoverAuthorizationRequest(
            record.clusterRef(), record.requesterId(), record.approverId(),
            "Scheduled execution for window: " + record.windowStart(),
            "window-" + record.scheduleId(),
            record.baselineSummary().assessmentDigest(),
            record.clientNonce()
        );

        FourEyesAuthorizationResult authzResult = authzService.authorizeFailover(authzReq);
        if (!(authzResult instanceof FourEyesAuthorizationResult.Authorized authorized)) {
            FailoverScheduleRecord aborted = claimed.withAbort(
                FailoverScheduleStatus.ABORTED_PRE_MUTATION, "AUTHORIZATION_REFUSED",
                "Authorization lease could not be issued: " + ((FourEyesAuthorizationResult.Refused) authzResult).reason()
            );
            updateScheduleCas(scheduleId, claimed.status(), aborted);
            scheduleLedger.recordTransition(scheduleId, claimed.status(), FailoverScheduleStatus.ABORTED_PRE_MUTATION, attemptId, triggeringActor, "Authz lease refused");
            return aborted;
        }

        FailoverLeaseToken leaseToken = authorized.leaseToken();

        // 6. Atomic In-Lock Single-Snapshot Execution (Astra P0.1 & Claude F-P1.9)
        FailoverExecutionResult execResult = executionService.executeScheduledFailover(
            record.clusterRef(),
            leaseToken.tokenId(),
            record.clientNonce(),
            record.actionKind(),
            "SYSTEM_SCHEDULER",
            record.signedMutationTarget(),
            record.executionDeadline(),
            liveSnapshot -> {
                // Inside Phase C exclusive lock:
                // a. Clock Health Check #13
                CheckResult clockRes = clockHealthCheck.evaluate(liveSnapshot);
                if (clockRes.isBlockingFailure()) {
                    return DriftEvaluationResult.blocked(
                        Map.of("clock_health", DriftDimensionStatus.MISMATCH),
                        List.of("CLOCK_UNTRUSTED"),
                        List.of("Clock health evaluation failed: " + clockRes.summary()),
                        preflightService.evaluateSnapshot(liveSnapshot)
                    );
                }

                // b. Full Preflight Battery evaluated against this exact live snapshot
                // (Claude CF-P0.13: never a separately cached/fetched report).
                PreflightReport t0Report = preflightService.evaluateSnapshot(liveSnapshot);

                // c. Drift Evaluation against signed baseline
                DriftEvaluationResult driftRes = driftEngine.evaluateDrift(record.baselineSummary(), liveSnapshot, t0Report);
                if (!driftRes.isPass()) {
                    return driftRes;
                }

                // d. Storage-enforced single-use grant consumption (Claude F-P0.3)
                try {
                    scheduleLedger.consumeGrant(record.grantId(), record.scheduleId(), attemptId);
                } catch (Exception ex) {
                    return DriftEvaluationResult.blocked(
                        Map.of("grant_consumption", DriftDimensionStatus.MISMATCH),
                        List.of("GRANT_ALREADY_CONSUMED"),
                        List.of("Single-use grant check failed: " + ex.getMessage()),
                        t0Report
                    );
                }

                return driftRes;
            }
        );

        // 7. Map Execution Result to Schedule Terminal State
        FailoverScheduleStatus finalStatus;
        if (execResult.state() == FailoverExecutionState.SUCCEEDED) {
            finalStatus = FailoverScheduleStatus.COMPLETED;
        } else if (execResult.state() == FailoverExecutionState.FAILED_NO_CHANGE) {
            finalStatus = FailoverScheduleStatus.FAILED_NO_CHANGE;
        } else if (execResult.state() == FailoverExecutionState.VENDOR_REJECTED) {
            finalStatus = FailoverScheduleStatus.VENDOR_REJECTED;
        } else if (execResult.state() == FailoverExecutionState.OUTCOME_UNKNOWN) {
            finalStatus = FailoverScheduleStatus.OUTCOME_UNKNOWN;
        } else {
            finalStatus = FailoverScheduleStatus.ABORTED_PRE_MUTATION;
        }

        FailoverScheduleRecord finished = claimed.withCompletion(finalStatus, execResult.executionId(), Instant.now());
        updateScheduleCas(scheduleId, claimed.status(), finished);
        scheduleLedger.recordTransition(scheduleId, claimed.status(), finalStatus, attemptId, triggeringActor, execResult.summary());

        return finished;
    }

    public List<FailoverScheduleRecord> getSchedulesForCluster(String clusterRef) {
        Objects.requireNonNull(clusterRef, "clusterRef must not be null");
        if (isDurable()) {
            return jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + "FROM failover_schedules WHERE cluster_ref = ? ORDER BY scheduled_at",
                (rs, rowNum) -> mapRow(rs), clusterRef);
        }
        List<FailoverScheduleRecord> result = new ArrayList<>();
        for (FailoverScheduleRecord r : memorySchedules.values()) {
            if (r.clusterRef().equals(clusterRef)) {
                result.add(r);
            }
        }
        return Collections.unmodifiableList(result);
    }

    public Optional<FailoverScheduleRecord> getSchedule(String scheduleId) {
        Objects.requireNonNull(scheduleId, "scheduleId must not be null");
        return Optional.ofNullable(loadSchedule(scheduleId));
    }

    /**
     * CF-P0.20: on every process start, any schedule left in {@code CLAIMED_VERIFYING} or
     * {@code DISPATCHING} by a prior process (crash, kill, deploy) is left in an unknown-outcome
     * state -- the mutation may or may not have reached the device. Rather than resume or retry
     * (which could double-submit an at-most-once command), every such schedule is forced to
     * {@code OUTCOME_UNKNOWN} and its cluster placed under sticky quarantine, so the ambiguity
     * requires an explicit 4-eyes-audited acknowledgment before any further execution.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void reconcileOrphanedAttemptsOnStartup() {
        if (!isDurable()) {
            return;
        }
        List<FailoverScheduleRecord> orphaned = jdbcTemplate.query(
            "SELECT " + SCHEDULE_COLUMNS + "FROM failover_schedules WHERE status IN (?, ?)",
            (rs, rowNum) -> mapRow(rs),
            FailoverScheduleStatus.CLAIMED_VERIFYING.name(), FailoverScheduleStatus.DISPATCHING.name());

        for (FailoverScheduleRecord record : orphaned) {
            String correlationId = "reconcile-" + record.scheduleId();
            FailoverScheduleRecord reconciled = record.withAbort(
                FailoverScheduleStatus.OUTCOME_UNKNOWN, RECONCILIATION_REASON_CODE,
                "Schedule was left in " + record.status() + " by a prior process; outcome cannot be corroborated after restart"
            );
            updateScheduleCas(record.scheduleId(), record.status(), reconciled);
            scheduleLedger.recordTransition(
                record.scheduleId(), record.status(), FailoverScheduleStatus.OUTCOME_UNKNOWN, correlationId,
                RECONCILIATION_ACTOR, "Startup reconciliation: prior process crashed mid-dispatch"
            );
            executionService.engageQuarantine(
                record.clusterRef(), correlationId,
                "Startup reconciliation: schedule " + record.scheduleId() + " left in " + record.status() + " by a prior process",
                Set.of(record.baselineSummary().activeMemberId(), record.baselineSummary().standbyMemberId())
            );
        }
    }

    private Collection<FailoverScheduleRecord> allSchedulesSnapshot() {
        if (isDurable()) {
            return jdbcTemplate.query("SELECT " + SCHEDULE_COLUMNS + "FROM failover_schedules", (rs, rowNum) -> mapRow(rs));
        }
        return List.copyOf(memorySchedules.values());
    }

    private FailoverScheduleRecord loadSchedule(String scheduleId) {
        if (isDurable()) {
            List<FailoverScheduleRecord> rows = jdbcTemplate.query(
                "SELECT " + SCHEDULE_COLUMNS + "FROM failover_schedules WHERE schedule_id = ?",
                (rs, rowNum) -> mapRow(rs), scheduleId);
            return rows.isEmpty() ? null : rows.get(0);
        }
        return memorySchedules.get(scheduleId);
    }

    private void insertSchedule(FailoverScheduleRecord record) {
        if (isDurable()) {
            jdbcTemplate.update(
                "INSERT INTO failover_schedules (" + SCHEDULE_COLUMNS + ", version) VALUES (" +
                    "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?,?,1)",
                record.scheduleId(), record.clusterRef(), record.maskedClusterName(), record.vendor(),
                record.commandFamilyId(), record.actionKind().name(), record.signedMutationTarget(),
                Timestamp.from(record.windowStart()), Timestamp.from(record.windowEnd()), record.maxStartDelayMinutes(),
                Timestamp.from(record.executionDeadline()), record.requesterId(), record.approverId(), record.grantId(),
                record.baselineSummary().assessmentDigest(), writeBaselineJson(record.baselineSummary()),
                record.envelopeSignature(), record.status().name(), record.clientNonce(),
                Timestamp.from(record.scheduledAt()), null, null, null, null, null, null, null
            );
            return;
        }
        memorySchedules.put(record.scheduleId(), record);
    }

    /**
     * CF-P1.4: applies a transition only if the schedule's current status still matches
     * {@code expectedFromStatus} -- a compare-and-set that keeps the durable record and this
     * service's understanding of it from silently diverging under a concurrent writer.
     */
    private void updateScheduleCas(String scheduleId, FailoverScheduleStatus expectedFromStatus, FailoverScheduleRecord newRecord) {
        if (isDurable()) {
            int rows = jdbcTemplate.update(
                "UPDATE failover_schedules SET status = ?, claimed_at = ?, executed_at = ?, execution_result_id = ?, " +
                    "abort_reason_code = ?, abort_reason = ?, cancelled_by = ?, cancelled_at = ?, version = version + 1 " +
                    "WHERE schedule_id = ? AND status = ?",
                newRecord.status().name(),
                newRecord.claimedAt() != null ? Timestamp.from(newRecord.claimedAt()) : null,
                newRecord.executedAt() != null ? Timestamp.from(newRecord.executedAt()) : null,
                newRecord.executionResultId(),
                newRecord.abortReasonCode(),
                newRecord.abortReason(),
                newRecord.cancelledBy(),
                newRecord.cancelledAt() != null ? Timestamp.from(newRecord.cancelledAt()) : null,
                scheduleId, expectedFromStatus.name()
            );
            if (rows != 1) {
                throw new IllegalStateException("Schedule " + scheduleId +
                    " did not transition: expected current status " + expectedFromStatus + " was not found (concurrent modification)");
            }
            return;
        }

        FailoverScheduleRecord current = memorySchedules.get(scheduleId);
        if (current == null || current.status() != expectedFromStatus) {
            throw new IllegalStateException("Schedule " + scheduleId +
                " did not transition: expected current status " + expectedFromStatus + " was not found (concurrent modification)");
        }
        memorySchedules.put(scheduleId, newRecord);
    }

    private String writeBaselineJson(BaselineSnapshotSummary baseline) {
        try {
            return baselineJsonMapper.writeValueAsString(baseline);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize baseline snapshot summary: " + e.getMessage(), e);
        }
    }

    private BaselineSnapshotSummary readBaselineJson(String json) {
        try {
            return baselineJsonMapper.readValue(json, BaselineSnapshotSummary.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize baseline snapshot summary: " + e.getMessage(), e);
        }
    }

    private FailoverScheduleRecord mapRow(ResultSet rs) throws SQLException {
        Timestamp claimedAt = rs.getTimestamp("claimed_at");
        Timestamp executedAt = rs.getTimestamp("executed_at");
        Timestamp cancelledAt = rs.getTimestamp("cancelled_at");
        return new FailoverScheduleRecord(
            rs.getString("schedule_id"),
            rs.getString("cluster_ref"),
            rs.getString("masked_cluster_name"),
            rs.getString("vendor"),
            rs.getString("command_family_id"),
            FailoverActionKind.valueOf(rs.getString("action_kind")),
            rs.getString("signed_mutation_target"),
            rs.getTimestamp("window_start").toInstant(),
            rs.getTimestamp("window_end").toInstant(),
            rs.getInt("max_start_delay_minutes"),
            rs.getTimestamp("execution_deadline").toInstant(),
            rs.getString("requester_id"),
            rs.getString("approver_id"),
            rs.getString("grant_id"),
            readBaselineJson(rs.getString("baseline_json")),
            rs.getString("envelope_signature"),
            FailoverScheduleStatus.valueOf(rs.getString("status")),
            rs.getString("client_nonce"),
            rs.getTimestamp("scheduled_at").toInstant(),
            claimedAt != null ? claimedAt.toInstant() : null,
            executedAt != null ? executedAt.toInstant() : null,
            rs.getString("execution_result_id"),
            rs.getString("abort_reason_code"),
            rs.getString("abort_reason"),
            rs.getString("cancelled_by"),
            cancelledAt != null ? cancelledAt.toInstant() : null
        );
    }
}
