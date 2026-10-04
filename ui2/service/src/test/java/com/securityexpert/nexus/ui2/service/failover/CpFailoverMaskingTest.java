package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CpFailoverMaskingTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void panelNamesAndBadgeFollowTheServerSession(boolean masked) throws Exception {
        String cluster = "synthetic-cluster";
        String first = "synthetic-firewall-1", second = "synthetic-firewall-2";
        var members = List.of(
            new DeviceSummaryRecord("opaque-2", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.of(second), Optional.empty(), Optional.empty(), Optional.of("STANDBY"), Optional.of(cluster)),
            new DeviceSummaryRecord("opaque-1", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
                Optional.of(first), Optional.empty(), Optional.empty(), Optional.of("ACTIVE"), Optional.of(cluster)));
        var unit = new CpFailoverService.Unit("opaque-cluster", "opaque-cluster", cluster, null, members);
        var service = mock(CpFailoverService.class);
        when(service.unitsForMember("opaque-1", "synthetic-actor", "check_point")).thenReturn(List.of(unit));
        when(service.summary("synthetic-actor")).thenReturn(List.of(new CpFailoverService.Summary(unit,
            "check_point", false, null, null, null,
            new JooqCpFailoverRepository.ReadinessStatus(cluster, null, "check_point", "READY", Instant.EPOCH, null,
                "[{\"memberRef\":\"opaque-1\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"role\":\"ACTIVE\",\"hostname\":\"synthetic-firewall-1\"}}]","COMMAND_UNAVAILABLE"), true)));
        byte[] key = "synthetic-readiness-masking-key-01".getBytes(StandardCharsets.UTF_8);
        var names = new TopologyNamePseudonymizer(key);
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(key), names);
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).setControllerAdvice(advice)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        for (String endpoint : List.of("summary", "units?memberDeviceId=opaque-1")) {
            var result = mvc.perform(get("/api/v2/cp-failover/" + endpoint)
                    .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "synthetic-actor")
                    .requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].masked").value(masked))
                .andExpect(jsonPath("$[0].unitId").value("opaque-cluster"))
                .andExpect(jsonPath("$[0].cluster_member_ref").value(masked ? names.maskClusterName(cluster) : cluster))
                .andExpect(jsonPath("$[0].members[0].device_id").value("opaque-1"))
                .andExpect(jsonPath("$[0].members[0].hostname").value(masked ? names.maskDeviceName(first, cluster) : first))
                .andExpect(jsonPath("$[0].members[1].hostname").value(masked ? names.maskDeviceName(second, cluster) : second))
                .andReturn();
            assertEquals(masked ? "true" : null, result.getResponse().getHeader("X-Nexus-Masked"));
            if (masked) {
                String body = result.getResponse().getContentAsString();
                assertFalse(body.contains(cluster));
                assertFalse(body.contains(first));
                assertFalse(body.contains(second));
            }
            if (endpoint.equals("summary")) {
                jsonPath("$[0].readiness.stopCode").value("COMMAND_UNAVAILABLE").match(result);
                var check = json.readTree(result.getResponse().getContentAsString()).get(0).path("readiness").path("checks").get(0);
                assertEquals("Member 1", check.path("member").asText());
                assertEquals("opaque-1", check.path("device_id").asText());
                assertEquals("Active", check.path("summary").asText());
                assertEquals(masked ? names.maskDeviceName(first, cluster) : first, check.path("derived").path("hostname").asText());
            }
        }
    }
}
