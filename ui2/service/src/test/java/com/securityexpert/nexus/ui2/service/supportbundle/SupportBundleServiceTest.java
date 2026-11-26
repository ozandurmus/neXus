package com.securityexpert.nexus.ui2.service.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.securityexpert.nexus.ui2.platform.ActionClass;
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
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
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
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-cp"), anyString(), eq("ACTOR-ALPHA"), eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), isNull());
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
        refused(ask("dev-mds", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "KIND_NOT_AVAILABLE");
        refused(ask("dev-mds", List.of("cp_hcp_latest"), Optional.empty(), "TAC case preparation"), 409, "KIND_NOT_AVAILABLE");
        refused(ask("dev-cp", List.of("rm -rf"), Optional.empty(), "TAC case preparation"), 400, "INVALID_KIND");
        refused(ask("dev-cp", List.of(), Optional.empty(), "TAC case preparation"), 400, "NO_ITEMS");
        verify(store, never()).insert(any(), any());
        verify(admission, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void eachVendorIsAdmittedAsItsOwnJobWithItsOwnPlatformScope() {
        record Case(String device, String capability, String scope, List<String> kinds, ActionClass floor) { }
        List<Case> cases = new java.util.ArrayList<>();
        cases.add(new Case("dev-pan-gw", "pan_support_bundle", "pan_firewall", List.of("pan_tsf"), ActionClass.CLASS_1_RECOVERY_WRITE));
        cases.add(new Case("dev-panorama", "pan_support_bundle", "panorama", List.of("pan_tsf"), ActionClass.CLASS_1_RECOVERY_WRITE));
        cases.add(new Case("dev-asa", "asa_support_bundle", "cisco_asa_firewall", List.of("asa_show_tech", "asa_crashinfo"), null));
        cases.add(new Case("dev-fgt", "fgt_support_bundle", "fortigate", List.of("fgt_tac_report", "fgt_crashlog"), null));
        for (Case c : cases) {
            org.mockito.Mockito.reset(store, admission);
            when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(new AdmissionResult.Admitted("job-" + c.device()));
            var o = ask(c.device(), c.kinds(), Optional.empty(), "TAC case preparation");
            assertTrue(o instanceof SupportBundleService.Outcome.Accepted, c.device() + " " + o);
            var rows = ArgumentCaptor.forClass(RequestRow.class);
            @SuppressWarnings("unchecked") var specs = ArgumentCaptor.forClass(List.class);
            verify(store).insert(rows.capture(), specs.capture());
            assertEquals(c.scope(), rows.getValue().platformRole(), c.device());
            assertEquals(c.kinds().size(), specs.getValue().size());
            assertTrue(specs.getValue().get(0).toString().contains(c.kinds().get(c.kinds().size() - 1)), "cheapest first: " + specs.getValue());
            verify(admission).submit(eq(c.capability()), eq(c.device()), anyString(), eq("ACTOR-ALPHA"), eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), eq(c.floor()));
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
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdmissionResult.Refused("CAPABILITY_NOT_EXECUTION_ELIGIBLE", "gate unknown"));
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "CAPABILITY_NOT_EXECUTION_ELIGIBLE");
        verify(store).failQueued(any(UUID.class), eq("ACTOR-ALPHA"), anyString());
    }

    @Test
    void aSecondActiveRequestOnTheDeviceIsRefusedWithItsOwnCode() {
        org.mockito.Mockito.doThrow(new RuntimeException("duplicate key value violates unique constraint uq_support_bundle_active_per_device"))
                .when(store).insert(any(), any());
        refused(ask("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), "TAC case preparation"), 409, "SUPPORT_BUNDLE_ALREADY_ACTIVE");
        verify(admission, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    // ------------------------------------------------------------------ step S6

    @Test
    void aRequestHoldingARecoveryWriteGateIsAdmittedAsClassOneAndReadsOnlyStayClassZero() {
        var o = ask("dev-cp", List.of("cp_gaia_extras", "cp_hcp_run"), Optional.empty(), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-cp"), anyString(), eq("ACTOR-ALPHA"),
                eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), eq(ActionClass.CLASS_1_RECOVERY_WRITE));
        assertFalse(((SupportBundleService.Outcome.Accepted) o).mdsWarning(), "a gateway carries no MDS warning");
        org.mockito.Mockito.clearInvocations(admission);
        ask("dev-cp", List.of("cp_gaia_extras", "cp_hcp_latest"), Optional.empty(), "TAC case preparation");
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-cp"), anyString(), eq("ACTOR-ALPHA"),
                eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), isNull());
    }

    @Test
    void aManagementServerOrMdsIsEnabledForTheHeavyItemsWithAClearWarningAndNoBlock() {
        var o = ask("dev-mds", List.of("cp_cpinfo", "cp_hcp_run"), Optional.empty(), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));
        assertTrue(((SupportBundleService.Outcome.Accepted) o).mdsWarning());
        assertTrue(SupportBundleService.MDS_WARNING.contains("Multi-Domain Server"));
        var rows = ArgumentCaptor.forClass(RequestRow.class);
        verify(store).insert(rows.capture(), any());
        assertEquals("cp_multi_domain_server", rows.getValue().platformRole());
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-mds"), anyString(), eq("ACTOR-ALPHA"),
                eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), eq(ActionClass.CLASS_1_RECOVERY_WRITE));
    }

    @Test
    void theOptionalCpdataCollectorItemBringsItsPresenceReadAndIsClassOne() {
        var o = ask("dev-cp", List.of("cpdata_collector_run"), Optional.empty(), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));
        @SuppressWarnings("unchecked") var specs = ArgumentCaptor.forClass(List.class);
        verify(store).insert(any(), specs.capture());
        assertEquals(2, specs.getValue().size());
        assertTrue(specs.getValue().get(0).toString().contains("cpdata_collector_present"));
        verify(admission).submit(eq("cp_support_bundle"), eq("dev-cp"), anyString(), eq("ACTOR-ALPHA"),
                eq(ActionRegistry.SUPPORT_BUNDLE_REQUEST), eq(ActionClass.CLASS_1_RECOVERY_WRITE));
    }

    @Test
    void aVsxGatewayRunsHcpAndCpinfoOncePerStoredVirtualSystem() {
        when(store.virtualSystemIds("dev-cp")).thenReturn(List.of(1, 2, 3));
        var o = ask("dev-cp", List.of("cp_hcp_run", "cp_cpinfo"), Optional.empty(), "TAC case preparation");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));
        @SuppressWarnings("unchecked") var specs = ArgumentCaptor.forClass(List.class);
        verify(store).insert(any(), specs.capture());
        assertEquals(6, specs.getValue().size());
        assertTrue(specs.getValue().get(0).toString().contains("cp_hcp_run") && specs.getValue().get(0).toString().contains("1"));
        // too many virtual systems for the 32 item bound
        when(store.virtualSystemIds("dev-cp")).thenReturn(java.util.stream.IntStream.range(1, 20).boxed().toList());
        refused(ask("dev-cp", List.of("cp_hcp_run", "cp_cpinfo"), Optional.empty(), "TAC case preparation"), 409, "TOO_MANY_ITEMS");
    }

    // ------------------------------------------------------------------ V274: quiet hour, Request again, why not started, and one auto-retry

    @Test
    void nextQuietHourAvoidsCollectionWindowsAndBackupsAndPicksEveningOrFourAm() {
        // 23:46 Istanbul (20:46Z): after 23:30 slot -> 04:00 next morning (01:00Z), avoiding 00:00 window and 02:00 backups
        assertEquals(Instant.parse("2026-11-26T01:00:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-25T20:46:00Z")));
        // 02:46 Istanbul (23:46Z, when 23:46 expired after 3 hours) -> 04:00 same morning (01:00Z)
        assertEquals(Instant.parse("2026-11-26T01:00:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-25T23:46:00Z")));
        // 04:00 Istanbul (01:00Z) -> 20:00 same evening (17:00Z), skipping 06:00, 12:00, 18:00 windows and 08:00-20:00 business hours
        assertEquals(Instant.parse("2026-11-26T17:00:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-26T01:00:00Z")));
        // 11:00 Istanbul (08:00Z) -> 20:00 same evening (17:00Z)
        assertEquals(Instant.parse("2026-11-26T17:00:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-26T08:00:00Z")));
        // 20:00 Istanbul (17:00Z) -> 21:00 same evening (18:00Z)
        assertEquals(Instant.parse("2026-11-26T18:00:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-26T17:00:00Z")));
        // 23:10 Istanbul (20:10Z) -> 23:30 same evening (20:30Z)
        assertEquals(Instant.parse("2026-11-26T20:30:00Z"),
                SupportBundleService.nextQuietHour(Instant.parse("2026-11-26T20:10:00Z")));
    }

    @Test
    void requestAgainLinksPreviousRequestAndPreservesVsIdsAndDomainRef() {
        UUID prevId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        RequestRow prev = new RequestRow(prevId, "dev-cp", "check_point", "cp_gaia_gateway", "NOW", NIGHT,
                "CASE-9", "Earlier busy run", "ACTOR-ALPHA", NIGHT, null, NIGHT, NIGHT.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-old", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY");
        when(store.find(prevId)).thenReturn(Optional.of(prev));
        when(store.virtualSystemIds("dev-cp")).thenReturn(List.of(1, 2, 3));

        Instant quiet = Instant.parse("2026-11-16T01:00:00Z"); // 04:00 Istanbul
        var o = service.request("dev-cp", List.of("cp_gaia_extras", "cp_cpinfo"), Optional.of(quiet), "CASE-9",
                "Re-request for TAC case", "ACTOR-ALPHA", prevId, List.of(2), "dom-retail");
        assertTrue(o instanceof SupportBundleService.Outcome.Accepted, String.valueOf(o));

        var rowCap = ArgumentCaptor.forClass(RequestRow.class);
        @SuppressWarnings("unchecked")
        var specCap = ArgumentCaptor.forClass(List.class);
        verify(store).insert(rowCap.capture(), specCap.capture());
        assertEquals(prevId, rowCap.getValue().previousRequestId());
        assertFalse(rowCap.getValue().autoRetry());
        List<JooqSupportBundleStore.ItemSpec> specs = specCap.getValue();
        assertEquals(2, specs.size());
        assertEquals(new JooqSupportBundleStore.ItemSpec("cp_gaia_extras", null, "dom-retail"), specs.get(0));
        assertEquals(new JooqSupportBundleStore.ItemSpec("cp_cpinfo", 2, "dom-retail"), specs.get(1));

        // Wrong device or unknown previous_request_id or unverified VSID is refused
        refused(service.request("dev-asa", List.of("asa_crashinfo"), Optional.empty(), null,
                "Re-request for TAC case", "ACTOR-ALPHA", prevId, null, null), 400, "PREVIOUS_DEVICE_MISMATCH");
        refused(service.request("dev-cp", List.of("cp_gaia_extras"), Optional.empty(), null,
                "Re-request for TAC case", "ACTOR-ALPHA", UUID.randomUUID(), null, null), 404, "PREVIOUS_REQUEST_NOT_FOUND");
        refused(service.request("dev-cp", List.of("cp_cpinfo"), Optional.empty(), null,
                "Re-request for TAC case", "ACTOR-ALPHA", prevId, List.of(99), null), 400, "INVALID_VS_ID");
    }

    @Test
    void expiredBusyRequestGetsExactlyOneAutomaticRetryAtNextQuietHourAsOriginalRequester() {
        // Expired at 2026-11-26 02:46 Istanbul (2026-11-25T23:46:00Z) -> next quiet hour is 04:00 Istanbul (2026-11-26T01:00:00Z)
        Instant expiredAt = Instant.parse("2026-11-25T23:46:00Z");
        Instant expectedQuiet = Instant.parse("2026-11-26T01:00:00Z");
        var retrySvc = new SupportBundleService(devices, admission, store, Clock.fixed(expiredAt, ZoneOffset.UTC),
                new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));

        UUID origId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        RequestRow expired = new RequestRow(origId, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                expiredAt.minus(Duration.ofHours(3)), "CASE-42", "Initial night bundle", "ACTOR-ORIG",
                expiredAt.minus(Duration.ofHours(3)), null, expiredAt, expiredAt.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-exp", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY",
                null, false, List.of("MODULE_DRAIN", "COLLECTION_WINDOW", "DEVICE_JOB_RUNNING"), null);

        when(store.autoRetryCandidates(20)).thenReturn(List.of(expired)).thenReturn(List.of());
        when(store.claimAutoRetryEvaluation(origId)).thenReturn(true);
        when(store.hasFollowUp(origId)).thenReturn(false);
        when(store.items(origId)).thenReturn(List.of(
                new JooqSupportBundleStore.ItemRow(UUID.randomUUID(), origId, 1, "cp_gaia_extras", null, null,
                        "CANCELLED", "NOT_STARTED_DEVICE_BUSY", null, null, null, null, null, "none", List.of(), null, expiredAt),
                new JooqSupportBundleStore.ItemRow(UUID.randomUUID(), origId, 2, "cp_cpinfo", 2, "dom-a",
                        "CANCELLED", "NOT_STARTED_DEVICE_BUSY", null, null, null, null, null, "none", List.of(), null, expiredAt)));
        when(admission.submit(anyString(), anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new AdmissionResult.Admitted("job-retry-1"));

        assertEquals(1, retrySvc.retryExpiredBusy());
        assertEquals(0, retrySvc.retryExpiredBusy(), "second pass creates nothing");

        var rowCap = ArgumentCaptor.forClass(RequestRow.class);
        @SuppressWarnings("unchecked")
        var specCap = ArgumentCaptor.forClass(List.class);
        verify(store).insert(rowCap.capture(), specCap.capture());
        RequestRow created = rowCap.getValue();
        assertEquals("AT", created.runMode());
        assertEquals(expectedQuiet, created.runAt());
        assertEquals("ACTOR-ORIG", created.requestedBy());
        assertEquals("automatic retry of " + origId, created.reason());
        assertEquals("CASE-42", created.caseRef());
        assertEquals(origId, created.previousRequestId());
        assertTrue(created.autoRetry());
        assertEquals("QUEUED", created.state());

        List<JooqSupportBundleStore.ItemSpec> specs = specCap.getValue();
        assertEquals(List.of(
                new JooqSupportBundleStore.ItemSpec("cp_gaia_extras", null, null),
                new JooqSupportBundleStore.ItemSpec("cp_cpinfo", 2, "dom-a")), specs);
        verify(store).attachJob(created.id(), "job-retry-1", "ACTOR-ORIG");
        verify(store).deferJob("job-retry-1", expectedQuiet, "ACTOR-ORIG");
        verify(store).finishAutoRetryEvaluation(origId, "ACTOR-ORIG", "RETRY_SCHEDULED");
    }

    @Test
    void neverRetriesARetryOrAPersonsCancelOrAcrossBusinessHours() {
        Instant expiredAt = Instant.parse("2026-11-25T23:46:00Z");
        UUID parentId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        UUID retryOfRetryId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        UUID cancelledByPersonId = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");

        // 1. An auto-retry that itself expired busy: never retried again
        RequestRow expiredRetry = new RequestRow(retryOfRetryId, "dev-cp", "check_point", "cp_gaia_gateway", "AT",
                expiredAt.minus(Duration.ofHours(3)), null, "automatic retry of " + parentId, "ACTOR-ALPHA",
                expiredAt.minus(Duration.ofHours(3)), null, expiredAt, expiredAt.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-r1", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY",
                parentId, true, List.of("DEVICE_JOB_RUNNING"), null);
        // 2. Cancelled by a person: never retried
        RequestRow personCancelled = new RequestRow(cancelledByPersonId, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                expiredAt.minus(Duration.ofMinutes(10)), null, "Operator cancelled", "ACTOR-ALPHA",
                expiredAt.minus(Duration.ofMinutes(10)), null, expiredAt, expiredAt.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-c1", "{}", "CANCELLED", "CANCELLED",
                null, false, List.of(), null);

        var nightSvc = new SupportBundleService(devices, admission, store, Clock.fixed(expiredAt, ZoneOffset.UTC),
                new BusinessHours(LocalTime.of(8, 0), LocalTime.of(20, 0)));
        when(store.autoRetryCandidates(20)).thenReturn(List.of(expiredRetry, personCancelled));
        assertEquals(0, nightSvc.retryExpiredBusy());
        verify(store, never()).insert(any(), any());
        verify(admission, never()).submit(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        // 3. When business hours cover the evaluation/quiet window, skip automatic retry instead of bypassing human ack
        UUID bhBlockedId = UUID.fromString("ffffffff-ffff-4fff-8fff-ffffffffffff");
        RequestRow bhCandidate = new RequestRow(bhBlockedId, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                expiredAt.minus(Duration.ofHours(3)), null, "TAC bundle", "ACTOR-ALPHA",
                expiredAt.minus(Duration.ofHours(3)), null, expiredAt, expiredAt.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-bh", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY",
                null, false, List.of("MODULE_DRAIN"), null);
        var allDayBusiness = new SupportBundleService(devices, admission, store, Clock.fixed(expiredAt, ZoneOffset.UTC),
                new BusinessHours(LocalTime.of(0, 0), LocalTime.of(23, 59)));
        when(store.autoRetryCandidates(20)).thenReturn(List.of(bhCandidate));
        when(store.claimAutoRetryEvaluation(bhBlockedId)).thenReturn(true);
        when(store.hasFollowUp(bhBlockedId)).thenReturn(false);

        assertEquals(0, allDayBusiness.retryExpiredBusy());
        verify(store, never()).insert(any(), any());
        verify(store).finishAutoRetryEvaluation(bhBlockedId, JooqSupportBundleStore.SYSTEM_RETRY_ACTOR, "SKIPPED_BUSINESS_HOURS");
    }

    @Test
    void notStartedSummaryUsesClosedVocabularyAndExpiryStampOnly() {
        Instant fin = Instant.parse("2026-11-26T02:46:00Z");
        UUID id = UUID.randomUUID();
        RequestRow held = new RequestRow(id, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                fin.minus(Duration.ofHours(3)), "CASE-SECRET", "free text reason", "ACTOR-ALPHA",
                fin.minus(Duration.ofHours(3)), null, fin, fin.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-1", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY",
                null, false, List.of("DEVICE_JOB_RUNNING", "MODULE_DRAIN", "COLLECTION_WINDOW"), null);
        assertEquals("Did not start: held by a module drain, a collection window, and another job on the device; "
                + "expired at 2026-11-26T02:46:00Z.", SupportBundleService.notStartedSummary(held));

        RequestRow unknownHold = new RequestRow(id, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                fin.minus(Duration.ofHours(3)), null, "reason text", "ACTOR-ALPHA",
                fin.minus(Duration.ofHours(3)), null, fin, fin.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-1", "{}", "FAILED", "NOT_STARTED_DEVICE_BUSY");
        assertEquals("Did not start: device remained busy for 3 hours; expired at 2026-11-26T02:46:00Z.",
                SupportBundleService.notStartedSummary(unknownHold));

        RequestRow completed = new RequestRow(id, "dev-cp", "check_point", "cp_gaia_gateway", "NOW",
                fin, null, "reason text", "ACTOR-ALPHA", fin, fin, fin, fin.plus(Duration.ofDays(14)),
                null, null, 0, null, null, "job-1", "{}", "COMPLETED", "COMPLETED");
        assertEquals(null, SupportBundleService.notStartedSummary(completed));
    }
}
