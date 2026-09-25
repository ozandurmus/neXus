package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;

/**
 * The enrollment confirm for a vendor reached over HTTPS (V64): the same lifecycle as the SSH/XML-API confirm -- only
 * a DRAFT, non-disabled device; CLAIMED -> EXECUTING; one attempt row before the contact; ENROLLED with the observed
 * identity on success -- without the Check Point / Palo Alto identity-mismatch and HA peer-follow steps, which have no
 * counterpart on these appliances yet.
 */
public final class HttpsVendorConfirmJobExecutor {

    private static final System.Logger LOG = System.getLogger(HttpsVendorConfirmJobExecutor.class.getName());
    private static final String ACTOR = "system:worker";

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final DeviceEnrollmentReadPort enrollment;
    private final DeviceRepository devices;
    private final HttpsVendorExecutor executor;

    public HttpsVendorConfirmJobExecutor(JobLeaseRepository leases, JobStepAttemptRepository attempts,
            DeviceEnrollmentReadPort enrollment, DeviceRepository devices, HttpsVendorExecutor executor) {
        this.leases = Objects.requireNonNull(leases);
        this.attempts = Objects.requireNonNull(attempts);
        this.enrollment = Objects.requireNonNull(enrollment);
        this.devices = Objects.requireNonNull(devices);
        this.executor = Objects.requireNonNull(executor);
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String deviceId, String vendor, Target target, String credentialRef) {
        var e = enrollment.findEnrollment(deviceId);
        if (e.isEmpty() || e.get().disabled() || e.get().enrollmentState() != DeviceEnrollmentState.DRAFT) {
            leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR, "confirm_claim_time_device_check",
                    "DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }
        if (!leases.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR, "confirm_claim_to_executing")) {
            return new JobOutcome.ZombieStopped();
        }
        String attemptId = attempts.insertPreContact(jobId, leaseEpoch, 0, "CONFIRM_HTTPS_READ",
                com.securityexpert.nexus.ui2.platform.ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }
        long start = System.currentTimeMillis();
        // PO, 2026-09-24: a DefensePro an enrolled Cyber Controller lists is confirmed by that listing (management-plane
        // evidence, labelled as such); the direct device read only when no Cyber Controller lists it.
        HttpsVendorExecutor.ConfirmOutcome outcome = null;
        if ("radware".equals(vendor)) {
            for (CyberControllers.Ref cc : CyberControllers.enrolled(devices)) {
                Optional<HttpsVendorExecutor.ConfirmOutcome> viaCc = executor.confirmViaCyberController(cc.target(), cc.credentialRef(), target.host());
                if (viaCc.isPresent()) {
                    outcome = viaCc.get();
                    break;
                }
            }
        }
        if (outcome == null) {
            outcome = executor.confirm(vendor, target, credentialRef);
        }
        attempts.writeOutcome(attemptId, leaseEpoch, outcome instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed ? "MATCHED" : "EXPECTATION_UNMET",
                null, 0L, 0L, outcome.getClass().getSimpleName());
        long ms = System.currentTimeMillis() - start;
        if (!(outcome instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed confirmed)) {
            String reason = switch (outcome) {
                case HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed a -> "authentication_failed: " + a.reason();
                case HttpsVendorExecutor.ConfirmOutcome.Failed f -> "connect_failed: " + f.reason();
                default -> "unknown";
            } + " (after " + ms + "ms)";
            LOG.log(System.Logger.Level.WARNING, "[JOB_FAILED] HTTPS confirm job {0} ({1}): {2}", jobId, vendor, reason);
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "confirm_failed", reason);
            return new JobOutcome.Failed(reason);
        }
        HttpsVendorExecutor.Identity id = confirmed.identity();
        DeviceConfirmFacts facts = new DeviceConfirmFacts(id.name(), id.model(), id.version(), Optional.empty(), Optional.empty(),
                Optional.empty(), DeviceConfirmFacts.IDENTITY_MISMATCH_NONE, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), DeviceConfirmFacts.PEER_FOLLOW_NONE, Optional.empty());
        if (!devices.recordConfirmSuccess(deviceId, facts, ACTOR, "confirm_completed")) {
            leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, "confirm_failed", "device_confirm_write_conflict");
            return new JobOutcome.Failed("device_confirm_write_conflict");
        }
        leases.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR, "confirm_completed",
                "completed in " + ms + "ms");
        return new JobOutcome.Completed();
    }
}
