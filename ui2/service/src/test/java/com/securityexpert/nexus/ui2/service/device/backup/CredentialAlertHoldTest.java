package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.BackupCapabilityIds;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository.BackupPolicy;
import com.securityexpert.nexus.ui2.persistence.artefact.CredentialFailureAlertRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/** V207: the scheduled fleet backup skips a credential with an open alert; the canary picks its device and its time. */
class CredentialAlertHoldTest {
    private static final String CP_REF = "synthetic-cp-backup-ref";
    private static final Instant TWO_AM = at("2026-11-12T02:00:05");

    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static DeviceRecord device(String id, String role, String vendor, String credentialRef, boolean disabled) {
        return new DeviceRecord(id, role, vendor, "manual", Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, disabled,
                credentialRef, true);
    }

    private static DeviceSummaryRecord summary(DeviceRecord d) {
        return new DeviceSummaryRecord(d.deviceId(), d.role(), d.vendorHint(), d.enrollmentState(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), d.backupTarget());
    }

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final JobAdmissionService jobs = mock(JobAdmissionService.class);
    private final CredentialFailureAlertRepository alerts = mock(CredentialFailureAlertRepository.class);

    private BackupCollectService service(Optional<String> cpRef) {
        when(jobs.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("synthetic-job"));
        return new BackupCollectService(devices, jobs, Set.of(), true, Clock.fixed(TWO_AM, JobWindowPolicy.ZONE_ID))
                .withCredentialAlerts(alerts, cpRef);
    }

    private void fleet(DeviceRecord... records) {
        when(devices.listAll()).thenReturn(java.util.Arrays.stream(records).map(CredentialAlertHoldTest::summary).toList());
        for (DeviceRecord r : records) when(devices.find(r.deviceId())).thenReturn(Optional.of(r));
    }

    @Test
    void aScheduledRunSkipsDevicesWhoseCredentialHasAnOpenAlertAndReportsThemAsHeld() {
        fleet(device("FW-TANGO-01", "gateway", "check_point", "col-1", false),
                device("FW-TANGO-02", "gateway", "check_point", "col-2", false),
                device("FW-PAN-01", "gateway", "palo_alto", "pan-cred-1", false));
        when(alerts.hasOpenAlert(CP_REF)).thenReturn(true);
        when(alerts.hasOpenAlert("pan-cred-1")).thenReturn(false);

        var outcome = service(Optional.of(CP_REF)).requestCollectAll(ScheduledBackupWindowPolicy.ACTOR, "Scheduled fleet backup");

        assertEquals(3, outcome.targets());
        assertEquals(1, outcome.admitted());
        assertEquals(2, outcome.refused());
        assertEquals(2, outcome.held(), "refused with reason " + BackupCollectService.CREDENTIAL_HOLD);
        assertEquals("credential_auth_failed_hold", BackupCollectService.CREDENTIAL_HOLD);
        verify(jobs, never()).submit(eq(BackupCapabilityIds.CP_GAIA_BACKUP_LOCAL), anyString(), anyString(), anyString(), anyString());
        verify(jobs).submit(eq(BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP), eq("FW-PAN-01"), anyString(), anyString(), anyString());
    }

