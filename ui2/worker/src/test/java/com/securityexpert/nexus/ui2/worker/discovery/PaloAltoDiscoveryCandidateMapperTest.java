package com.securityexpert.nexus.ui2.worker.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.pan.CandidateRow;
import com.securityexpert.nexus.ui2.discovery.pan.HostLink;
import com.securityexpert.nexus.ui2.discovery.pan.PairingOutcome;
import com.securityexpert.nexus.ui2.discovery.pan.Serial;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryCandidateRecord;

/** Synthetic Palo Alto candidate rows only (FW-TANGO-04 style names, 192.0.2.x addresses). */
class PaloAltoDiscoveryCandidateMapperTest {

    private static CandidateRow device(String serial, String displayName, Optional<String> ipv4, Optional<String> ipv6,
            Optional<HostLink> hostLink, PairingOutcome pairing) {
        return new CandidateRow(
                Serial.ofOptional(Optional.ofNullable(serial)),
                displayName,
                Optional.of("SYN-PA-MARKER"),
                ipv4,
                ipv6,
                Serial.absent(),
                Optional.of("connected"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                hostLink,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                pairing,
                List.of());
    }

    private static CandidateRow device(String serial, String displayName) {
        return device(serial, displayName, Optional.of("192.0.2.50"), Optional.empty(),
                Optional.empty(), new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL));
    }

