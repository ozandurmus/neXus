package com.securityexpert.nexus.ui2.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Contract §5.2: HL-1 fail-closed (AC-9), HL-2, HL-3, CR-3a. */
class HostLinkResolverTest {

    private Map<String, CandidateRow> rowsByLocalId() {
        List<CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.allInputs());
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> r.key().stableIdentifier().value(), r -> r));
    }

    @Test
    void exactlyOneMatchLinksByStableIdentifier() {
        CandidateRow vs = rowsByLocalId().get("vs-1");
        assertEquals(HostResolution.VIRTUAL_SYSTEM_HOSTED, vs.hostResolution());
        HostLink.Linked linked = assertInstanceOf(HostLink.Linked.class, vs.hostLink().orElseThrow());
        assertEquals("host-1", linked.hostStableIdentifier().value());
    }

    /** AC-9: zero matches -> MISSING, no host identifier recorded. */
    @Test
    void zeroMatchesIsMissing() {
        CandidateRow vs = rowsByLocalId().get("vs-missing-host");
        assertInstanceOf(HostLink.Missing.class, vs.hostLink().orElseThrow());
    }

    /** AC-9: more than one match -> AMBIGUOUS, no host identifier recorded, no preference. */
    @Test
    void multipleMatchesIsAmbiguous() {
        CandidateRow vs = rowsByLocalId().get("vs-ambiguous-host");
        assertInstanceOf(HostLink.Ambiguous.class, vs.hostLink().orElseThrow());
    }

    @Test
    void hostLinkDoesNotApplyOutsideVirtualSystemHosted() {
        CandidateRow physicalDevice = rowsByLocalId().get("gw-1");
        assertEquals(HostResolution.PHYSICAL_DEVICE, physicalDevice.hostResolution());
        assertTrue(physicalDevice.hostLink().isEmpty());

        CandidateRow notApplicable = rowsByLocalId().get("interop-1");
        assertEquals(HostResolution.NOT_APPLICABLE, notApplicable.hostResolution());
        assertTrue(notApplicable.hostLink().isEmpty());
    }

    /** HL-1: same owning domain only — a matching address in a different domain is not a link. */
    @Test
    void hostSearchNeverCrossesDomains() {
        List<CandidateRow> rows = CandidateRowAssembler.assemble(Fixtures.allInputs());
        CandidateRow vsInDomainA = rows.stream()
                .filter(r -> r.key().stableIdentifier().value().equals("vs-1"))
                .findFirst().orElseThrow();
        // domainBInputs() also has an own address of 198.51.100.3, but in DOMAIN_B.
        HostLink.Linked linked = assertInstanceOf(HostLink.Linked.class, vsInDomainA.hostLink().orElseThrow());
        assertEquals("host-1", linked.hostStableIdentifier().value());
    }
}
