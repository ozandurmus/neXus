package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Contract §11 check 10 (AC-4) / ID-4 address non-join. Two distinct
 * candidates sharing an identical own-address value (IPv4 and IPv6 both)
 * form no relationship from that fact alone — no pairing, and (structurally,
 * since neither is a virtual-system entry) no host link either.
 */
class Check10AddressNonJoinTest {

    @Test
    void identicalAddressesFormNoPairing() {
        List<RawDeviceInput> inputs = Fixtures.check10SharedAddressInputs();
        assertEquals(inputs.get(0).ownIpv4Address(), inputs.get(1).ownIpv4Address());
        assertEquals(inputs.get(0).ownIpv6Address(), inputs.get(1).ownIpv6Address());

        List<CandidateRow> rows = CandidateRowAssembler.assemble(inputs);

        for (CandidateRow row : rows) {
            PairingOutcome.NotEvaluable outcome = assertInstanceOf(PairingOutcome.NotEvaluable.class, row.pairingOutcome());
            assertEquals(PairingOutcome.Reason.NO_PEER_SERIAL, outcome.reason());
        }
    }

    @Test
    void identicalAddressesFormNoHostLink() {
        List<CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.check10SharedAddressInputs());
        for (CandidateRow row : rows) {
            assertEquals(java.util.Optional.empty(), row.hostLink());
        }
    }
}
