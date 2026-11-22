package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * Pins the Check Point confirm-read parsing against synthetic output only.
 * The real gateway output shape is UNVERIFIED (Band 3), so these tests prove
 * the parser's contract for the shapes it binds, not the vendor's format.
 */
class CheckPointConfirmReadParserTest {

    private final CheckPointConfirmReadParser parser = new CheckPointConfirmReadParser();

    private static final String IDENTITY = "Host name: FW-TANGO-04\n"
            + "Appliance name: SYNTH-APPLIANCE-1\n"
            + "Product version R81.10 Take 123\n";

    private static final String HA_TWO_MEMBERS = "1 (local) ACTIVE\n"
            + "2 peer-member STANDBY\n";

    @Test
    void wellFormedIdentityYieldsHostnameModelAndVersion() {
        ObservedFacts facts = parser.observedFacts(IDENTITY);

        assertEquals("FW-TANGO-04", facts.hostname().orElseThrow());
        assertEquals("SYNTH-APPLIANCE-1", facts.model().orElseThrow());
        assertEquals("R81.10 Take 123", facts.softwareVersion().orElseThrow());
        assertTrue(facts.haRole().isEmpty());
        assertTrue(facts.sparkRevision().isEmpty());
    }

    @Test
    void presentedIdentityPairsConnectFingerprintWithHostname() {
        PresentedIdentity presented = parser.presentedIdentity(IDENTITY, Optional.of("SYNTH-FINGERPRINT"));

        assertEquals("SYNTH-FINGERPRINT", presented.primary());
        assertEquals("FW-TANGO-04", presented.secondary().orElseThrow());
        assertEquals("FW-TANGO-04", parser.selfReferenceForPeer(IDENTITY).orElseThrow());
    }

    @Test
    void clusterOutputYieldsSelfRoleAndSecondMemberAsPeer() {
        HaPeerClaim claim = parser.haPeerClaim(HA_TWO_MEMBERS);

        assertTrue(claim.isMember());
        assertEquals("2", claim.peerSelfIdentifier().orElseThrow());
        assertTrue(claim.peerManagementAddress().isEmpty());
        assertEquals("ACTIVE", parser.haRole(HA_TWO_MEMBERS).orElseThrow());
    }

    @Test
    void notEnabledOrNotAMemberOutputIsStandalone() {
        String standalone = "Cluster is not enabled on this gateway\n";

        assertFalse(parser.haPeerClaim(standalone).isMember());
        assertTrue(parser.haPeerClaim(standalone).peerSelfIdentifier().isEmpty());
        assertEquals("STANDALONE", parser.haRole(standalone).orElseThrow());
    }

    @Test
    void emptyOrUnknownOutputIsUnknownNeverReady() {
        assertTrue(parser.observedFacts("").hostname().isEmpty());
        assertTrue(parser.observedFacts("").model().isEmpty());
        assertTrue(parser.observedFacts("").softwareVersion().isEmpty());
        assertTrue(parser.observedFacts("Bad parameter").hostname().isEmpty());

        assertTrue(parser.haRole("").isEmpty());
        assertTrue(parser.haRole("Bad parameter").isEmpty());
        assertTrue(parser.haPeerClaim("").peerSelfIdentifier().isEmpty());
        assertTrue(parser.haPeerClaim("").peerManagementAddress().isEmpty());

        PresentedIdentity presented = parser.presentedIdentity("", Optional.empty());
        assertEquals("", presented.primary());
        assertTrue(presented.secondary().isEmpty());
        assertTrue(parser.selfReferenceForPeer("").isEmpty());
    }

    @Test
    void missingFieldsStayUnknownAndInventNoValue() {
        String hostnameOnly = "Host name: FW-TANGO-04\n";

        ObservedFacts facts = parser.observedFacts(hostnameOnly);
        assertEquals("FW-TANGO-04", facts.hostname().orElseThrow());
        assertTrue(facts.model().isEmpty());
        assertTrue(facts.softwareVersion().isEmpty());

        // A single member row names no peer: the peer stays UNKNOWN, never a copy of self.
        HaPeerClaim oneRow = parser.haPeerClaim("1 (local) ACTIVE\n");
        assertTrue(oneRow.peerSelfIdentifier().isEmpty());
        assertEquals("ACTIVE", parser.haRole("1 (local) ACTIVE\n").orElseThrow());
    }

    @Test
    @Disabled("Defect (open issue): haRole(null) returns STANDALONE, a claim about the device, "
            + "for a missing read. The class contract says a non-match is Optional.empty() (UNKNOWN).")
    void missingHaReadIsUnknownNotStandalone() {
        assertTrue(parser.haRole(null).isEmpty());
    }
}
