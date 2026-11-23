package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceReadRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentityRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/** Pairs come from the inventory grouping; the pair counts as one only when the recorded identity is MATCH. */
class HaPolicyPairServiceTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-22T10:00:00Z"), ZoneOffset.UTC);

    static DeviceSummaryRecord device(String id, String vendor, String cluster) {
        return new DeviceSummaryRecord(id, "gateway", vendor, DeviceEnrollmentState.ENROLLED, Optional.of("FW-" + id.toUpperCase()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.ofNullable(cluster));
    }

    final LocalFirewallPolicyService snapshots = mock(LocalFirewallPolicyService.class);
    final DeviceReadRepository devices = mock(DeviceReadRepository.class);
    final HaPairIdentityRepository identities = mock(HaPairIdentityRepository.class);
    final HaPolicyPairService service = new HaPolicyPairService(snapshots, devices, identities, CLOCK);

    @Test void groupsExactlyTwoMembersOfOneVendorPerClusterAndReadsTheIdentityState() {
        when(devices.listAll()).thenReturn(List.of(
                device("tango-04", "cisco_asa", "cluster-asa"), device("tango-05", "cisco_asa", "cluster-asa"),
                device("tango-06", "fortinet", "cluster-fgt"), device("tango-07", "fortinet", "cluster-fgt"),
                device("tango-08", "cisco_asa", "cluster-three"), device("tango-09", "cisco_asa", "cluster-three"),
                device("tango-10", "cisco_asa", "cluster-three"),
                device("tango-11", "palo_alto", "cluster-pan"), device("tango-12", "palo_alto", "cluster-pan"),
                device("tango-13", "cisco_asa", "cluster-mixed"), device("tango-14", "fortinet", "cluster-mixed"),
                device("tango-15", "cisco_asa", null)));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        when(identities.find("cluster-fgt")).thenReturn(Optional.of(new HaPairIdentity("cluster-fgt", "fortinet", "S1", "S2", HaPairIdentity.State.MISMATCH)));
        var pairs = service.pairs();
        assertEquals(2, pairs.size());
        assertEquals("ASA", pairs.get(0).pair().vendor());
        assertTrue(pairs.get(0).pair().identityMatch());
        assertEquals(List.of("tango-04", "tango-05"), List.of(pairs.get(0).pair().memberA(), pairs.get(0).pair().memberB()));
        assertEquals(HaPolicyPairService.opaque("cluster-asa"), pairs.get(0).pair().pairRef());
        assertNotEquals("cluster-asa", pairs.get(0).pair().pairRef());
        assertEquals("FGT", pairs.get(1).pair().vendor());
        assertFalse(pairs.get(1).pair().identityMatch(), "MISMATCH is not MATCH");
    }

    @Test void identityOfAnotherVendorOrAbsentIsNotMatch() {
        when(devices.listAll()).thenReturn(List.of(device("tango-04", "cisco_asa", "cluster-asa"), device("tango-05", "cisco_asa", "cluster-asa")));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "fortinet", "S1", "S2", HaPairIdentity.State.MATCH)));
        assertFalse(service.pairs().get(0).pair().identityMatch());
        when(identities.find("cluster-asa")).thenReturn(Optional.empty());
        assertFalse(service.pairs().get(0).pair().identityMatch());
    }

    @Test void viewReadsOnlyTheNeededVendorAndReportsEvidenceAgesNeverAHash() {
        when(devices.listAll()).thenReturn(List.of(device("tango-04", "cisco_asa", "cluster-asa"), device("tango-05", "cisco_asa", "cluster-asa")));
        when(identities.find("cluster-asa")).thenReturn(Optional.of(new HaPairIdentity("cluster-asa", "cisco_asa", "S1", "S2", HaPairIdentity.State.MATCH)));
        var spec = new HaPolicyPairingTest.Spec("R1", "permit");
        when(snapshots.asaSnapshots()).thenReturn(List.of(
                HaPolicyPairingTest.asa("tango-04", "2026-11-22T09:00:00Z", spec), HaPolicyPairingTest.asa("tango-05", "2026-11-21T10:00:00Z", spec)));
        var body = service.view().body();
        verify(snapshots, never()).fgtSnapshots();
        assertEquals(1, body.get("pairCount"));
        assertEquals(Map.of("SAME", 1, "HA_POLICY_DIVERGENT", 0, "UNKNOWN", 0), body.get("contextRowsByOutcome"));
        @SuppressWarnings("unchecked")
        var row = ((List<Map<String, Object>>) body.get("contextRows")).get(0);
        assertEquals("SAME", row.get("haOutcome"));
        assertEquals(3600L, row.get("newerSnapshotAgeSeconds"));
        assertEquals(86400L, row.get("olderSnapshotAgeSeconds"));
        String text = body.toString();
        assertFalse(text.contains(HaPolicyPairing.canonicalHash(HaPolicyPairingTest.asa("tango-04", "2026-11-22T09:00:00Z", spec))));
        assertFalse(text.contains("192.0.2.10"));
    }

    @Test void noPairsReadsNoSnapshots() {
        when(devices.listAll()).thenReturn(List.of(device("tango-15", "cisco_asa", null)));
        var body = service.view().body();
        assertEquals(0, body.get("pairCount"));
        verifyNoInteractions(snapshots);
    }

    @Test void unreadableTimesGiveNoAgeNeverZero() {
        assertNull(HaPolicyPairService.ageSeconds(null, CLOCK.instant()));
        assertNull(HaPolicyPairService.ageSeconds("not-a-time", CLOCK.instant()));
    }
}
