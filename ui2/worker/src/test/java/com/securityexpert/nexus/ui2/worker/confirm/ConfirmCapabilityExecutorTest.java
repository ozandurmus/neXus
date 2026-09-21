package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;

class ConfirmCapabilityExecutorTest {

    private static final String CRED = "cred-ref-1";
    private static final String TRUST = "trust-ref-1";

    @Test
    void checkPointRefusesBeforeAnyContactOnUnresolvableCredential() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(Map.of(), Map.of());
        transport.makeCredentialUnresolvable();
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST));

        assertInstanceOf(ConfirmResult.CredentialUnresolvable.class, result);
        assertTrue(transport.connectedTargets().isEmpty(), "no connect attempt may be recorded before refusal");
    }

    @Test
    void paloAltoRefusesBeforeAnyContactOnUnresolvableCredential() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(Map.of(), Map.of());
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED));

        assertInstanceOf(ConfirmResult.CredentialUnresolvable.class, result);
        assertTrue(transport.connectedBaseUrls().isEmpty(), "no key-generation call may be recorded before refusal");
    }

    @Test
    void unparseableReadsLandUnknownFactsRatherThanAFailure() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "completely unrecognized output, no known fields at all"),
                Map.of("fw-a-host", "also unrecognized -- neither standalone nor a member row"));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result);
        assertTrue(completed.facts().hostname().isEmpty());
        assertTrue(completed.facts().model().isEmpty());
        assertTrue(completed.facts().softwareVersion().isEmpty());
        assertTrue(completed.facts().haRole().isEmpty());
    }

    /** Product Owner measured live, 2026-09-21: a Quantum Spark/Gaia Embedded device (1570/1590
     * appliances) lands directly in Clish, so the bare Expert-mode identity read literal never
     * answers -- previously the confirm never tried DeviceFirstContactCommandSet's own documented
     * "clish -c ..." fallback at all, so this always ran out the full read timeout for nothing. */
    @Test
    void fallsBackToTheClishFormWhenTheBareExpertFormFails() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.10\n"),
                Map.of("fw-a-host", "Standalone"));
        transport.failBareIdentityForm();
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result,
                "the clish fallback must still be tried, not an immediate connect failure");
        assertEquals("fw-a", completed.facts().hostname().orElse(null));
    }

    @Test
    void peerFollowDialsExactlyTheReportedAddressNeverASubstitute() {
        String reportedPeerAddress = "fw-b-base";
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(
                Map.of("fw-a-base", identityBody("0001A", "fw-a"), reportedPeerAddress, identityBody("0001B", "fw-b")),
                Map.of(
                        "fw-a-base", haPeerBody("0001B", reportedPeerAddress),
                        reportedPeerAddress, haPeerBody("0001A", "fw-a-base")));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, workingPanResolver());
        PeerFollowResolver resolver = new PeerFollowResolver(executor);
        ConfirmResult.Completed first = assertInstanceOf(ConfirmResult.Completed.class,
                executor.confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED)));

        AtomicReference<String> dialedAddress = new AtomicReference<>();
        resolver.resolve(first, address -> {
            dialedAddress.set(address);
            return ConfirmRequest.paloAlto(new ApiTarget("ep-peer", address), CRED);
        });

        // The resolver never derives, guesses or substitutes a different address (e.g. a cluster
        // virtual address) -- it dials exactly what the HA read reported, once (13F CL-1, PF-5).
        assertEquals(reportedPeerAddress, dialedAddress.get());
    }

    private static String identityBody(String serial, String hostname) {
        return "<response><result><system><serial>" + serial + "</serial><hostname>" + hostname
                + "</hostname></system></result></response>";
    }

    private static String haPeerBody(String peerSerial, String peerMgmtIp) {
        return "<response><result><enabled>yes</enabled><local><state>active</state></local>"
                + "<peer><serial>" + peerSerial + "</serial><mgmt-ip>" + peerMgmtIp
                + "</mgmt-ip></peer></result></response>";
    }

    private static com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver workingPanResolver() {
        return ref -> new PanCredentialMaterial("api-user", "api-password".toCharArray());
    }

    private static com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver unresolvablePanResolver() {
        return ref -> {
            throw new IllegalStateException("pan credential reference not resolvable: " + ref);
        };
    }
}
