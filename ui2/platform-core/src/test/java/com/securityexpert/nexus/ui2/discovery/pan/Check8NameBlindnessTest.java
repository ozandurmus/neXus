package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §11 check 8 (AC-2) / ID-3 name-blindness. Resolving the same
 * constructed candidate set twice — once as given, once with every display
 * name (device and virtual-system) replaced by one constant — must produce
 * identical pairing outcomes (check 7) and identical structural host links
 * (check 12). Any difference proves a rule read a name.
 */
class Check8NameBlindnessTest {

    private static Map<String, CandidateRow> byId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.stableIdentifier().value().orElseThrow(), Function.identity()));
    }

    @Test
    void replacingEveryDisplayNameChangesNothingButTheName() {
        List<RawDeviceInput> original = Fixtures.check7Inputs();
        List<RawDeviceInput> renamed = Fixtures.withEveryDisplayNameReplaced(original, "CONSTANT-NAME");

        assertFalse(original.isEmpty());
        Map<String, CandidateRow> before = byId(CandidateRowAssembler.assemble(original));
        Map<String, CandidateRow> after = byId(CandidateRowAssembler.assemble(renamed));

        assertEquals(before.keySet(), after.keySet());
        for (String id : before.keySet()) {
            CandidateRow b = before.get(id);
            CandidateRow a = after.get(id);
            assertEquals(b.pairingOutcome(), a.pairingOutcome(), "pairing outcome changed for " + id);
            assertEquals(b.unreciprocatedInboundClaimantSerials(), a.unreciprocatedInboundClaimantSerials(),
                    "inbound claims changed for " + id);
            assertEquals(b.hostLink(), a.hostLink(), "host link changed for " + id);
            assertEquals("CONSTANT-NAME", a.displayName());
        }
    }

    @Test
    void replacingEveryDisplayNameChangesNothingAboutNestedVirtualSystemHostLinks() {
        List<RawDeviceInput> original = Fixtures.check12NestedVirtualSystems();
        List<RawDeviceInput> renamed = Fixtures.withEveryDisplayNameReplaced(original, "CONSTANT-NAME");

        Map<String, CandidateRow> before = byId(CandidateRowAssembler.assemble(original));
        Map<String, CandidateRow> after = byId(CandidateRowAssembler.assemble(renamed));

        assertEquals(before.keySet(), after.keySet());
        for (String id : before.keySet()) {
            assertEquals(before.get(id).hostLink(), after.get(id).hostLink(), "host link changed for " + id);
        }
    }
}
