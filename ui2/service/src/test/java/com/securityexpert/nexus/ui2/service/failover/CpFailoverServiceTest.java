package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.securityexpert.nexus.ui2.service.api.CpFailoverController;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.device.inventory.*;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

class CpFailoverServiceTest {
    private static final String CLUSTER="CLS-TEST-01", A="FW-TEST-01", B="FW-TEST-02";
    private static final String CLUSTER_ID=UUID.nameUUIDFromBytes(CLUSTER.getBytes(StandardCharsets.UTF_8)).toString();
    private final DeviceRepository devices=mock(DeviceRepository.class);
    private final DeviceInventoryRepository inventory=mock(DeviceInventoryRepository.class);
    private final JooqCpFailoverRepository store=mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac=mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust=mock(ManagementEndpointSshTrustRepository.class);
    private final CpFailoverService service=new CpFailoverService(devices,inventory,store,rbac,trust);

    private static DeviceSummaryRecord summary(String id) {
        return new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
    }
    private static InventoryRun run(String id,String role) {
        return new InventoryRun("inventory-"+id,id,"job-"+id,Instant.now(),1,
            List.of(new InventoryContext("physical",List.of(),List.of())),
            List.of(new InventoryHaFact("ha-"+id,"physical",role,Optional.empty(),
                InventoryHaFact.SOURCE_CP_CPHAPROB_STAT)));
    }
    private void ready(String aRole,String bRole) {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.empty(),Optional.empty(),Optional.empty()));
        when(devices.listAll()).thenReturn(List.of(summary(A),summary(B)));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(summary(A),summary(B)));
        for(String id:List.of(A,B)) {
            when(devices.find(id)).thenReturn(Optional.of(new DeviceRecord(id,"gateway","check_point","manual",
                Instant.EPOCH,false,DeviceEnrollmentState.ENROLLED,false,"credential-ref")));
            when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(new EndpointRecord("endpoint-"+id,id,
                "ssh_exec",A.equals(id)?"192.0.2.11":"192.0.2.12",Instant.EPOCH)));
        }
        when(inventory.findLatestRun(A)).thenReturn(Optional.of(run(A,aRole)));
        when(inventory.findLatestRun(B)).thenReturn(Optional.of(run(B,bRole)));
        when(trust.findActiveAlgorithms(anyString(),eq(22))).thenReturn(List.of("ssh-ed25519"));
    }
    @Test void clusterReferenceResolvesToOpaqueUnitsAndServerPermissions() {
        ready("ACTIVE","STANDBY");
        var units=service.unitsForRef(CLUSTER,"actor-1");
        assertEquals(1,units.size());
        assertEquals(CLUSTER_ID,units.get(0).id());
        assertEquals(CLUSTER,units.get(0).label());
        assertTrue(service.mayApprove("actor-1"));
        assertTrue(service.mayStart("actor-1"));
        assertEquals("CLUSTER_NOT_FOUND",assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForRef("unknown","actor-1")).code());
    }
    @Test void vsUnitsUseRawNamesFromDeviceSummaryAndVsidWhenMissing() {
        ready("ACTIVE","STANDBY");
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-APP (VSID 13)"), summaryWithVs(B, "VS-TEST-APP (VSID 13)")));
        when(inventory.findLatestRun(A)).thenReturn(Optional.of(runWithVs(A, "13", "35")));
        when(inventory.findLatestRun(B)).thenReturn(Optional.of(runWithVs(B, "13", "35")));
        var units=service.units(CLUSTER_ID,"actor-1");
        assertEquals(List.of("VS-TEST-APP (VSID 13)", "35"), units.stream().map(CpFailoverService.Unit::label).toList());
        assertEquals(List.of("13", "35"), units.stream().map(CpFailoverService.Unit::vsId).toList());
        assertTrue(units.stream().allMatch(u -> CLUSTER_ID.equals(u.clusterId())));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-APP, VS-TEST-DB"), summaryWithVs(B, "VS-TEST-APP, VS-TEST-DB")));
        assertEquals(List.of("VS-TEST-APP", "VS-TEST-DB"),
            service.units(CLUSTER_ID,"actor-1").stream().map(CpFailoverService.Unit::label).toList());
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-DB (VSID 35), VS-TEST-APP"),
            summaryWithVs(B, "VS-TEST-DB (VSID 35), VS-TEST-APP")));
        assertEquals(List.of("VS-TEST-APP", "VS-TEST-DB (VSID 35)"),
            service.units(CLUSTER_ID,"actor-1").stream().map(CpFailoverService.Unit::label).toList());
    }
    private static DeviceSummaryRecord summaryWithVs(String id,String vsNames) {
        return new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER),
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(vsNames));
    }
    private static InventoryRun runWithVs(String id,String... vsids) {
        return new InventoryRun("inventory-"+id,id,"job-"+id,Instant.EPOCH,vsids.length,
            java.util.Arrays.stream(vsids).map(vs -> new InventoryContext(vs,List.of(),List.of())).toList());
    }
    @Test void wrongRoleRefusedBeforeAdmission() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.DENIED,Optional.empty(),Optional.empty(),Optional.empty()));
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1"));
        assertEquals("WRONG_ROLE",refused.code());
        verifyNoInteractions(store,devices,inventory,trust);
    }
    @Test void noWindowRefusedBeforeJob() {
        ready("ACTIVE","STANDBY");
        when(store.request(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true)))
            .thenReturn(new JooqCpFailoverRepository.Decision("NO_PRE_APPROVAL",null));
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1"));
        assertEquals("NO_PRE_APPROVAL",refused.code());
    }
    @Test void concurrentRunRefusedBeforeJob() {
        ready("ACTIVE","STANDBY");
        when(store.request(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true)))
            .thenReturn(new JooqCpFailoverRepository.Decision("RUN_ALREADY_ACTIVE",null));
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1"));
        assertEquals("RUN_ALREADY_ACTIVE",refused.code());
    }
    @Test void onDemandReadinessPropagatesActiveUnitRefusal() {
        ready("ACTIVE","STANDBY");
        when(store.requestReadiness(CLUSTER,null,"actor-1",A,"check_point"))
            .thenReturn(new JooqCpFailoverRepository.Decision("RUN_ALREADY_ACTIVE",null));
        assertEquals("RUN_ALREADY_ACTIVE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.requestReadiness(CLUSTER_ID,CLUSTER_ID,"actor-1","check_point")).code());
        verify(store).requestReadiness(CLUSTER,null,"actor-1",A,"check_point");
    }
    @Test void concurrentReadinessAdmissionRaceIsRefused() {
        ready("ACTIVE","STANDBY");
        when(store.requestReadiness(CLUSTER,null,"actor-1",A,"check_point"))
            .thenThrow(new org.springframework.dao.DuplicateKeyException("Synthetic active-unit collision"));
        assertEquals("RUN_ALREADY_ACTIVE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.requestReadiness(CLUSTER_ID,CLUSTER_ID,"actor-1","check_point")).code());
    }
    @ParameterizedTest
    @ValueSource(strings = {"READY", "NOT_READY", "UNKNOWN"})
    void summaryEndpointIncludesReadinessStatusFailedCheckAndObservation(String outcome) throws Exception {
        ready("ACTIVE","STANDBY");
        when(store.summaryMembers()).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryMember(A,true,"ssh_exec",true,"physical",false),
            new JooqCpFailoverRepository.SummaryMember(B,true,"ssh_exec",true,"physical",false)));
        Instant observedAt = Instant.parse("2026-09-30T10:00:00Z");
        String failedCheck = "READY".equals(outcome) ? null : "State synchronization";
        String checkStatus = "READY".equals(outcome) ? "PASS" : "NOT_READY".equals(outcome) ? "FAIL" : "UNKNOWN";
        when(store.readinessStatuses()).thenReturn(List.of(new JooqCpFailoverRepository.ReadinessStatus(
            CLUSTER,null,"check_point",outcome,observedAt,failedCheck,
            "[{\"checkNo\":9,\"status\":\"" + checkStatus + "\",\"observedAt\":\"2026-09-30T10:00:00Z\"}]")));
        var json = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        mvc.perform(get("/api/v2/cp-failover/summary")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].unitId").value(CLUSTER_ID))
            .andExpect(jsonPath("$[0].canRunReadiness").value(true))
            .andExpect(jsonPath("$[0].readiness.status").value(outcome))
            .andExpect(jsonPath("$[0].readiness.failedCheck").value(failedCheck == null ? "" : failedCheck))
            .andExpect(jsonPath("$[0].readiness.observedAt").value(observedAt.toString()))
            .andExpect(jsonPath("$[0].readiness.checks[0].checkNo").value(9))
            .andExpect(jsonPath("$[0].readiness.checks[0].status").value(checkStatus))
            .andExpect(jsonPath("$[0].readiness.checks[0].result").value(checkStatus))
            .andExpect(jsonPath("$[0].readiness.checks[0].title").value("State synchronization"))
            .andExpect(jsonPath("$[0].readiness.checks[0].member").value("Member 1"))
            .andExpect(jsonPath("$[0].readiness.checks[0].blocking").value(true))
            .andExpect(jsonPath("$[0].readiness.checks[0].summary").isString());
        when(store.readinessStatuses()).thenReturn(List.of());
        mvc.perform(get("/api/v2/cp-failover/summary")
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].readiness").value(org.hamcrest.Matchers.nullValue()));
    }
    @ParameterizedTest
    @ValueSource(strings = {"check_point", "palo_alto"})
    void detailProjectsSafeMemberLabelsAndSummaries(String vendor) throws Exception {
        ready("ACTIVE","STANDBY");
        Instant observed=Instant.parse("2026-09-30T10:00:00Z");
        var run=new JooqCpFailoverRepository.Run("run-1",CLUSTER,null,null,"actor-1",observed,
            "job-1","DONE","DONE","READY",null,null,vendor,"READINESS");
        when(store.detail("run-1")).thenReturn(Optional.of(new JooqCpFailoverRepository.Detail(run,List.of(
            new JooqCpFailoverRepository.Check("pre",B,null,1,"PASS","{\"role\":\"STANDBY\"}",observed),
            new JooqCpFailoverRepository.Check("pre",A,null,1,"PASS","{\"role\":\"ACTIVE\"}",observed)))));
        var json=new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var mvc=MockMvcBuilders.standaloneSetup(new CpFailoverController(service))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(json)).build();
        String path="/api/v2/"+("palo_alto".equals(vendor)?"pan":"cp")+"-failover/runs/run-1";
        mvc.perform(get(path).servletPath(path)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.checks[0].member").value("Member 2"))
            .andExpect(jsonPath("$.checks[1].member").value("Member 1"))
            .andExpect(jsonPath("$.checks[1].summary").value("Active"))
            .andExpect(jsonPath("$.checks[1].result").value("PASS"))
            .andExpect(jsonPath("$.checks[1].derived").value("{\"role\":\"ACTIVE\"}"));
        verify(devices,never()).findSummary(anyString());
    }
    @Test void incorrectMemberStateRefusedBeforeAdmission() {
        ready("ACTIVE","ACTIVE");
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1"));
        assertEquals("CLUSTER_STATE_NOT_READY",refused.code());
        verifyNoInteractions(store);
    }
    @Test void untrustedHostRefusedBeforeAdmission() {
        ready("ACTIVE","STANDBY");
        when(trust.findActiveAlgorithms(anyString(),eq(22))).thenReturn(List.of());
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1"));
        assertEquals("TRUSTED_HOST_KEY_REQUIRED",refused.code());
        verifyNoInteractions(store);
    }
    @Test void panActiveActiveRefusedWithoutVsUnits() {
        ready("ACTIVE","STANDBY");
        var panA=new DeviceSummaryRecord(A,"gateway","palo_alto",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
        var panB=new DeviceSummaryRecord(B,"gateway","palo_alto",DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of(CLUSTER));
        when(devices.listAll()).thenReturn(List.of(panA,panB));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(panA,panB));
        for (String id:List.of(A,B)) when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(
            new EndpointRecord("endpoint-"+id,id,"pan_xml_api",A.equals(id)?"192.0.2.11":"192.0.2.12",Instant.EPOCH)));
        for (String id:List.of(A,B)) when(inventory.findLatestRun(id)).thenReturn(Optional.of(new InventoryRun(
            "inventory-"+id,id,"job-"+id,Instant.now(),1,
            List.of(new InventoryContext("physical",List.of(),List.of())),
            List.of(new InventoryHaFact("ha-"+id,"physical","ACTIVE",Optional.of("Active-Active"),
                InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE)))));
        assertEquals("UNSUPPORTED_HA_MODE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.units(CLUSTER_ID,"actor-1","palo_alto")).code());
        verifyNoInteractions(store);
    }
    @Test void summaryIncludesCpVsxAndPanWithWindowsAndLatestRuns() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.empty(),Optional.empty(),Optional.empty()));
        var cpA = summary(A);
        var cpB = summary(B);
        var vsA = new DeviceSummaryRecord("VS-A", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("CLS-TEST-VSX"),
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("VS-TEST-APP (VSID 13)"));
        var vsB = new DeviceSummaryRecord("VS-B", "gateway", "check_point", DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("CLS-TEST-VSX"),
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("VS-TEST-APP (VSID 13)"));
        var panA = new DeviceSummaryRecord("PAN-A", "gateway", "palo_alto", DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("CLS-TEST-PAN"));
        var panB = new DeviceSummaryRecord("PAN-B", "gateway", "palo_alto", DeviceEnrollmentState.ENROLLED,
            Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("CLS-TEST-PAN"));
        when(devices.listAll()).thenReturn(List.of(cpA, cpB, vsA, vsB, panA, panB));
        var member = new JooqCpFailoverRepository.SummaryMember(A,true,"ssh_exec",true,"physical",false);
        when(store.summaryMembers()).thenReturn(List.of(member,
            new JooqCpFailoverRepository.SummaryMember(B,true,"ssh_exec",true,"physical",false),
            new JooqCpFailoverRepository.SummaryMember("VS-A",true,"ssh_exec",true,"13",false),
            new JooqCpFailoverRepository.SummaryMember("VS-B",true,"ssh_exec",true,"13",false),
            new JooqCpFailoverRepository.SummaryMember("PAN-A",true,"pan_xml_api",true,"physical",true),
            new JooqCpFailoverRepository.SummaryMember("PAN-B",true,"pan_xml_api",true,"physical",true)));
        Instant runAt = Instant.parse("2026-09-28T10:00:00Z");
        when(store.summaryStatuses()).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryStatus(CLUSTER,null,"check_point",false,null,null,null),
            new JooqCpFailoverRepository.SummaryStatus("CLS-TEST-VSX","13","check_point",true,"DONE","PASS",runAt),
            new JooqCpFailoverRepository.SummaryStatus("CLS-TEST-PAN",null,"palo_alto",false,"STOPPED","FAILED",runAt)));
        var rows = service.summary("actor-1");
        assertEquals(3, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.unit().clusterId().equals(CLUSTER_ID)
            && !r.activeWindow() && r.lastRunAt() == null));
        assertTrue(rows.stream().anyMatch(r -> "13".equals(r.unit().vsId()) && r.activeWindow()
            && "VS-TEST-APP (VSID 13)".equals(r.unit().label()) && "PASS".equals(r.lastRunOutcome())));
        assertTrue(rows.stream().anyMatch(r -> "palo_alto".equals(r.vendor()) && !r.activeWindow()
            && "STOPPED".equals(r.lastRunState()) && runAt.equals(r.lastRunAt())));
        verify(rbac,org.mockito.Mockito.times(2)).evaluate(anyString(),any(),any());
        verify(store).readinessStatuses();
        verify(store).summaryMembers();
        verify(store).summaryStatuses();
        verify(devices).listAll();
        verifyNoInteractions(inventory, trust);
    }
    @Test void summaryKeepsBulkReadsAndAuthorizationConstantFor45Clusters() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.empty(),Optional.empty(),Optional.empty()));
        var members = new java.util.ArrayList<DeviceSummaryRecord>();
        var facts = new java.util.ArrayList<JooqCpFailoverRepository.SummaryMember>();
        for (int i=0;i<45;i++) for (int member=0;member<2;member++) {
            String id="synthetic-device-"+i+"-"+member;
            members.add(new DeviceSummaryRecord(id,"gateway","check_point",DeviceEnrollmentState.ENROLLED,
                Optional.empty(),Optional.empty(),Optional.empty(),Optional.empty(),Optional.of("synthetic-cluster-"+i)));
            facts.add(new JooqCpFailoverRepository.SummaryMember(id,true,"ssh_exec",true,"physical",false));
        }
        when(devices.listAll()).thenReturn(members);
        when(store.summaryMembers()).thenReturn(facts);
        assertEquals(45,service.summary("actor-1").size());
        verify(rbac,org.mockito.Mockito.times(2)).evaluate(anyString(),any(),any());
        verify(store).summaryMembers();
        verify(store).summaryStatuses();
        verify(store).readinessStatuses();
        verify(devices).listAll();
        verifyNoInteractions(inventory,trust);
    }

    @Test void summaryRequiresReaderBeforeBulkReads() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.DENIED,Optional.empty(),Optional.empty(),Optional.empty()));
        assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
            () -> service.summary("actor-1")).code());
        verifyNoInteractions(store, devices, inventory, trust);
    }
    public static void main(String[] args) {
        new CpFailoverServiceTest().wrongRoleRefusedBeforeAdmission();
        new CpFailoverServiceTest().noWindowRefusedBeforeJob();
        new CpFailoverServiceTest().concurrentRunRefusedBeforeJob();
        new CpFailoverServiceTest().incorrectMemberStateRefusedBeforeAdmission();
        new CpFailoverServiceTest().untrustedHostRefusedBeforeAdmission();
        new CpFailoverServiceTest().panActiveActiveRefusedWithoutVsUnits();
    }
}
