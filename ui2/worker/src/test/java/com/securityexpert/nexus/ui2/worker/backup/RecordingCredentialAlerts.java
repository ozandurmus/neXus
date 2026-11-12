package com.securityexpert.nexus.ui2.worker.backup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.CredentialFailureAlertRepository;

/** A recording {@link CredentialFailureAlertRepository} for worker tests; {@code nextRecorded} scripts the answer. */
final class RecordingCredentialAlerts implements CredentialFailureAlertRepository {
    final List<String> failures = new ArrayList<>();
    final List<String> closed = new ArrayList<>();
    final List<String> holds = new ArrayList<>();
    Recorded nextRecorded = new Recorded(true, true, 1, 0, Optional.empty());
    RuntimeException failWith;

    @Override
    public Recorded recordAuthenticationFailure(String credentialRef, String vendor, String jobId, String deviceId) {
        if (failWith != null) {
            throw failWith;
        }
        failures.add(credentialRef + "|" + vendor + "|" + jobId + "|" + deviceId);
        return nextRecorded;
    }

    @Override
    public boolean closeOnSuccess(String credentialRef) {
        closed.add(credentialRef);
        return true;
    }

    @Override
    public boolean hasOpenAlert(String credentialRef) {
        return false;
    }

    @Override
    public int holdScheduledBackups(String credentialRef, String vendor, LocalDate day) {
        holds.add(credentialRef + "|" + vendor + "|" + day);
        return 3;
    }
}
