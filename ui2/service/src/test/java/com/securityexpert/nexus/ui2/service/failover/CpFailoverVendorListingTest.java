package com.securityexpert.nexus.ui2.service.failover;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.securityexpert.nexus.ui2.jobs.failover.AsaFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.jobs.failover.FailoverMutationSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** Cisco ASA pairs are listed with readiness unavailable; nothing may write or submit for them. */
class CpFailoverVendorListingTest {
    private static final String CLUSTER = "CLS-TEST-09", A = "FW-TEST-09", B = "FW-TEST-10";
    private static final String CLUSTER_ID = UUID.nameUUIDFromBytes(CLUSTER.getBytes(StandardCharsets.UTF_8)).toString();
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class,
        withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust = mock(ManagementEndpointSshTrustRepository.class);
    private final CpFailoverService service = new CpFailoverService(devices, inventory, store, rbac, trust,
        new FailoverMutationSwitch(true), new JobWindowPolicy(
            java.time.Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60));

    /** The same service with the Cisco ASA readiness switch on (UI2_ASA_FAILOVER_READINESS=on). */
    private final CpFailoverService asaOn = new CpFailoverService(devices, inventory, store, rbac, trust,
        new FailoverMutationSwitch(true), new JobWindowPolicy(
            java.time.Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60),
        new AsaFailoverReadinessSwitch(true));

