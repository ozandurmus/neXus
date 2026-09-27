package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
    private final CpFailoverService service=new CpFailoverService(devices,inventory,store,rbac,trust,
        new com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer(new byte[32]));

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
        assertTrue(service.mayApprove("actor-1"));
        assertTrue(service.mayStart("actor-1"));
        assertEquals("CLUSTER_NOT_FOUND",assertThrows(CpFailoverService.Refusal.class,
            () -> service.unitsForRef("unknown","actor-1")).code());
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
    public static void main(String[] args) {
        new CpFailoverServiceTest().wrongRoleRefusedBeforeAdmission();
        new CpFailoverServiceTest().noWindowRefusedBeforeJob();
        new CpFailoverServiceTest().concurrentRunRefusedBeforeJob();
        new CpFailoverServiceTest().incorrectMemberStateRefusedBeforeAdmission();
        new CpFailoverServiceTest().untrustedHostRefusedBeforeAdmission();
    }
}
