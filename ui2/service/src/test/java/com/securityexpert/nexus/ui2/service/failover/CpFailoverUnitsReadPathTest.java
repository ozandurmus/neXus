package com.securityexpert.nexus.ui2.service.failover;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.EndpointRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * The member lookup behind the device Failover tab reads one cluster, never the fleet, and returns
 * exactly what the fleet summary reports for the same units.
 */
class CpFailoverUnitsReadPathTest {
    private static final String ACTOR = "synthetic-actor";
    private static final String VSX = "CLS-TEST-VSX", PLAIN = "CLS-TEST-PLAIN", PAN = "CLS-TEST-PAN", BROKEN = "CLS-TEST-BROKEN";
    private static final String VS_NAMES = "VS-TEST-APP (VSID 13), VS-TEST-DB (VSID 35)";

    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class);
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust = mock(ManagementEndpointSshTrustRepository.class);
    private final List<DeviceSummaryRecord> fleet = new ArrayList<>();
    private final List<JooqCpFailoverRepository.SummaryMember> facts = new ArrayList<>();
    private final List<JooqCpFailoverRepository.SummaryStatus> statuses = new ArrayList<>();
    private final List<JooqCpFailoverRepository.ReadinessStatus> readiness = new ArrayList<>();
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private MockMvc mvc;
    private MockMvc namedMvc;

    private DeviceSummaryRecord member(String id, String vendor, String cluster, String vsNames) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.of("host-" + id),
            Optional.empty(), Optional.empty(), Optional.of("ACTIVE"), Optional.of(cluster),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(vsNames));
    }

    /** Registers one device on every read surface the lookup and the fleet summary use. */
    private void pair(String cluster, String vendor, String vsNames, String transport, boolean inventoryPresent, String... contexts) {
        for (String suffix : List.of("a", "b")) {
            String id = cluster + "-" + suffix;
            var summary = member(id, vendor, cluster, vsNames);
            fleet.add(summary);
            when(devices.findSummary(id)).thenReturn(Optional.of(summary));
            when(devices.find(id)).thenReturn(Optional.of(new DeviceRecord(id, "gateway", vendor, "manual",
                Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(new EndpointRecord("endpoint-" + id, id,
                transport, "192.0.2." + (fleet.size() + 10), Instant.EPOCH)));
            when(inventory.hasRun(id)).thenReturn(inventoryPresent);
            when(inventory.findLatestContextIds(id)).thenReturn(List.of(contexts));
            when(inventory.findLatestHaFacts(id)).thenReturn(List.of(new InventoryHaFact("ha-" + id, "physical",
                "ACTIVE", Optional.of("active-passive"), InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE)));
            for (String context : contexts) facts.add(new JooqCpFailoverRepository.SummaryMember(id, true, transport,
                inventoryPresent, context, "palo_alto".equals(vendor)));
        }
        when(devices.findMembersByClusterRef(cluster)).thenReturn(fleet.stream()
            .filter(d -> d.clusterMemberRef().filter(cluster::equals).isPresent()).toList());
    }

    private static String checks(String member, String mode) {
        return "[{\"memberRef\":\"" + member + "\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"role\":\"ACTIVE\",\"mode\":\""
            + mode + "\"}}]";
    }

    @BeforeEach
    void fixtures() throws Exception {
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED, Optional.of(RbacEvaluator.ROOT_AUTHORITY), Optional.empty(), Optional.empty()));
        pair(VSX, "check_point", VS_NAMES, "ssh_exec", true, "physical", "13", "35");
        pair(PLAIN, "check_point", null, "ssh_exec", true, "physical");
        pair(PAN, "palo_alto", null, "pan_xml_api", true, "physical");
        // Eligible for the unit lookup but without a transport kind in the summary facts: the summary omits it.
        pair(BROKEN, "check_point", null, "ssh_exec", true, "physical");
        facts.removeIf(f -> f.deviceId().startsWith(BROKEN));
        for (String suffix : List.of("a", "b")) facts.add(new JooqCpFailoverRepository.SummaryMember(
            BROKEN + "-" + suffix, true, null, true, "physical", false));
        Instant at = Instant.parse("2026-10-19T12:00:00Z");
        readiness.add(new JooqCpFailoverRepository.ReadinessStatus(VSX, "13", "check_point", "READY", at, null,
            checks(VSX + "-a", "HA"), null));
        readiness.add(new JooqCpFailoverRepository.ReadinessStatus(PLAIN, null, "check_point", "NOT_READY", at, "State",
            checks(PLAIN + "-a", "VSLS"), "UNSUPPORTED_MODE"));
        // Live READY runs carry the message "READY" (2026-10-21: it was read as a refusal and disabled Start failover).
        readiness.add(new JooqCpFailoverRepository.ReadinessStatus(PAN, null, "palo_alto", "READY", at, null,
            checks(PAN + "-a", "active-passive"), "READY"));
        readiness.add(new JooqCpFailoverRepository.ReadinessStatus(BROKEN, null, "check_point", "READY", at, null,
            checks(BROKEN + "-a", "HA"), null));
        statuses.add(new JooqCpFailoverRepository.SummaryStatus(VSX, "13", "check_point", true, "DONE", "PASS", at));

        when(devices.listAll()).thenReturn(fleet);
        when(store.summaryMembers()).thenReturn(facts);
        when(store.summaryStatuses()).thenReturn(statuses);
        when(store.readinessStatuses()).thenReturn(readiness);
        when(store.summaryMembers(anyCollection())).thenAnswer(call -> {
            Set<String> ids = new HashSet<>(call.<java.util.Collection<String>>getArgument(0));
            return facts.stream().filter(f -> ids.contains(f.deviceId())).toList();
        });
        when(store.summaryStatuses(anyString())).thenAnswer(call ->
            statuses.stream().filter(s -> s.clusterRef().equals(call.getArgument(0))).toList());
        when(store.readinessStatuses(anyString())).thenAnswer(call ->
            readiness.stream().filter(s -> s.clusterRef().equals(call.getArgument(0))).toList());

        var service = new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true),
            new JobWindowPolicy(java.time.Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60));
        mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        namedMvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service, null,
                new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(
                    "k".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        clearInvocations(devices, inventory, store, rbac);
    }

    private JsonNode read(String vendorPath, String query) throws Exception {
        String path = "/api/v2/" + vendorPath + "/" + query.split("\\?")[0];
        var request = get(path).servletPath(path).requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR);
        if (query.contains("?")) request.param("memberDeviceId", query.split("=")[1]);
        var result = mvc.perform(request).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertSameAsSummary(JsonNode unit, JsonNode summaryRows) {
        JsonNode counterpart = null;
        for (JsonNode row : summaryRows) if (row.path("unitId").equals(unit.path("unitId"))) counterpart = row;
        assertNotNull(counterpart, "summary lacks unit " + unit.path("unitId"));
        var names = new ArrayList<String>();
        unit.fieldNames().forEachRemaining(names::add);
        int compared = 0;
        for (String name : names) if (counterpart.has(name)) {
            assertEquals(counterpart.get(name), unit.get(name), "field " + name);
            compared++;
        }
        for (String name : List.of("unitId", "clusterId", "cluster_member_ref", "members", "vendor", "masked",
                "mode", "refusalReason", "readinessObservedAt")) assertTrue(unit.has(name), name);
        assertTrue(compared >= 8);
    }

    @Test
    void vsxMemberLookupMatchesTheFleetSummaryUnitForUnit() throws Exception {
        JsonNode all = read("cp-failover", "units?memberDeviceId=" + VSX + "-a");
        // The chassis unit first (no readiness yet, no failover actions), then the two VS units.
        assertEquals(3, all.size());
        JsonNode chassis = all.get(0);
        assertEquals("VSX_CHASSIS", chassis.path("unitKind").asText());
        assertEquals(chassis.path("clusterId").asText(), chassis.path("unitId").asText());
        assertFalse(chassis.has("virtual_system"));
        assertEquals("INSUFFICIENT_EVIDENCE", chassis.path("refusalReason").asText());
        assertEquals(CpFailoverService.CHASSIS_FAILOVER_NOT_APPROVED, chassis.path("failoverRefusal").asText());
        List<JsonNode> units = List.of(all.get(1), all.get(2));
        assertEquals(List.of("VS-TEST-APP (VSID 13)", "VS-TEST-DB (VSID 35)"),
            List.of(units.get(0).path("virtual_system").asText(), units.get(1).path("virtual_system").asText()));
        assertEquals("VIRTUAL_SYSTEM", units.get(0).path("unitKind").asText());
        assertEquals("HA", units.get(0).path("mode").asText());
        assertEquals("2026-10-19T12:00:00Z", units.get(0).path("readinessObservedAt").asText());
        assertEquals("INSUFFICIENT_EVIDENCE", units.get(1).path("refusalReason").asText());
        assertTrue(units.get(1).path("readinessObservedAt").isNull());
        assertTrue(units.get(0).path("canApprove").asBoolean() && units.get(0).path("canStart").asBoolean()
            && units.get(0).path("canSchedule").asBoolean());
        JsonNode summary = read("cp-failover", "summary");
        for (JsonNode unit : all) assertSameAsSummary(unit, summary);
    }

    @Test
    void pseudonymsAreSentOnlyToTheMaskedView() throws Exception {
        // PO 2026-10-21: the failover tab showed pseudonyms (CLS-<WORD>-NN) to security_admin.
        String path = "/api/v2/cp-failover/units";
        for (boolean masked : new boolean[] {false, true}) {
            var request = get(path).servletPath(path).requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, ACTOR)
                .requestAttr(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE, masked).param("memberDeviceId", VSX + "-a");
            JsonNode unit = json.readTree(namedMvc.perform(request).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString()).get(0);
            assertEquals(masked, unit.has("maskedName"), "maskedName only when masked=" + masked);
            assertEquals(masked, unit.has("maskedMembers"), "maskedMembers only when masked=" + masked);
        }
    }

    @Test
    void plainAndPanMembersMatchTheFleetSummaryToo() throws Exception {
        JsonNode summary = read("cp-failover", "summary");
        JsonNode plain = read("cp-failover", "units?memberDeviceId=" + PLAIN + "-b");
        assertEquals(1, plain.size());
        assertEquals("UNSUPPORTED_MODE", plain.get(0).path("refusalReason").asText());
        assertEquals("VSLS", plain.get(0).path("mode").asText());
        assertSameAsSummary(plain.get(0), summary);
        JsonNode pan = read("pan-failover", "units?memberDeviceId=" + PAN + "-a");
        assertEquals(1, pan.size());
        assertEquals("palo_alto", pan.get(0).path("vendor").asText());
        assertEquals("active-passive", pan.get(0).path("mode").asText());
        assertTrue(pan.get(0).path("refusalReason").isNull(), "a READY run's message is not a refusal");
        assertSameAsSummary(pan.get(0), read("pan-failover", "summary"));
    }

    @Test
    void unitsTheSummaryExcludesGetNoReadinessExactlyAsBefore() throws Exception {
        JsonNode summary = read("cp-failover", "summary");
        for (JsonNode row : summary) assertNotEquals(BROKEN, row.path("cluster_member_ref").asText());
        JsonNode broken = read("cp-failover", "units?memberDeviceId=" + BROKEN + "-a");
        assertEquals(1, broken.size());
        assertTrue(broken.get(0).path("readinessObservedAt").isNull());
        assertEquals("INSUFFICIENT_EVIDENCE", broken.get(0).path("refusalReason").asText());
    }

    @Test
    void memberLookupReadsOneClusterNotTheFleet() throws Exception {
        read("cp-failover", "units?memberDeviceId=" + VSX + "-a");
        verify(devices, never()).listAll();
        verify(store, never()).summaryMembers();
        verify(store, never()).summaryStatuses();
        verify(store, never()).readinessStatuses();
        verify(store).summaryMembers(List.of(VSX + "-a", VSX + "-b"));
        verify(store).summaryStatuses(VSX);
        verify(store).readinessStatuses(VSX);
        verify(devices).findSummary(VSX + "-a");
        verify(devices).findMembersByClusterRef(VSX);
        verify(inventory, never()).findLatestRun(anyString());
        verify(inventory, never()).findLatestRuns(anyList());
    }

    @Test
    void storeAndAuthorizationWorkDoesNotGrowWithTheNumberOfUnits() throws Exception {
        read("cp-failover", "units?memberDeviceId=" + PLAIN + "-a");
        int oneUnitEvaluations = mockingDetails(rbac).getInvocations().size();
        int oneUnitStoreReads = mockingDetails(store).getInvocations().size();
        clearInvocations(rbac, store);
        read("cp-failover", "units?memberDeviceId=" + VSX + "-a");
        assertEquals(oneUnitEvaluations, mockingDetails(rbac).getInvocations().size());
        assertEquals(oneUnitStoreReads, mockingDetails(store).getInvocations().size());
        assertTrue(oneUnitEvaluations <= 6, "authorization lookups per request: " + oneUnitEvaluations);
    }

    @Test
    void ineligibleOrUnknownMembersStillReturnAnEmptyListWithoutStoreAccess() throws Exception {
        assertEquals(0, read("cp-failover", "units?memberDeviceId=unknown-device").size());
        when(inventory.hasRun(PLAIN + "-a")).thenReturn(false);
        assertEquals(Set.of("INVENTORY_REQUIRED"), Set.of(assertThrows(CpFailoverService.Refusal.class,
            () -> new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true))
                .unitsForMember(PLAIN + "-a", ACTOR, "check_point")).code()));
        verifyNoInteractions(store);
    }
}
