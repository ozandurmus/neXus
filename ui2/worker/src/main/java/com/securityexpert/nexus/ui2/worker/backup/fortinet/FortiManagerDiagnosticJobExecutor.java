package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import java.time.Duration;
import java.time.Instant;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.capability.CanonicalCommandKey;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateResolver;
import com.securityexpert.nexus.ui2.capability.GateResolution;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/** One closed class-0 FortiManager read, with a committed pre-contact attempt and no replay. */
public final class FortiManagerDiagnosticJobExecutor {
    private static final String ACTOR = "system:worker";

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceEnrollmentReadPort enrollment;
    private final DeviceInventoryRepository inventory;
    private final JobRecordDao jobs;
    private final FortiManagerExecutor fortiManager;
    private final GateRegistryPort gates;

    public FortiManagerDiagnosticJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            DeviceEnrollmentReadPort enrollment, DeviceInventoryRepository inventory, JobRecordDao jobs,
            FortiManagerExecutor fortiManager, GateRegistryPort gates) {
        this.leases = leases;
        this.attempts = attempts;
        this.enrollment = enrollment;
        this.inventory = inventory;
        this.jobs = jobs;
        this.fortiManager = fortiManager;
        this.gates = gates;
    }

    public void execute(String jobId, long leaseEpoch, String deviceId, Target target, String credentialRef) {
        var request = jobs.findDiagnostic(jobId);
        var run = inventory.findLatestRun(deviceId);
        boolean eligible = enrollment.findEnrollment(deviceId).filter(e -> e.permitsReadCollection()).isPresent();
        boolean portKnown = request.isPresent() && deviceId.equals(request.get().targetDeviceId())
                && run.isPresent() && !run.get().collectedAt().isBefore(Instant.now().minus(Duration.ofHours(24)))
                && run.get().contexts().stream().filter(c -> InventoryContext.PHYSICAL.equals(c.context()))
                        .flatMap(c -> c.interfaces().stream())
                        .anyMatch(i -> request.get().port().equals(i.name())
                                && InventoryInterface.KIND_PHYSICAL.equals(i.kind()));
        boolean signedOff;
        try {
            var resolved = GateResolver.resolve(new CanonicalCommandKey("fortinet", "fortimanager", "cli", "SSH_EXEC",
                    "diagnose fmnetwork interface detail <interface>"),
                    java.util.Optional.of(com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ), gates);
            signedOff = resolved instanceof GateResolution.Known known
                    && "fmg_ssh_fmnetwork_interface_detail".equals(known.gateId()) && known.timeoutS() == 60;
        } catch (RuntimeException invalidGate) {
            signedOff = false;
        }
        if (!eligible || !portKnown || !signedOff) {
            leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    "diagnostic_claim_check", "DIAGNOSTIC_TARGET_UNAVAILABLE");
            return;
        }
        if (!leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                "diagnostic_claim_to_executing")) {
            return;
        }
        if (!mayDispatch(attempts.findByJobAndStep(jobId, 0))) {
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                    "diagnostic_no_replay", "PRIOR_ATTEMPT_MAY_HAVE_SENT_COMMAND");
            return;
        }
        String attemptId = attempts.insertPreContact(jobId, leaseEpoch, 0, "FMG_INTERFACE_DETAIL", "read", 1);
        if (attemptId == null || !attempts.markBoundaryCrossed(attemptId, leaseEpoch)) {
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                    "diagnostic_pre_contact_uncertain", "PRE_CONTACT_STATE_UNKNOWN");
            return;
        }
        try {
            var observation = fortiManager.readInterfaceDetail(target, credentialRef, request.get().port());
            if (observation.isEmpty()) {
                attempts.writeOutcome(attemptId, leaseEpoch, "EXPECTATION_UNMET", "DIAGNOSTIC_READ_FAILED",
                        false, null, null, null);
                leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR,
                        "diagnostic_read_failed", "DIAGNOSTIC_READ_FAILED");
                return;
            }
            var safe = observation.get();
            if (!jobs.writeDiagnosticResult(jobId, safe.statusToken(), safe.lineCount(), safe.shapeId())) {
                leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                        "diagnostic_result_uncertain", "SAFE_RESULT_WRITE_FAILED");
                return;
            }
            if (!attempts.writeOutcome(attemptId, leaseEpoch, "MATCHED", null, true, null,
                    (long) safe.lineCount(), null)) {
                leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                        "diagnostic_attempt_uncertain", "ATTEMPT_OUTCOME_WRITE_FAILED");
                return;
            }
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR,
                    "diagnostic_completed", "SAFE_RESULT_RECORDED");
        } catch (RuntimeException failed) {
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.OUTCOME_UNKNOWN, ACTOR,
                    "diagnostic_dispatch_uncertain", "DIAGNOSTIC_DISPATCH_UNKNOWN");
        }
    }

    static boolean mayDispatch(java.util.List<com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt> priorAttempts) {
        return priorAttempts.isEmpty();
    }
}
