package com.securityexpert.nexus.ui2.service.device.diagnostic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.jobs.diagnostic.PanSystemLogCollect;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

class SystemLogCollectionServiceTest {
    private static final Instant WINDOW_START = Instant.parse("2026-10-15T03:00:00Z"); // 06:00 Europe/Istanbul

    private static DeviceSummaryRecord summary(String id, String role, String vendor) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private static DeviceRecord record(String id, String role, String vendor) {
        return new DeviceRecord(id, role, vendor, "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "cred");
    }

    private static JobWindowPolicy window(Instant at) {
        return new JobWindowPolicy(Clock.fixed(at, ZoneOffset.UTC), 60);
    }

    private static DeviceRepository fleet() {
        var devices = mock(DeviceRepository.class);
        when(devices.listReadCollectionTargets("pan_xml_api")).thenReturn(List.of(
                summary("fw-1", "gateway", "palo_alto"), summary("fw-2", "gateway", "palo_alto"),
                summary("pano-1", "management_server", "palo_alto")));
        return devices;
    }

    @Test
    void theStaggerIsAStableHashOfTheDeviceIdModuloFortyMinutes() {
        for (String id : List.of("fw-1", "fw-2", "0f6f8d5e-1111-4222-8333-444455556666", "")) {
            int offset = SystemLogCollectionService.staggerMinute(id, 60);
            assertTrue(offset >= 0 && offset < 40, id);
            assertEquals(offset, SystemLogCollectionService.staggerMinute(id, 60), "stable");
            assertEquals(Math.floorMod(id.hashCode(), 40), offset);
        }
        // A window shorter than 40 minutes (UI2_JOB_WINDOW_MINUTES) never leaves a device without a start inside it.
        for (String id : List.of("fw-1", "fw-2", "fw-3", "fw-4"))
            assertTrue(SystemLogCollectionService.staggerMinute(id, 10) < 10);
    }

    @Test
    void aTickAdmitsADeviceOnlyOnceItsOffsetIsReachedAndOnlyOncePerWindow() {
        var devices = fleet();
        var admission = mock(JobAdmissionService.class);
        List<String> keys = new ArrayList<>();
        when(admission.submit(eq(PanSystemLogCollect.CAPABILITY), anyString(), anyString(), eq("system:test"),
                eq(ActionRegistry.SYSTEM_LOG_COLLECT_START))).thenAnswer(call -> {
                    String key = call.getArgument(2);
                    boolean seen = keys.contains(key);
                    keys.add(key);
                    return seen ? new AdmissionResult.Deduplicated("job") : new AdmissionResult.Admitted("job");
                });
        int offset1 = SystemLogCollectionService.staggerMinute("fw-1", 60), offset2 = SystemLogCollectionService.staggerMinute("fw-2", 60);
        assertNotEquals(offset1, offset2, "the fixture ids must stagger differently for this test to mean anything");
        int first = Math.min(offset1, offset2), second = Math.max(offset1, offset2);
        String firstId = offset1 < offset2 ? "fw-1" : "fw-2", secondId = offset1 < offset2 ? "fw-2" : "fw-1";

        var before = new SystemLogCollectionService(devices, admission, window(WINDOW_START.plusSeconds(60L * first - 60)));
        if (first > 0) assertEquals(0, before.admitDue(WINDOW_START.plusSeconds(60L * first - 60), "system:test").admitted());

        var atFirst = new SystemLogCollectionService(devices, admission, window(WINDOW_START.plusSeconds(60L * first)));
        var outcome = atFirst.admitDue(WINDOW_START.plusSeconds(60L * first), "system:test");
        assertEquals(1, outcome.admitted());
        verify(admission).submit(eq(PanSystemLogCollect.CAPABILITY), eq(firstId), anyString(), anyString(), anyString());
        verify(admission, never()).submit(anyString(), eq(secondId), anyString(), anyString(), anyString());

        // Later in the same window both are due; the first one resolves to its existing job.
        Instant late = WINDOW_START.plusSeconds(60L * second);
        var atSecond = new SystemLogCollectionService(devices, admission, window(late)).admitDue(late, "system:test");
        assertEquals(1, atSecond.admitted());
        assertEquals(1, atSecond.deduplicated());
        assertTrue(keys.stream().allMatch(k -> k.matches("syslog-collect:fw-[12]:[0-9]+")));
        verify(admission, never()).submit(anyString(), eq("pano-1"), anyString(), anyString(), anyString());
    }

    @Test
    void aTickOutsideAJobWindowAdmitsNothing() {
        var admission = mock(JobAdmissionService.class);
        Instant outside = Instant.parse("2026-10-15T05:10:00Z"); // 08:10 Europe/Istanbul
        var outcome = new SystemLogCollectionService(fleet(), admission, window(outside)).admitDue(outside, "system:test");
        assertEquals(0, outcome.admitted());
        verifyNoInteractions(admission);
    }

    @Test
    void aManualStartForOneDeviceHasNoStaggerAndAManagementServerIsRefused() {
        var devices = fleet();
        when(devices.find("fw-1")).thenReturn(Optional.of(record("fw-1", "gateway", "palo_alto")));
        when(devices.find("pano-1")).thenReturn(Optional.of(record("pano-1", "management_server", "palo_alto")));
        when(devices.find("cp-1")).thenReturn(Optional.of(record("cp-1", "gateway", "check_point")));
        var admission = mock(JobAdmissionService.class);
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("job-1"));
        var service = new SystemLogCollectionService(devices, admission, window(WINDOW_START));

        var one = service.start(Optional.of("fw-1"), "synthetic-admin");
        assertEquals(1, one.admitted());
        verify(admission).submit(eq(PanSystemLogCollect.CAPABILITY), eq("fw-1"),
                eq("syslog-collect:fw-1:" + java.time.ZonedDateTime.ofInstant(WINDOW_START, JobWindowPolicy.ZONE_ID).toEpochSecond()),
                eq("synthetic-admin"), eq(ActionRegistry.SYSTEM_LOG_COLLECT_START));
        assertEquals(List.of("SYSTEM_LOG_DISABLED_ON_MANAGEMENT"), service.start(Optional.of("pano-1"), "a").refusalCodes());
        assertEquals(List.of("SYSTEM_LOG_VENDOR_UNSUPPORTED"), service.start(Optional.of("cp-1"), "a").refusalCodes());
        assertEquals(List.of("DEVICE_NOT_FOUND"), service.start(Optional.of("missing"), "a").refusalCodes());
        verify(admission, times(1)).submit(anyString(), anyString(), anyString(), anyString(), anyString());

        var all = service.start(Optional.empty(), "synthetic-admin");
        assertEquals(2, all.targets(), "Panorama is never a system-log source");
        assertEquals(2, all.admitted());
    }

