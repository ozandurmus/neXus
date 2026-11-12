package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.LocalDate;
import java.util.Optional;

/**
 * The credential failure alert (V207; MDS_FORK_STORM_FIX_CONTRACT_2026_11_11.md part D): one open alert per backup
 * credential after an authentication failure, closed by the next successful use. The credential reference is passed in
 * and only its {@link CredentialRefDigest} is stored.
 */
public interface CredentialFailureAlertRepository {

    String ACTOR = "system:credential-alert";
    String ACTION_OPENED = "credential_failure_alert_opened";
    String ACTION_UPDATED = "credential_failure_alert_updated";
    String ACTION_CLOSED = "credential_failure_alert_closed";
    String ACTION_HOLD = "credential_failure_hold";
    String CLOSED_AUTH_SUCCEEDED = "auth_succeeded";
    /** A job's {@code terminal_reason} when the early stop cancelled it before it started. */
    String HOLD_REASON = "credential_auth_failed_hold";
    /** Distinct devices failing in one scheduled run that hold the rest of that run (a single device never does). */
    int HOLD_DEVICE_THRESHOLD = 2;

    /**
     * @param opened this call opened the alert
     * @param deviceCountChanged opened, or {@code failedDeviceCount} grew
     * @param failedDeviceCount distinct devices that failed since the alert opened
     * @param runDeviceCount distinct devices that failed in {@code scheduledDay}'s scheduled run (0 when the job was not scheduled)
     * @param scheduledDay the scheduled run the failing job belonged to, if it was one
     */
    record Recorded(boolean opened, boolean deviceCountChanged, int failedDeviceCount, int runDeviceCount,
            Optional<LocalDate> scheduledDay) {
    }

    /** Opens or updates the alert for {@code credentialRef} after {@code jobId} failed authentication on {@code deviceId}. */
    Recorded recordAuthenticationFailure(String credentialRef, String vendor, String jobId, String deviceId);

    /** Closes the open alert for {@code credentialRef} ({@value #CLOSED_AUTH_SUCCEEDED}); true when one was open. */
    boolean closeOnSuccess(String credentialRef);

    boolean hasOpenAlert(String credentialRef);

    /**
     * Cancels every still-REQUESTED scheduled backup job of {@code day} whose job uses {@code credentialRef}
     * ({@value #HOLD_REASON}). Check Point: every Check Point device (one environment-wide reference); otherwise the
     * devices whose own credential reference equals it. Returns the number cancelled.
     */
    int holdScheduledBackups(String credentialRef, String vendor, LocalDate day);

    /** For tests and compositions without a database: nothing is recorded, nothing is open. */
    CredentialFailureAlertRepository NONE = new CredentialFailureAlertRepository() {
        @Override
        public Recorded recordAuthenticationFailure(String credentialRef, String vendor, String jobId, String deviceId) {
            return new Recorded(false, false, 0, 0, Optional.empty());
        }

        @Override
        public boolean closeOnSuccess(String credentialRef) {
            return false;
        }

        @Override
        public boolean hasOpenAlert(String credentialRef) {
            return false;
        }

        @Override
        public int holdScheduledBackups(String credentialRef, String vendor, LocalDate day) {
            return 0;
        }
    };
}
