package com.securityexpert.nexus.ui2.jobs.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.capability.*;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import java.util.List;
import org.junit.jupiter.api.Test;

class CpPolicyGatesTest {
    @Test void uidVariantsAreSignedOffAndStayOutOfDiagnostics() {
        var rows = GateRegistryFixtureLoader.loadFromStream(
            getClass().getResourceAsStream("/capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort gates = key -> rows.stream().filter(row -> row.key().equals(key)).toList();
        for (boolean hits : List.of(false, true)) {
            String id = "cp_policy_access_rulebase_uid" + (hits ? "_hits" : "");
            assertTrue(rows.stream().anyMatch(row -> row.gateId().equals(id)
                && row.canonicalCommandKey().equals(CpPolicyGates.accessUidCommand(hits))));
            assertDoesNotThrow(() -> CpPolicyGates.requireAccessUid(gates, hits));
            assertThrows(IllegalStateException.class, () -> CpPolicyGates.requireAccessUid(key -> List.of(), hits));
            GateRegistryPort duplicate = key -> {
                var matches = gates.findByCanonicalKey(key);
                return matches.isEmpty() ? matches : List.of(matches.get(0), matches.get(0));
            };
            assertThrows(IllegalStateException.class, () -> CpPolicyGates.requireAccessUid(duplicate, hits));
        }
        assertDoesNotThrow(() -> CpPolicyGates.requireAll(gates));
        assertTrue(DiagnosticRead.commands("check_point", "management_server", null, gates).stream()
            .noneMatch(command -> command.gateId().startsWith("cp_policy_access_rulebase_uid")));
    }
}
