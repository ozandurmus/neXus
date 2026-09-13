package com.securityexpert.nexus.ui2.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Contract §11 check 12 (AC-5) / VS-3 structural resolution. A device entry
 * carrying nested virtual-system entries: each virtual-system candidate's
 * host link is exactly its parent entry, established with zero simulated
 * calls beyond the one enumeration — {@link CandidateRowAssembler} performs
 * no search here at all (unlike cp's address-searched host resolution), so
 * this is provable by construction, not merely by assertion.
 */
class Check12StructuralHostLinkTest {

    private static Map<String, CandidateRow> byId(List<CandidateRow> rows) {
        return rows.stream().collect(Collectors.toMap(r -> r.stableIdentifier().value().orElseThrow(), Function.identity()));
    }

    @Test
    void everyVirtualSystemRowsHostLinkIsExactlyItsParent() {
        List<RawDeviceInput> inputs = Fixtures.check12NestedVirtualSystems();
        List<CandidateRow> rows = CandidateRowAssembler.assemble(inputs);

        // VS-4: one row per input entry, plus one per nested virtual system.
        assertEquals(3, rows.size());

        Map<String, CandidateRow> byId = byId(rows);
        CandidateRow host = byId.get("fixture-host-1");
        assertTrue(host.hostLink().isEmpty());

        CandidateRow vs1 = byId.get("fixture-vs-1");
        assertEquals(java.util.Optional.of(new HostLink(host.stableIdentifier())), vs1.hostLink());
        assertEquals(java.util.Optional.of("fixture-policy-a-1"), vs1.sharedPolicyElementOne());

        CandidateRow vs2 = byId.get("fixture-vs-2");
        assertEquals(java.util.Optional.of(new HostLink(host.stableIdentifier())), vs2.hostLink());
        assertEquals(java.util.Optional.of("fixture-policy-b-2"), vs2.sharedPolicyElementTwo());
        assertEquals(java.util.Optional.of("fixture-policy-c-2"), vs2.sharedPolicyElementThree());
    }
}
