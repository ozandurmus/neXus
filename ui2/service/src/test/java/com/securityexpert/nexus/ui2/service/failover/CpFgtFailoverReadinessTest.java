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
import com.securityexpert.nexus.ui2.jobs.failover.FgtFailoverReadinessSwitch;
import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;
import com.securityexpert.nexus.ui2.persistence.device.*;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/** FortiGate: one enrolled cluster address is a one-device readiness unit, only while UI2_FGT_FAILOVER_READINESS is on; never execution. */
class CpFgtFailoverReadinessTest {
    private static final String DEVICE = "DEV-SYNTH-07", REF = "fgt-single|" + DEVICE;
    private static final String CLUSTER_ID = UUID.nameUUIDFromBytes(REF.getBytes(StandardCharsets.UTF_8)).toString();
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final DeviceInventoryRepository inventory = mock(DeviceInventoryRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
    private final JooqCpFailoverRepository store = mock(JooqCpFailoverRepository.class);
    private final RbacEvaluator rbac = mock(RbacEvaluator.class);
    private final ManagementEndpointSshTrustRepository trust = mock(ManagementEndpointSshTrustRepository.class);
    private static final JobWindowPolicy WINDOW = new JobWindowPolicy(
        java.time.Clock.fixed(Instant.parse("2026-10-08T09:10:00Z"), JobWindowPolicy.ZONE_ID), 60);
    private final CpFailoverService off = new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true),
        WINDOW, AsaFailoverReadinessSwitch.OFF, FgtFailoverReadinessSwitch.OFF);
    private final CpFailoverService on = new CpFailoverService(devices, inventory, store, rbac, trust, new FailoverMutationSwitch(true),
        WINDOW, AsaFailoverReadinessSwitch.OFF, new FgtFailoverReadinessSwitch(true));

    private static DeviceSummaryRecord enrolled(boolean peerUnconfirmed) {
        return new DeviceSummaryRecord(DEVICE, "gateway", "fortinet", DeviceEnrollmentState.ENROLLED, Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of("primary"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.empty(), Optional.empty(), false, peerUnconfirmed);
    }
    private void single(boolean peerUnconfirmed) {
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED, Optional.of(RbacEvaluator.ROOT_AUTHORITY), Optional.empty(), Optional.empty()));
        when(devices.listAll()).thenReturn(List.of(enrolled(peerUnconfirmed)));
        when(devices.findSummary(DEVICE)).thenReturn(Optional.of(enrolled(peerUnconfirmed)));
        when(devices.find(DEVICE)).thenReturn(Optional.of(new DeviceRecord(DEVICE, "gateway", "fortinet", "manual",
            Instant.EPOCH, false, DeviceEnrollmentState.ENROLLED, false, "credential-ref")));
        when(devices.findEndpointByDeviceId(DEVICE)).thenReturn(Optional.of(new EndpointRecord("endpoint-1", DEVICE, "ssh_exec", "192.0.2.41", Instant.EPOCH)));
        doReturn(true).when(inventory).hasRun(anyString());
        when(store.summaryMembers()).thenReturn(List.of(new JooqCpFailoverRepository.SummaryMember(DEVICE, true, "ssh_exec", true, "physical", false)));
        when(store.summaryStatuses()).thenReturn(List.of());
        when(store.readinessStatuses()).thenReturn(List.of());
    }
    private void refused(org.junit.jupiter.api.function.Executable call) {
        assertEquals("FAILOVER_VENDOR_NOT_AVAILABLE", assertThrows(CpFailoverService.Refusal.class, call).code());
    }

    @Test void switchOffListsNothingAndRefusesReadiness() {
        single(true);
        assertEquals(List.of(), off.summary("actor-1"));
        assertEquals(List.of(), off.readinessTargets());
        refused(() -> off.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
        assertFalse(FgtFailoverReadinessSwitch.fromValue(null).enabled());
    }
    @Test void switchOnOffersOneUnitForTheSingleEnrolledAddressAndNothingForOtherDevices() {
        single(true);
        var rows = on.summary("actor-1");
        assertEquals(1, rows.size());
        var row = rows.get(0);
        assertEquals("fortinet", row.vendor());
        assertTrue(row.readinessAvailable());
        assertTrue(row.canRunReadiness());
        assertEquals(CLUSTER_ID, row.unit().id());
        assertEquals(1, row.unit().members().size());
        assertEquals(REF, row.unit().members().get(0).clusterMemberRef().orElseThrow());
        assertEquals(List.of(new CpFailoverService.ReadinessTarget(CLUSTER_ID, CLUSTER_ID, "fortinet")), on.readinessTargets());
        assertFalse(FailoverVendor.FORTINET.executionAvailable());
        assertFalse(FailoverVendor.FORTINET.readinessAvailable());
        // A standalone FortiGate (no reported FGCP pair) is not a unit.
        single(false);
        assertEquals(List.of(), on.summary("actor-1"));
    }
    @Test void switchOnAdmitsAReadinessRunWithTrustedHostKeysOnTheOverlayReference() {
        single(true);
        when(trust.findActiveAlgorithms(anyString(), eq(22))).thenReturn(List.of());
        assertEquals("TRUSTED_HOST_KEY_REQUIRED", assertThrows(CpFailoverService.Refusal.class,
            () -> on.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet")).code());
        verify(store, never()).requestReadiness(anyString(), any(), anyString(), anyString(), anyString());
        when(trust.findActiveAlgorithms(anyString(), eq(22))).thenReturn(List.of("ssh-ed25519"));
        when(store.requestReadiness(eq(REF), isNull(), eq("actor-1"), eq(DEVICE), eq("fortinet")))
            .thenReturn(new JooqCpFailoverRepository.Decision("ADMITTED", "run-1"));
        assertEquals("run-1", on.requestReadiness(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
        assertEquals(1, on.unitsForMember(DEVICE, "actor-1", "fortinet").size());
    }
    @Test void switchOnStillRefusesEveryExecutionApprovalAndResumeEntryPoint() {
        single(true);
        Instant from = Instant.now(), until = from.plusSeconds(600);
        clearInvocations(store);
        refused(() -> on.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "fortinet", "r", 1, "n", true));
        refused(() -> on.request(CLUSTER_ID, CLUSTER_ID, null, "actor-1", "fortinet"));
        refused(() -> on.approve(CLUSTER_ID, CLUSTER_ID, from, until, "x", "actor-1", "fortinet"));
        refused(() -> on.createApprovalRequest(UUID.randomUUID().toString(), CLUSTER_ID, CLUSTER_ID, from, until, "x", "actor-1", "fortinet"));
        refused(() -> on.secondApproval("r", 1, CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
        assertEquals(List.of(), on.approvals(CLUSTER_ID, CLUSTER_ID, "actor-1", "fortinet"));
        verifyNoInteractions(store);
    }
    @Test void switchOnStartDueNeverStartsAFortigateRun() {
        single(true);
        var planned = new JooqCpFailoverRepository.Run("run-9", REF, null, "approval-9", "actor-1", Instant.now(), null,
            "PLANNED", "PLANNED", null, null, null, "fortinet", "FAILOVER");
        when(store.due()).thenReturn(List.of(planned));
        on.startDue();
        verify(store, never()).startDue(anyString(), anyString());
    }
    @Test void switchOnAlsoListsATwoMemberEnrolledFortigatePairWithReadinessAvailable() {
        String cluster = "CLS-ROMEO-04", a = "DEV-SYNTH-08", b = "DEV-SYNTH-09";
        var first = new DeviceSummaryRecord(a, "gateway", "fortinet", DeviceEnrollmentState.ENROLLED, Optional.of("FW-ROMEO-04-M1"),
            Optional.empty(), Optional.empty(), Optional.of("primary"), Optional.of(cluster));
        var second = new DeviceSummaryRecord(b, "gateway", "fortinet", DeviceEnrollmentState.ENROLLED, Optional.of("FW-ROMEO-04-M2"),
            Optional.empty(), Optional.empty(), Optional.of("secondary"), Optional.of(cluster));
        when(rbac.evaluate(anyString(), any(), any())).thenReturn(new RbacEvaluator.Decision(
            AuthzOutcome.PERMITTED, Optional.of(RbacEvaluator.ROOT_AUTHORITY), Optional.empty(), Optional.empty()));
        when(devices.listAll()).thenReturn(List.of(first, second));
        when(store.summaryMembers()).thenReturn(List.of(
            new JooqCpFailoverRepository.SummaryMember(a, true, "ssh_exec", true, "physical", false),
            new JooqCpFailoverRepository.SummaryMember(b, true, "ssh_exec", true, "physical", false)));
        when(store.summaryStatuses()).thenReturn(List.of());
        when(store.readinessStatuses()).thenReturn(List.of());
        var rows = on.summary("actor-1");
        assertEquals(1, rows.size());
        assertEquals("fortinet", rows.get(0).vendor());
        assertTrue(rows.get(0).readinessAvailable());
        assertTrue(rows.get(0).canRunReadiness());
        assertEquals(2, rows.get(0).unit().members().size());
        assertFalse(off.summary("actor-1").get(0).readinessAvailable());
    }
}
