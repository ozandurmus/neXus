package com.securityexpert.nexus.ui2.jobs.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundle.Exec;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle step S2 (PO approval 2026-11-15): exactly the approved Check Point literals are gated, the fixture and
 * V230 agree, the two later-approved cpdata_collector rows exist as gate rows only, and nothing executes them.
 */
class CpSupportBundleGateTest {

    private static final String P = CpSupportBundle.CP_ENV;
    private static final String LIST = P + "cpdata_collector --list";
    private static final String RUN = P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"timeout 1800 cpdata_collector --run "
            + "-p %1$s/cpsdc.tgz -t 3 -m 100 > %1$s/cpsdc.log 2>&1; echo \\$? > %1$s/cpsdc.rc\" </dev/null >/dev/null 2>&1 &";

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(CpSupportBundleGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRow row(String id) {
        return fixture().stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test
    void theEightLightReadsAreSignedOffClassZeroAndExact() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertEquals(7, Exec.values().length);
        for (Exec exec : Exec.values()) {
            GateRow r = row(exec.gateId());
            assertEquals(exec.command(), r.canonicalCommandKey(), exec.gateId());
            assertTrue(exec.command().startsWith(P), exec.gateId());
            assertFalse(exec.command().contains("bash -lc"), exec.gateId());
            assertEquals("check_point", r.vendor());
            assertEquals("cp_gaia_gateway", r.platformRoleScope());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass(), exec.gateId());
            assertEquals(SignOffState.SIGNED_OFF, r.signOffState(), exec.gateId());
            assertEquals("none", r.retryRule());
            assertTrue(CpSupportBundle.gate(exec, registry).isPresent(), exec.gateId());
        }
        assertEquals(30, row("cp_sb_cpdata_collector_present").timeoutS());
        assertEquals(60, row("cp_sb_show_asset_all").timeoutS());
        assertEquals(300, row("cp_sb_sftp_get_hcp").timeoutS());
        assertEquals(CpSupportBundle.SFTP_KEY, row(CpSupportBundle.SFTP_GATE).canonicalCommandKey());
        assertTrue(CpSupportBundle.sftpGate(registry).isPresent());
    }

    @Test
    void theApprovedLiteralsAreTheDesignLiterals() {
        assertEquals(P + "command -v cpdata_collector", Exec.CPDATA_PRESENT.command());
        assertEquals(P + "cpstat os -f multi_cpu", Exec.CPU_MULTI.command());
        assertEquals(P + "df -P /var/log", Exec.DF_VAR_LOG.command());
        assertEquals(P + "clish -c 'show asset all'", Exec.SHOW_ASSET_ALL.command());
        assertEquals(P + "clish -c 'show version all'", Exec.SHOW_VERSION_ALL.command());
        assertEquals(P + "cphaprob stat", Exec.CPHAPROB_STAT.command());
        assertEquals(P + "ls -lgo --time-style=+%s /var/log/hcp/last/", Exec.LS_HCP_LAST.command());
    }

    @Test
    void theCpdataCollectorRowsAreApprovedGateRowsOnly() {
        GateRow list = row(CpSupportBundle.CPDATA_LIST_GATE);
        assertEquals(LIST, list.canonicalCommandKey());
        assertEquals(ActionClass.CLASS_0_READ, list.actionClass());
        assertEquals(60, list.timeoutS());
        assertEquals(SignOffState.SIGNED_OFF, list.signOffState());
        GateRow run = row(CpSupportBundle.CPDATA_RUN_GATE);
        assertEquals(RUN, run.canonicalCommandKey());
        assertEquals(SignOffState.SIGNED_OFF, run.signOffState());
        assertEquals("none", run.retryRule());
        assertFalse(run.canonicalCommandKey().contains("bash -lc"));
    }

    @Test
    void v230SeedsTheSameRowsAsTheFixture() throws IOException {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V230__support_bundle_collection.sql"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        for (GateRow r : fixture()) {
            if (!r.gateId().startsWith("cp_sb_")) continue;
            assertTrue(sql.contains("('" + r.gateId() + "', '" + r.vendor() + "', '" + r.platformRoleScope() + "', '" + r.shellContext()
                    + "', '" + r.transportKind() + "',\n     '" + r.canonicalCommandKey().replace("'", "''") + "',"), r.gateId());
        }
        assertEquals(10, fixture().stream().filter(r -> r.gateId().startsWith("cp_sb_")).count());
    }

    @Test
    void noMainCodeExecutesTheCpdataCollectorRows() throws IOException {
        for (String module : List.of("../worker/src/main", "../job-engine/src/main", "../service/src/main/java")) {
            try (Stream<Path> files = Files.walk(Path.of(module))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    if (file.getFileName().toString().equals("CpSupportBundle.java")) continue;
                    String text = Files.readString(file);
                    assertFalse(text.contains("cpdata_collector --run") || text.contains("CPDATA_RUN_GATE")
                            || text.contains("cp_sb_cpdata_collector_run") || text.contains("CPDATA_LIST_GATE"), file.toString());
                }
            }
        }
    }

    @Test
    void theCapabilityIsExecutionEligibleOverTheLightReads() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        var capability = CpSupportBundle.capability(registry);
        assertTrue(capability.executionEligible());
    }
}
