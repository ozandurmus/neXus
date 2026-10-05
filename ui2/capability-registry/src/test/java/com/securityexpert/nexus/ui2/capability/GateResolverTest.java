package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * C4 §3.3's seven-step resolution algorithm, plus contract §3's rejection
 * rules (sftp_put unconditional refusal, restore_push action-class check).
 */
class GateResolverTest {

    private static GateRow signedOffRow(String canonicalKey, ActionClass actionClass) {
        return new GateRow("gate_1", "check_point", "cp_gaia_gateway", "clish", "SSH_EXEC", canonicalKey,
                actionClass, SignOffState.SIGNED_OFF, 30, null, null, null, null, null, List.of(), "test");
    }

    @Test
    void pagedPackagesGateResolvesAndOffsetInjectionIsRejected() {
        var rows = GateRegistryFixtureLoader.loadFromStream(
            getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort registry = key -> rows.stream().filter(row -> row.key().equals(key)).toList();
        String command = "mgmt_cli -r true -d '<DOMAIN>' -f json show-packages limit 20 offset '<N>' details-level full";
        var key = new CanonicalCommandKey("check_point", "cp_multi_domain_server", "expert", "SSH_EXEC", command);
        assertTrue(GateResolver.resolve(key, Optional.empty(), registry) instanceof GateResolution.Known);
        for (String injected : List.of("-1", "0; echo injected", "0' ; echo injected; '")) {
            var invalid = new CanonicalCommandKey("check_point", "cp_multi_domain_server", "expert", "SSH_EXEC",
                command.replace("<N>", injected));
            assertTrue(GateResolver.resolve(invalid, Optional.empty(), registry) instanceof GateResolution.Unknown);
        }
    }

    @Test
    void zeroRowsResolvesUnknownRequiresGateEntry() {
        CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show version");
        GateResolution resolution = GateResolver.resolve(key, Optional.empty(), k -> List.of());

        assertTrue(resolution instanceof GateResolution.Unknown);
        assertEquals("requires gate entry", ((GateResolution.Unknown) resolution).reason());
        assertTrue(resolution.executionEligible() == false);
    }

    @Test
    void exactlyOneSignedOffRowResolvesKnown() {
        CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show version");
        GateRow row = signedOffRow("show version", ActionClass.CLASS_0_READ);
        GateResolution resolution = GateResolver.resolve(key, Optional.empty(), k -> List.of(row));

        assertTrue(resolution instanceof GateResolution.Known known && known.actionClass() == ActionClass.CLASS_0_READ);
        assertTrue(resolution.executionEligible());
    }

    @Test
    void moreThanOneMatchingRowIsAHardLoadTimeError() {
        CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show version");
        GateRow row1 = signedOffRow("show version", ActionClass.CLASS_0_READ);
        GateRow row2 = signedOffRow("show version", ActionClass.CLASS_0_READ);

        assertThrows(AmbiguousGateResolutionException.class,
                () -> GateResolver.resolve(key, Optional.empty(), k -> List.of(row1, row2)));
    }

    @Test
    void nonSignedOffStateResolvesUnknownNamedWithTheState() {
        CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show diskspace");
        GateRow pending = new GateRow("rb3b_freespace_read", "check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show diskspace", ActionClass.CLASS_0_READ, SignOffState.SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION,
                30, null, null, null, null, null, List.of(), "test");
        GateResolution resolution = GateResolver.resolve(key, Optional.empty(), k -> List.of(pending));

        assertTrue(resolution instanceof GateResolution.Unknown unknown
                && unknown.reason().contains("SIGNED_OFF_PENDING_HARDWARE_CONFIRMATION"));
    }

    @Test
    void nearMissCommandKeysNeverMatchTheRegisteredRow() {
        GateRow row = signedOffRow("add backup local", ActionClass.CLASS_1_RECOVERY_WRITE);
        // A fuzz of proper prefixes/suffixes/superstrings around the
        // registered canonical key -- C4 §7 test 2's own property.
        List<String> nearMisses = List.of("add backup", "add backup local ", " add backup local",
                "add backup local extra", "ADD BACKUP LOCAL", "add-backup-local");
        for (String candidate : nearMisses) {
            CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                    candidate);
            GateResolution resolution = GateResolver.resolve(key, Optional.empty(),
                    k -> k.equals(row.key()) ? List.of(row) : List.of());
            assertTrue(resolution instanceof GateResolution.Unknown,
                    "near-miss '" + candidate + "' must never resolve KNOWN against 'add backup local'");
        }
    }

    @Test
    void declaredActionClassMismatchFailsRatherThanSilentlyOverriding() {
        CanonicalCommandKey key = new CanonicalCommandKey("check_point", "cp_gaia_gateway", "clish", "SSH_EXEC",
                "show version");
        GateRow row = signedOffRow("show version", ActionClass.CLASS_0_READ);

        assertThrows(GateActionClassMismatchException.class, () -> GateResolver.resolve(key,
                Optional.of(ActionClass.CLASS_1_RECOVERY_WRITE), k -> List.of(row)));
    }

    @Test
    void panFailoverWriteExceptionRequiresExactKeyAndScope() {
        String command="<request><high-availability><state><suspend/></state></high-availability></request>";
        GateRow approved=new GateRow("pan_failover_suspend","palo_alto","pan_firewall","not_applicable",
            "PAN_XML_API",command,ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE,SignOffState.SIGNED_OFF,
            30,"none","once per run","one key per run","UNKNOWN","none",List.of(),"test");
        assertTrue(!approved.violatesWriteMarkerDenylist());
        GateRow nearMiss=new GateRow("pan_failover_suspend","palo_alto","pan_firewall","not_applicable",
            "PAN_XML_API",command+" ",ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE,SignOffState.SIGNED_OFF,
            30,"none","once per run","one key per run","UNKNOWN","none",List.of(),"test");
        assertTrue(nearMiss.violatesWriteMarkerDenylist());
    }

    @Test
    void sftpPutIsRefusedUnconditionallyAtSpecValidation() {
        CapabilityStep sftpPut = new CapabilityStep(StepKind.SFTP_PUT, "not_applicable", "push", false,
                Optional.empty(), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec("test_sftp_put", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(sftpPut), List.of(), "UNKNOWN",
                List.of(), false);

        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecValidator.validate(spec));
        assertEquals(CapabilityValidationException.SFTP_PUT_RESERVED, e.code());
    }

    @Test
    void restorePushWithWrongActionClassIsRefused() {
        CapabilityStep restorePush = new CapabilityStep(StepKind.RESTORE_PUSH, "not_applicable", "restore", false,
                Optional.of(ActionClass.CLASS_1_RECOVERY_WRITE), Optional.empty(), Optional.empty());
        CapabilitySpec spec = new CapabilitySpec("test_restore_push", "check_point", "cp_gaia_gateway",
                TransportKind.SSH_EXEC, MaturityState.CAP_OFFLINE, List.of(restorePush), List.of(), "UNKNOWN",
                List.of(), false);

        CapabilityValidationException e = assertThrows(CapabilityValidationException.class,
                () -> CapabilitySpecValidator.validate(spec));
        assertEquals(CapabilityValidationException.RESTORE_PUSH_ACTION_CLASS_INVALID, e.code());
    }
}