    @Test
    void completeSyntheticDeviceMapsToPaloAltoCandidateWithIdentityFields() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9",
                List.of(device("SYN-SERIAL-0001", "FW-TANGO-04")));
        assertEquals(1, out.size());
        DiscoveryCandidateRecord r = out.get(0);
        assertEquals("run-9", r.runId());
        assertEquals("palo_alto", r.vendor());
        assertEquals("PALO_ALTO_DEVICE", r.kind());
        assertEquals("SYN-SERIAL-0001", r.stableIdentifier());
        assertEquals("FW-TANGO-04", r.displayName());
        assertEquals(Optional.of("192.0.2.50"), r.ownAddress());
        assertEquals(Optional.of("connected"), r.connectionState());
        assertEquals(Optional.empty(), r.clusterReference());
        assertTrue(r.importable());
    }

    @Test
    void displayNameIsNeverUsedAsStableIdentifier() {
        DiscoveryCandidateRecord r = PaloAltoDiscoveryCandidateMapper.map("run-9",
                List.of(device("SYN-SERIAL-0002", "FW-JULIET-06"))).get(0);
        assertNotEquals("FW-JULIET-06", r.stableIdentifier());
    }

    @Test
    void blankIdentityGetsAPlaceholderNeverTheDisplayLabelOrAnotherDevicesSerial() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("", "FW-BRAVO-02"),
                device("SYN-SERIAL-0003", "FW-ROMEO-07")));
        DiscoveryCandidateRecord blank = out.get(0);
        assertTrue(blank.stableIdentifier().startsWith("unidentified-"));
        assertNotEquals("FW-BRAVO-02", blank.stableIdentifier());
        assertNotEquals("SYN-SERIAL-0003", blank.stableIdentifier());
    }

    @Test
    void ipv6IsUsedOnlyAsFallbackWhenNoIpv4IsPresent() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-SERIAL-0004", "FW-TANGO-05", Optional.empty(), Optional.of("2001:db8::4"),
                        Optional.empty(), new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL)),
                device("SYN-SERIAL-0005", "FW-TANGO-06", Optional.of("192.0.2.51"), Optional.of("2001:db8::5"),
                        Optional.empty(), new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL))));
        assertEquals(Optional.of("2001:db8::4"), out.get(0).ownAddress());
        assertEquals(Optional.of("192.0.2.51"), out.get(1).ownAddress());
    }

    @Test
    void absentAddressStaysEmptyNeverInvented() {
        DiscoveryCandidateRecord r = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-SERIAL-0006", "FW-TANGO-07", Optional.empty(), Optional.empty(),
                        Optional.empty(), new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL)))).get(0);
        assertEquals(Optional.empty(), r.ownAddress());
    }

    @Test
    void duplicateSerialIsDisambiguatedRatherThanCollapsed() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-SERIAL-0007", "FW-TANGO-08"),
                device("SYN-SERIAL-0007", "FW-TANGO-09")));
        assertEquals("SYN-SERIAL-0007", out.get(0).stableIdentifier());
        assertEquals("SYN-SERIAL-0007-1", out.get(1).stableIdentifier());
    }

    @Test
    void virtualSystemLinkedToHostInRunResolvesParent() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-HOST-0001", "FW-TANGO-10"),
                device("SYN-VS-0001", "VSYS-SYN-1", Optional.empty(), Optional.empty(),
                        Optional.of(new HostLink(Serial.of("SYN-HOST-0001"))),
                        new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL))));
        assertEquals("PALO_ALTO_VIRTUAL_SYSTEM", out.get(1).kind());
        assertEquals(out.get(0).candidateId(), out.get(1).parentCandidateId().orElseThrow());
        assertTrue(out.get(1).importable());
    }

    @Test
    void virtualSystemWhoseHostIsNotInRunHasNoGuessedParentAndIsNotImportable() {
        DiscoveryCandidateRecord r = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-VS-0002", "VSYS-SYN-2", Optional.empty(), Optional.empty(),
                        Optional.of(new HostLink(Serial.of("SYN-HOST-NOT-IN-RUN"))),
                        new PairingOutcome.NotEvaluable(PairingOutcome.Reason.NO_PEER_SERIAL)))).get(0);
        assertEquals(Optional.empty(), r.parentCandidateId());
        assertFalse(r.importable());
    }

    @Test
    void reciprocalHaPairSharesOneClusterReferenceAndMapperTrustsUpstreamPairing() {
        List<DiscoveryCandidateRecord> out = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-HA-B", "FW-TANGO-11", Optional.empty(), Optional.empty(), Optional.empty(),
                        new PairingOutcome.Paired("SYN-HA-A")),
                device("SYN-HA-A", "FW-TANGO-12", Optional.empty(), Optional.empty(), Optional.empty(),
                        new PairingOutcome.Paired("SYN-HA-B")),
                device("SYN-HA-C", "FW-TANGO-13", Optional.empty(), Optional.empty(), Optional.empty(),
                        new PairingOutcome.Paired("SYN-HA-D"))));
        assertEquals(Optional.of("SYN-HA-A|SYN-HA-B"), out.get(0).clusterReference());
        assertEquals(out.get(0).clusterReference(), out.get(1).clusterReference());
        assertEquals(Optional.of("SYN-HA-C|SYN-HA-D"), out.get(2).clusterReference(),
                "the mapper trusts an upstream Paired outcome; reciprocity is decided before mapping");
    }

    @Test
    void notEvaluablePairingNeverFormsAGrouping() {
        DiscoveryCandidateRecord r = PaloAltoDiscoveryCandidateMapper.map("run-9", List.of(
                device("SYN-HA-E", "FW-TANGO-14", Optional.empty(), Optional.empty(), Optional.empty(),
                        new PairingOutcome.NotEvaluable(PairingOutcome.Reason.ONE_SIDED_CLAIM)))).get(0);
        assertEquals(Optional.empty(), r.clusterReference());
    }

    @Test
    @Disabled("OPEN ISSUE (identity law): a device with a blank stable identifier is mapped with importable=true, "
            + "only a placeholder id is substituted. Needs a PO decision; production code not changed in this test-only lane.")
    void deviceWithoutStableIdentityIsNotImportable() {
        DiscoveryCandidateRecord r = PaloAltoDiscoveryCandidateMapper.map("run-9",
                List.of(device("", "FW-BRAVO-03"))).get(0);
        assertFalse(r.importable());
    }
}
