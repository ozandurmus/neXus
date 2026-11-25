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

import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository;
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
    private final DeviceInstallEventRepository installs = mock(DeviceInstallEventRepository.class);
    private final SoftwareHistoryController controller = new SoftwareHistoryController(history, devices, installs);

    private static DeviceInstallEventRepository.Event jumbo() {
        return new DeviceInstallEventRepository.Event("device-1", Optional.of("synthetic-gateway"),
                DeviceInstallEventRepository.Kind.JUMBO_INSTALL, "R81.20", Optional.of(119),
                java.time.LocalDateTime.parse("2026-11-01T03:10:51"), "install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN.log",
                Instant.parse("2026-11-17T10:00:00Z"));
    }

    @Test void installEventsAreCarriedOnTheDeviceAndTheFleetRoutes() {
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point", "manual",
                Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "cred")));
        when(installs.forDevice("device-1")).thenReturn(List.of(jumbo()));
        var events = (List<?>) controller.device("device-1").getBody().get("install_events");
        assertEquals(1, events.size());
        String row = events.get(0).toString();
        assertTrue(row.contains("kind=JUMBO_INSTALL"));
        assertTrue(row.contains("product_version=R81.20"));
        assertTrue(row.contains("jumbo_take=119"));
        assertTrue(row.contains("installed_at_local=2026-11-01T03:10:51"));
        assertTrue(row.contains("first_observed_at=2026-11-17T10:00:00Z"));

        when(installs.installedSince(any(), anyInt())).thenReturn(List.of(jumbo()));
        var fleet = (List<?>) controller.fleet(30).get("install_events");
        assertEquals(1, fleet.size());
        assertTrue(fleet.get(0).toString().contains("hostname=synthetic-gateway"));
        assertTrue(fleet.get(0).toString().contains("device_id=device-1"));

        when(installs.forDevice("device-1")).thenReturn(List.of());
        assertEquals(List.of(), controller.device("device-1").getBody().get("install_events"));
    }

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

    @Test void replayViewerMasksNonGenericInstallLogFileNamesWhileKeepingStandardVendorNames() throws Exception {
        byte[] key = String.join("-", "synthetic", "history", "mask", "key").getBytes(StandardCharsets.UTF_8);
        var names = new TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        org.springframework.beans.factory.ObjectProvider<TopologyNamePseudonymizer> provider = mock(
                org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(names);
        var maskedController = new SoftwareHistoryController(history, devices, installs, provider);

        String rawHost = "istx9a8b7-fw-01";
        String customLog = "install_SecurePlatform_HOTFIX_R81_20_JUMBO_" + rawHost + ".log";
        var customEvent = new DeviceInstallEventRepository.Event("device-1", Optional.of(rawHost),
                DeviceInstallEventRepository.Kind.JUMBO_INSTALL, "R81.20", Optional.of(119),
                java.time.LocalDateTime.parse("2026-11-02T04:15:00"), customLog,
                Instant.parse("2026-11-18T10:00:00Z"));
        when(devices.find("device-1")).thenReturn(Optional.of(new DeviceRecord("device-1", "gateway", "check_point", "manual",
                Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "cred")));
        when(installs.forDevice("device-1")).thenReturn(List.of(jumbo(), customEvent));
        when(installs.installedSince(any(), anyInt())).thenReturn(List.of(jumbo(), customEvent));

        var mvc = MockMvcBuilders.standaloneSetup(maskedController).setControllerAdvice(advice).build();
        for (String path : List.of("/devices/device-1/software-history", "/api/v2/software-history/changes")) {
            String maskedBody = mvc.perform(get(path).requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, true))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(maskedBody.contains(rawHost), path + " leaked raw hostname in install_events");
            assertFalse(maskedBody.contains(customLog), path + " leaked custom install_events file_name");
            assertTrue(maskedBody.contains("install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN.log"),
                    path + " keeps vendor-generic install file_name");

            String rawBody = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertTrue(rawBody.contains(customLog), path + " preserves raw file_name for non-replay-viewer");
        }
    }
}
