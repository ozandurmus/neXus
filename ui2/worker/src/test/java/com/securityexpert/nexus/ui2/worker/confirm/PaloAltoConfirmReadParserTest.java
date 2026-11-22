package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Pins the Palo Alto confirm-read parsing against synthetic XML-shaped output
 * only. The real firewall output shape is UNVERIFIED (Band 3); these tests
 * prove the tag-text contract the parser binds, not the vendor's format.
 */
class PaloAltoConfirmReadParserTest {

    private final PaloAltoConfirmReadParser parser = new PaloAltoConfirmReadParser();

    private static final String IDENTITY = "<response status=\"success\"><result><system>"
            + "<hostname>FW-TANGO-04</hostname>"
            + "<model>SYNTH-PA-1</model>"
            + "<sw-version>10.2.0</sw-version>"
            + "<serial>SYNTHSERIAL0001</serial>"
            + "</system></result></response>";

    private static final String HA_ACTIVE = "<response status=\"success\"><result>"
            + "<enabled>yes</enabled>"
            + "<group><local-info><state>active</state></local-info>"
            + "<peer-info><serial-num>SYNTHSERIAL0002</serial-num>"
            + "<mgmt-ip>192.0.2.10/24</mgmt-ip><state>passive</state></peer-info>"
            + "</group></result></response>";

    @Test
    void wellFormedIdentityYieldsHostnameModelAndVersion() {
        ObservedFacts facts = parser.observedFacts(IDENTITY);

        assertEquals("FW-TANGO-04", facts.hostname().orElseThrow());
        assertEquals("SYNTH-PA-1", facts.model().orElseThrow());
        assertEquals("10.2.0", facts.softwareVersion().orElseThrow());
        assertTrue(facts.haRole().isEmpty());
    }

    @Test
    void presentedIdentityUsesSerialAsPrimaryAndConnectValueAsSecondary() {
        PresentedIdentity presented = parser.presentedIdentity(IDENTITY, Optional.of("SYNTH-CONNECT"));

        assertEquals("SYNTHSERIAL0001", presented.primary());
        assertEquals("SYNTH-CONNECT", presented.secondary().orElseThrow());
        assertEquals("SYNTHSERIAL0001", parser.selfReferenceForPeer(IDENTITY).orElseThrow());
    }

    @Test
    void activeHaYieldsPeerSerialAndAddressWithPrefixStripped() {
        HaPeerClaim claim = parser.haPeerClaim(HA_ACTIVE);

        assertTrue(claim.isMember());
        assertEquals("SYNTHSERIAL0002", claim.peerSelfIdentifier().orElseThrow());
        assertEquals("192.0.2.10", claim.peerManagementAddress().orElseThrow());
        assertEquals("active", parser.haRole(HA_ACTIVE).orElseThrow());
    }

    @Test
    void haDisabledIsStandalone() {
        String disabled = "<response><result><enabled>no</enabled></result></response>";

        assertFalse(parser.haPeerClaim(disabled).isMember());
        assertTrue(parser.haPeerClaim(disabled).peerSelfIdentifier().isEmpty());
        assertEquals("STANDALONE", parser.haRole(disabled).orElseThrow());
    }

    @Test
    void emptyOrUnknownOutputYieldsNoFacts() {
        String error = "<response status=\"error\"><msg>Invalid command</msg></response>";

        for (String output : new String[] {"", error}) {
            ObservedFacts facts = parser.observedFacts(output);
            assertTrue(facts.hostname().isEmpty());
            assertTrue(facts.model().isEmpty());
            assertTrue(facts.softwareVersion().isEmpty());
            assertTrue(parser.selfReferenceForPeer(output).isEmpty());
            assertTrue(parser.haPeerClaim(output).peerSelfIdentifier().isEmpty());
            assertTrue(parser.haPeerClaim(output).peerManagementAddress().isEmpty());
        }
        assertTrue(parser.presentedIdentity(error, Optional.empty()).secondary().isEmpty());
        assertEquals("", parser.presentedIdentity(error, Optional.empty()).primary());
    }

    @Test
    void missingFieldsStayUnknownAndInventNoValue() {
        String noModel = "<response><result><system><hostname>FW-TANGO-04</hostname></system>"
                + "</result></response>";
        ObservedFacts facts = parser.observedFacts(noModel);
        assertEquals("FW-TANGO-04", facts.hostname().orElseThrow());
        assertTrue(facts.model().isEmpty());

        PresentedIdentity noSerial = parser.presentedIdentity(noModel, Optional.of("SYNTH-CONNECT"));
        assertEquals("", noSerial.primary());
        assertEquals("SYNTH-CONNECT", noSerial.secondary().orElseThrow());

        String enabledNoPeer = "<response><result><enabled>yes</enabled>"
                + "<group><local-info><state>active</state></local-info></group></result></response>";
        HaPeerClaim claim = parser.haPeerClaim(enabledNoPeer);
        assertTrue(claim.isMember());
        assertTrue(claim.peerSelfIdentifier().isEmpty());
        assertTrue(claim.peerManagementAddress().isEmpty());
    }

    @Test
    void emptyOrMissingHaReadIsUnknownNotStandalone() {
        assertTrue(parser.haRole("").isEmpty());
        assertTrue(parser.haRole(null).isEmpty());
    }
}
