package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository.Stretch;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

class SoftwareVersionHistoryControllerTest {
    private final DeviceSoftwareHistoryRepository history = mock(DeviceSoftwareHistoryRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final SoftwareHistoryController controller = new SoftwareHistoryController(history, devices);

    private static Stretch change() {
        return new Stretch("device-1", Optional.of("synthetic-gateway"), Optional.of("check_point"), "R82", Optional.of("Take 10"),
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-10T10:00:00Z"), Optional.of("R81.20"), Optional.empty(),
                Optional.of(Instant.parse("2026-10-30T10:00:00Z")), "inventory");
    }

    @Test void deviceHistoryCarriesTheChangeWindowAndIsUnknownForAnUnseenDevice() {
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point", "manual",
                Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "cred")));
        when(history.forDevice("device-1")).thenReturn(List.of(change()));
        var body = controller.device("device-1").getBody();
        assertEquals(true, body.get("known"));
        var row = ((List<?>) body.get("stretches")).get(0).toString();
        assertTrue(row.contains("window_start=2026-10-30T10:00:00Z"));
        assertTrue(row.contains("window_end=2026-11-01T10:00:00Z"));
        assertTrue(row.contains("previous_software_version=R81.20"));

        when(history.forDevice("device-1")).thenReturn(List.of());
        assertEquals(false, controller.device("device-1").getBody().get("known"));
        assertEquals(404, controller.device("device-missing").getStatusCode().value());
    }

    @Test void fleetDefaultsToNinetyDaysAndClampsTheWindow() {
        when(history.changesSince(any(), anyInt())).thenReturn(List.of(change()));
        var body = controller.fleet(null);
        assertEquals(90, body.get("days"));
        assertEquals(1, ((List<?>) body.get("changes")).size());
        assertEquals(1, controller.fleet(0).get("days"));
        assertEquals(3650, controller.fleet(100000).get("days"));
    }

    @Test void replayViewerResponsesAreMaskedByTheExistingPseudonymizer() throws Exception {
        byte[] key = String.join("-", "synthetic", "history", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var names = new TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        when(history.changesSince(any(), anyInt())).thenReturn(List.of(change()));
        var mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice).build();
        var response = mvc.perform(get("/api/v2/software-history/changes").requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                .andExpect(status().isOk()).andExpect(header().string("X-Nexus-Masked", "true")).andReturn().getResponse().getContentAsString();
        assertFalse(response.contains("synthetic-gateway"));
        assertTrue(response.contains("R82"));
    }
}