    @Test
    void thePaloAltoHoldFollowsTheDevicesOwnCredentialReference() {
        fleet(device("FW-PAN-01", "gateway", "palo_alto", "pan-cred-1", false),
                device("FW-TANGO-01", "gateway", "check_point", "col-1", false));
        when(alerts.hasOpenAlert("pan-cred-1")).thenReturn(true);
        var outcome = service(Optional.of(CP_REF)).requestCollectAll(ScheduledBackupWindowPolicy.ACTOR, "Scheduled fleet backup");
        assertEquals(1, outcome.held());
        assertEquals(1, outcome.admitted());
        verify(jobs, never()).submit(eq(BackupCapabilityIds.PAN_DEVICE_STATE_BACKUP), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void aManualFleetBackupIsNeverHeld() {
        fleet(device("FW-TANGO-01", "gateway", "check_point", "col-1", false));
        when(alerts.hasOpenAlert(CP_REF)).thenReturn(true);
        var outcome = service(Optional.of(CP_REF)).requestCollectAll("operator-synthetic", "Manual fleet backup run");
        assertEquals(0, outcome.held());
        assertEquals(1, outcome.admitted());
        verify(alerts, never()).hasOpenAlert(anyString());
    }

    @Test
    void anAlertLookupFailureNeverHoldsABackup() {
        fleet(device("FW-TANGO-01", "gateway", "check_point", "col-1", false));
        when(alerts.hasOpenAlert(CP_REF)).thenThrow(new IllegalStateException("db down"));
        var outcome = service(Optional.of(CP_REF)).requestCollectAll(ScheduledBackupWindowPolicy.ACTOR, "Scheduled fleet backup");
        assertEquals(0, outcome.held());
        assertEquals(1, outcome.admitted());
    }

    @Test
    void theCanaryRunsOnTheFirstEnabledCheckPointBackupGatewayByDeviceId() {
        fleet(device("FW-TANGO-09", "gateway", "check_point", "c", false),
                device("FW-TANGO-02", "gateway", "check_point", "c", true),
                device("FW-TANGO-05", "gateway", "check_point", "c", false),
                device("FW-PAN-01", "gateway", "palo_alto", "p", false),
                device("MDS-01", "management_server", "check_point", "c", false));
        int admitted = service(Optional.of(CP_REF)).requestCredentialCanaries(
                ScheduledBackupWindowPolicy.CANARY_ACTOR, LocalDate.of(2026, 11, 12));
        assertEquals(1, admitted);
        verify(jobs).submit(eq("credential_canary"), eq("FW-TANGO-05"), eq("FW-TANGO-05:credential_canary:2026-11-12"),
                eq("system:credential-canary"), anyString());
    }

    @Test
    void withoutACheckPointCredentialOrDeviceNothingIsSubmitted() {
        fleet(device("FW-TANGO-05", "gateway", "check_point", "c", false));
        assertEquals(0, service(Optional.empty()).requestCredentialCanaries("system:credential-canary", LocalDate.of(2026, 11, 12)));
        fleet(device("FW-PAN-01", "gateway", "palo_alto", "p", false));
        assertEquals(0, service(Optional.of(CP_REF)).requestCredentialCanaries("system:credential-canary", LocalDate.of(2026, 11, 12)));
        verify(jobs, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static final class PolicyStore implements BackupPolicyRepository {
        boolean enabled = true;
        @Override public Optional<BackupPolicy> find() {
            return Optional.of(new BackupPolicy("default", enabled, "0 2 * * *", 10, 4, 30, Optional.empty(), Instant.EPOCH));
        }
        @Override public boolean claimScheduledRun(Instant dueSince, Instant runAt, String actor, String action) { return true; }
        @Override public boolean update(boolean enabled, String cron, int days, int depth, Integer configurationDays, String actor, String action) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void theSchedulerSubmitsTheCanaryOnlyFrom0045WhileTheFamilyAndScheduleAreOn() {
        var collect = mock(BackupCollectService.class);
        when(collect.requestCredentialCanaries(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(1);
        var store = new PolicyStore();
        var scheduler = new BackupScheduler(store, collect);
        assertEquals(0, scheduler.canaryTick(at("2026-11-12T00:44:59")));
        assertEquals(0, scheduler.canaryTick(at("2026-11-12T01:45:00")), "01:45 is outside every job window");
        assertEquals(0, scheduler.canaryTick(at("2026-11-12T02:00:10")));
        verify(collect, never()).requestCredentialCanaries(anyString(), org.mockito.ArgumentMatchers.any());
        assertEquals(1, scheduler.canaryTick(at("2026-11-12T00:45:00")));
        assertEquals(0, scheduler.canaryTick(at("2026-11-12T00:46:00")), "once per local day");
        verify(collect).requestCredentialCanaries("system:credential-canary", LocalDate.of(2026, 11, 12));

        store.enabled = false;
        assertEquals(0, new BackupScheduler(store, collect).canaryTick(at("2026-11-13T00:50:00")));
        store.enabled = true;
        assertEquals(0, new BackupScheduler(store, collect, family -> false).canaryTick(at("2026-11-13T00:50:00")),
                "the scheduled_backup family switch gates the canary too");
    }

    @Test
    void theCanaryTimeIsInsideTheRegularJobWindowAndTheBackupFamilyOwnsItsActor() {
        var window = new JobWindowPolicy(Clock.fixed(at("2026-11-12T00:45:00"), JobWindowPolicy.ZONE_ID), 60);
        window.requireDeviceJobOpen("credential_canary", "credential_canary", ScheduledBackupWindowPolicy.CANARY_ACTOR);
        var late = new JobWindowPolicy(Clock.fixed(at("2026-11-12T01:45:00"), JobWindowPolicy.ZONE_ID), 60);
        org.junit.jupiter.api.Assertions.assertThrows(JobWindowPolicy.OutsideWindow.class,
                () -> late.requireDeviceJobOpen("credential_canary", "credential_canary", ScheduledBackupWindowPolicy.CANARY_ACTOR));
        assertTrue(com.securityexpert.nexus.ui2.service.schedule.ScheduledJobFamily.SCHEDULED_BACKUP.actors()
                .contains(ScheduledBackupWindowPolicy.CANARY_ACTOR));
        assertTrue(ScheduledBackupWindowPolicy.isCanaryTime(at("2026-11-12T00:59:59")));
        assertTrue(!ScheduledBackupWindowPolicy.isCanaryTime(at("2026-11-12T01:00:00")));
    }
}
