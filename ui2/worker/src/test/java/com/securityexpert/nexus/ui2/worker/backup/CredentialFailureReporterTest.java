package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.CredentialFailureAlertRepository.Recorded;

class CredentialFailureReporterTest {
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);

    @Test
    void oneFailedDeviceInAScheduledRunHoldsNothing() {
        var alerts = new RecordingCredentialAlerts();
        alerts.nextRecorded = new Recorded(true, true, 1, 1, Optional.of(NIGHT));
        new CredentialFailureReporter(alerts).authenticationFailed(Optional.of("cred-1"), "check_point", "job-1", "dev-1");
        assertEquals(List.of("cred-1|check_point|job-1|dev-1"), alerts.failures);
        assertTrue(alerts.holds.isEmpty(), "a single device's failure alone never holds anything");
    }

    @Test
    void twoDistinctFailedDevicesInOneScheduledRunHoldTheRestOfThatNight() {
        var alerts = new RecordingCredentialAlerts();
        alerts.nextRecorded = new Recorded(false, true, 2, 2, Optional.of(NIGHT));
        new CredentialFailureReporter(alerts).authenticationFailed(Optional.of("cred-1"), "check_point", "job-2", "dev-2");
        assertEquals(List.of("cred-1|check_point|2026-11-11"), alerts.holds);
    }

    @Test
    void aManualOrCanaryFailureNeverHolds() {
        var alerts = new RecordingCredentialAlerts();
        alerts.nextRecorded = new Recorded(false, true, 5, 0, Optional.empty());
        new CredentialFailureReporter(alerts).authenticationFailed(Optional.of("cred-1"), "check_point", "job-9", "dev-9");
        assertTrue(alerts.holds.isEmpty());
    }

    @Test
    void noCredentialReferenceMeansNothingIsRecorded() {
        var alerts = new RecordingCredentialAlerts();
        var reporter = new CredentialFailureReporter(alerts);
        assertTrue(reporter.authenticationFailed(Optional.empty(), "palo_alto", "job-1", "dev-1").isEmpty());
        assertTrue(reporter.authenticationFailed(Optional.of(" "), "palo_alto", "job-1", "dev-1").isEmpty());
        reporter.succeeded(Optional.empty());
        assertTrue(alerts.failures.isEmpty());
        assertTrue(alerts.closed.isEmpty());
    }

    @Test
    void bookkeepingFailureNeverEscapes() {
        var alerts = new RecordingCredentialAlerts();
        alerts.failWith = new IllegalStateException("db down");
        assertTrue(new CredentialFailureReporter(alerts).authenticationFailed(Optional.of("c"), "check_point", "j", "d").isEmpty());
    }

    @Test
    void successClosesTheAlert() {
        var alerts = new RecordingCredentialAlerts();
        new CredentialFailureReporter(alerts).succeeded(Optional.of("cred-1"));
        assertEquals(List.of("cred-1"), alerts.closed);
    }
}
