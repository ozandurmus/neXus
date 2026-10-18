package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupPolicyRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;

class BackupSchedulerWindowTest {
    private static Instant at(String local) {
        return LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID).toInstant();
    }

    private static BackupCollectService collector() {
        var collector = mock(BackupCollectService.class);
        when(collector.requestCollectAll(anyString(), anyString()))
                .thenReturn(new BackupCollectService.BulkOutcome(1, 1, 0));
        return collector;
    }

    private static final class PolicyStore implements BackupPolicyRepository {
        String cron = "0 2 * * *";
        boolean enabled = true;
        Optional<Instant> last = Optional.of(at("2026-10-08T02:00:00"));
        final List<Instant> claims = new ArrayList<>();
        BackupPolicy staleRead;

        @Override public Optional<BackupPolicy> find() {
            return Optional.of(staleRead != null ? staleRead : new BackupPolicy("default", enabled, cron,
                    10, 4, 30, last, Instant.EPOCH));
        }

        @Override public boolean claimScheduledRun(Instant dueSince, Instant runAt, String actor, String action) {
            if (!enabled || last.filter(value -> !value.isBefore(dueSince)).isPresent()) return false;
            last = Optional.of(runAt);
            claims.add(runAt);
            return true;
        }

        @Override public boolean update(boolean enabled, String cron, int days, int depth, Integer configurationDays, String actor, String action) {
            throw new UnsupportedOperationException();
        }
    }

    @Test void dailyCronRunsBothMdsJobsAndOneCyberControllerBackupPerDayAtTwoAcrossAllMinutes() {
        var store = new PolicyStore();
        var devices = mock(DeviceRepository.class);
        var targets = new ArrayList<DeviceRecord>();
        for (var vendor : List.of("check_point", "palo_alto", "fortinet", "cisco_asa",
                "infoblox", "radware", "bluecoat", "pulse_secure")) {
            targets.add(target(vendor, "gateway", vendor));
        }
        targets.add(target("spark", "gateway", "check_point"));
        targets.add(target("controller", "management_server", "radware"));
        targets.add(target("mds-measured", "management_server", "check_point"));
        targets.add(target("mds-unmeasured", "management_server", "check_point"));
        when(devices.listAll()).thenReturn(targets.stream().map(BackupSchedulerWindowTest::summary).toList());
        for (var target : targets) when(devices.find(target.deviceId())).thenReturn(Optional.of(target));
        when(devices.findSummary("spark")).thenReturn(Optional.of(new DeviceSummaryRecord("spark", "gateway",
                "check_point", DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.of("Quantum Spark 1590"),
                Optional.empty(), Optional.empty(), Optional.empty())));
        var eligibility = mock(MdsExportEligibility.class);
        when(eligibility.measuredTargets()).thenReturn(Set.of("mds-measured"));
        var jobs = mock(JobAdmissionService.class);
        var submissions = new ArrayList<String>();
        var types = new java.util.HashSet<String>();
        var perDeviceTypes = new java.util.HashMap<String, Set<String>>();
        var expected = new ArrayList<Instant>();
        for (int day = 9; day <= 15; day++) {
            var date = LocalDate.of(2026, 10, day);
            expected.add(date.atTime(2, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant());
            for (int minute = 0; minute < 24 * 60; minute++) {
                var now = date.atStartOfDay(JobWindowPolicy.ZONE_ID).toInstant().plusSeconds(minute * 60L);
                var window = new JobWindowPolicy(Clock.fixed(now, JobWindowPolicy.ZONE_ID), 60);
                doAnswer(call -> {
                    String type = call.getArgument(0), device = call.getArgument(1), actor = call.getArgument(3);
                    window.requireDeviceJobOpen(type, type, actor);
                    assertEquals(date.atTime(2, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant(), now);
                    assertEquals("cp_mds_export".equals(type) ? ScheduledBackupWindowPolicy.MDS_ACTOR
                            : ScheduledBackupWindowPolicy.ACTOR, actor);
                    assertEquals(device + ":" + type + ":scheduled:" + date, call.getArgument(2));
                    types.add(type);
                    submissions.add(device + ":" + type + ":" + date);
                    perDeviceTypes.computeIfAbsent(device, ignored -> new java.util.HashSet<>()).add(type);
                    return new AdmissionResult.Admitted("synthetic-job");
                }).when(jobs).submit(anyString(), anyString(), anyString(), anyString(), anyString());
                var collect = new BackupCollectService(devices, jobs, Set.of(), true,
                        Clock.fixed(now, JobWindowPolicy.ZONE_ID)).withMdsExportEligibility(eligibility);
                var scheduler = new BackupScheduler(store, collect);
                scheduler.tick(now);
                scheduler.tick(now.plusSeconds(20));
            }
        }
        assertEquals(expected, store.claims);
        // The two MDSs get no Gaia backup (PO 2026-10-16); the measured one gets its MDS export.
        assertEquals(7 * (targets.size() - 2 + 1), submissions.size());
        assertEquals(submissions.size(), Set.copyOf(submissions).size());
        var expectedTypes = new java.util.HashSet<>(ScheduledBackupWindowPolicy.FLEET_JOB_TYPES);
        expectedTypes.add(ScheduledBackupWindowPolicy.MDS_EXPORT);
        assertEquals(expectedTypes, types);
        assertEquals(Set.of("cp_mds_export"), perDeviceTypes.get("mds-measured"));
        assertEquals(null, perDeviceTypes.get("mds-unmeasured"));
        assertEquals(Set.of("rdw_cc_config_backup"), perDeviceTypes.get("controller"));
    }

    private static DeviceSummaryRecord summary(DeviceRecord target) {
        return new DeviceSummaryRecord(target.deviceId(), target.role(), target.vendorHint(),
                target.enrollmentState(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), target.backupTarget());
    }

    private static DeviceRecord target(String id, String role, String vendor) {
        return new DeviceRecord(id, role, vendor, "manual", Instant.EPOCH, false,
                DeviceEnrollmentState.ENROLLED, false, null, true);
    }

    @Test void manualFleetBackupRefusesTheMdsGaiaBackupAndDoesNotReadScheduledMdsEligibility() {
        var devices = mock(DeviceRepository.class);
        var target = target("mds-measured", "management_server", "check_point");
        when(devices.listAll()).thenReturn(List.of(summary(target)));
        when(devices.find(target.deviceId())).thenReturn(Optional.of(target));
        var eligibility = mock(MdsExportEligibility.class);
        var jobs = mock(JobAdmissionService.class);
        when(jobs.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("synthetic-job"));
        var collect = new BackupCollectService(devices, jobs, Set.of(), true,
                Clock.fixed(at("2026-10-09T06:00:00"), JobWindowPolicy.ZONE_ID))
                .withMdsExportEligibility(eligibility);
        assertEquals(new BackupCollectService.BulkOutcome(1, 0, 1),
                collect.requestCollectAll("synthetic-operator", "Manual fleet backup"));
        verifyNoInteractions(eligibility);
        verifyNoInteractions(jobs);
    }

    @Test void scheduledMdsExportUsesTheLastWindowSecondAndNeverReadsEligibilityOutsideIt() {
        var devices = mock(DeviceRepository.class);
        var target = target("mds-measured", "management_server", "check_point");
        when(devices.find(target.deviceId())).thenReturn(Optional.of(target));
        var eligibility = mock(MdsExportEligibility.class);
        when(eligibility.measuredTargets()).thenReturn(Set.of(target.deviceId()));
        var jobs = mock(JobAdmissionService.class);
        when(jobs.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("synthetic-job"));
        for (var local : List.of("2026-10-09T01:59:59", "2026-10-09T02:59:59", "2026-10-09T03:00:00")) {
            new BackupCollectService(devices, jobs, Set.of(), true,
                    Clock.fixed(at(local), JobWindowPolicy.ZONE_ID))
                    .withMdsExportEligibility(eligibility).requestScheduledMdsExports();
        }
        verify(eligibility).measuredTargets();
        verify(jobs).submit(eq("cp_mds_export"), eq(target.deviceId()),
                eq("mds-measured:cp_mds_export:scheduled:2026-10-09"),
                eq(ScheduledBackupWindowPolicy.MDS_ACTOR), anyString());
        verifyNoMoreInteractions(jobs);
    }

    @Test void restartAndStaleReplicaCannotClaimAnotherRunOnTheSameLocalDay() {
        var store = new PolicyStore();
        var collect = collector();
        var stale = store.find().orElseThrow();
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T02:00:20")).isPresent());
        assertEquals(Optional.of(at("2026-10-09T02:00:20")), store.last);
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T02:30:00")).isEmpty());
        store.staleRead = stale;
        assertTrue(new BackupScheduler(store, collect).tick(at("2026-10-09T02:59:00")).isEmpty());
        assertEquals(1, store.claims.size());
        verify(collect).requestCollectAll(anyString(), anyString());
        verify(collect).requestScheduledMdsExports();
    }

    @Test void missedDaysAreSkippedAndOnlyTodaysRunCanStartWithinTheBackupWindow() {
        var store = new PolicyStore();
        var collect = collector();
        var scheduler = new BackupScheduler(store, collect);
        for (int hour : new int[] {0, 3, 6, 12, 18})
            assertTrue(scheduler.tick(at("2026-10-14T" + String.format("%02d", hour) + ":00:00")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-15T01:59:59")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-15T02:30:00")).isPresent());
        assertTrue(scheduler.tick(at("2026-10-15T02:59:59")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-15T03:00:00")).isEmpty());
        assertEquals(List.of(at("2026-10-15T02:30:00")), store.claims);
        verify(collect).requestCollectAll(anyString(), anyString());
        verify(collect).requestScheduledMdsExports();
    }

    @Test void cronOutsideBackupWindowDoesNotCarryOverToTheNextDay() {
        var store = new PolicyStore();
        store.cron = "0 20 * * *";
        var scheduler = new BackupScheduler(store, collector());
        assertTrue(scheduler.tick(at("2026-10-09T20:00:00")).isEmpty());
        assertTrue(scheduler.tick(at("2026-10-10T02:00:00")).isEmpty());
        assertTrue(store.claims.isEmpty());
    }

    @Test void frequentCronIsStillCappedAtOnePerCalendarDay() {
        var store = new PolicyStore();
        store.cron = "* * * * *";
        var collect = collector();
        var stale = store.find().orElseThrow();
        var scheduler = new BackupScheduler(store, collect);
        scheduler.tick(at("2026-10-09T02:00:00"));
        store.staleRead = stale;
        scheduler.tick(at("2026-10-09T02:30:00"));
        assertEquals(1, store.claims.size());
    }

    @Test void disabledOrInvalidCronNeverClaims() {
        var store = new PolicyStore();
        var collect = collector();
        var scheduler = new BackupScheduler(store, collect);
        store.enabled = false;
        assertTrue(scheduler.tick(at("2026-10-09T02:00:00")).isEmpty());
        store.enabled = true;
        store.cron = "invalid";
        assertTrue(scheduler.tick(at("2026-10-09T02:00:00")).isEmpty());
        assertTrue(store.claims.isEmpty());
        verifyNoInteractions(collect);
    }

    @Test void weeklyCronArithmeticProducesOnlyOneSundayRunPerWeek() {
        Optional<Instant> last = Optional.of(at("2026-10-04T06:00:00"));
        var runs = new ArrayList<Instant>();
        for (var day = LocalDate.of(2026, 10, 5); day.isBefore(LocalDate.of(2026, 10, 19)); day = day.plusDays(1)) {
            for (int hour : new int[] {0, 6, 12, 18}) {
                var now = day.atTime(hour, 0).atZone(JobWindowPolicy.ZONE_ID).toInstant();
                if (BackupScheduleDue.dueSlot("0 3 * * 0", last, now, 24 * 3600,
                        JobWindowPolicy.ZONE_ID).isPresent()) {
                    runs.add(now);
                    last = Optional.of(now);
                }
            }
        }
        assertEquals(List.of(at("2026-10-11T06:00:00"), at("2026-10-18T06:00:00")), runs);
    }

    @Test void cronUsesIstanbulZoneRulesIncludingHistoricalDst() {
        for (var date : List.of("2015-01-15", "2015-07-15", "2026-01-15", "2026-07-15")) {
            assertEquals(Optional.of(at(date + "T02:00:00")),
                    BackupScheduleDue.dueSlot("0 2 * * *", Optional.empty(), at(date + "T06:00:00"),
                            24 * 3600, JobWindowPolicy.ZONE_ID));
        }
    }
}
