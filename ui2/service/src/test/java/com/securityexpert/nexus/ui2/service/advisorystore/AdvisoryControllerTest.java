package com.securityexpert.nexus.ui2.service.advisorystore;

import static com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.PRODUCT_CISCO_ASA;
import static com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.advisory.AdvisoryModel.*;
import com.securityexpert.nexus.ui2.service.api.AdvisoryController;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/** The three read routes through MockMvc with the real masking advice: device names are pseudonyms for the replay viewer. */
class AdvisoryControllerTest {
    private final MemoryRepository repo = new MemoryRepository();
    private final FakeCore core = new FakeCore();
    private final DeviceRepository devices = mock(DeviceRepository.class);

    @Test void theMaskedPersonaNeverSeesADeviceNameButKeepsVersionsTitlesAndLinksIntact() throws Exception {
        when(devices.listAll()).thenReturn(List.of(new DeviceSummaryRecord("device-1", "gateway", "cisco_asa", DeviceEnrollmentState.ENROLLED,
                Optional.of("FW-TANGO-04"), Optional.of("Example Appliance"), Optional.of("9.18.4"), Optional.empty(), Optional.empty())));
        repo.seedRun("cisco", "OK", 1, Instant.now());
        var parsed = advisory("cisco", "cisco-sa-example", Severity.HIGH, ParseState.PARSED, List.of(),
                List.of(below(PRODUCT_CISCO_ASA, "9.18", 6, "Upgrade to 9.18.6.2")));
        repo.upsertAdvisories(List.of(AdvisoryIngestService.toStored(core, "cisco", "https://www.cisco.com/.well-known/csaf/x.json", "0".repeat(64), parsed).orElseThrow()));
        byte[] key = String.join("-", "synthetic", "advisory", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var names = new TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        var controller = new AdvisoryController(new AdvisoryReadService(repo, devices, () -> Optional.of(core)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();

        String masked = mvc.perform(get("/api/v2/lifecycle/advisories/cisco:cisco-sa-example").requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true")).andReturn().getResponse().getContentAsString();
        assertFalse(masked.contains("FW-TANGO-04"), masked);
        assertTrue(masked.contains(names.maskDeviceName("FW-TANGO-04", null, "device-1")), masked);
        assertTrue(masked.contains("\"version\":\"9.18.4\""), "a four-part version is a product fact, not an address: " + masked);
        assertTrue(masked.contains("Upgrade to 9.18.6.2"), masked);
        assertTrue(masked.contains("\"title\":\"Advisory cisco-sa-example\""), masked);
        assertTrue(masked.contains("\"device_id\":\"device-1\""), masked);

        String plain = mvc.perform(get("/api/v2/lifecycle/advisories/cisco:cisco-sa-example")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(plain.contains("FW-TANGO-04"));

        String list = mvc.perform(get("/api/v2/lifecycle/advisories").requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(list.contains("FW-TANGO-04"));
        assertTrue(list.contains("\"advisory_id\":\"cisco:cisco-sa-example\""), list);
        assertTrue(list.contains("https://www.cisco.com/cisco-sa-example"), list);
    }

    /** The pass-through is tied to an advisory id: a row of another screen that happens to carry a verdict is masked as before. */
    @Test void onlyRowsWithAnAdvisoryIdKeepVersionAndTitleUnmasked() {
        byte[] key = String.join("-", "synthetic", "advisory", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), new TopologyNamePseudonymizer(key));
        String dotted = String.join(".", "9", "18", "4", "50");
        var advisoryRow = new java.util.LinkedHashMap<String, Object>();
        advisoryRow.put("advisory_id", "cisco:cisco-sa-example");
        advisoryRow.put("verdict", "AFFECTED");
        advisoryRow.put("version", dotted);
        advisoryRow.put("fixed_text", "Upgrade to " + dotted);
        var otherRow = new java.util.LinkedHashMap<String, Object>();
        otherRow.put("verdict", "ALLOWED");
        otherRow.put("version", dotted);
        otherRow.put("title", "host " + dotted);
        var bogusId = new java.util.LinkedHashMap<String, Object>(otherRow);
        bogusId.put("advisory_id", 7);
        @SuppressWarnings("unchecked")
        var masked = (java.util.Map<String, Object>) advice.maskObject(java.util.Map.of("a", advisoryRow, "b", otherRow, "c", bogusId), null);
        @SuppressWarnings("unchecked") var a = (java.util.Map<String, Object>) masked.get("a");
        @SuppressWarnings("unchecked") var b = (java.util.Map<String, Object>) masked.get("b");
        @SuppressWarnings("unchecked") var c = (java.util.Map<String, Object>) masked.get("c");
        assertEquals(dotted, a.get("version"));
        assertEquals("Upgrade to " + dotted, a.get("fixed_text"));
        assertFalse(String.valueOf(b.get("version")).contains(dotted), "a verdict alone does not lift the masking: " + b);
        assertFalse(String.valueOf(b.get("title")).contains(dotted), String.valueOf(b));
        assertFalse(String.valueOf(c.get("version")).contains(dotted), "an advisory id that is not one does not lift it: " + c);
    }

    @Test void anUnknownAdvisoryIsA404CodeWordAndTheSourcesRouteAnswers() throws Exception {
        when(devices.listAll()).thenReturn(List.of());
        var controller = new AdvisoryController(new AdvisoryReadService(repo, devices, () -> Optional.of(core)));
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/v2/lifecycle/advisories/paloalto:CVE-0000-0000")).andExpect(status().isNotFound());
        String body = mvc.perform(get("/api/v2/lifecycle/advisories/paloalto:CVE-0000-0000")).andReturn().getResponse().getContentAsString();
        assertEquals("{\"error\":\"ADVISORY_NOT_FOUND\"}", body);
        String sources = mvc.perform(get("/api/v2/lifecycle/advisory-sources")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(sources.contains("\"age_state\":\"NEVER\""), sources);
    }
}