    @Test
    void aManualStartOutsideAJobWindowIsRefusedBeforeAnyAdmission() {
        var admission = mock(JobAdmissionService.class);
        var service = new SystemLogCollectionService(fleet(), admission, window(Instant.parse("2026-10-15T05:10:00Z")));
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> service.start(Optional.empty(), "synthetic-admin"));
        verifyNoInteractions(admission);
    }

    @Test
    void theScheduledTriggerIsNotARegisteredBeanUnlessTheFlagIsTrue() {
        var runner = new ApplicationContextRunner().withUserConfiguration(SchedulerFixture.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(SystemLogCollectionScheduler.class));
        runner.withPropertyValues("ui2.system-log.scheduled-collection.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(SystemLogCollectionScheduler.class));
        runner.withPropertyValues("ui2.system-log.scheduled-collection.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(SystemLogCollectionScheduler.class));
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.Import(SystemLogCollectionScheduler.class)
    static class SchedulerFixture {
        @org.springframework.context.annotation.Bean SystemLogCollectionService service() {
            return mock(SystemLogCollectionService.class);
        }
    }

    @Test
    void theCollectionCapabilityIsAdmissibleOnlyWithItsSignedOffCollectionGates() {
        var rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        var capabilities = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key)).toList());
        assertTrue(capabilities.find(PanSystemLogCollect.CAPABILITY).orElseThrow().executionEligible());
        var onlyMeasurement = new DeviceCompositionConfiguration().deviceCapabilityRegistry(key -> rows.stream()
                .filter(row -> row.key().equals(key) && row.gateId().startsWith("pan_syslog_measure")).toList());
        assertFalse(onlyMeasurement.find(PanSystemLogCollect.CAPABILITY).orElseThrow().executionEligible(),
                "the V151 measurement rows never authorize collection");
    }
}