    private static DeviceSummaryRecord member(String id, String vendor) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(CLUSTER));
    }
    private void pair(String vendor, String transport) {
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED, Optional.of(RbacEvaluator.ROOT_AUTHORITY), Optional.empty(), Optional.empty()));
        when(devices.listAll()).thenReturn(List.of(member(A, vendor), member(B, vendor)));
        when(devices.findMembersByClusterRef(CLUSTER)).thenReturn(List.of(member(A, vendor), member(B, vendor)));
        for (String id : List.of(A, B)) {
            when(devices.find(id)).thenReturn(Optional.of(new DeviceRecord(id, "gateway", vendor, "manual",
                Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
            when(devices.findEndpointByDeviceId(id)).thenReturn(Optional.of(new EndpointRecord("endpoint-" + id, id,
                transport, "192.0.2.21", Instant.EPOCH)));
        }
        doReturn(true).when(inventory).hasRun(anyString());
        when(store.summaryMembers()).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryMember(A, true, transport, true, "physical", false),
            new JooqCpFailoverRepository.SummaryMember(B, true, transport, true, "physical", false)));
        when(store.summaryStatuses()).thenReturn(List.of());
        when(store.readinessStatuses()).thenReturn(List.of());
    }
    private static Instant now() { return Instant.now(); }
    private void refused(org.junit.jupiter.api.function.Executable call) {
        var r = assertThrows(CpFailoverService.Refusal.class, call);
        assertEquals("FAILOVER_VENDOR_NOT_AVAILABLE", r.code());
    }

    @Test void asaPairIsOneUnitWithReadinessUnavailable() {
        pair("cisco_asa", "ssh_exec");
        var rows = service.summary("actor-1");
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals("cisco_asa", row.vendor());
        assertFalse(row.readinessAvailable());
        assertFalse(row.canRunReadiness());
        assertNull(row.readiness());
        assertNull(row.unit().vsId());
        assertEquals(CLUSTER_ID, row.unit().id());
        assertEquals(List.of(), service.readinessTargets());
        assertEquals(1, service.unitsForRef(CLUSTER, "actor-1", "cisco_asa").size());
    }
    @Test void asaWithTheWrongTransportOrAnUnreadableMemberIsNotListed() {
        pair("cisco_asa", "pan_xml_api");
        assertEquals(List.of(), service.summary("actor-1"));
        pair("cisco_asa", "ssh_exec");
        when(store.summaryMembers()).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryMember(A, true, "ssh_exec", true, "physical", false),
            new JooqCpFailoverRepository.SummaryMember(B, false, "ssh_exec", true, "physical", false)));
        assertEquals(List.of(), service.summary("actor-1"));
    }
    @Test void checkPointAndPaloAltoSummariesKeepReadinessAvailable() {
        pair("check_point", "ssh_exec");
        var rows = service.summary("actor-1");
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).readinessAvailable());
        assertTrue(rows.get(0).canRunReadiness());
        assertEquals(1, service.readinessTargets().size());
    }
    @Test void everyWriteOrSubmitEntryPointRefusesBeforeAnyStoreCall() {
        pair("cisco_asa", "ssh_exec");
        Instant from = now(), until = from.plusSeconds(600);
        refused(() -> service.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        refused(() -> service.requestScheduledReadiness(
            new CpFailoverService.ReadinessTarget(CLUSTER_ID, CLUSTER_ID, "cisco_asa")));
        refused(() -> service.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "cisco_asa", "r", 1, "n", true));
        refused(() -> service.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "cisco_asa"));
        refused(() -> service.approve(CLUSTER_ID, CLUSTER_ID, from, until, "x", "actor-1", "cisco_asa"));
        refused(() -> service.createApprovalRequest(UUID.randomUUID().toString(), CLUSTER_ID, CLUSTER_ID, from, until,
            "x", "actor-1", "cisco_asa"));
        refused(() -> service.secondApproval("r", 1, CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        refused(() -> service.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
        verifyNoInteractions(store);
    }
    @Test void approvalsAndRunsAreEmptyWithoutStoreCalls() {
        pair("cisco_asa", "ssh_exec");
        assertEquals(List.of(), service.approvals(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        assertEquals(List.of(), service.runs(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        verifyNoInteractions(store);
    }

    // ---- UI2_ASA_FAILOVER_READINESS=on: readiness only ----

    @Test void switchOnOffersAsaReadinessAndNothingElse() {
        pair("cisco_asa", "ssh_exec");
        var rows = asaOn.summary("actor-1");
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals("cisco_asa", row.vendor());
        assertTrue(row.readinessAvailable());
        assertTrue(row.canRunReadiness());
        assertNull(row.unit().vsId());
        assertEquals(CLUSTER_ID, row.unit().id());
        assertEquals(List.of(new CpFailoverService.ReadinessTarget(CLUSTER_ID, CLUSTER_ID, "cisco_asa")), asaOn.readinessTargets());
        assertFalse(FailoverVendor.CISCO_ASA.executionAvailable(), "the vendor descriptor stays immutable: no execution");
        assertFalse(FailoverVendor.CISCO_ASA.readinessAvailable());
    }
    @Test void switchOnAdmitsAnAsaReadinessRunAndRequiresTrustedHostKeys() {
        pair("cisco_asa", "ssh_exec");
        when(trust.findActiveAlgorithms(anyString(), eq(22))).thenReturn(List.of());
        var untrusted = assertThrows(CpFailoverService.Refusal.class,
            () -> asaOn.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        assertEquals("TRUSTED_HOST_KEY_REQUIRED", untrusted.code());
        verify(store, never()).requestReadiness(anyString(), any(), anyString(), anyString(), anyString());
        when(trust.findActiveAlgorithms(anyString(), eq(22))).thenReturn(List.of("ssh-ed25519"));
        when(store.requestReadiness(eq(CLUSTER), isNull(), eq("actor-1"), anyString(), eq("cisco_asa")))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "run-1"));
        assertEquals("run-1", asaOn.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        when(store.requestReadiness(eq(CLUSTER), isNull(), eq("system:failover-readiness-scheduler"), anyString(), eq("cisco_asa")))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "run-2"));
        assertEquals("run-2", asaOn.requestScheduledReadiness(new CpFailoverService.ReadinessTarget(CLUSTER_ID, CLUSTER_ID, "cisco_asa")));
        // FortiGate keeps refusing readiness: only Cisco ASA has a switch.
        refused(() -> asaOn.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
    }
    @Test void switchOnStillRefusesEveryExecutionApprovalAndResumeEntryPoint() {
        pair("cisco_asa", "ssh_exec");
        Instant from = now(), until = from.plusSeconds(600);
        clearInvocations(store);
        refused(() -> asaOn.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "cisco_asa", "r", 1, "n", true));
        refused(() -> asaOn.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "cisco_asa"));
        refused(() -> asaOn.approve(CLUSTER_ID, CLUSTER_ID, from, until, "x", "actor-1", "cisco_asa"));
        refused(() -> asaOn.createApprovalRequest(UUID.randomUUID().toString(), CLUSTER_ID, CLUSTER_ID, from, until,
            "x", "actor-1", "cisco_asa"));
        refused(() -> asaOn.secondApproval("r", 1, CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        assertEquals(List.of(), asaOn.approvals(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        verifyNoInteractions(store);
    }
    @Test void switchOnStartDueNeverStartsAnAsaRun() {
        pair("cisco_asa", "ssh_exec");
        var planned = new JooqCpFailoverRepository.Run("run-9", CLUSTER, null, "approval-9", "actor-1", Instant.now(), null,
            "PLANNED", "PLANNED", null, null, null, "cisco_asa", "FAILOVER");
        when(store.due()).thenReturn(List.of(planned));
        asaOn.startDue();
        verify(store, never()).startDue(anyString(), anyString());
    }
    @Test void switchOffIsPhaseZeroExactly() {
        pair("cisco_asa", "ssh_exec");
        var row = service.summary("actor-1").get(0);
        assertFalse(row.readinessAvailable());
        assertFalse(row.canRunReadiness());
        assertEquals(List.of(), service.readinessTargets());
        assertEquals(List.of(), service.runs(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        refused(() -> service.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "cisco_asa"));
        assertFalse(AsaFailoverReadinessSwitch.fromValue(null).enabled());
    }
}
