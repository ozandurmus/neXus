package com.securityexpert.nexus.ui2.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.jobs.diagnostic.DiagnosticRead;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreviewPlanSet;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ReadGate;
import com.securityexpert.nexus.ui2.persistence.staticroute.StaticRoutePreviewRepository.PreviewRow;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig;

/**
 * Architectural no-write proof for Operations static route Phase R0
 * ({@code docs/design/OPERATIONS_STATIC_ROUTE_R0_READ_COMMANDS_2026_11_18.md}, APPROVED 2026-11-18).
 *
 * <p>Proves that:
 * <ul>
 *   <li>No static-route write literal or write gate exists in the gate registry fixture or V252 migration.</li>
 *   <li>Every {@link ReadGate} in {@link StaticRoutePreview} is {@link ActionClass#CLASS_0_READ} and contains no
 *       {@code bash -lc} and no write command.</li>
 *   <li>{@code StaticRoutePreviewJobExecutor} dispatches only the closed {@link ReadGate} read literals and never sends
 *       compiled preview steps to any transport.</li>
 *   <li>{@code mutationPossible} is structurally fenced to {@code false} in both {@link PreviewPlanSet},
 *       {@link PreviewRow}, and the V252 database constraint.</li>
 *   <li>No static-route write/approve/execute/rollback HTTP route or ActionRegistry action exists in R0.</li>
 * </ul>
 */
class StaticRouteR0FenceTest {

    private static final List<String> FORBIDDEN_WRITE_LITERALS = List.of(
            "set static-route",
            "save config",
            "action=set",
            "action=delete",
            "type=commit",
            "<revert><config/></revert>",
            "configure terminal",
            "no route ",
            "write memory",
            "write standby",
            "config router static");

    private static final Set<String> FORBIDDEN_WRITE_GATE_IDS = Set.of(
            "cp_route_set_static_route_on",
            "cp_route_set_static_route_comment",
            "cp_route_set_static_route_nexthop_off",
            "cp_route_save_config",
            "pan_route_set_vr_static_route",
            "pan_route_delete_vr_static_route",
            "pan_route_revert_config",
            "pan_route_commit_partial_admin",
            "pan_route_show_job_id",
            "asa_route_add_static",
            "asa_route_delete_static",
            "asa_route_write_memory",
            "fgt_route_check_central_mgmt_and_vdoms",
            "fgt_route_show_router_static",
            "fgt_route_add_static_edit0",
            "fgt_route_delete_static_seq");

