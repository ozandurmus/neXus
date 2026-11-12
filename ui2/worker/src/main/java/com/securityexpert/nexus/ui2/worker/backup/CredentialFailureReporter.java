package com.securityexpert.nexus.ui2.worker.backup;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.CredentialFailureAlertRepository;
import com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime;

/**
 * The worker side of the credential failure alert (MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md part D): an authentication
 * failure opens or updates the alert for the credential the job used; once two distinct devices have failed in one
 * scheduled run, the rest of that night's queued scheduled backups for the credential are cancelled (D.2); a success
 * closes the alert. It never lets an alert-bookkeeping failure change the outcome of the job itself.
 */
public final class CredentialFailureReporter {

    private static final System.Logger LOG = System.getLogger(CredentialFailureReporter.class.getName());

    private final CredentialFailureAlertRepository alerts;

    public CredentialFailureReporter(CredentialFailureAlertRepository alerts) {
        this.alerts = Objects.requireNonNull(alerts, "alerts");
    }

    public static CredentialFailureReporter none() {
        return new CredentialFailureReporter(CredentialFailureAlertRepository.NONE);
    }

    /** @return the recording, or empty when there was no credential reference or the bookkeeping failed. */
    public Optional<CredentialFailureAlertRepository.Recorded> authenticationFailed(Optional<String> credentialRef,
            String vendor, String jobId, String deviceId) {
        if (credentialRef.isEmpty() || credentialRef.get().isBlank()) {
            return Optional.empty();
        }
        try {
            CredentialFailureAlertRepository.Recorded recorded =
                    alerts.recordAuthenticationFailure(credentialRef.get(), vendor, jobId, deviceId);
            if (recorded.scheduledDay().isPresent()
                    && recorded.runDeviceCount() >= CredentialFailureAlertRepository.HOLD_DEVICE_THRESHOLD) {
                int held = alerts.holdScheduledBackups(credentialRef.get(), vendor, recorded.scheduledDay().get());
                if (held > 0) {
                    LOG.log(System.Logger.Level.WARNING,
                            "[CREDENTIAL_HOLD] {0} queued scheduled backup(s) cancelled after authentication failed on {1} devices",
                            held, recorded.runDeviceCount());
                }
            }
            return Optional.of(recorded);
        } catch (RuntimeException e) {
            EndpointRuntime.rethrow(e);
            LOG.log(System.Logger.Level.WARNING, "[CREDENTIAL_ALERT] not recorded: {0}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** A completed job (or a passing canary) used the credential successfully: close its open alert. */
    public void succeeded(Optional<String> credentialRef) {
        if (credentialRef.isEmpty() || credentialRef.get().isBlank()) {
            return;
        }
        try {
            alerts.closeOnSuccess(credentialRef.get());
        } catch (RuntimeException e) {
            EndpointRuntime.rethrow(e);
            LOG.log(System.Logger.Level.WARNING, "[CREDENTIAL_ALERT] not closed: {0}", e.getClass().getSimpleName());
        }
    }
}
