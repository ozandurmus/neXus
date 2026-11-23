package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.securityexpert.nexus.ui2.persistence.device.DeviceReadRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentityRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.service.api.PolicyController;
import com.securityexpert.nexus.ui2.service.policy.HaPolicyPairService;
import com.securityexpert.nexus.ui2.service.policy.HaPolicyPairing;
import com.securityexpert.nexus.ui2.service.policy.LocalFirewallPolicyService;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

import jakarta.servlet.http.Cookie;

/** {@code GET /api/v2/policy/ha-pairs}: policy_read gate, masked for the replay viewer, never a hash or rule content. */
class PolicyControllerHaPairsTest {
    static final String PATH = "/api/v2/policy/ha-pairs";
    static final String HOST_A = "edge-fw-a01", HOST_B = "edge-fw-a02", HOST_C = "edge-fw-b01", HOST_D = "edge-fw-b02";

    static DeviceSummaryRecord device(String id, String vendor, String host, String cluster) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.of(host),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(cluster));
    }

    static PolicySnapshot snapshot(String device, String vendor, String context, String action) {
        String sid = PolicySnapshot.ref(vendor, device, context);
        String obj = PolicySnapshot.ref(sid, "host");
        var object = new PolicyObject(obj, "HOST-WEB-SYNTH", "host", List.of(), List.of("198.51.100.20/32"), "RESOLVED");
        var rule = new Rule(PolicySnapshot.ref(sid, "rule"), null, 1, "RULE-SYNTH-ONE", true, new Cell(List.of("any"), false),
                new Cell(List.of(obj), false), new Cell(List.of("tcp/443"), false), new Cell(List.of(), false), action, "", "", java.util.Map.of());
        var metadata = new Metadata(sid, device, "x", vendor, PolicySnapshot.ref(device, context), context,
                "policy", "2026-11-22T09:00:00Z", "artefact-" + device, List.of());
        return new PolicySnapshot(metadata, List.of(new Section(PolicySnapshot.ref(sid, "s"), "ACL-SYNTH-OUT", "ASA access-group", null, List.of(rule))),
                java.util.Map.of(obj, object));
    }

    MockMvc mvc(HaPolicyPairService service, SessionRepository sessions, RbacEvaluator rbac) {
        var advice = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(new byte[32]), new TopologyNamePseudonymizer(new byte[32]));
        var gate = new GateChain(sessions, new ActionRegistry(), rbac, mock(AuthzDecisionRepository.class));
        var controller = new PolicyController(mock(PolicyQueryService.class), new com.securityexpert.nexus.ui2.service.policy.PolicyBulkhead(),
                mock(com.securityexpert.nexus.ui2.service.policy.PolicyHygieneFleetSummary.class), service);
        return MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(advice)
                .addInterceptors(new GateChainInterceptor(gate, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE)).build();
    }

    static String session(SessionRepository sessions, RbacEvaluator rbac, String role) {
        String cookie = "synthetic-session-" + role, actor = "synthetic-actor-" + role;
        Instant now = Instant.now();
        var record = new SessionRecord(SessionHasher.hash(cookie), actor, "synthetic-csrf", SessionState.ACTIVE,
                now, now, now.plusSeconds(3600), now.plusSeconds(7200), Optional.empty(), Optional.empty(), Optional.empty());
        when(sessions.findBySessionId(eq(record.sessionId()), any(Instant.class))).thenReturn(Optional.of(record));
        when(rbac.evaluateAny(eq(actor), eq(new ActionRegistry().find(ActionRegistry.POLICY_READ).orElseThrow().requiredRoleTokens()), any()))
                .thenReturn(new RbacEvaluator.Decision(AuthzOutcome.PERMITTED, Optional.of("test"), Optional.empty(), Optional.empty()));
        when(rbac.evaluate(eq(actor), eq(Optional.of(RoleToken.REPLAY_VIEWER)), any())).thenReturn(new RbacEvaluator.Decision(
                role.equals(RoleToken.REPLAY_VIEWER) ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED, Optional.of("test"), Optional.empty(), Optional.empty()));
        return cookie;
    }

    @Test void routeUsesThePolicyReadGate() {
        assertEquals(ActionRegistry.POLICY_READ, SecurityWebMvcConfig.ACTION_ID_BY_ROUTE.get("GET " + PATH));
    }

    @Test void maskedAndUnmaskedBodiesCarryOutcomesButNoHashNoRuleContentAndAnonymousIsRefused() throws Exception {
        var snapshots = mock(LocalFirewallPolicyService.class);
        var devices = mock(DeviceReadRepository.class);
        var identities = mock(HaPairIdentityRepository.class);
        when(devices.listAll()).thenReturn(List.of(
                device("dev-tango-04", "cisco_asa", HOST_A, "cluster-asa"), device("dev-tango-05", "cisco_asa", HOST_B, "cluster-asa"),
                device("dev-tango-06", "fortinet", HOST_C, "cluster-fgt"), device("dev-tango-07", "fortinet", HOST_D, "cluster-fgt")));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        when(identities.find("cluster-fgt")).thenReturn(Optional.of(new HaPairIdentity("cluster-fgt", "fortinet", "S1", "S2", HaPairIdentity.State.MATCH)));
        var asaA = snapshot("dev-tango-04", "ASA", "single", "permit");
        when(snapshots.asaSnapshots()).thenReturn(List.of(asaA, snapshot("dev-tango-05", "ASA", "single", "deny")));
        var fgtA = snapshot("dev-tango-06", "FGT", "VDOM-SYNTH-DMZ", "accept");
        when(snapshots.fgtSnapshots()).thenReturn(List.of(fgtA, snapshot("dev-tango-07", "FGT", "VDOM-SYNTH-DMZ", "accept")));
        var service = new HaPolicyPairService(snapshots, devices, identities, Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC));
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        var mvc = mvc(service, sessions, rbac);

        mvc.perform(get(PATH).servletPath(PATH)).andExpect(status().isUnauthorized());

        List<String> forbidden = List.of(HaPolicyPairing.canonicalHash(asaA), HaPolicyPairing.canonicalHash(fgtA), "198.51.100.20",
                "HOST-WEB-SYNTH", "RULE-SYNTH-ONE", "ACL-SYNTH-OUT", "tcp/443", "cluster-asa", "cluster-fgt", "permit", "artefact-");

        String admin = mvc.perform(get(PATH).servletPath(PATH).cookie(new Cookie("ui2_session", session(sessions, rbac, RoleToken.SECURITY_ADMIN))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER))
                .andExpect(jsonPath("$.pairCount").value(2))
                .andExpect(jsonPath("$.contextRows[0].vendor").value("ASA"))
                .andExpect(jsonPath("$.contextRows[0].haContext").value("single"))
                .andExpect(jsonPath("$.contextRows[0].haOutcome").value("HA_POLICY_DIVERGENT"))
                .andExpect(jsonPath("$.contextRows[0].memberDevices[0].deviceId").value("dev-tango-04"))
                .andExpect(jsonPath("$.contextRows[0].memberDevices[0].name").value(HOST_A))
                .andExpect(jsonPath("$.contextRows[0].newerSnapshotAgeSeconds").value(3600))
                .andExpect(jsonPath("$.contextRows[1].vendor").value("FGT"))
                .andExpect(jsonPath("$.contextRows[1].haContext").value("VDOM-SYNTH-DMZ"))
                .andExpect(jsonPath("$.contextRows[1].haOutcome").value("SAME"))
                .andReturn().getResponse().getContentAsString();
        for (String value : forbidden) assertFalse(admin.contains(value), "unmasked body must not carry " + value);

        String masked = mvc.perform(get(PATH).servletPath(PATH).cookie(new Cookie("ui2_session", session(sessions, rbac, RoleToken.REPLAY_VIEWER))))
                .andExpect(status().isOk()).andExpect(header().string(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, "true"))
                .andExpect(jsonPath("$.contextRows[0].haOutcome").value("HA_POLICY_DIVERGENT"))
                .andExpect(jsonPath("$.contextRows[0].haContext").value("single"))
                .andExpect(jsonPath("$.contextRows[0].memberDevices[1].deviceId").value("dev-tango-05"))
                .andExpect(jsonPath("$.contextRows[1].haOutcome").value("SAME"))
                .andExpect(jsonPath("$.contextRowsByOutcome.SAME").value(1))
                .andReturn().getResponse().getContentAsString();
        for (String value : forbidden) assertFalse(masked.contains(value), "masked body must not carry " + value);
        for (String raw : List.of(HOST_A, HOST_B, HOST_C, HOST_D, "VDOM-SYNTH-DMZ")) assertFalse(masked.contains(raw), "masked body must not carry " + raw);
    }

    @Test void unreadableStoredConfigurationIsServiceUnavailableNotAnEmptyList() throws Exception {
        var service = mock(HaPolicyPairService.class);
        when(service.view()).thenThrow(new IllegalStateException("LOCAL_POLICY_CONFIGURATION_UNAVAILABLE"));
        var sessions = mock(SessionRepository.class);
        var rbac = mock(RbacEvaluator.class);
        mvc(service, sessions, rbac).perform(get(PATH).servletPath(PATH).cookie(new Cookie("ui2_session", session(sessions, rbac, RoleToken.SECURITY_ADMIN))))
                .andExpect(status().isServiceUnavailable());
    }
}
