package com.securityexpert.nexus.ui2.service.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.securityexpert.nexus.ui2.jobs.admission.AdmissionResult;
import com.securityexpert.nexus.ui2.jobs.admission.JobAdmissionService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.platform.BusinessHours;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.ManualAdmission;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;

class SupportBundleServiceTest {
    /** 01:00 Istanbul: outside business hours, night work window. */
    private static final Instant NIGHT = Instant.parse("2026-11-15T22:00:00Z");
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final JobAdmissionService admission = mock(JobAdmissionService.class);
    private final JooqSupportBundleStore store = mock(JooqSupportBundleStore.class);
    private SupportBundleService service;

    @BeforeEach
    void setUp() {
        when(devices.find("dev-cp")).thenReturn(Optional.of(device("gateway", "check_point")));
        when(devices.find("dev-pan")).thenReturn(Optional.of(device("firewall", "palo_alto")));
        when(devices.find("dev-mds")).thenReturn(Optional.of(device("management_server", "check_point")));
        when(devices.find("dev-pan-gw")).thenReturn(Optional.of(device("gateway", "palo_alto")));
        when(devices.find("dev-panorama")).thenReturn(Optional.of(device("management_server", "palo_alto")));
        when(devices.find("dev-asa")).thenReturn(Optional.of(device("gateway", "cisco_asa")));
        when(devices.find("dev-fgt")).thenReturn(Optional.of(device("gateway", "fortinet")));
        when(devices.find("dev-fmg")).thenReturn(Optional.of(device("management_server", "fortinet")));
        when(devices.find("dev-other")).thenReturn(Optional.of(device("gateway", "infoblox")));
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Admitted("job-1"));
        service = new SupportBundleService(devices, admission, store, Clock.fixed(NIGHT, ZoneOffset.UTC),
                new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));
    }

    @AfterEach
    void closeGrant() { ManualAdmission.close(); }

    private static DeviceRecord device(String role, String vendor) {
        return new DeviceRecord("d", role, vendor, "manual", Instant.now(), false, DeviceEnrollmentState.ENROLLED, false, "cred");
    }

    private SupportBundleService.Outcome ask(String device, List<String> kinds, Optional<Instant> at, String reason) {
        return service.request(device, kinds, at, "CASE-1", reason, "ACTOR-ALPHA");
    }

    private static void refused(SupportBundleService.Outcome o, int status, String code) {
        var r = assertInstanceOf(o);
        assertEquals(status, r.status(), r.reason());
        assertEquals(code, r.code());
    }

    private static SupportBundleService.Outcome.Refused assertInstanceOf(SupportBundleService.Outcome o) {
        assertTrue(o instanceof SupportBundleService.Outcome.Refused, String.valueOf(o));
        return (SupportBundleService.Outcome.Refused) o;
    }

    @Test
    void acceptsTheLightItemsAsANowJobThroughTheSharedAdmission() {
        var o = ask("dev-cp", List.of("cp_hcp_latest", "cp_gaia_extras", "cpdata_collector_present"), Optional.empty(), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));
        var rows = ArgumentCaptor.forClass(RequestRow.class);
        @SuppressWarnings("unchecked") var specs = ArgumentCaptor.forClass(List.class);
        verify(store).insert(rows.capture(), specs.capture());
        assertEquals("NOW", rows.getValue().runMode());
        assertEquals(NIGHT.plus(Duration.ofDays(14)), rows.getValue().expiresAt());
        assertEquals(3, specs.getValue().size());
        assertTrue(specs.getValue().get(0).toString().contains("cpdata_collector_present"), "cheapest first");
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-cp"), anyString(), eq("ACTOR-ALPHA"), eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST));
        verify(store).attachJob(any(), eq("job-1"), eq("ACTOR-ALPHA"));
        verify(store, never()).deferJob(anyString(), any(), anyString());
    }

    @Test
    void refusesWhatTheCatalogueDoesNotAllow() {
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "short"), 400, "REASON_REQUIRED");
        refused(service.request("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "bad ref!", "TAC case preparation", "A"), 400, "INVALID_CASE_REF");
        refused(ask("nope", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 404, "DEVICE_NOT_FOUND");
        refused(ask("dev-pan", List.of("pan_tsf"), Optional.empty(), "TAC case preparation"), 409, "ROLE_UNRECOGNISED");
        refused(ask("dev-other", List.of("pan_tsf"), Optional.empty(), "TAC case preparation"), 409, "VENDOR_NOT_YET_ENABLED");
        refused(ask("dev-fmg", List.of("fgt_crashlog"), Optional.empty(), "TAC case preparation"), 409, "PLATFORM_NOT_YET_ENABLED");
        refused(ask("dev-asa", List.of("pan_tsf"), Optional.empty(), "TAC case preparation"), 400, "INVALID_KIND");
        refused(ask("dev-fgt", List.of("asa_show_tech"), Optional.empty(), "TAC case preparation"), 400, "INVALID_KIND");
        refused(ask("dev-mds", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "PLATFORM_NOT_YET_ENABLED");
        refused(ask("dev-cp", List.of("cp_cpinfo"), Optional.empty(), "TAC case preparation"), 409, "KIND_NOT_AVAILABLE");
        refused(ask("dev-cp", List.of("rm -rf"), Optional.empty(), "TAC case preparation"), 400, "INVALID_KIND");
        refused(ask("dev-cp", List.of(), Optional.empty(), "TAC case preparation"), 400, "NO_ITEMS");
        verify(store, never()).insert(any(), any());
        verify(admission, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void eachVendorIsAdmittedAsItsOwnJobWithItsOwnPlatformScope() {
        record Case(String device, String capability, String scope, List<String> kinds) { }
        List<Case> cases = new java.util.ArrayList<>();
        cases.add(new Case("dev-pan-gw", "pan_support_bundle", "pan_firewall", List.of("pan_tsf")));
        cases.add(new Case("dev-panorama", "pan_support_bundle", "panorama", List.of("pan_tsf")));
        cases.add(new Case("dev-asa", "asa_support_bundle", "cisco_asa_firewall", List.of("asa_show_tech", "asa_crashinfo")));
        cases.add(new Case("dev-fgt", "fgt_support_bundle", "fortigate", List.of("fgt_tac_report", "fgt_crashlog")));
        for (Case c : cases) {
            org.mockito.Mockito.reset(store, admission);
            when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(new AdmissionResult.Admitted("job-" + c.device()));
            var o = ask(c.device(), c.kinds(), Optional.empty(), "TAC case preparation");
            assertTrue(o instanceof SupportBundleService.Outcome.Accepted, c.device() + " " + o);
            var rows = ArgumentCaptor.forClass(RequestRow.class);
            @SuppressWarnings("unchecked") var specs = ArgumentCaptor.forClass(List.class);
            verify(store).insert(rows.capture(), specs.capture());
            assertEquals(c.scope(), rows.getValue().platformRole(), c.device());
            assertEquals(c.kinds().size(), specs.getValue().size());
            assertTrue(specs.getValue().get(0).toString().contains(c.kinds().get(c.kinds().size() - 1)), "cheapest first: " + specs.getValue());
            verify(admission).submit(eq(c.capability()), eq(c.device()), anyString(), eq("ACTOR-ALPHA"), eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST));
        }
    }

    @Test
    void enforcesTheDeviceAndFleetQuotas() {
        when(store.liveRequestCount("dev-cp")).thenReturn(3);
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "DEVICE_BUNDLE_QUOTA_EXCEEDED");
        when(store.liveRequestCount("dev-cp")).thenReturn(0);
        when(store.liveBundleBytes()).thenReturn(SupportBundleService.FLEET_BYTES_CAP);
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "FLEET_BUNDLE_QUOTA_EXCEEDED");
    }

    @Test
    void aScheduledRequestIsDeferredAndAcceptedAtAnyTimeInSevenDays() {
        Instant at = NIGHT.plus(Duration.ofHours(3));
        var o = ask("dev-cp", List.of("cp_gaia_extras"), Optional.of(at), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted);
        verify(store).deferJob("job-1", at, "ACTOR-ALPHA");
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.of(NIGHT.plus(Duration.ofDays(8))), "TAC case preparation"), 400, "RUN_AT_OUT_OF_RANGE");
    }

    @Test
    void aChosenTimeInsideBusinessHoursNeedsTheConfirmation() {
        Instant daytime = NIGHT.plus(Duration.ofHours(14)); // 15:00 Istanbul
        // BusinessHours.inside reads the configured zone; the check uses the instant, so build the daytime in that zone
        Instant inside = BusinessHours.SYSTEM.inside(daytime) ? daytime : NIGHT.plus(Duration.ofHours(10));
        var hours = new BusinessHours(LocalTime.of(0, 0), LocalTime.of(23, 59));
        var strict = new SupportBundleService(devices, admission, store, Clock.fixed(NIGHT, ZoneOffset.UTC), hours);
        assertThrows(ManualAdmission.BusinessHoursConfirmRequired.class, () -> strict.request("dev-cp", List.of("cp_gaia_extras"),
                Optional.of(inside), null, "TAC case preparation", "ACTOR-ALPHA"));
        ManualAdmission.open(new ManualAdmission.Grant("ACTOR-ALPHA", "security_admin", true));
        assertTrue(strict.request("dev-cp", List.of("cp_gaia_extras"), Optional.of(inside), null, "TAC case preparation", "ACTOR-ALPHA")
                instanceof SupportBundleService.Outcome.Accepted);
    }

    @Test
    void aRefusedAdmissionClosesTheRequestSoItDoesNotHoldTheDevice() {
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new AdmissionResult.Refused("CAPABILITY_NOT_EXECUTION_ELIGIBLE", "gate unknown"));
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "CAPABILITY_NOT_EXECUTION_ELIGIBLE");
        verify(store).failQueued(any(UUID.class), eq("ACTOR-ALPHA"), anyString());
    }

    @Test
    void aSecondActiveRequestOnTheDeviceIsRefusedWithItsOwnCode() {
        org.mockito.Mockito.doThrow(new RuntimeException("duplicate key value violates unique constraint uq_support_bundle_active_per_device"))
                .when(store).insert(any(), any());
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "SUPPORT_BUNDLE_ALREADY_ACTIVE");
        verify(admission, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
