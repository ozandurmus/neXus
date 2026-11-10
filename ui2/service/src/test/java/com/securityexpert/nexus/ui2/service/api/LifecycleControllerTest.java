package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.service.security.*;

class LifecycleControllerTest {
    private final LifecycleCatalogRepository catalog = mock(LifecycleCatalogRepository.class);
    private final LifecycleService service = mock(LifecycleService.class);
    private final LifecycleController controller = new LifecycleController(service, catalog, mock(RbacEvaluator.class));
    private MockHttpServletRequest request(boolean masked) {
        var request = new MockHttpServletRequest();
        request.setAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked);
        request.setAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor");
        return request;
    }

    @Test void invalidImportIsAtomicAndValidImportReplacesThroughTheRepository() {
        String header = "vendor,kind,product,end_of_sale,end_of_support,end_of_engineering,note\n";
        var invalid = controller.importCsv(new LifecycleController.ImportRequest(header
                + "CHECKPOINT,HARDWARE,Example Appliance,,2030-01-01,,\nCHECKPOINT,SOFTWARE,R81.20,,2030-02-30,,\n"), request(false));
        assertEquals(400, invalid.getStatusCode().value());
        verifyNoInteractions(catalog);
        var valid = controller.importCsv(new LifecycleController.ImportRequest(header
                + "CHECKPOINT,HARDWARE,Example Appliance,,2030-01-01,,\n"), request(false));
        assertEquals(200, valid.getStatusCode().value());
        verify(catalog).save(argThat(entries -> entries.size() == 1 && entries.getFirst().source().equals("IMPORT")),
                isNull(), eq("synthetic-actor"));
    }

    @Test void replayViewerCannotMutateEvenWhenAnotherRoleMightAuthorizeIt() {
        var request = request(true);
        assertEquals(403, controller.add(new LifecycleController.EntryRequest("CHECKPOINT", "HARDWARE", "Example Appliance", null, null, null, ""), request).getStatusCode().value());
        assertEquals(403, controller.edit("row-1", null, request).getStatusCode().value());
        assertEquals(403, controller.delete("row-1", request).getStatusCode().value());
        assertEquals(403, controller.importCsv(new LifecycleController.ImportRequest(""), request).getStatusCode().value());
        verifyNoInteractions(catalog);
    }

    @Test void replayViewerCatalogIsReadOnlyAndProvenanceIsRedacted() {
        when(catalog.list()).thenReturn(List.of(new LifecycleCatalogEntry("row-1", "CHECKPOINT", "HARDWARE", "Example Appliance",
                null, null, null, "MANUAL", "Synthetic private note", "synthetic-actor", Instant.EPOCH)));
        var body = controller.catalog(request(true));
        assertEquals(false, body.get("can_manage"));
        assertFalse(body.toString().contains("Synthetic private note"));
        assertFalse(body.toString().contains("synthetic-actor"));
    }

    @Test void fleetAndDeviceEndpointsUseTheExistingServerSidePseudonymizer() throws Exception {
        byte[] key = String.join("-", "synthetic", "lifecycle", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var names = new TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        var row = Map.<String, Object>of("device_id", "device-1", "hostname", "synthetic-gateway", "cluster_member_ref", "synthetic-cluster",
                "licenses", List.of(Map.of("member", "synthetic-grid-member", "label", "Never", "status", "UNKNOWN")));
        when(service.fleet(any())).thenReturn(List.of(row));
        when(service.device("device-1")).thenReturn(java.util.Optional.of(row));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        for (String path : List.of("/api/v2/lifecycle", "/devices/device-1/lifecycle")) {
            var response = mvc.perform(get(path).requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                    .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true")).andReturn().getResponse().getContentAsString();
            assertFalse(response.contains("synthetic-gateway"));
            assertFalse(response.contains("synthetic-cluster"));
            assertFalse(response.contains("synthetic-grid-member"));
            assertTrue(response.contains(names.maskDeviceName("synthetic-gateway", "synthetic-cluster")));
            assertTrue(response.contains(names.maskClusterName("synthetic-cluster")));
        }
    }

    @Test void newLicenseAndLivePatchKeysAreServedAndTheMaskedPersonaOnlyPseudonymisesMembers() throws Exception {
        byte[] key = String.join("-", "synthetic", "lifecycle", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var names = new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        var seen = Instant.parse("2026-10-09T01:00:00Z");
        var today = java.time.LocalDate.of(2026, 10, 9);
        var items = List.of(
                new com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem("mc_device_license", "LICENSE",
                        "Web Filter", "VALID", today.plusDays(20), "valid", "synthetic-managed-gateway", "MANAGER_REPORTED", seen),
                new com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem("infoblox_license_gridwide", "LICENSE",
                        "DNS", "VALID", today.plusDays(40), null, "grid", "DIRECT_DEVICE", seen),
                new com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem("cp_cplic_print", "SUPPORT_CONTRACT",
                        "CPAP-SYNTH", "EXPIRED", null, null, null, "DIRECT_DEVICE", seen));
        var patches = List.of(new com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem(
                "PATCH-SYNTH-01", "ready", "auto", 1, 2, java.time.LocalDateTime.of(2026, 9, 1, 8, 30), List.of("sk000000", "CVE-2026-0001"), seen));
        var facts = new com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts("device-1", java.util.Optional.empty(),
                java.util.Optional.of("Jumbo Hotfix Take 99"), java.util.Optional.empty(), Map.of("live_patch_package_take", "42"),
                java.util.Optional.empty(), "synthetic", java.util.Optional.empty());
        var row = com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection.device(
                new com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord("device-1", "gateway", "check_point",
                        com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState.ENROLLED, java.util.Optional.of("synthetic-gateway"),
                        java.util.Optional.of("Example Appliance"), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.of("synthetic-cluster")),
                List.of(), new LifecycleCatalogRepository.StoredInventory(null, null, null), today,
                new com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection.Stored(List.of(
                        new com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.Read("device-1", "cp_cplp_list", "COLLECTED", "OK", 1, seen)),
                        items, patches, facts));
        when(service.fleet(any())).thenReturn(List.of(row));
        when(service.device("device-1")).thenReturn(java.util.Optional.of(row));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        String maskedMember = names.maskManagedDeviceName("synthetic-managed-gateway");
        for (String path : List.of("/api/v2/lifecycle", "/devices/device-1/lifecycle")) {
            var raw = mvc.perform(get(path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            for (String k : List.of("\"license_problem\":true", "\"license_problems\"", "\"license_reads\"", "\"live_patch\"",
                    "\"package_take\":\"42\"", "\"evidence\":\"MANAGER_REPORTED\"", "synthetic-managed-gateway"))
                assertTrue(raw.contains(k), k);
            var json = mvc.perform(get(path).requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(json.contains("synthetic-managed-gateway")); assertFalse(json.contains("synthetic-cluster"));
            assertTrue(json.contains("\"member\":\"" + maskedMember + "\""), json);
            assertTrue(json.contains("\"member\":\"grid\""));
            for (String kept : List.of("\"name\":\"Web Filter\"", "\"status\":\"DATED\"", "\"status\":\"EXPIRED\"", "\"expiry\":\"2026-10-29\"",
                    "\"days_remaining\":20", "\"label\":\"CPAP-SYNTH\"".replace("label", "name"), "\"patch\":\"PATCH-SYNTH-01\"",
                    "\"status\":\"ready\"", "\"refs\":[\"sk000000\",\"CVE-2026-0001\"]", "\"package_take\":\"42\"",
                    "\"jumbo\":\"Jumbo Hotfix Take 99\"", "\"evidence\":\"MANAGER_REPORTED\"", "\"source\":\"mc_device_license\"",
                    "\"detail\":\"valid\""))
                assertTrue(json.contains(kept), kept + " in " + json);
        }
    }
}
