package com.securityexpert.nexus.ui2.worker.backup;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.device.DeviceEnrollmentReadPort;
import com.securityexpert.nexus.ui2.jobs.executor.JobOutcome;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectResult;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec;
import com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.WorkerActor;
import com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime;

/**
 * The nightly {@code credential_canary} (MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md D.4; PO 2026-11-11): one SSH
 * authentication with a backup credential on one canary device, then a disconnect. It is a class 0 read that sends
 * nothing: no {@code exec}, no {@code fetch}, no command of any kind -- the only calls on the transport are
 * {@code connect} and {@code disconnect}.
 *
 * <ul>
 *   <li>authenticated: the credential's open alert (if any) is closed and the job COMPLETES;</li>
 *   <li>authentication failed: the alert opens or updates (the canary device counts as a failed device) and the job
 *       FAILS with {@value #AUTH_FAILED_REASON}, so the 02:00 fleet backup skips that credential's devices;</li>
 *   <li>any other failure (timeout, host key): the job FAILS with its own reason and the alert is left alone.</li>
 * </ul>
 */
public final class CredentialCanaryJobExecutor {

    public static final String AUTH_FAILED_REASON = "credential_canary_auth_failed";
    private static final String ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    private static final String ACTION_CLAIM_TO_EXECUTING = "job_credential_canary_claim_to_executing";
    private static final String ACTION_CLAIM_TIME_DEVICE_CHECK = "job_credential_canary_claim_time_device_check";
    private static final String ACTION_COMPLETED = "job_credential_canary_completed";
    private static final String ACTION_FAILED = "job_credential_canary_failed";
    private static final String VENDOR = "check_point";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(30);

    private final JobLeaseRepository leaseRepository;
    private final JobStepAttemptRepository attemptRepository;
    private final DeviceEnrollmentReadPort deviceEnrollmentReadPort;
    private final DeviceTransport transport;
    private final CredentialFailureReporter alerts;

    public CredentialCanaryJobExecutor(JobLeaseRepository leaseRepository, JobStepAttemptRepository attemptRepository,
            DeviceEnrollmentReadPort deviceEnrollmentReadPort, DeviceTransport transport, CredentialFailureReporter alerts) {
        this.leaseRepository = Objects.requireNonNull(leaseRepository, "leaseRepository");
        this.attemptRepository = Objects.requireNonNull(attemptRepository, "attemptRepository");
        this.deviceEnrollmentReadPort = Objects.requireNonNull(deviceEnrollmentReadPort, "deviceEnrollmentReadPort");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    public JobOutcome execute(String jobId, long leaseEpoch, String targetDeviceId, BackupRequest request) {
        var enrollment = deviceEnrollmentReadPort.findEnrollment(targetDeviceId);
        if (enrollment.isEmpty() || !enrollment.get().permitsReadCollection()) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.REJECTED, ACTOR,
                    ACTION_CLAIM_TIME_DEVICE_CHECK);
            return new JobOutcome.Rejected("DEVICE_NOT_ELIGIBLE_AT_CLAIM_TIME");
        }
        if (!leaseRepository.transitionState(jobId, leaseEpoch, JobState.CLAIMED, JobState.EXECUTING, ACTOR,
                ACTION_CLAIM_TO_EXECUTING)) {
            return new JobOutcome.ZombieStopped();
        }
        String attemptId = attemptRepository.insertPreContact(jobId, leaseEpoch, 0, "CREDENTIAL_CANARY",
                ActionClass.CLASS_0_READ.id(), 1);
        if (attemptId == null) {
            return new JobOutcome.ZombieStopped();
        }

        Optional<String> credentialRef = request.credentialRef().filter(ref -> !ref.isBlank());
        if (credentialRef.isEmpty()) {
            return finish(jobId, leaseEpoch, attemptId, false, "credential_canary_not_configured: no backup credential configured");
        }
        ConnectResult connect;
        try {
            connect = transport.connect(request.connectionTarget(),
                    new ConnectSpec(credentialRef.get(), request.trustRuleRef(), Optional.empty()), CONNECT_TIMEOUT);
        } catch (IllegalStateException unresolvable) {
            EndpointRuntime.rethrow(unresolvable);
            return finish(jobId, leaseEpoch, attemptId, false, "credential_canary_unresolvable: "
                    + unresolvable.getClass().getSimpleName());
        }
        switch (connect) {
            case ConnectResult.Authenticated authenticated -> {
                // Authentication only: disconnect at once, nothing is sent.
                transport.disconnect(authenticated.session());
                alerts.succeeded(credentialRef);
                return finish(jobId, leaseEpoch, attemptId, true, null);
            }
            case ConnectResult.AuthenticationFailed failed -> {
                alerts.authenticationFailed(credentialRef, VENDOR, jobId, targetDeviceId);
                return finish(jobId, leaseEpoch, attemptId, false, AUTH_FAILED_REASON);
            }
            case ConnectResult.HostKeyRejected rejected -> {
                return finish(jobId, leaseEpoch, attemptId, false, "credential_canary_host_key_rejected: " + rejected.reason());
            }
            case ConnectResult.TimedOut timedOut -> {
                return finish(jobId, leaseEpoch, attemptId, false, "credential_canary_timed_out: " + timedOut.reason());
            }
        }
    }

    private JobOutcome finish(String jobId, long leaseEpoch, String attemptId, boolean success, String failureReason) {
        if (!attemptRepository.writeOutcome(attemptId, leaseEpoch, success ? "MATCHED" : "EXPECTATION_UNMET", null, 0L, 0L,
                null)) {
            return new JobOutcome.ZombieStopped();
        }
        if (success) {
            leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.COMPLETED, ACTOR, ACTION_COMPLETED);
            return new JobOutcome.Completed();
        }
        leaseRepository.transitionState(jobId, leaseEpoch, JobState.EXECUTING, JobState.FAILED, ACTOR, ACTION_FAILED,
                failureReason);
        return new JobOutcome.Failed(failureReason);
    }
}
