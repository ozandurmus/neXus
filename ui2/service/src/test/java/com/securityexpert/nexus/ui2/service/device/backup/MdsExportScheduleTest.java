package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

class MdsExportScheduleTest {
    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static final class Policy implements BackupPolicyRepository {
        Optional<Instant> last = Optional.of(at("2026-11-18T02:00:05"));
        boolean enabled = true;
        @Override public Optional<BackupPolicy> find() {
            return Optional.of(new BackupPolicy("default", enabled, "0 2 * * *", 10, 4, 30, last, Instant.EPOCH));
        }
        @Override public boolean claimScheduledRun(Instant a, Instant b, String c, String d) { return false; }
        @Override public boolean update(boolean e, String c, int d, int n, Integer cd, String a, String act) {
            throw new UnsupportedOperationException();
        }
    }

    /** Eligibility that models the SQL: a device is due unless busy or already exported tonight. */
    private static final class Fixture {
        final Policy policy = new Policy();
        final Set<String> due = new HashSet<>(Set.of("mds-1"));
        final List<String> requested = new ArrayList<>();
        final AtomicBoolean switchOn = new AtomicBoolean(true);
        final BackupScheduler scheduler;
        Fixture() {
            var eligibility = mock(MdsExportEligibility.class);
            when(eligibility.dueTargets(any())).thenAnswer(call -> Set.copyOf(due));
            var collect = mock(BackupCollectService.class);
            when(collect.requestScheduledMdsExport(anyString())).thenAnswer(call -> {
                String id = call.getArgument(0);
                requested.add(id);
                due.remove(id); // the job is non-terminal now, as in the database
                return true;
            });
            scheduler = new BackupScheduler(policy, collect).withMdsExport(eligibility, switchOn::get);
        }
    }

    @Test void requestsOnceAtOrAfterThirtyMinutesInsideTheWindowAndNeverTwiceANight() {
        var f = new Fixture();
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:00:00")));
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:29:59")));
        assertTrue(f.requested.isEmpty());
        assertEquals(1, f.scheduler.mdsExportTick(at("2026-11-18T02:30:00")));
        f.due.add("mds-1"); // even if the database showed it due again (job finished), the same night asks no more
        for (int m = 31; m < 60; m++) assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:" + m + ":00")));
        assertEquals(List.of("mds-1"), f.requested);
        // the next night asks again
        f.policy.last = Optional.of(at("2026-11-19T02:00:05"));
        assertEquals(1, f.scheduler.mdsExportTick(at("2026-11-19T02:30:00")));
        assertEquals(2, f.requested.size());
    }

    @Test void notOutsideTheWindow() {
        var f = new Fixture();
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T03:00:00")));
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T12:30:00")));
        assertTrue(f.requested.isEmpty());
    }

    @Test void waitsWhileTheSameDeviceHasANonTerminalJobThenAsksOnce() {
        var f = new Fixture();
        f.due.clear(); // the Gaia backup of that device is still running
        for (int m = 30; m < 45; m++) assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:" + m + ":00")));
        assertTrue(f.requested.isEmpty());
        f.due.add("mds-1"); // the backup went terminal
        assertEquals(1, f.scheduler.mdsExportTick(at("2026-11-18T02:45:00")));
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:46:00")));
        assertEquals(List.of("mds-1"), f.requested);
    }

    @Test void notWhenTheSwitchIsOffOrNotConfigured() {
        var f = new Fixture();
        f.switchOn.set(false);
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:30:00")));
        assertTrue(f.requested.isEmpty());
        var unconfigured = new BackupScheduler(f.policy, mock(BackupCollectService.class));
        assertEquals(0, unconfigured.mdsExportTick(at("2026-11-18T02:30:00")));
    }

    @Test void notOnANightWhoseFleetRunWasNotClaimed() {
        var f = new Fixture();
        f.policy.last = Optional.of(at("2026-11-17T02:00:05"));
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:30:00")));
        f.policy.last = Optional.of(at("2026-11-18T02:00:05"));
        f.policy.enabled = false;
        assertEquals(0, f.scheduler.mdsExportTick(at("2026-11-18T02:31:00")));
        assertTrue(f.requested.isEmpty());
    }

    @Test void collectServiceSendsOnlyCheckPointManagementBackupTargets() {
        for (var c : List.of(
                new String[] {"gw", "gateway", "check_point", "true"},
                new String[] {"pano", "management_server", "palo_alto", "true"},
                new String[] {"mds", "management_server", "check_point", "false"})) {
            var devices = mock(com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.class);
            var rec = new com.securityexpert.nexus.ui2.persistence.device.DeviceRecord(c[0], c[1], c[2], "manual",
                    Instant.EPOCH, false, com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, false,
                    null, Boolean.parseBoolean(c[3]));
            when(devices.find(c[0])).thenReturn(Optional.of(rec));
            var jobs = mock(com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService.class);
            var collect = new BackupCollectService(devices, jobs, Set.of(), true,
                    java.time.Clock.fixed(at("2026-11-18T02:30:00"), JobWindowPolicy.ZONE_ID));
            assertFalse(collect.requestScheduledMdsExport(c[0]), c[0]);
            verifyNoInteractions(jobs);
        }
    }

    @Test void collectServiceAdmitsTheExportForAnEligibleServerWithTheSchedulerActorAndANightlyKey() {
        var devices = mock(com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.class);
        var rec = new com.securityexpert.nexus.ui2.persistence.device.DeviceRecord("mds", "management_server",
                "check_point", "manual", Instant.EPOCH, false,
                com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, false, null, true);
        when(devices.find("mds")).thenReturn(Optional.of(rec));
        var jobs = mock(com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService.class);
        when(jobs.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult.Admitted("j"));
        var collect = new BackupCollectService(devices, jobs, Set.of(), true,
                java.time.Clock.fixed(at("2026-11-18T02:30:00"), JobWindowPolicy.ZONE_ID));
        assertTrue(collect.requestScheduledMdsExport("mds"));
        verify(jobs).submit(eq("cp_mds_export"), eq("mds"), eq("mds:cp_mds_export:scheduled:2026-11-18"),
                eq(com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy.MDS_ACTOR), anyString());
        // outside the window nothing is sent
        var late = new BackupCollectService(devices, jobs, Set.of(), true,
                java.time.Clock.fixed(at("2026-11-18T03:00:00"), JobWindowPolicy.ZONE_ID));
        assertFalse(late.requestScheduledMdsExport("mds"));
    }
}
