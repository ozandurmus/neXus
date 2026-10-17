package com.securityexpert.nexus.ui2.jobs.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.platform.ActionClass;

class PolicyCompletenessGatesTest {
    private final List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
        getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
    private final GateRegistryPort gates = key -> rows.stream().filter(row -> row.key().equals(key)).toList();

    @Test void everyApprovedCommandResolvesToItsOwnSignedOffClass0ReadRow() {
        assertEquals(6, PolicyCompletenessGates.COMMANDS.size());
        for (var command : PolicyCompletenessGates.COMMANDS) {
            var matches = gates.findByCanonicalKey(command.key());
            assertEquals(1, matches.size(), command.label());
            var row = matches.get(0);
            assertEquals(command.gateId(), row.gateId());
            assertEquals(ActionClass.CLASS_0_READ, row.actionClass());
            assertEquals(SignOffState.SIGNED_OFF, row.signOffState());
            assertEquals(command.timeoutS(), row.timeoutS());
            assertEquals(command.retryRule(), row.retryRule());
            assertEquals(command.text(), row.canonicalCommandKey(), "command text is byte-identical to the approved row");
            assertTrue(PolicyCompletenessGates.enabled(gates, command), command.label());
            assertDoesNotThrow(() -> PolicyCompletenessGates.require(gates, command));
        }
        assertTrue(PolicyCompletenessGates.panConfigLogEnabled(gates));
        assertTrue(PolicyCompletenessGates.cpShowChangesEnabled(gates));
    }

    @Test void aMissingDraftedDuplicatedOrChangedRowNeverAuthorizesACommand() {
        for (var command : PolicyCompletenessGates.COMMANDS) {
            assertFalse(PolicyCompletenessGates.enabled(key -> List.of(), command));
            assertThrows(IllegalStateException.class, () -> PolicyCompletenessGates.require(key -> List.of(), command));
            GateRegistryPort drafted = key -> gates.findByCanonicalKey(key).stream().map(r -> with(r, SignOffState.DRAFTED, r.timeoutS(), r.retryRule())).toList();
            assertFalse(PolicyCompletenessGates.enabled(drafted, command), command.label());
            GateRegistryPort slower = key -> gates.findByCanonicalKey(key).stream().map(r -> with(r, r.signOffState(), r.timeoutS() + 1, r.retryRule())).toList();
            assertFalse(PolicyCompletenessGates.enabled(slower, command), command.label());
            GateRegistryPort retrying = key -> gates.findByCanonicalKey(key).stream().map(r -> with(r, r.signOffState(), r.timeoutS(), "once")).toList();
            assertFalse(PolicyCompletenessGates.enabled(retrying, command), command.label());
            GateRegistryPort twice = key -> { var m = gates.findByCanonicalKey(key); return m.isEmpty() ? m : List.of(m.get(0), m.get(0)); };
            assertFalse(PolicyCompletenessGates.enabled(twice, command), command.label());
        }
        assertFalse(PolicyCompletenessGates.panConfigLogEnabled(key -> gates.findByCanonicalKey(key).stream()
            .filter(r -> !r.gateId().equals("pan_policy_log_job_get")).toList()), "all three Panorama log rows are needed");
    }

    @Test void p2OnTheFirewallScopeAndOnPanoramaAreTwoDifferentKeys() {
        var firewall = rows.stream().filter(r -> r.gateId().equals("pan_syslog_collect_config_enqueue")).findFirst().orElseThrow();
        assertEquals(PolicyCompletenessGates.P2_CONFIG_LOG_ENQUEUE.text(), firewall.canonicalCommandKey());
        assertEquals("pan_firewall", firewall.platformRoleScope());
        assertEquals("panorama", PolicyCompletenessGates.P2_CONFIG_LOG_ENQUEUE.platformRoleScope());
        assertNotEquals(firewall.key(), PolicyCompletenessGates.P2_CONFIG_LOG_ENQUEUE.key());
    }

    @Test void theConditionalC3RowIsRegisteredAndSignedOffButHasNoCommandHere() {
        var c3 = rows.stream().filter(r -> r.gateId().equals("cp_policy_nat_rulebase_page100")).findFirst().orElseThrow();
        assertEquals(SignOffState.SIGNED_OFF, c3.signOffState());
        assertEquals(300, c3.timeoutS());
        assertEquals("mgmt_cli -r true -d '<DOMAIN>' -f json show-nat-rulebase package '<PKG>' limit 100 offset '<N>' details-level standard use-object-dictionary true",
            c3.canonicalCommandKey());
        assertTrue(c3.sourceDocumentPointer().contains("CONDITIONAL"));
        assertTrue(PolicyCompletenessGates.COMMANDS.stream().noneMatch(c -> c.gateId().equals(c3.gateId()) || c.text().equals(c3.canonicalCommandKey())));
    }

    @Test void theNewRowsDoNotDisturbTheExistingPolicyGatesAndStayOutOfDiagnostics() {
        assertDoesNotThrow(() -> CpPolicyGates.requireAll(gates));
        assertDoesNotThrow(() -> PanPolicyGates.requireAll(gates));
        var diagnostic = DiagnosticRead.commands("check_point", "management_server", null, gates);
        assertTrue(diagnostic.stream().noneMatch(c -> c.gateId().equals("cp_policy_show_changes") || c.gateId().equals("cp_policy_show_task")
            || c.gateId().equals("cp_policy_nat_rulebase_page100")));
    }

    @Test void parameterShapesRefuseAnythingOutsideTheApprovedForms() {
        assertTrue(PolicyCompletenessGates.PAN_TIMESTAMP.matcher("2026/10/18 06:00:00").matches());
        assertFalse(PolicyCompletenessGates.PAN_TIMESTAMP.matcher("2026/10/18 06:00:00' or 1=1").matches());
        assertTrue(PolicyCompletenessGates.PAN_JOB_ID.matcher("41").matches());
        assertFalse(PolicyCompletenessGates.PAN_JOB_ID.matcher("41;x").matches());
        assertTrue(PolicyCompletenessGates.CP_ISO_TIME.matcher("2026-10-18T06:00:00+0300").matches());
        assertTrue(PolicyCompletenessGates.CP_ISO_TIME.matcher("2026-10-18T06:00+03:00").matches());
        assertFalse(PolicyCompletenessGates.CP_ISO_TIME.matcher("2026-10-18T06:00:00' ; rm").matches());
        assertTrue(PolicyCompletenessGates.CP_TASK_ID.matcher("2f4f6a3e-0000-0000-0000-000000000001").matches());
        assertFalse(PolicyCompletenessGates.CP_TASK_ID.matcher("a b").matches());
        assertEquals(5000, PolicyCompletenessGates.PAN_LOG_PAGE_MAX);
        assertEquals(4, PolicyCompletenessGates.PAN_LOG_MAX_PAGES);
        assertEquals(30, PolicyCompletenessGates.PAN_LOG_MAX_POLLS);
        assertEquals(20, PolicyCompletenessGates.CP_SHOW_CHANGES_MAX_OFFSETS);
        assertEquals(24, PolicyCompletenessGates.CP_SHOW_TASK_MAX_POLLS);
    }

    private static GateRow with(GateRow r, SignOffState state, int timeout, String retry) {
        return new GateRow(r.gateId(), r.vendor(), r.platformRoleScope(), r.shellContext(), r.transportKind(), r.canonicalCommandKey(),
            r.actionClass(), state, timeout, retry, r.maxFrequency(), r.sessionReuseRule(), r.unsupportedBehaviorRef(),
            r.secretOutputRisk(), r.safeTelemetryFields(), r.sourceDocumentPointer());
    }
}