    @Test
    void noStaticRouteWriteLiteralOrWriteGateIsReachableInRegistryOrReadGateEnum() throws Exception {
        List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(
                getClass().getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();

        // 1. None of the candidate R1/R2 write or FortiGate gate IDs exist in the registry fixture
        for (GateRow entry : rows) {
            assertFalse(FORBIDDEN_WRITE_GATE_IDS.contains(entry.gateId()),
                    "Forbidden write/FGT gate ID present in fixture: " + entry.gateId());
            String cmd = entry.canonicalCommandKey();
            for (String writeLiteral : FORBIDDEN_WRITE_LITERALS) {
                assertFalse(cmd.contains(writeLiteral),
                        "Forbidden static-route write literal '" + writeLiteral + "' present in gate " + entry.gateId());
            }
        }

        // 2. Every ReadGate in StaticRoutePreview resolves as CLASS_0_READ and contains no write literal or bash -lc
        assertEquals(16, ReadGate.values().length);
        for (ReadGate gate : ReadGate.values()) {
            var resolved = StaticRoutePreview.resolve(gate, registry);
            assertTrue(resolved.isPresent(), "ReadGate did not resolve: " + gate);
            assertEquals(ActionClass.CLASS_0_READ, resolved.get().actionClass());
            assertFalse(gate.template().contains("bash -lc"), "bash -lc is forbidden: " + gate);
            for (String writeLiteral : FORBIDDEN_WRITE_LITERALS) {
                assertFalse(gate.template().contains(writeLiteral),
                        "Write literal in ReadGate " + gate + ": " + writeLiteral);
            }
        }

        // 3. The 7 new R0 gates are excluded from ad-hoc diagnostic_read
        for (String gateId : StaticRoutePreview.NEW_R0_GATE_IDS) {
            GateRow entry = rows.stream().filter(r -> gateId.equals(r.gateId())).findFirst().orElseThrow();
            assertTrue(DiagnosticRead.resolve(
                    entry.vendor(), "gateway", entry.platformRoleScope(), gateId, "192.0.2.1", registry).isEmpty(),
                    "R0 gate must not be runnable through ad-hoc diagnostic_read: " + gateId);
        }
    }

    @Test
    void workerExecutorAndMigrationEnforceReadOnlyAndMutationPossibleFalse() throws Exception {
        String executorSource = Files.readString(Path.of(
                "../worker/src/main/java/com/securityexpert/nexus/ui2/worker/staticroute/StaticRoutePreviewJobExecutor.java"));

        // Worker never calls PAN config with action "set", "delete", "edit", or "get", and never calls type "commit"
        assertFalse(executorSource.contains("\"set\""));
        assertFalse(executorSource.contains("\"delete\""));
        assertFalse(executorSource.contains("\"commit\""));
        assertTrue(executorSource.contains("new XmlApiSpec(\"POST\", \"config\", \"show\""));
        assertTrue(executorSource.contains("attempts.insertPreContact(jobId, epoch, step, gate.gateId(), \"read\", 1)"));

        // V252 SQL enforces mutation_possible = FALSE via CHECK constraint and registers only read gates
        String v251Sql = Files.readString(Path.of(
                "../service/src/main/resources/db/migration/V252__static_route_r0_preview.sql"));
        assertTrue(v251Sql.contains("CHECK (mutation_possible = FALSE)"));
        assertFalse(v251Sql.contains("configuration-write"));
        for (String forbiddenGate : FORBIDDEN_WRITE_GATE_IDS) {
            assertFalse(v251Sql.contains(forbiddenGate), "Forbidden gate in V252: " + forbiddenGate);
        }

        // PreviewPlanSet and PreviewRow reject mutationPossible == true at construction time
        assertThrows(IllegalArgumentException.class, () ->
                new PreviewPlanSet("R0", "digest", true, List.of(), List.of(), List.of(), List.of(), "note"));
        assertThrows(IllegalArgumentException.class, () ->
                new PreviewRow("id", "idem", "hash", "actor", Instant.now(), "u", "d", "check_point", "gaia-default",
                        "ADD", "192.0.2.0/24", "192.0.2.1", Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), "192.0.2.1", Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), "DONE", "PASS", Optional.empty(),
                        true, Optional.empty(), "[]", Optional.empty(), "{}", "[]", Optional.empty()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void serviceExposesOnlyPreviewAndReadRoutesWithZeroStaticRouteWriteRoutes() throws Exception {
        var field = SecurityWebMvcConfig.class.getDeclaredField("ACTION_ID_BY_ROUTE");
        field.setAccessible(true);
        var routes = (Map<String, String>) field.get(null);

        List<String> staticRouteMappings = routes.keySet().stream()
                .filter(k -> k.contains("static-route"))
                .sorted()
                .toList();
        assertEquals(List.of(
                "GET /api/v2/operations/static-route/preview",
                "GET /api/v2/operations/static-route/preview/*",
                "POST /api/v2/operations/static-route/preview"), staticRouteMappings);

        var actions = new ActionRegistry();
        assertTrue(actions.find(ActionRegistry.STATIC_ROUTE_PREVIEW).isPresent());
        assertTrue(actions.find(ActionRegistry.STATIC_ROUTE_READ).isPresent());
        for (String nonExistentWriteAction : List.of(
                "static_route_request", "static_route_approve", "static_route_execute",
                "static_route_rollback", "static_route_kill_switch_off", "static_route_kill_switch_on")) {
            assertTrue(actions.find(nonExistentWriteAction).isEmpty(),
                    "Write action must not exist in Phase R0: " + nonExistentWriteAction);
        }
    }
}
