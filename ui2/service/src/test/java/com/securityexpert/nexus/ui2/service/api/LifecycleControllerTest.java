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
        byte[] key = "synthetic-lifecycle-mask-key".getBytes(StandardCharsets.UTF_8);
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
}
