package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

/**
 * AC-1, THE DECISIVE TEST: over a fake transport, a peer that names the
 * first device back yields one unit with both rows sharing a {@code
 * cluster_member_ref}; a one-sided claim yields no unit and the "peer
 * named, not confirmed" outcome (PF-2/PF-3). Palo Alto is the vendor used
 * here because its HA state read is the one PF-4 says actually carries a
 * peer management address -- Check Point's does not (see {@link
 * #noPeerAddressStopsBeforeAnySecondContact()} below), so only the Palo
 * Alto path can ever reach PF-2's corroboration check at all.
 */
class PeerFollowResolverTest {

    private static final String CRED = "cred-ref-1";
    private static final String TRUST = "trust-ref-1";

    @Test
    void reciprocalClaimFormsOneUnitWithSharedClusterMemberRef() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(
                Map.of(
                        "fw-a-base", identityBody("0001A", "fw-a"),
                        "fw-b-base", identityBody("0001B", "fw-b")),
                Map.of(
                        "fw-a-base", haPeerBody("active", "0001B", "fw-b-base"),
                        "fw-b-base", haPeerBody("passive", "0001A", "fw-a-base")));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, workingPanResolver());
        PeerFollowResolver resolver = new PeerFollowResolver(executor);

        ConfirmResult firstResult =
                executor.confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED));
        ConfirmResult.Completed firstCompleted = assertInstanceOf(ConfirmResult.Completed.class, firstResult);
        assertTrue(firstCompleted.haPeerClaim().isMember());
        assertEquals("0001B", firstCompleted.haPeerClaim().peerSelfIdentifier().orElseThrow());
        assertEquals("0001A", firstCompleted.selfReferenceForPeer().orElseThrow());

        PeerFollowOutcome outcome = resolver.resolve(firstCompleted,
                address -> ConfirmRequest.paloAlto(new ApiTarget("ep-peer", address), CRED));

        PeerFollowOutcome.Corroborated corroborated = assertInstanceOf(PeerFollowOutcome.Corroborated.class, outcome);
        assertEquals("0001A|0001B", corroborated.unitId());
        assertEquals("fw-b", corroborated.peerResult().facts().hostname().orElseThrow());

        // Only the one PF-1 hop was ever dialed -- the first device's own contact, plus exactly one
        // peer contact. Never a second hop, never more than these two targets.
        assertEquals(2, transport.connectedBaseUrls().size());
    }

    @Test
    void oneSidedClaimFormsNoUnitAndReportsPeerNamedNotConfirmed() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(
                Map.of(
                        "fw-a-base", identityBody("0001A", "fw-a"),
                        "fw-b-base", identityBody("0001B", "fw-b")),
                Map.of(
                        "fw-a-base", haPeerBody("active", "0001B", "fw-b-base"),
                        // fw-b's own read names an unrelated serial, never fw-a's -- a one-sided claim.
                        "fw-b-base", haPeerBody("passive", "9999Z", "somewhere-else")));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, workingPanResolver());
        PeerFollowResolver resolver = new PeerFollowResolver(executor);

        ConfirmResult.Completed firstCompleted = assertInstanceOf(ConfirmResult.Completed.class,
                executor.confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED)));

        PeerFollowOutcome outcome = resolver.resolve(firstCompleted,
                address -> ConfirmRequest.paloAlto(new ApiTarget("ep-peer", address), CRED));

        PeerFollowOutcome.NotConfirmed notConfirmed = assertInstanceOf(PeerFollowOutcome.NotConfirmed.class, outcome);
        assertEquals(PeerFollowOutcome.Reason.ONE_SIDED_CLAIM, notConfirmed.reason());
    }

    private static String identityBody(String serial, String hostname) {
        return "<response><result><system><serial>" + serial + "</serial><hostname>" + hostname
                + "</hostname><model>PA-820</model><sw-version>10.2.0</sw-version></system></result></response>";
    }

    private static String haPeerBody(String localState, String peerSerial, String peerMgmtIp) {
        return "<response><result><enabled>yes</enabled><local><state>" + localState + "</state></local>"
                + "<peer><serial>" + peerSerial + "</serial><mgmt-ip>" + peerMgmtIp
                + "</mgmt-ip></peer></result></response>";
    }

    private static com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver workingPanResolver() {
        return ref -> new PanCredentialMaterial("api-user", "api-password".toCharArray());
    }

    @Test
    void noPeerAddressStopsBeforeAnySecondContact() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.20\n"),
                // cphaprob names a member but carries no management address (PF-4's documented Check
                // Point case) -- the member row still parses, but there is no address to dial.
                Map.of("fw-a-host", "fw-a ACTIVE\nfw-b STANDBY\n"));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());
        PeerFollowResolver resolver = new PeerFollowResolver(executor);

        ConfirmResult.Completed firstCompleted = assertInstanceOf(ConfirmResult.Completed.class,
                executor.confirm(ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST)));
        assertTrue(firstCompleted.haPeerClaim().peerManagementAddress().isEmpty());

        PeerFollowOutcome outcome = resolver.resolve(firstCompleted,
                address -> {
                    throw new AssertionError("must never be invoked -- PF-3: no address, no contact");
                });

        PeerFollowOutcome.NotConfirmed notConfirmed = assertInstanceOf(PeerFollowOutcome.NotConfirmed.class, outcome);
        assertEquals(PeerFollowOutcome.Reason.ADDRESS_MISSING, notConfirmed.reason());
        // Only the first device's own connect ever happened -- never a second hop for a missing address.
        assertEquals(1, transport.connectedTargets().size());
    }

    private static com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver unresolvablePanResolver() {
        return ref -> {
            throw new IllegalStateException("not used by check_point confirms");
        };
    }
}
