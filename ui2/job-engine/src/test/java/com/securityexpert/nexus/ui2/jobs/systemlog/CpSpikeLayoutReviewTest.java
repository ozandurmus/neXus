package com.securityexpert.nexus.ui2.jobs.systemlog;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;

/**
 * The layout review issues only the two approved V160 rows C4 and C5 (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md §10.1),
 * byte for byte, and C5 only for a safe directory name.
 */
class CpSpikeLayoutReviewTest {
    private final List<GateRow> rows = GateRegistryFixtureLoader.loadFromStream(getClass().getClassLoader()
            .getResourceAsStream("capabilities/gate_registry_fixture.yaml")).stream().toList();
    private final GateRegistryPort gates = key -> rows.stream().filter(r -> r.key().equals(key)).toList();

    private GateRow row(String gateId) {
        return rows.stream().filter(r -> r.gateId().equals(gateId)).findFirst().orElseThrow();
    }

    @Test
    void bothCommandsAreByteIdenticalToTheirFixtureRowsAndResolveToThem() {
        GateRow c4 = row(CpSpikeLayoutReview.LS_SPIKE_GATE);
        GateRow c5 = row(CpSpikeLayoutReview.LS_SPIKE_DIR_GATE);
        assertEquals(c4.canonicalCommandKey(), CpSpikeLayoutReview.LS_SPIKE_COMMAND);
        assertEquals(c5.canonicalCommandKey(), CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE);
        assertEquals(c4.shellContext(), CpSpikeLayoutReview.LS_SPIKE_SHELL);
        assertEquals(c5.shellContext(), CpSpikeLayoutReview.LS_SPIKE_DIR_SHELL);
        assertEquals("none", c4.retryRule());
        assertEquals("none", c5.retryRule());
        assertEquals(30, CpSpikeLayoutReview.listingGate(gates).orElseThrow().timeoutS());
        assertEquals(30, CpSpikeLayoutReview.directoryGate(gates).orElseThrow().timeoutS());
        assertEquals(CpSpikeLayoutReview.LS_SPIKE_GATE, CpSpikeLayoutReview.listingGate(gates).orElseThrow().gateId());
        assertEquals(CpSpikeLayoutReview.LS_SPIKE_DIR_GATE, CpSpikeLayoutReview.directoryGate(gates).orElseThrow().gateId());
        assertTrue(CpSpikeLayoutReview.capability(gates).executionEligible());
        // The V157 measurement row carries the same C4 text under another shell context; it never authorizes the review.
        GateRegistryPort measurementOnly = key -> rows.stream()
                .filter(r -> r.key().equals(key) && r.gateId().startsWith("cp_syslog_measure")).toList();
        assertTrue(CpSpikeLayoutReview.listingGate(measurementOnly).isEmpty());
        assertTrue(CpSpikeLayoutReview.directoryGate(measurementOnly).isEmpty());
        assertFalse(CpSpikeLayoutReview.capability(measurementOnly).executionEligible());
        assertFalse(CpSpikeLayoutReview.capability(key -> List.of()).executionEligible());
    }

    @Test
    void bothCommandsAreByteIdenticalToTheV160Migration() throws Exception {
        String sql = Files.readString(migration("V160__system_log_phase_b.sql"));
        for (String[] pair : List.of(new String[] {CpSpikeLayoutReview.LS_SPIKE_GATE, CpSpikeLayoutReview.LS_SPIKE_COMMAND},
                new String[] {CpSpikeLayoutReview.LS_SPIKE_DIR_GATE, CpSpikeLayoutReview.LS_SPIKE_DIR_TEMPLATE})) {
            String expected = "('" + pair[0] + "', 'check_point', 'cp_gaia_gateway', '"
                    + (pair[0].equals(CpSpikeLayoutReview.LS_SPIKE_GATE) ? "expert_collect" : "expert") + "', 'SSH_EXEC', '"
                    + pair[1].replace("'", "''") + "', 'read', 'SIGNED_OFF', 30, 'none',";
            assertTrue(sql.contains(expected), pair[0] + " is seeded in V160 with exactly this command text");
        }
    }

    @Test
    void theDirectoryCommandIsTheTemplateWithOneSafeName() {
        assertEquals(Optional.of("bash -lc 'ls -lgo --time-style=+%s /var/log/spike_detective/sd_20261019_0800/'"),
                CpSpikeLayoutReview.directoryCommand("sd_20261019_0800"));
        assertTrue(CpSpikeLayoutReview.directoryCommand("x".repeat(128)).isPresent());
        for (String unsafe : new String[] {null, "", ".", "..", "x".repeat(129), "a/b", "a b", "a'b", "a;b", "a$b", "a\nb",
                "a`b", "a*b", "a\\b"})
            assertTrue(CpSpikeLayoutReview.directoryCommand(unsafe).isEmpty(), String.valueOf(unsafe));
    }

    private static Path migration(String name) {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("service/src/main/resources/db/migration").resolve(name);
            if (Files.exists(candidate)) return candidate;
            candidate = dir.resolve("ui2/service/src/main/resources/db/migration").resolve(name);
            if (Files.exists(candidate)) return candidate;
        }
        throw new AssertionError("migration not found: " + name);
    }
}
