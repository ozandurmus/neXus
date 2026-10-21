package com.securityexpert.nexus.ui2.worker.confirm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.ApiTarget;
import com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget;
import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;
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

        ConfirmResult result = executor.confirm(ConfirmRequest.panorama(new ApiTarget("ep-a", "fw-a-base"), CRED));

        assertInstanceOf(ConfirmResult.CredentialUnresolvable.class, result);
        assertTrue(transport.connectedBaseUrls().isEmpty(), "no key-generation call may be recorded before refusal");
    }

    /** Synthetic type=version serial, built at runtime so no literal looks like a real one. */
    private static final String FW_SERIAL = "TESTSER" + "0001";

    private static String typeVersionBody(String serial, String model, String version) {
        return "<response status=\"success\"><result>" + (version == null ? "" : "<sw-version>" + version + "</sw-version>")
                + "<multi-vsys>off</multi-vsys>" + (model == null ? "" : "<model>" + model + "</model>")
                + (serial == null ? "" : "<serial>" + serial + "</serial>") + "</result></response>";
    }

    /**
     * PO 2026-10-21: a Palo Alto firewall's confirm reads its serial, model and PAN-OS version with the documented
     * type=version request -- never show system info (retired for firewalls) -- then the HA state read. No hostname is
     * read (type=version does not return one).
     */
    @Test
    void paloAltoFirewallConfirmReadsTypeVersionAndNeverShowSystemInfo() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(Map.of(),
                Map.of("fw-a-base", haPeerBody("0001B", "fw-b-base")));
        transport.typeVersionBodyByBaseUrl.put("fw-a-base", typeVersionBody(FW_SERIAL, "PA-TESTMODEL", "9.9.9-test"));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, workingPanResolver());

        ConfirmResult result = executor.confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result);
        assertEquals(FW_SERIAL, completed.presentedIdentity().primary(), "the identity baseline is the type=version serial");
        assertEquals(Optional.of(FW_SERIAL), completed.selfReferenceForPeer());
        assertEquals(Optional.of("PA-TESTMODEL"), completed.facts().model());
        assertEquals(Optional.of("9.9.9-test"), completed.facts().softwareVersion());
        assertTrue(completed.facts().hostname().isEmpty(), "type=version carries no hostname: UNKNOWN, never guessed");
        assertEquals(Optional.of("0001B"), completed.haPeerClaim().peerSelfIdentifier(), "the HA state read is unchanged");
        assertEquals(java.util.List.of("type=version", "<show><high-availability><state/></high-availability></show>"),
                transport.commandsIssued(), "type=version, then the HA state; show system info is never sent");
        XmlApiSpec request = transport.typeVersionSpecs.get(0);
        assertTrue(request.formParams().isEmpty(), "a form request with no cmd");
        assertEquals(Map.of("X-PAN-KEY", "FAKE-KEY"), request.headers());
    }

    /** No serial means no identity baseline: the confirm fails clearly, never records an empty or guessed identity. */
    @Test
    void paloAltoFirewallConfirmFailsWhenTypeVersionGivesNoUsableSerial() {
        for (String body : java.util.Arrays.asList(null, "", "not xml", "<response status=\"error\"/>",
                typeVersionBody(null, "PA-TESTMODEL", "9.9.9-test"))) {
            ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(Map.of(), Map.of());
            if (body != null) {
                transport.typeVersionBodyByBaseUrl.put("fw-a-base", body);
            }
            ConfirmResult result = new ConfirmCapabilityExecutor(transport, workingPanResolver())
                    .confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED));
            ConfirmResult.ConnectFailed failed = assertInstanceOf(ConfirmResult.ConnectFailed.class, result, String.valueOf(body));
            assertTrue(failed.reason().contains("type=version"), failed.reason());
            assertEquals(java.util.List.of("type=version"), transport.commandsIssued(), "nothing is read after a failed identity read");
        }
    }

    /** A missing model or version is UNKNOWN on the enrolled facts; the serial is still the baseline. */
    @Test
    void paloAltoFirewallConfirmLandsMissingModelAndVersionAsUnknown() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(Map.of(), Map.of("fw-a-base", ""));
        transport.typeVersionBodyByBaseUrl.put("fw-a-base", typeVersionBody(FW_SERIAL, null, null));
        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class,
                new ConfirmCapabilityExecutor(transport, workingPanResolver())
                        .confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED)));
        assertEquals(FW_SERIAL, completed.presentedIdentity().primary());
        assertTrue(completed.facts().model().isEmpty() && completed.facts().softwareVersion().isEmpty());
    }

    @Test
    void paloAltoFirewallConfirmRefusesBeforeAnyContactOnUnresolvableCredential() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(Map.of(), Map.of());
        ConfirmResult result = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver())
                .confirm(ConfirmRequest.paloAlto(new ApiTarget("ep-a", "fw-a-base"), CRED));
        assertInstanceOf(ConfirmResult.CredentialUnresolvable.class, result);
        assertTrue(transport.connectedBaseUrls().isEmpty());
    }

    /** A Panorama keeps its own system info identity read (out of the 2026-10-21 scope), never type=version. */
    @Test
    void panoramaConfirmStillReadsShowSystemInfo() {
        ScriptedPanDeviceTransport transport = new ScriptedPanDeviceTransport(
                Map.of("pano-base", identityBody("0001P", "pano")), Map.of("pano-base", ""));
        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class,
                new ConfirmCapabilityExecutor(transport, workingPanResolver())
                        .confirm(ConfirmRequest.panorama(new ApiTarget("ep-p", "pano-base"), CRED)));
        assertEquals("0001P", completed.presentedIdentity().primary());
        assertTrue(transport.commandsIssued().contains("<show><system><info/></system></show>"));
        assertFalse(transport.commandsIssued().contains("type=version"));
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

    /** The pre-Java product's own probe (checkpoint/direct_ssh_probe.py) also fell through on a
     * "completed but empty" result, not only on a hard failure -- a device can accept a command
     * and simply print nothing for it. */
    @Test
    void fallsBackPastACompletedButBlankForm() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.10\n"),
                Map.of("fw-a-host", "Standalone"));
        transport.blankOutOnFirstIdentityForms(2);
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result,
                "a completed-but-blank form must not be mistaken for a successful identity read");
        assertEquals("fw-a", completed.facts().hostname().orElse(null));
    }

    /** Product Owner measured live, 2026-09-21, and confirmed by reading the pre-Java product's
     * own real-fleet-proven probe: a Gaia Embedded/Quantum Spark device rejects the exec channel
     * outright, no matter which literal form is sent -- only a persistent interactive shell
     * answers. */
    @Test
    void fallsBackToAnInteractiveShellWhenTheExecChannelIsRejectedOutright() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.10\n"),
                Map.of("fw-a-host", "Standalone"));
        transport.rejectExecChannelEntirely();
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result,
                "the interactive-shell fallback must still be tried, not an immediate connect failure");
        assertEquals("fw-a", completed.facts().hostname().orElse(null));
    }

    /** Product Owner directive, 2026-09-22: a device's discovery candidate can already carry a
     * Check Point Management Server "hardware" model (joined in by {@code
     * JooqDeviceRepository.DEVICE_SUMMARY_SELECT} before any confirm ever ran) -- when it names a
     * Quantum Spark/Gaia Embedded appliance, the confirm must try the interactive shell first and
     * skip the four exec-channel attempts that are doomed on that hardware (measured live,
     * 2026-09-21: ~120s burned there before the interactive shell -- which always answered -- ever
     * ran), rather than discovering the same fact the slow way on every single confirm. */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Check Point 1570/1590 Appliances", "gaia_embedded"})
    void aDiscoveryKnownSparkModelTriesTheInteractiveShellFirstSkippingTheDoomedExecAttempts(String sparkHint) {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.10\n"),
                Map.of("fw-a-host", "Standalone"));
        transport.allowExecInteractiveWithoutRejection();
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST,
                        Optional.of(sparkHint)));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result);
        assertEquals("fw-a", completed.facts().hostname().orElse(null));
        assertEquals(0, transport.execCallCount(),
                "a discovery-known Spark model must skip the doomed exec-channel attempts entirely");
        assertTrue(transport.execInteractiveCallCount() > 0,
                "the interactive shell must be tried first (and here, exclusively) for a known Spark model");
        assertTrue(completed.facts().model().isEmpty());
        assertEquals("V0", completed.facts().sparkRevision().orElseThrow());
        assertEquals("R81.10.10", completed.facts().softwareVersion().orElse(null));
    }

    /** The same model hint must never change WHICH commands are sent (the closed set stays
     * {@link DeviceFirstContactCommandSet}'s own four literal forms either way) -- only the order
     * the two already-approved channels are tried in, and only for a model the hint actually
     * names; an unrelated/unknown model keeps the original exec-first order. */
    @Test
    void anUnknownModelHintKeepsTheOriginalExecFirstOrder() {
        ScriptedDeviceTransport transport = new ScriptedDeviceTransport(
                Map.of("fw-a-host", "Host Name: fw-a\nProduct version Check Point Gaia R81.10\n"),
                Map.of("fw-a-host", "Standalone"));
        ConfirmCapabilityExecutor executor = new ConfirmCapabilityExecutor(transport, unresolvablePanResolver());

        ConfirmResult result = executor.confirm(
                ConfirmRequest.checkPoint(new ConnectionTarget("ep-a", "fw-a-host", 22), CRED, TRUST,
                        Optional.of("Check Point 6500 Appliance")));

        ConfirmResult.Completed completed = assertInstanceOf(ConfirmResult.Completed.class, result);
        assertEquals("fw-a", completed.facts().hostname().orElse(null));
        assertTrue(transport.execCallCount() > 0, "a non-Spark model hint must still try the exec channel first");
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
                executor.confirm(ConfirmRequest.panorama(new ApiTarget("ep-a", "fw-a-base"), CRED)));

        AtomicReference<String> dialedAddress = new AtomicReference<>();
        resolver.resolve(first, address -> {
            dialedAddress.set(address);
            return ConfirmRequest.panorama(new ApiTarget("ep-peer", address), CRED);
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
