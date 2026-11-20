package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    // Real default methods: the sliced reads (hasRun, contexts, HA facts) derive from the stubbed findLatestRun.
    private final DeviceInventoryRepository inventory=mock(DeviceInventoryRepository.class,
        org.mockito.Mockito.withSettings().defaultAnswer(org.mockito.Mockito.CALLS_REAL_METHODS));
    private final JooqCpFailoverRepository store=mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac=mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust=mock(ManagementEndpointSshTrustRepository.class);
    private static final JobWindowPolicy WINDOWS = new JobWindowPolicy(
        java.time.Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60);
    private final CpFailoverService service=new CpFailoverService(devices,inventory,store,rbac,trust,
        new FailoverMutationSwitch(true), WINDOWS);

    @ParameterizedTest
    @ValueSource(strings = {"check_point", "palo_alto"})
    void outsideJobWindowRefusesManualReadinessAndFailoverBeforeTargetAccess(String vendor) {
        roles("actor-1", java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN));
        var outside = new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true),
            new com.securityexpert.nexus.ui2.platform.JobWindowPolicy(java.time.Clock.fixed(
                Instant.parse("2026-10-08T10:00:00Z"), java.time.ZoneOffset.UTC), 60));
        assertThrows(com.securityexpert.nexus.ui2.platform.JobWindowPolicy.OutsideWindow.class,
            () -> outside.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", vendor, "request", 1, "nonce", true));
        assertThrows(com.securityexpert.nexus.ui2.platform.JobWindowPolicy.OutsideWindow.class,
            () -> outside.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", vendor));
        verifyNoInteractions(store, devices, inventory, trust);
    }

    /** {local Istanbul time, inside the job window, inside business hours} x role x acknowledgement (PO 2026-10-20). */
    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> adminWindowMatrix() {
        List<org.junit.jupiter.params.provider.Arguments> rows = new java.util.ArrayList<>();
        String[][] times = {{"2026-10-08T12:10:00", "true", "true"}, {"2026-10-08T13:00:00", "false", "true"},
            {"2026-10-08T06:10:00", "true", "false"}, {"2026-10-08T03:30:00", "false", "false"}};
        for (String role : List.of(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN,
                com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN,
                com.securityexpert.nexus.ui2.platform.RoleToken.OPERATOR,
                com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER))
            for (String[] t : times) for (boolean ack : new boolean[] {false, true})
                rows.add(org.junit.jupiter.params.provider.Arguments.of(role, t[0], Boolean.parseBoolean(t[1]),
                    Boolean.parseBoolean(t[2]), ack));
        return rows.stream();
    }
    private CpFailoverService serviceAt(String local) {
        return new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true),
            new JobWindowPolicy(java.time.Clock.fixed(java.time.LocalDateTime.parse(local).atZone(JobWindowPolicy.ZONE_ID)
                .toInstant(), JobWindowPolicy.ZONE_ID), 60, new com.securityexpert.nexus.ui2.platform.BusinessHours(
                    com.securityexpert.nexus.ui2.platform.BusinessHours.DEFAULT_START,
                    com.securityexpert.nexus.ui2.platform.BusinessHours.DEFAULT_END)));
    }
    /** What the request interceptor does: the grant exists only for an actor AdminWindowBypass names. */
    private <T> T asRequest(String actor, boolean ack, java.util.function.Supplier<T> work) {
        var role = new com.securityexpert.nexus.ui2.service.security.AdminWindowBypass(rbac).role(actor);
        return role.isPresent() ? com.securityexpert.nexus.ui2.platform.ManualAdmission.under(
            new com.securityexpert.nexus.ui2.platform.ManualAdmission.Grant(actor, role.get(), ack), work) : work.get();
    }

    @ParameterizedTest(name = "readiness {0} at {1} window={2} businessHours={3} ack={4}")
    @org.junit.jupiter.params.provider.MethodSource("adminWindowMatrix")
    void readinessRunAdmissionPerRoleWindowBusinessHoursAndAcknowledgement(String role, String local, boolean window,
            boolean businessHours, boolean ack) {
        ready("ACTIVE","STANDBY");
        roles("actor-1", java.util.Set.of(role));
        when(store.requestReadiness(CLUSTER,null,"actor-1",A,"check_point"))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-1",null));
        var service = serviceAt(local);
        boolean admin = !role.equals(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATOR)
            && !role.equals(com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER);
        java.util.function.Supplier<String> run = () -> service.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "check_point");
        if (admin && businessHours && !ack) {
            var refusal = assertThrows(com.securityexpert.nexus.ui2.platform.ManualAdmission.BusinessHoursConfirmRequired.class,
                () -> asRequest("actor-1", ack, run));
            assertTrue(refusal.getMessage().startsWith("Business hours (08:00"));
            verifyNoInteractions(store);
        } else if (admin || window) {
            assertEquals("run-1", asRequest("actor-1", ack, run));
        } else {
            // Operator and aiview keep today's refusal, with or without an acknowledgement header.
            assertThrows(JobWindowPolicy.OutsideWindow.class, () -> asRequest("actor-1", ack, run));
            verifyNoInteractions(store);
        }
    }

    @ParameterizedTest(name = "failover start {0} at {1} window={2} businessHours={3} ack={4}")
    @org.junit.jupiter.params.provider.MethodSource("adminWindowMatrix")
    void failoverStartAdmissionPerRoleWindowBusinessHoursAndAcknowledgement(String role, String local, boolean window,
            boolean businessHours, boolean ack) {
        ready("ACTIVE","STANDBY");
        roles("actor-1", java.util.Set.of(role));
        when(store.requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),
            eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),any()))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-2",null));
        var service = serviceAt(local);
        java.util.function.Supplier<String> start = () -> service.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1",
            "check_point", "request-a", 1, "nonce-a", true);
        if (role.equals(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATOR)
                || role.equals(com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER)) {
            // Failover stays an administrator's action: the role refusal comes first, at any hour.
            assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class, () -> asRequest("actor-1", ack, start)).code());
        } else if (businessHours && !ack) {
            assertThrows(com.securityexpert.nexus.ui2.platform.ManualAdmission.BusinessHoursConfirmRequired.class,
                () -> asRequest("actor-1", ack, start));
            verifyNoInteractions(store);
        } else {
            // Every other safeguard (approval binding, nonce, policy, quarantine, members) is the repository's, unchanged.
            assertEquals("run-2", asRequest("actor-1", ack, start));
            verify(store).requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),
                eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),
                eq(role.equals(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN)
                    ? JooqCpFailoverRepository.ADMIN_SINGLE : JooqCpFailoverRepository.TWO_PERSON));
        }
    }

    @Test void scheduledReadinessKeepsTheWindowEvenWhileAnAdministratorsGrantIsOpenOnTheThread() {
        ready("ACTIVE","STANDBY");
        var outside = serviceAt("2026-10-08T13:00:00");
        var grant = new com.securityexpert.nexus.ui2.platform.ManualAdmission.Grant("actor-1",
            com.securityexpert.nexus.ui2.platform.ManualAdmission.SECURITY_ADMIN, true);
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> com.securityexpert.nexus.ui2.platform.ManualAdmission.under(grant,
            () -> outside.requestScheduledReadiness(new CpFailoverService.ReadinessTarget(CLUSTER_ID, CLUSTER_ID, "check_point"))));
        verifyNoInteractions(store);
    }

    @Test void anAdministratorsApprovalRequestOutsideTheWindowNeedsNoAcknowledgementAndOperatorsStillCannot() {
        ready("ACTIVE","STANDBY");
        roles("principal-b", java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN));
        String id="1d730385-5706-4625-a0e9-23c2f3fe1863";
        Instant from=Instant.now(),until=from.plusSeconds(600);
        var outsideInHours = serviceAt("2026-10-08T13:00:00");
        asRequest("principal-b", false, () -> outsideInHours.createApprovalRequest(id,CLUSTER_ID,CLUSTER_ID,from,until,
            "Synthetic request","principal-b","check_point"));
        verify(store).createRequestApproval(id,CLUSTER,null,CLUSTER_ID,java.util.Set.of(A,B),from,until,
            "Synthetic request","principal-b","check_point",JooqCpFailoverRepository.TWO_PERSON,
            JooqCpFailoverRepository.OPERATION_FAILOVER,grant(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN));
        // The same request without the administrator's grant (no session role named it) keeps the window refusal.
        assertThrows(JobWindowPolicy.OutsideWindow.class, () -> outsideInHours.createApprovalRequest(id,CLUSTER_ID,CLUSTER_ID,
            from,until,"Synthetic request","principal-b","check_point"));
    }

    @Test void disabledSwitchRefusesBothVendorsBeforeAdmissionAndDueDispatch() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.ROOT_AUTHORITY),Optional.empty(),Optional.empty()));
        var disabled=new CpFailoverService(devices,inventory,store,rbac,trust,
            new FailoverMutationSwitch(false), WINDOWS);
        for (String vendor:List.of("check_point","palo_alto")) {
            assertEquals(FailoverMutationSwitch.DISABLED,assertThrows(CpFailoverService.Refusal.class,
                () -> disabled.request(CLUSTER_ID,CLUSTER_ID,null,"synthetic-actor",vendor)).code());
            assertEquals(FailoverMutationSwitch.DISABLED,assertThrows(CpFailoverService.Refusal.class,
                () -> disabled.request(CLUSTER_ID,CLUSTER_ID,Instant.now().plusSeconds(60),"synthetic-actor",vendor)).code());
        }
        disabled.startDue();
        verifyNoInteractions(store,devices,inventory,trust);
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN_INCIDENT", "UNRESOLVED_DISPATCH", "MEMBER_SET_CHANGED", "FLEET_MUTATION_ACTIVE"})
    void commonAdmissionRefusalsReachTheCaller(String code) {
        ready("ACTIVE","STANDBY");
        when(store.requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),
            eq("check_point"),eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),eq(JooqCpFailoverRepository.ADMIN_SINGLE)))
            .thenReturn(new JooqCpFailoverRepository.Decision(code,null,"OPEN_INCIDENT".equals(code)?"dispatch-a":null));
        var refusal=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
        assertEquals(code,refusal.code());
        assertEquals("OPEN_INCIDENT".equals(code)?"dispatch-a":null,refusal.dispatchRef());
    }

    private static com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant grant(String role) {
        return new com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant(RbacEvaluator.LOCAL_AUTHORITY,
            "binding-"+role,role);
    }
    private void roles(String actor,java.util.Set<String> roles) {
        when(rbac.evaluate(eq(actor),any(),any())).thenAnswer(call -> {
            Optional<String> role=call.getArgument(1);
            boolean held=role.filter(roles::contains).isPresent();
            return new RbacEvaluator.Decision(held?AuthzOutcome.PERMITTED:AuthzOutcome.DENIED,
                Optional.of(RbacEvaluator.LOCAL_AUTHORITY),Optional.empty(),held?role.map(r -> "binding-"+r):Optional.empty());
        });
    }
    /** V257: the authority that admitted the administrator is what the worker rechecks; one that cannot be recorded is refused. */
    @Test void theAdmittingAuthorityIsRecordedAndAnUnrecordableOneIsRefused() {
        var root=mock(RbacEvaluator.class);
        when(root.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.ROOT_AUTHORITY),Optional.empty(),Optional.empty()));
        assertEquals(new com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant("ui2_root",null,
            com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN),CpFailoverService.adminGrant(root,"synthetic-root"));
        roles("principal-d",java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN));
        assertEquals(grant(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN),CpFailoverService.adminGrant(rbac,"principal-d"));
        for (var decision:List.of(
                new RbacEvaluator.Decision(AuthzOutcome.PERMITTED,Optional.of("synthetic-authority"),Optional.empty(),Optional.of("binding-x")),
                new RbacEvaluator.Decision(AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.LOCAL_AUTHORITY),Optional.empty(),Optional.empty()),
                new RbacEvaluator.Decision(AuthzOutcome.DENIED,Optional.empty(),Optional.empty(),Optional.empty()))) {
            var other=mock(RbacEvaluator.class);
            when(other.evaluate(anyString(),any(),any())).thenReturn(decision);
            assertEquals("WRONG_ROLE",assertThrows(CpFailoverService.Refusal.class,
                () -> CpFailoverService.adminGrant(other,"synthetic-actor")).code());
        }
        verifyNoInteractions(store);
    }
    @Test void serverRolesSelectAdminAndTwoPersonPolicyAndReplayViewerIsDenied() {
        ready("ACTIVE","STANDBY");
        String id="1d730385-5706-4625-a0e9-23c2f3fe1863";
        Instant from=Instant.now(),until=from.plusSeconds(600);
        roles("principal-a",java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN));
        service.createApprovalRequest(id,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic request","principal-a","check_point");
        verify(store).createRequestApproval(id,CLUSTER,null,CLUSTER_ID,java.util.Set.of(A,B),from,until,
            "Synthetic request","principal-a","check_point",JooqCpFailoverRepository.ADMIN_SINGLE,
            JooqCpFailoverRepository.OPERATION_FAILOVER,grant(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN));
        roles("principal-b",java.util.Set.of(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN));
        service.createApprovalRequest(id,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic request","principal-b","check_point");
        verify(store).createRequestApproval(id,CLUSTER,null,CLUSTER_ID,java.util.Set.of(A,B),from,until,
            "Synthetic request","principal-b","check_point",JooqCpFailoverRepository.TWO_PERSON,
            JooqCpFailoverRepository.OPERATION_FAILOVER,grant(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN));
        for (String role:List.of(com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER,
                com.securityexpert.nexus.ui2.platform.RoleToken.OPERATOR)) {
            roles("principal-c",java.util.Set.of(role));
            assertFalse(service.mayApprove("principal-c")); assertFalse(service.mayStart("principal-c"));
            assertEquals("WRONG_ROLE",assertThrows(CpFailoverService.Refusal.class,() ->
                service.createApprovalRequest(id,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic request","principal-c","check_point")).code());
            for (Instant when:new Instant[]{null,until.minusSeconds(60)}) assertEquals("WRONG_ROLE",
                assertThrows(CpFailoverService.Refusal.class,() -> service.request(CLUSTER_ID,CLUSTER_ID,when,
                    "principal-c","check_point",id,1,"nonce-a",true)).code());
        }
    }
    @Test void secondApprovalTakesAuthenticatedActorNotBrowserApprover() throws Exception {
        var mockService=mock(CpFailoverService.class);
        var approved=new JooqCpFailoverRepository.Approval("request-a",CLUSTER,null,Instant.EPOCH,
            Instant.EPOCH.plusSeconds(3600),"Synthetic drill","principal-a",null);
        when(mockService.secondApproval("request-a",1,"unit-a","unit-a","principal-a","check_point"))
            .thenReturn(new JooqCpFailoverRepository.RequestApproval(approved,1,"unit-a","members-opaque",
                JooqCpFailoverRepository.TWO_PERSON,1,"initiator-opaque","nonce-opaque"));
        var mvc=MockMvcBuilders.standaloneSetup(new CpFailoverController(mockService)).build();
        String path="/api/v2/cp-failover/approvals/request-a/approve";
        mvc.perform(post(path).servletPath(path).contentType("application/json")
            .content("{\"clusterId\":\"unit-a\",\"unitId\":\"unit-a\",\"revision\":1,\"approver_id\":\"forged-principal\"}")
            .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE,"principal-a"))
            .andExpect(status().isOk());
        verify(mockService).secondApproval("request-a",1,"unit-a","unit-a","principal-a","check_point");
    }

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
            AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.ROOT_AUTHORITY),Optional.empty(),Optional.empty()));
        when(devices.listAll()).thenReturn(List.of(summary(A),summary(B)));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(summary(A),summary(B)));
        for(String id:List.of(A,B)) when(devices.findSummary(id)).thenReturn(Optional.of(summary(id)));
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
        assertNull(units.get(0).vsId());
        assertFalse(units.get(0).vsxChassis(),"a plain ClusterXL unit is not a VSX chassis");
        assertTrue(service.mayApprove("actor-1"));
        assertTrue(service.mayStart("actor-1"));
        assertEquals(List.of(),service.unitsForRef("unknown","actor-1"));
    }
    private void noUnit(String scenario, String vendor) {
        ready("ACTIVE", "STANDBY");
        Optional<String> ref = "ineligible".equals(scenario) ? Optional.of(CLUSTER) : Optional.empty();
        var member = new DeviceSummaryRecord(A,
            "management_server".equals(scenario) ? "management_server" : "gateway", vendor,
            DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), ref);
        when(devices.listAll()).thenReturn(List.of(member));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(member));
        when(devices.findSummary(A)).thenReturn(Optional.of(member));
    }
    @ParameterizedTest
    @ValueSource(strings = {"standalone", "management_server", "ineligible"})
    void panReadLookupsReturnEmptyAtServiceAndController(String scenario) throws Exception {
        noUnit(scenario, "palo_alto");
        assertEquals(List.of(), service.unitsForMember(A, "actor-1", "palo_alto"));
        assertEquals(List.of(), service.unitsForRef(CLUSTER, "actor-1", "palo_alto"));
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        String path = "/api/v2/pan-failover/units";
        for (String parameter : List.of("memberDeviceId", "clusterRef"))
            mvc.perform(get(path).servletPath(path).param(parameter, "memberDeviceId".equals(parameter) ? A : CLUSTER)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(store);
    }
    @ParameterizedTest
    @ValueSource(strings = {"standalone", "management_server", "ineligible"})
    void cpReadLookupsReturnEmptyAtServiceAndController(String scenario) throws Exception {
        noUnit(scenario, "check_point");
        assertEquals(List.of(), service.unitsForMember(A, "actor-1"));
        assertEquals(List.of(), service.unitsForRef(CLUSTER, "actor-1"));
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        String path = "/api/v2/cp-failover/units";
        for (String parameter : List.of("memberDeviceId", "clusterRef"))
            mvc.perform(get(path).servletPath(path).param(parameter, "memberDeviceId".equals(parameter) ? A : CLUSTER)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        verifyNoInteractions(store);
    }
    @ParameterizedTest
    @ValueSource(strings = {"standalone", "management_server", "ineligible"})
    void writeEndpointsStillRefuseMissingOrIneligibleClusters(String scenario) throws Exception {
        String code = "ineligible".equals(scenario) ? "CLUSTER_NOT_ELIGIBLE" : "CLUSTER_NOT_FOUND";
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        for (String vendor : List.of("check_point", "palo_alto")) {
            noUnit(scenario, vendor);
            for (String endpoint : List.of("approvals", "runs", "units/" + CLUSTER_ID + "/readiness")) {
                String path = "/api/v2/" + ("palo_alto".equals(vendor) ? "pan" : "cp") + "-failover/" + endpoint;
                mvc.perform(post(path).servletPath(path)
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content("{\"clusterId\":\"" + CLUSTER_ID + "\",\"unitId\":\"" + CLUSTER_ID + "\"}")
                    .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
            }
        }
        verifyNoInteractions(store);
    }
    @ParameterizedTest
    @ValueSource(strings = {"check_point", "palo_alto"})
    void readLookupsDoNotSuppressAuthorizationRefusals(String vendor) {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.DENIED,Optional.empty(),Optional.empty(),Optional.empty()));
        assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForMember(A, "actor-1", vendor)).code());
        assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForRef(CLUSTER, "actor-1", vendor)).code());
        verifyNoInteractions(devices, inventory, store);
    }
    @Test void vsUnitsUseRawNamesFromDeviceSummaryAndVsidWhenMissing() {
        ready("ACTIVE","STANDBY");
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-APP (VSID 13)"), summaryWithVs(B, "VS-TEST-APP (VSID 13)")));
        when(inventory.findLatestRun(A)).thenReturn(Optional.of(runWithVs(A, "13", "35")));
        when(inventory.findLatestRun(B)).thenReturn(Optional.of(runWithVs(B, "13", "35")));
        var units=service.units(CLUSTER_ID,"actor-1");
        // The chassis step first (its own unit, the plain-cluster identity), then each VS step.
        assertEquals(List.of(CLUSTER, "VS-TEST-APP (VSID 13)", "35"), units.stream().map(CpFailoverService.Unit::label).toList());
        assertEquals(java.util.Arrays.asList(null, "13", "35"), units.stream().map(CpFailoverService.Unit::vsId).toList());
        assertEquals(List.of(true, false, false), units.stream().map(CpFailoverService.Unit::vsxChassis).toList());
        assertEquals(CLUSTER_ID, units.get(0).id());
        assertTrue(units.stream().allMatch(u -> CLUSTER_ID.equals(u.clusterId())));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-APP, VS-TEST-DB"), summaryWithVs(B, "VS-TEST-APP, VS-TEST-DB")));
        assertEquals(List.of(CLUSTER, "VS-TEST-APP", "VS-TEST-DB"),
            service.units(CLUSTER_ID,"actor-1").stream().map(CpFailoverService.Unit::label).toList());
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-DB (VSID 35), VS-TEST-APP"),
            summaryWithVs(B, "VS-TEST-DB (VSID 35), VS-TEST-APP")));
        assertEquals(List.of(CLUSTER, "VS-TEST-APP", "VS-TEST-DB (VSID 35)"),
            service.units(CLUSTER_ID,"actor-1").stream().map(CpFailoverService.Unit::label).toList());
    }
    private void vsx() {
        ready("ACTIVE","STANDBY");
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(
            summaryWithVs(A, "VS-TEST-APP (VSID 13)"), summaryWithVs(B, "VS-TEST-APP (VSID 13)")));
        when(devices.listAll()).thenReturn(List.of(summaryWithVs(A, "VS-TEST-APP (VSID 13)"), summaryWithVs(B, "VS-TEST-APP (VSID 13)")));
        when(inventory.findLatestRun(A)).thenReturn(Optional.of(runWithVs(A, "13")));
        when(inventory.findLatestRun(B)).thenReturn(Optional.of(runWithVs(B, "13")));
    }
    // ---- VSX chassis failover (PO_DECISION_RECORD_2026_11_19_VSX_CHASSIS_FAILOVER.md, RATIFIED) ----
    private static final String VS_UNIT=UUID.nameUUIDFromBytes((CLUSTER+":13").getBytes(StandardCharsets.UTF_8)).toString();
    /** The chassis unit's latest readiness reports {@code mode} on both members (check 1); the VS unit's is {@code vsOutcome}. */
    private void chassisReadiness(String mode,String chassisOutcome,String vsOutcome) {
        when(store.summaryMembers(any(java.util.Collection.class))).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryMember(A,true,"ssh_exec",true,"physical",false),
            new JooqCpFailoverRepository.SummaryMember(A,true,"ssh_exec",true,"13",false),
            new JooqCpFailoverRepository.SummaryMember(B,true,"ssh_exec",true,"physical",false),
            new JooqCpFailoverRepository.SummaryMember(B,true,"ssh_exec",true,"13",false)));
        String checks=mode==null ? "[]" : "[{\"memberRef\":\""+A+"\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"mode\":\""+mode
            +"\",\"role\":\"ACTIVE\"}},{\"memberRef\":\""+B+"\",\"checkNo\":1,\"status\":\"PASS\",\"derived\":{\"mode\":\""+mode
            +"\",\"role\":\"STANDBY\"}}]";
        Instant at=Instant.parse("2026-11-19T20:00:00Z");
        when(store.readinessStatuses(CLUSTER)).thenReturn(List.of(
            new JooqCpFailoverRepository.ReadinessStatus(CLUSTER,null,"check_point",chassisOutcome,at,null,checks),
            new JooqCpFailoverRepository.ReadinessStatus(CLUSTER,"13","check_point",vsOutcome,at,null,"[]")));
    }
    @Test void chassisModeIsReadFromBothMembersCheckOne() {
        var at=Instant.EPOCH;
        java.util.function.BiFunction<String,String,JooqCpFailoverRepository.ReadinessStatus> status=(ma,mb) ->
            new JooqCpFailoverRepository.ReadinessStatus(CLUSTER,null,"check_point","READY",at,null,
                "[{\"memberRef\":\"m1\",\"checkNo\":1,\"derived\":{\"mode\":\""+ma+"\"}},"
                +"{\"memberRef\":\"m2\",\"checkNo\":1,\"derived\":{\"mode\":\""+mb+"\"}}]");
        assertEquals("HA",CpFailoverService.chassisMode(status.apply("HA","HA")));
        assertEquals("VSLS",CpFailoverService.chassisMode(status.apply("VSLS","VSLS")));
        assertEquals("UNKNOWN",CpFailoverService.chassisMode(status.apply("HA","VSLS")));
        assertEquals("UNKNOWN",CpFailoverService.chassisMode(status.apply("UNSUPPORTED","UNSUPPORTED")));
        assertEquals("UNKNOWN",CpFailoverService.chassisMode(null));
    }
    @Test void aChassisWithoutAKnownModeIsRefusedButItsReadinessIsAdmitted() {
        vsx();
        chassisReadiness(null,"UNKNOWN","READY");
        String request=UUID.randomUUID().toString();
        Instant from=Instant.now(), until=from.plusSeconds(600);
        assertEquals(CpFailoverService.CHASSIS_MODE_UNKNOWN, assertThrows(CpFailoverService.Refusal.class,
            () -> service.createApprovalRequest(request,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic","actor-1","check_point")).code());
        assertEquals(CpFailoverService.CHASSIS_MODE_UNKNOWN, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point",request,1,"nonce",true)).code());
        verify(store,never()).createRequestApproval(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any());
        verify(store,never()).requestBound(any(),any(),any(),any(),any(),anyBoolean(),any(),any(),any(),anyLong(),any(),anyBoolean(),any());
        when(store.requestReadiness(CLUSTER,null,"actor-1",A,"check_point"))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-chassis",null));
        assertEquals("run-chassis",service.requestReadiness(CLUSTER_ID,CLUSTER_ID,"actor-1","check_point"));
        // The VS step keeps its own unit and is never refused for the chassis rule.
        when(store.requestBound(eq(CLUSTER),eq("13"),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),
            eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),any()))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-vs-failover",null));
        assertEquals("run-vs-failover",service.request(CLUSTER_ID,VS_UNIT,null,"actor-1","check_point","request-a",1,"nonce-a",true));
    }
    @Test void aVsxHaModeChassisIsApprovedAndStartedAsOneChassisRun() {
        vsx();
        chassisReadiness("HA","READY","READY");
        String request=UUID.randomUUID().toString();
        Instant from=Instant.now(), until=from.plusSeconds(600);
        service.createApprovalRequest(request,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic","actor-1","check_point");
        // Option B: an ordinary unit approval (operation FAILOVER) on the chassis unit.
        verify(store).createRequestApproval(eq(request),eq(CLUSTER),isNull(),eq(CLUSTER_ID),eq(java.util.Set.of(A,B)),eq(from),eq(until),
            eq("Synthetic"),eq("actor-1"),eq("check_point"),eq(JooqCpFailoverRepository.ADMIN_SINGLE),
            eq(JooqCpFailoverRepository.OPERATION_FAILOVER),any());
        when(store.requestOperation("request-a")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_FAILOVER));
        when(store.requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),
            eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),eq(JooqCpFailoverRepository.ADMIN_SINGLE)))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-chassis",null));
        assertEquals("run-chassis",service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
        // An approval of the other path (the mode changed since): refused, nothing started.
        when(store.requestOperation("request-b")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS));
        assertEquals(CpFailoverService.CHASSIS_MODE_CHANGED, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-b",1,"nonce-b",true)).code());
        verify(store,never()).requestSequence(any(),any(),any(),any(),any(),anyLong(),any(),anyBoolean(),any(),any(),any(),anyBoolean());
    }
    @Test void aChassisStartNeedsTheChassisAndEveryVirtualSystemReady() {
        vsx();
        when(store.requestOperation("request-a")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_FAILOVER));
        chassisReadiness("HA","NOT_READY","READY");
        assertEquals(CpFailoverService.CHASSIS_READINESS_NOT_READY, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true)).code());
        chassisReadiness("HA","READY","NOT_READY");
        assertEquals(CpFailoverService.VIRTUAL_SYSTEM_NOT_READY, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true)).code());
        verify(store,never()).requestBound(any(),any(),any(),any(),any(),anyBoolean(),any(),any(),any(),anyLong(),any(),anyBoolean(),any());
    }
    @Test void aVslsChassisRunsEveryVirtualSystemInSequenceAndNeverTheVs0Write() {
        vsx();
        chassisReadiness("VSLS","READY","READY");
        String request=UUID.randomUUID().toString();
        Instant from=Instant.now(), until=from.plusSeconds(600);
        service.createApprovalRequest(request,CLUSTER_ID,CLUSTER_ID,from,until,"Synthetic","actor-1","check_point");
        verify(store).createRequestApproval(eq(request),eq(CLUSTER),isNull(),eq(CLUSTER_ID),any(),any(),any(),any(),any(),any(),any(),
            eq(JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS),any());
        // A chassis-run approval on a VSLS chassis: option B is refused with its own code.
        when(store.requestOperation("request-b")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_FAILOVER));
        assertEquals(CpFailoverService.CHASSIS_MODE_VSLS, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-b",1,"nonce-b",true)).code());
        when(store.requestOperation("request-a")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS));
        assertEquals(CpFailoverService.SEQUENCE_NOT_SCHEDULABLE, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,Instant.now().plusSeconds(60),"actor-1","check_point","request-a",1,"nonce-a",true)).code());
        when(store.requestSequence(eq(CLUSTER),eq("actor-1"),eq(A),eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),
            eq(true),eq(JooqCpFailoverRepository.ADMIN_SINGLE),
            eq(List.of(new JooqCpFailoverRepository.SequenceStep("13",VS_UNIT))),any(),anyBoolean()))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED","run-vs-13",null));
        assertEquals("run-vs-13",service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
        verify(store,never()).requestBound(any(),any(),any(),any(),any(),anyBoolean(),any(),any(),any(),anyLong(),any(),anyBoolean(),any());
        when(store.requestSequence(any(),any(),any(),any(),eq("request-c"),anyLong(),any(),anyBoolean(),any(),any(),any(),anyBoolean()))
            .thenReturn(new JooqCpFailoverRepository.Decision("FLEET_MUTATION_ACTIVE",null));
        when(store.requestOperation("request-c")).thenReturn(Optional.of(JooqCpFailoverRepository.OPERATION_ALL_VIRTUAL_SYSTEMS));
        assertEquals("FLEET_MUTATION_ACTIVE", assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-c",1,"nonce-c",true)).code());
    }
    @Test void aRunningSequenceAdvancesUnderTheInitiatorsAdmissionAndStopsOnAWindowRefusal() {
        vsx();
        var seq=new JooqCpFailoverRepository.Sequence("seq-1",CLUSTER,"approval-1","actor-1",
            com.securityexpert.nexus.ui2.platform.ManualAdmission.SECURITY_ADMIN,true,List.of("13","35"),1,"run-13","RUNNING",
            null,null,Instant.EPOCH,null);
        when(store.runningSequences()).thenReturn(List.of(seq));
        var steps=List.of(new JooqCpFailoverRepository.SequenceStep("13",VS_UNIT),
            new JooqCpFailoverRepository.SequenceStep("35",UUID.nameUUIDFromBytes((CLUSTER+":35").getBytes(StandardCharsets.UTF_8)).toString()));
        when(store.advanceSequence("seq-1",steps,A)).thenAnswer(call -> {
            var grant=com.securityexpert.nexus.ui2.platform.ManualAdmission.currentFor("actor-1").orElseThrow();
            assertEquals(com.securityexpert.nexus.ui2.platform.ManualAdmission.SECURITY_ADMIN,grant.role());
            assertTrue(grant.acknowledged());
            return "ADMITTED";
        });
        service.advanceSequences();
        verify(store).advanceSequence("seq-1",steps,A);
        assertTrue(com.securityexpert.nexus.ui2.platform.ManualAdmission.current().isEmpty(),"the grant is never left on the thread");
        org.mockito.Mockito.doThrow(new JobWindowPolicy.OutsideWindow(java.time.ZonedDateTime.now()))
            .when(store).advanceSequence("seq-1",steps,A);
        service.advanceSequences();
        verify(store).stopSequence("seq-1",JobWindowPolicy.CODE,"35");
    }
    @Test void stepFenceRefusalsFromTheRepositoryReachTheCaller() {
        vsx();
        when(store.requestReadiness(CLUSTER,null,"actor-1",A,"check_point"))
            .thenReturn(new JooqCpFailoverRepository.Decision(JooqCpFailoverRepository.VS_RUN_ACTIVE,null));
        assertEquals(JooqCpFailoverRepository.VS_RUN_ACTIVE, assertThrows(CpFailoverService.Refusal.class,
            () -> service.requestReadiness(CLUSTER_ID,CLUSTER_ID,"actor-1","check_point")).code());
        String vsUnit=UUID.nameUUIDFromBytes((CLUSTER+":13").getBytes(StandardCharsets.UTF_8)).toString();
        when(store.requestBound(eq(CLUSTER),eq("13"),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),
            eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),any()))
            .thenReturn(new JooqCpFailoverRepository.Decision(JooqCpFailoverRepository.CHASSIS_RUN_ACTIVE,null));
        assertEquals(JooqCpFailoverRepository.CHASSIS_RUN_ACTIVE, assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,vsUnit,null,"actor-1","check_point","request-a",1,"nonce-a",true)).code());
    }
    @Test void vsxChassisIsAScheduledReadinessTargetAndAnApiUnitWithoutFailoverActions() throws Exception {
        vsx();
        assertEquals(List.of(CLUSTER_ID, UUID.nameUUIDFromBytes((CLUSTER+":13").getBytes(StandardCharsets.UTF_8)).toString()),
            service.units(CLUSTER_ID,"actor-1").stream().map(CpFailoverService.Unit::id).toList());
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        String path = "/api/v2/cp-failover/units";
        mvc.perform(get(path).servletPath(path).param("clusterRef", CLUSTER)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].unitId").value(CLUSTER_ID))
            .andExpect(jsonPath("$[0].unitKind").value("VSX_CHASSIS"))
            .andExpect(jsonPath("$[0].failoverRefusal").value(CpFailoverService.CHASSIS_MODE_UNKNOWN))
            .andExpect(jsonPath("$[0].canStart").value(false))
            .andExpect(jsonPath("$[0].virtual_system").doesNotExist())
            .andExpect(jsonPath("$[1].unitKind").value("VIRTUAL_SYSTEM"))
            .andExpect(jsonPath("$[1].canStart").value(true))
            .andExpect(jsonPath("$[1].failoverRefusal").doesNotExist());
    }
    @Test void theChassisUnitOffersTheActionItsModeAllows() throws Exception {
        vsx();
        var mvc = MockMvcBuilders.standaloneSetup(new CpFailoverController(service)).build();
        String path = "/api/v2/cp-failover/units";
        chassisReadiness("HA","READY","READY");
        mvc.perform(get(path).servletPath(path).param("clusterRef", CLUSTER)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].unitKind").value("VSX_CHASSIS"))
            .andExpect(jsonPath("$[0].chassisMode").value("HA"))
            .andExpect(jsonPath("$[0].chassisAction").value(CpFailoverService.ACTION_CHASSIS_FAILOVER))
            .andExpect(jsonPath("$[0].failoverRefusal").doesNotExist())
            .andExpect(jsonPath("$[0].canStart").value(true))
            .andExpect(jsonPath("$[0].canSchedule").value(true));
        chassisReadiness("VSLS","READY","NOT_READY");
        mvc.perform(get(path).servletPath(path).param("clusterRef", CLUSTER)
                .requestAttr(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE, "actor-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].chassisAction").value(CpFailoverService.ACTION_ALL_VIRTUAL_SYSTEMS))
            .andExpect(jsonPath("$[0].chassisStartRefusal").value(CpFailoverService.VIRTUAL_SYSTEM_NOT_READY))
            .andExpect(jsonPath("$[0].canStart").value(true))
            .andExpect(jsonPath("$[0].canSchedule").value(false));
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
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
        assertEquals("WRONG_ROLE",refused.code());
        verifyNoInteractions(store,devices,inventory,trust);
    }
    @Test void noWindowRefusedBeforeJob() {
        ready("ACTIVE","STANDBY");
        when(store.requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),eq(JooqCpFailoverRepository.ADMIN_SINGLE)))
            .thenReturn(new JooqCpFailoverRepository.Decision("NO_PRE_APPROVAL",null));
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
        assertEquals("NO_PRE_APPROVAL",refused.code());
    }
    @Test void concurrentRunRefusedBeforeJob() {
        ready("ACTIVE","STANDBY");
        when(store.requestBound(eq(CLUSTER),isNull(),any(),eq("actor-1"),eq(A),eq(true),eq("check_point"),eq(java.util.Set.of(A,B)),eq("request-a"),eq(1L),eq("nonce-a"),eq(true),eq(JooqCpFailoverRepository.ADMIN_SINGLE)))
            .thenReturn(new JooqCpFailoverRepository.Decision("RUN_ALREADY_ACTIVE",null));
        var refused=assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",true));
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
    @Test void replayViewerAdmissionCreatesOnlyReadinessJobWithActorAudit() {
        ready("ACTIVE", "STANDBY");
        when(rbac.evaluate(anyString(), any(), any())).thenAnswer(call -> {
            Optional<String> role = call.getArgument(1);
            return new RbacEvaluator.Decision(role.filter(
                com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER::equals).isPresent()
                ? AuthzOutcome.PERMITTED : AuthzOutcome.DENIED,
                Optional.of("test"), Optional.empty(), Optional.empty());
        });
        var writes = new java.util.ArrayList<String>();
        var dsl = org.jooq.impl.DSL.using(new org.jooq.tools.jdbc.MockConnection(query -> {
            String sql = query.sql();
            if (sql.startsWith("select run_id")) return new org.jooq.tools.jdbc.MockResult[]{
                new org.jooq.tools.jdbc.MockResult(0, org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES)
                    .newResult(org.jooq.impl.DSL.field("run_id", String.class)))};
            // No other step of the cluster (VSX chassis / VS) has an open failover run.
            if (sql.startsWith("select 1 from failover_run where cluster_ref")) return new org.jooq.tools.jdbc.MockResult[]{
                new org.jooq.tools.jdbc.MockResult(0, org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES)
                    .newResult(org.jooq.impl.DSL.field("one", Integer.class)))};
            // V170: no resumed return is in flight on this unit.
            if (sql.startsWith("select 1 from failover_resume")) return new org.jooq.tools.jdbc.MockResult[]{
                new org.jooq.tools.jdbc.MockResult(0, org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES)
                    .newResult(org.jooq.impl.DSL.field("one", Integer.class)))};
            // V178: no open config sync on this unit.
            if (sql.startsWith("select 1 from pan_config_sync")) return new org.jooq.tools.jdbc.MockResult[]{
                new org.jooq.tools.jdbc.MockResult(0, org.jooq.impl.DSL.using(org.jooq.SQLDialect.POSTGRES)
                    .newResult(org.jooq.impl.DSL.field("one", Integer.class)))};
            writes.add(sql);
            if (sql.startsWith("insert into failover_run")) {
                assertTrue(sql.contains("'READINESS'"));
                assertEquals("actor-1", query.bindings()[3]);
            }
            if (sql.startsWith("insert into jobs")) {
                assertEquals("cp_failover_readiness", query.bindings()[1]);
                assertEquals("actor-1", query.bindings()[4]);
                assertEquals("read", query.bindings()[6]);
            }
            return new org.jooq.tools.jdbc.MockResult[]{new org.jooq.tools.jdbc.MockResult(1)};
        }), org.jooq.SQLDialect.POSTGRES);
        var repository = new JooqCpFailoverRepository(new com.securityexpert.nexus.ui2.persistence.TransactionBoundary() {
            @Override public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                return work.apply(dsl);
            }
        }, false, WINDOWS);
        var admission = new CpFailoverService(devices, inventory, repository, rbac, trust,new FailoverMutationSwitch(false), WINDOWS);
        assertNotNull(admission.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "check_point"));
        assertEquals(1, writes.stream().filter(sql -> sql.startsWith("insert into jobs")).count());
        assertEquals(1, writes.stream().filter(sql -> sql.startsWith("insert into failover_run")).count());
        assertTrue(writes.contains("SET LOCAL app.actor_fingerprint = 'actor-1'"));
        assertTrue(writes.contains("SET LOCAL app.action_id = 'failover_readiness_request'"));
        assertFalse(admission.mayStart("actor-1"));
        assertFalse(admission.mayApprove("actor-1"));
        assertEquals("WRONG_ROLE", assertThrows(CpFailoverService.Refusal.class,
            () -> admission.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1")).code());
        verifyNoInteractions(store);
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
    @Test void unboundLegacyRequestIsRefusedBeforeAdmission() {
        ready("ACTIVE","STANDBY");
        assertEquals("REQUEST_BINDING_REQUIRED",assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1")).code());
        verifyNoInteractions(store);
    }
    @Test void warningConfirmationIsRequiredBeforeAdmission() {
        ready("ACTIVE","STANDBY");
        assertEquals("WARNING_CONFIRMATION_REQUIRED",assertThrows(CpFailoverService.Refusal.class,
            () -> service.request(CLUSTER_ID,CLUSTER_ID,null,"actor-1","check_point","request-a",1,"nonce-a",false)).code());
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
        when(devices.findSummary(A)).thenReturn(Optional.of(panA));
        for (String id:List.of(A,B)) when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(
            new EndpointRecord("endpoint-"+id,id,"pan_xml_api",A.equals(id)?"192.0.2.11":"192.0.2.12",Instant.EPOCH)));
        for (String id:List.of(A,B)) when(inventory.findLatestRun(id)).thenReturn(Optional.of(new InventoryRun(
            "inventory-"+id,id,"job-"+id,Instant.now(),1,
            List.of(new InventoryContext("physical",List.of(),List.of())),
            List.of(new InventoryHaFact("ha-"+id,"physical","ACTIVE",Optional.of("Active-Active"),
                InventoryHaFact.SOURCE_PAN_HIGH_AVAILABILITY_STATE)))));
        assertEquals("UNSUPPORTED_HA_MODE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.units(CLUSTER_ID,"actor-1","palo_alto")).code());
        assertEquals("UNSUPPORTED_HA_MODE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForMember(A,"actor-1","palo_alto")).code());
        assertEquals("UNSUPPORTED_HA_MODE",assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForRef(CLUSTER,"actor-1","palo_alto")).code());
        verifyNoInteractions(store);
    }
    @Test void summaryIncludesCpVsxAndPanWithWindowsAndLatestRuns() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.ROOT_AUTHORITY),Optional.empty(),Optional.empty()));
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
        // Plain cluster, the VSX chassis, its VS 13 and the PAN pair: four units.
        assertEquals(4, rows.size());
        String vsxId = UUID.nameUUIDFromBytes("CLS-TEST-VSX".getBytes(StandardCharsets.UTF_8)).toString();
        assertTrue(rows.stream().anyMatch(r -> r.unit().id().equals(vsxId) && r.unit().vsId() == null && r.unit().vsxChassis()));
        assertTrue(rows.stream().anyMatch(r -> r.unit().clusterId().equals(CLUSTER_ID)
            && !r.activeWindow() && r.lastRunAt() == null));
        assertTrue(rows.stream().anyMatch(r -> "13".equals(r.unit().vsId()) && r.activeWindow()
            && "VS-TEST-APP (VSID 13)".equals(r.unit().label()) && "PASS".equals(r.lastRunOutcome())));
        assertTrue(rows.stream().anyMatch(r -> "palo_alto".equals(r.vendor()) && !r.activeWindow()
            && "STOPPED".equals(r.lastRunState()) && runAt.equals(r.lastRunAt())));
        verify(rbac,org.mockito.Mockito.times(3)).evaluate(anyString(),any(),any());
        verify(store).readinessStatuses();
        verify(store).summaryMembers();
        verify(store).summaryStatuses();
        verify(devices).listAll();
        verifyNoInteractions(inventory, trust);
    }
    @Test void summaryKeepsBulkReadsAndAuthorizationConstantFor45Clusters() {
        when(rbac.evaluate(anyString(),any(),any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED,Optional.of(RbacEvaluator.ROOT_AUTHORITY),Optional.empty(),Optional.empty()));
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
        verify(rbac,org.mockito.Mockito.times(3)).evaluate(anyString(),any(),any());
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

    /** Fake directory harness for service-side LDAP refresh at immediate start, due scheduled start and sequence advance. */
    private static final class FakeDirectory {
        static final String PROFILE = "synthetic-ad-profile", GROUP = "CN=FailoverAdmins,DC=example,DC=invalid";
        static final String BINDING = "binding-ldap-op-admin";
        final com.securityexpert.nexus.ui2.platform.GroupReferenceCipher cipher;
        final java.util.Map<String, com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord> actors = new java.util.HashMap<>();
        final java.util.Map<String, java.util.Set<String>> members = new java.util.HashMap<>();
        boolean reachable = true;

        FakeDirectory() {
            byte[] key = new byte[32];
            new java.security.SecureRandom().nextBytes(key);
            cipher = com.securityexpert.nexus.ui2.platform.GroupReferenceCipher.fromBase64Key(
                java.util.Base64.getEncoder().encodeToString(key), "synthetic-key");
        }

        void enroll(String actor, String principal, boolean inGroup) {
            Instant now = Instant.now();
            actors.put(actor, new com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord(
                actor, java.util.Set.of(GROUP), now.minusSeconds(60), now.plusSeconds(240), PROFILE,
                cipher.encryptDirectory(principal, PROFILE, com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                cipher.keyId()));
            members.put(principal, inGroup ? java.util.Set.of(GROUP) : java.util.Set.of());
        }

        RbacEvaluator evaluator() {
            var bindingRow = new com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord(
                BINDING, com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN,
                cipher.encryptDirectory(GROUP, PROFILE, com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_GROUP),
                cipher.keyId(), "actor-setup", Instant.now(), Optional.empty(), Optional.empty(),
                com.securityexpert.nexus.ui2.platform.DirectoryBindingKind.DIRECTORY_GROUP, PROFILE);
            var actorRepo = mock(com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository.class);
            when(actorRepo.find(anyString())).thenAnswer(c -> Optional.ofNullable(actors.get(c.getArgument(0))));
            doAnswer(c -> {
                var r = (com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord) c.getArgument(0);
                actors.put(r.actorFingerprint(), r);
                return null;
            }).when(actorRepo).upsertDirectory(any());
            doAnswer(c -> {
                actors.remove(c.getArgument(0));
                return null;
            }).when(actorRepo).delete(anyString());
            var sessionRepo = mock(com.securityexpert.nexus.ui2.persistence.identity.SessionRepository.class);
            when(sessionRepo.findActiveByActor(anyString(), any())).thenReturn(Optional.empty());
            when(sessionRepo.findActiveByActor(anyString())).thenReturn(Optional.empty());
            var localRepo = mock(com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository.class);
            when(localRepo.findAll()).thenReturn(List.of());
            var bindingRepo = mock(com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository.class);
            when(bindingRepo.findActiveByToken(com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN))
                .thenReturn(List.of(bindingRow));
            when(bindingRepo.findActiveByToken(com.securityexpert.nexus.ui2.platform.RoleToken.SECURITY_ADMIN))
                .thenReturn(List.of());
            when(bindingRepo.findActiveByToken(com.securityexpert.nexus.ui2.platform.RoleToken.REPLAY_VIEWER))
                .thenReturn(List.of());
            when(bindingRepo.directoryMutation(any())).thenAnswer(c -> {
                @SuppressWarnings("unchecked")
                var work = (java.util.function.Function<com.securityexpert.nexus.ui2.persistence.identity.DirectoryMutationRepositories, Object>) c.getArgument(0);
                return work.apply(new com.securityexpert.nexus.ui2.persistence.identity.DirectoryMutationRepositories(
                    bindingRepo, actorRepo, sessionRepo, localRepo));
            });
            var port = new com.securityexpert.nexus.ui2.platform.LdapRevalidationPort() {
                @Override public boolean directoryPostureEnabled() { return true; }
                @Override public com.securityexpert.nexus.ui2.platform.Result<java.util.Set<String>> revalidate(String a, String b) {
                    return com.securityexpert.nexus.ui2.platform.Result.err("directory_identity_not_proven", "typed");
                }
                @Override public com.securityexpert.nexus.ui2.platform.Result<com.securityexpert.nexus.ui2.platform.DirectoryObservation> revalidatePrincipal(String principal) {
                    if (!reachable) return com.securityexpert.nexus.ui2.platform.Result.err("directory_unavailable", "connect_failed");
                    var groups = members.getOrDefault(principal, java.util.Set.of());
                    if (groups.isEmpty()) return com.securityexpert.nexus.ui2.platform.Result.err("access_group_lost", "not_in_group");
                    return com.securityexpert.nexus.ui2.platform.Result.ok(
                        new com.securityexpert.nexus.ui2.platform.DirectoryObservation(PROFILE, principal, groups, r -> { r.run(); return true; }));
                }
            };
            var refresh = new com.securityexpert.nexus.ui2.service.security.DirectoryAuthorizationRefresh(
                port, bindingRepo, cipher, actorRepo, sessionRepo);
            var eval = new RbacEvaluator(bindingRepo, actorRepo, cipher);
            eval.useDirectoryRefresh(refresh);
            return eval;
        }
    }

    @Test void directoryRefreshAtStartRefusesWhenRemovedOrUnreachableAndRunsWhenMember() {
        ready("ACTIVE", "STANDBY");
        var dir = new FakeDirectory();
        var directoryService = new CpFailoverService(devices, inventory, store, dir.evaluator(), trust,
            new FailoverMutationSwitch(true), WINDOWS);
        String reqId = UUID.randomUUID().toString();
        var approval = new JooqCpFailoverRepository.Approval(reqId, CLUSTER, null,
            Instant.now().minusSeconds(60), Instant.now().plusSeconds(3600), "Synthetic drill", "dir-approver", null);
        when(store.requestApproval(reqId)).thenReturn(Optional.of(new JooqCpFailoverRepository.RequestApproval(
            approval, 1L, CLUSTER_ID, "rev-1", JooqCpFailoverRepository.TWO_PERSON, 1, "dir-initiator", "nonce-a")));
        when(store.requestBound(eq(CLUSTER), isNull(), any(), eq("dir-initiator"), eq(A), eq(true), eq("check_point"),
            eq(java.util.Set.of(A, B)), eq(reqId), eq(1L), eq("nonce-a"), eq(true), eq(JooqCpFailoverRepository.TWO_PERSON)))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "run-ldap-ok"));

        // 1. Directory down at start -> DIRECTORY_UNREACHABLE_AT_START.
        dir.enroll("dir-initiator", "uid=init,dc=example", true);
        dir.enroll("dir-approver", "uid=appr,dc=example", true);
        dir.reachable = false;
        assertEquals(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.DIRECTORY_UNREACHABLE,
            assertThrows(CpFailoverService.Refusal.class, () -> directoryService.request(
                CLUSTER_ID, CLUSTER_ID, null, "dir-initiator", "check_point", reqId, 1, "nonce-a", true)).code());
        verify(store, never()).requestBound(any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(), anyLong(), any(), anyBoolean(), any());

        // 2. Directory up, initiator removed from AD group -> INITIATOR_NOT_AUTHORIZED_AT_START.
        dir.reachable = true;
        dir.enroll("dir-initiator", "uid=init,dc=example", false);
        dir.enroll("dir-approver", "uid=appr,dc=example", true);
        assertEquals(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.INITIATOR_NOT_AUTHORIZED,
            assertThrows(CpFailoverService.Refusal.class, () -> directoryService.request(
                CLUSTER_ID, CLUSTER_ID, null, "dir-initiator", "check_point", reqId, 1, "nonce-a", true)).code());

        // 3. Initiator in group, second approver removed from AD group -> APPROVER_NOT_AUTHORIZED_AT_START.
        dir.enroll("dir-initiator", "uid=init,dc=example", true);
        dir.enroll("dir-approver", "uid=appr,dc=example", false);
        assertEquals(com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.APPROVER_NOT_AUTHORIZED,
            assertThrows(CpFailoverService.Refusal.class, () -> directoryService.request(
                CLUSTER_ID, CLUSTER_ID, null, "dir-initiator", "check_point", reqId, 1, "nonce-a", true)).code());

        // 4. Both in AD group -> refreshed authority recorded, run starts.
        dir.enroll("dir-initiator", "uid=init,dc=example", true);
        dir.enroll("dir-approver", "uid=appr,dc=example", true);
        assertEquals("run-ldap-ok", directoryService.request(
            CLUSTER_ID, CLUSTER_ID, null, "dir-initiator", "check_point", reqId, 1, "nonce-a", true));
        var expectedGrant = new com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.Grant(
            "ui2_ldap", FakeDirectory.BINDING, com.securityexpert.nexus.ui2.platform.RoleToken.OPERATION_ADMIN);
        verify(store, atLeastOnce()).recordActorAuthority(
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.KIND_APPROVAL, reqId,
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.INITIATOR, "dir-initiator", expectedGrant);
        verify(store, atLeastOnce()).recordActorAuthority(
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.KIND_APPROVAL, reqId,
            com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.APPROVER, "dir-approver", expectedGrant);

        // 5. Scheduled start (startDue) and sequence advance (advanceSequences) refresh directory membership too.
        var dueRun = new JooqCpFailoverRepository.Run("run-due", CLUSTER, null, reqId, "dir-initiator",
            Instant.now(), null, "PLANNED", "PLANNED", null, null, null, "check_point", "FAILOVER");
        when(store.due()).thenReturn(List.of(dueRun));
        dir.reachable = false;
        directoryService.startDue();
        verify(store).stopPlanned("run-due", com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.DIRECTORY_UNREACHABLE);
        dir.reachable = true;
        dir.enroll("dir-approver", "uid=appr,dc=example", false);
        directoryService.startDue();
        verify(store).stopPlanned("run-due", com.securityexpert.nexus.ui2.persistence.FailoverActorAuthority.APPROVER_NOT_AUTHORIZED);
        dir.enroll("dir-approver", "uid=appr,dc=example", true);
        directoryService.startDue();
        verify(store).startDue("run-due", A);
    }
    public static void main(String[] args) {
        new CpFailoverServiceTest().wrongRoleRefusedBeforeAdmission();
        new CpFailoverServiceTest().noWindowRefusedBeforeJob();
        new CpFailoverServiceTest().concurrentRunRefusedBeforeJob();
        new CpFailoverServiceTest().unboundLegacyRequestIsRefusedBeforeAdmission();
        new CpFailoverServiceTest().warningConfirmationIsRequiredBeforeAdmission();
        new CpFailoverServiceTest().panActiveActiveRefusedWithoutVsUnits();
    }
}
