package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class HaPairIdentityTest {

    private static final Optional<HaPairIdentity> NONE = Optional.empty();
    private static final Optional<HaPairIdentity> RECORDED = Optional.of(
            new HaPairIdentity("asa-failover|0001", "cisco_asa", "SYNTHA0001", "SYNTHB0002", HaPairIdentity.State.MATCH));

    @Test
    void firstCompleteObservationRecordsThePairInOneOrder() {
        var d = HaPairIdentity.decide(NONE, Optional.of("SYNTHB0002"), Optional.of("SYNTHA0001"));
        assertEquals(HaPairIdentity.State.MATCH, d.state());
        assertArrayEquals(new String[] {"SYNTHA0001", "SYNTHB0002"}, d.toRecord().orElseThrow());
    }

    @Test
    void addressSlotsSwappedAfterAFailoverStillMatch() {
        // An ASA's two address slots follow the active/standby role: after a switch each address shows the other unit.
        assertEquals(HaPairIdentity.State.MATCH,
                HaPairIdentity.decide(RECORDED, Optional.of("SYNTHB0002"), Optional.of("SYNTHA0001")).state());
        assertEquals(HaPairIdentity.State.MATCH,
                HaPairIdentity.decide(RECORDED, Optional.of("SYNTHA0001"), Optional.of("SYNTHB0002")).state());
    }

    @Test
    void anUnknownUnitIsAMismatchAndIsNeverRecorded() {
        var d = HaPairIdentity.decide(RECORDED, Optional.of("SYNTHA0001"), Optional.of("SYNTHC0003"));
        assertEquals(HaPairIdentity.State.MISMATCH, d.state());
        assertTrue(d.toRecord().isEmpty());
    }

    @Test
    void identifiersAreComparedExactlyNeverNormalised() {
        assertEquals(HaPairIdentity.State.MISMATCH,
                HaPairIdentity.decide(RECORDED, Optional.of("syntha0001"), Optional.of("SYNTHB0002")).state());
        assertEquals(HaPairIdentity.State.MISMATCH,
                HaPairIdentity.decide(RECORDED, Optional.of("SYNTHA001"), Optional.of("SYNTHB0002")).state());
    }

    @Test
    void aMissingSerialIsMissingAndRecordsNothing() {
        assertEquals(HaPairIdentity.State.MISSING, HaPairIdentity.decide(NONE, Optional.of("SYNTHA0001"), Optional.empty()).state());
        assertEquals(HaPairIdentity.State.MISSING, HaPairIdentity.decide(RECORDED, Optional.of(" "), Optional.of("SYNTHB0002")).state());
        assertTrue(HaPairIdentity.decide(NONE, Optional.empty(), Optional.empty()).toRecord().isEmpty());
    }

    @Test
    void theSameSerialOnBothAddressesIsAmbiguousAndRecordsNothing() {
        var d = HaPairIdentity.decide(NONE, Optional.of("SYNTHA0001"), Optional.of("SYNTHA0001"));
        assertEquals(HaPairIdentity.State.AMBIGUOUS, d.state());
        assertTrue(d.toRecord().isEmpty());
    }

    @Test
    void theRecordKeepsOneRepresentation() {
        assertThrows(IllegalArgumentException.class,
                () -> new HaPairIdentity("r", "cisco_asa", "SYNTHB0002", "SYNTHA0001", HaPairIdentity.State.MATCH));
    }
}
