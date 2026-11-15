package com.securityexpert.nexus.ui2.jobs.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.jobs.supportbundle.CpSupportBundleHeavy.Step;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle step S6 (PO approval 2026-11-15): exactly the approved Check Point heavy literals C8-C20 and the two
 * cpdata_collector literals are gated, under the gateway and the management scope; the fixture and V234 agree; every literal
 * carries CP_ENV; the write literals are recovery-write (BW-2) and the reads are class 0.
 */
class CpSupportBundleHeavyGateTest {
    private static final String P = CpSupportBundle.CP_ENV;

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(CpSupportBundleHeavyGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static GateRegistryPort registry() {
        List<GateRow> rows = fixture();
        return key -> rows.stream().filter(r -> r.key().equals(key)).toList();
    }

    private static GateRow row(String id) {
        return fixture().stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test
    void theApprovedLiteralsAreTheDesignLiteralsWithTheEnvironmentPrefix() {
        assertEquals(P + "mkdir -p %s", Step.MKDIR.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"hcp -r all --include-wts yes -s > %1$s/hcp.log 2>&1; echo \\$? > %1$s/hcp.rc\" </dev/null >/dev/null 2>&1 &",
                Step.HCP_START.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"hcp -r all --include-wts yes -s -c <VSID> > %1$s/hcp.log 2>&1; echo \\$? > %1$s/hcp.rc\" </dev/null >/dev/null 2>&1 &",
                Step.HCP_START_VS.template());
        assertEquals(P + "cp /var/log/hcp/last/<listed name> %s/", Step.COPY_HCP.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"cpinfo -z -D -d -S -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &",
                Step.CPINFO_START.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"cpinfo -z -D -d -S -x -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &",
                Step.CPINFO_START_SMS.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"vsenv <VSID> >/dev/null 2>&1 && cpinfo -z -D -d -S -o %1$s/cpinfo > %1$s/cpinfo.log 2>&1; echo \\$? > %1$s/cpinfo.rc\" </dev/null >/dev/null 2>&1 &",
                Step.CPINFO_START_VS.template());
        assertEquals(P + "cat %s/<hcp|cpinfo>.rc", Step.POLL_RC.template());
        assertEquals(P + "ls -l %s", Step.LIST_WORKDIR.template());
        assertEquals(P + "cd %1$s && tar -czf %1$s.tgz .", Step.BUNDLE.template());
        assertEquals(P + "sha256sum %s.tgz", Step.SHA256.template());
        assertEquals(P + "rm -rf %1$s %1$s.tgz", Step.REMOVE.template());
        assertEquals(P + "cpdata_collector --list", Step.CPDATA_LIST.template());
        assertEquals(P + "cd %1$s && setsid nohup bash --noprofile --norc -c \"timeout 1800 cpdata_collector --run -p %1$s/cpsdc.tgz -t 3 -m 100 > %1$s/cpsdc.log 2>&1; echo \\$? > %1$s/cpsdc.rc\" </dev/null >/dev/null 2>&1 &",
                Step.CPDATA_RUN.template());
        assertEquals(P + "cat %s/cpsdc.rc", Step.POLL_CPSDC.template());
        assertEquals("sftp get of %s.tgz", CpSupportBundleHeavy.SFTP_KEY);
        for (Step step : Step.values()) {
            assertTrue(step.template().startsWith(P), step.name());
            assertFalse(step.template().contains("bash -lc"), step.name());
        }
    }

    @Test
    void everyRowIsSignedOffWithTheRightClassUnderBothScopesAndResolves() {
        GateRegistryPort registry = registry();
        for (String scope : List.of(CpSupportBundle.PLATFORM_ROLE_SCOPE, CpSupportBundle.MANAGEMENT_SCOPE)) {
            assertTrue(CpSupportBundleHeavy.allGatesKnown(scope, registry), scope);
            for (Step step : Step.values()) {
                if (!step.onScope(scope)) {
                    assertTrue(fixture().stream().noneMatch(r -> r.gateId().equals(step.gateId(scope))), step.gateId(scope));
                    continue;
                }
                GateRow r = row(step.gateId(scope));
                assertEquals(step.template(), r.canonicalCommandKey(), step.gateId(scope));
                assertEquals(scope, r.platformRoleScope());
                assertEquals(step.actionClass(), r.actionClass(), step.gateId(scope));
                assertEquals(step.timeoutS(), r.timeoutS(), step.gateId(scope));
                assertEquals(SignOffState.SIGNED_OFF, r.signOffState(), step.gateId(scope));
                assertEquals("none", r.retryRule());
                assertTrue(step.gate(scope, registry).isPresent(), step.gateId(scope));
            }
            GateRow sftp = row(CpSupportBundleHeavy.sftpGateId(scope));
            assertEquals("sftp get of %s.tgz", sftp.canonicalCommandKey());
            assertEquals("SFTP_GET", sftp.transportKind());
            assertEquals(ActionClass.CLASS_0_READ, sftp.actionClass());
            assertEquals(7200, sftp.timeoutS());
        }
        // the per virtual system rows exist for a gateway only
        assertFalse(Step.HCP_START_VS.onScope(CpSupportBundle.MANAGEMENT_SCOPE));
        assertFalse(Step.CPINFO_START_VS.onScope(CpSupportBundle.MANAGEMENT_SCOPE));
        // the write rows are BW-2 recovery-write, the polls, listings and digests are reads
        for (Step step : List.of(Step.MKDIR, Step.HCP_START, Step.HCP_START_VS, Step.COPY_HCP, Step.CPINFO_START, Step.CPINFO_START_SMS,
                Step.CPINFO_START_VS, Step.BUNDLE, Step.REMOVE, Step.CPDATA_RUN)) assertEquals(ActionClass.CLASS_1_RECOVERY_WRITE, step.actionClass(), step.name());
        for (Step step : List.of(Step.POLL_RC, Step.POLL_CPSDC, Step.LIST_WORKDIR, Step.SHA256, Step.CPDATA_LIST)) assertEquals(ActionClass.CLASS_0_READ, step.actionClass(), step.name());
        assertTrue(row("cp_sb_cpinfo_start").secretOutputRisk().contains("secret-bearing"));
        assertTrue(row("cp_sb_bundle").secretOutputRisk().contains("secret-bearing"));
        assertTrue(row("cp_sb_sftp_get_bundle").secretOutputRisk().contains("secret-bearing"));
    }

    @Test
    void theManagementScopeReadsAreTheApprovedC0C1C2C6Literals() {
        GateRegistryPort registry = registry();
        for (CpSupportBundle.Exec exec : CpSupportBundle.Exec.values()) {
            if (!exec.onManagement()) {
                assertTrue(CpSupportBundle.gate(exec, registry, CpSupportBundle.MANAGEMENT_SCOPE).isEmpty(), exec.name());
                continue;
            }
            assertTrue(CpSupportBundle.gate(exec, registry, CpSupportBundle.MANAGEMENT_SCOPE).isPresent(), exec.name());
            assertEquals(exec.command(), row(exec.gateId(CpSupportBundle.MANAGEMENT_SCOPE)).canonicalCommandKey());
        }
    }

    @Test
    void renderingFillsOnlyValidatedValues() {
        String dir = CpSupportBundleHeavy.workDir("11111111-2222-3333-4444-555555555555", 4);
        assertTrue(CpSupportBundleHeavy.WORK_DIR.matcher(dir).matches(), dir);
        assertEquals(dir, CpSupportBundleHeavy.workDir("11111111-2222-3333-4444-555555555555", 4));
        assertFalse(dir.equals(CpSupportBundleHeavy.workDir("11111111-2222-3333-4444-555555555555", 5)));
        assertEquals(P + "rm -rf " + dir + " " + dir + ".tgz", CpSupportBundleHeavy.render(Step.REMOVE, dir, null, null));
        assertTrue(CpSupportBundleHeavy.render(Step.HCP_START_VS, dir, "0012", null).contains("-s -c 12 >"));
        assertTrue(CpSupportBundleHeavy.render(Step.CPINFO_START_VS, dir, "4095", null).contains("vsenv 4095 >/dev/null 2>&1 && cpinfo"));
        assertEquals(P + "cat " + dir + "/hcp.rc", CpSupportBundleHeavy.renderPoll(dir, "hcp"));
        assertEquals(P + "cp /var/log/hcp/last/hcp_report_fwtango04_15_11_26_03_00.tar.gz " + dir + "/",
                CpSupportBundleHeavy.render(Step.COPY_HCP, dir, null, "hcp_report_fwtango04_15_11_26_03_00.tar.gz"));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.MKDIR, "/var/log/nexus-sb-abc; rm -rf /", null, null));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.MKDIR, "/tmp/x", null, null));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.HCP_START_VS, dir, "4096", null));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.HCP_START_VS, dir, "1; id", null));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.HCP_START_VS, dir, null, null));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.COPY_HCP, dir, null, "x; rm -rf /"));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.render(Step.COPY_HCP, dir, null, "../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> CpSupportBundleHeavy.renderPoll(dir, "cpsdc"));
        assertTrue(CpSupportBundleHeavy.validVsId("0") && CpSupportBundleHeavy.validVsId("4095"));
        assertFalse(CpSupportBundleHeavy.validVsId("4096") || CpSupportBundleHeavy.validVsId("-1") || CpSupportBundleHeavy.validVsId(""));
    }

    @Test
    void v234SeedsTheSameRowsAsTheFixtureAndEarlierMigrationsAreUntouched() throws IOException {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V234__support_bundle_cp_heavy.sql"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        List<GateRow> s6 = fixture().stream().filter(r -> !r.gateId().endsWith("_poll_cpsdc")).filter(r -> r.gateId().startsWith("cpm_sb_") || (r.gateId().startsWith("cp_sb_")
                && List.of("mkdir", "hcp_start", "hcp_start_vs", "copy_hcp", "cpinfo_start", "cpinfo_start_sms", "cpinfo_start_vs", "poll_rc",
                        "list_workdir", "bundle", "sha256", "sftp_get_bundle", "remove_workdir").contains(r.gateId().substring(6)))).toList();
        assertEquals(30, s6.size());
        for (GateRow r : s6) {
            assertTrue(sql.contains("('" + r.gateId() + "', 'check_point', '" + r.platformRoleScope() + "', '" + r.shellContext()
                    + "', '" + r.transportKind() + "',\n     '" + r.canonicalCommandKey().replace("'", "''") + "',\n     '"
                    + r.actionClass().id() + "', 'SIGNED_OFF', " + r.timeoutS() + ", 'none',"), r.gateId());
        }
        assertEquals(30, sql.split("'SIGNED_OFF'", -1).length - 1);
        String v230 = Files.readString(Path.of("../service/src/main/resources/db/migration/V230__support_bundle_collection.sql"));
        String v232 = Files.readString(Path.of("../service/src/main/resources/db/migration/V232__support_bundle_vendor_gates.sql"));
        for (GateRow r : s6) {
            assertFalse(v230.contains("('" + r.gateId() + "'"), r.gateId());
            assertFalse(v232.contains("('" + r.gateId() + "'"), r.gateId());
        }
        assertTrue(sql.contains("'cpdata_collector_run'"));
    }

    @Test
    void v235SeedsTheApprovedCpsdcPollForBothScopesAndNothingElse() throws IOException {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V235__support_bundle_cpsdc_poll.sql"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        assertEquals(2, sql.split("'SIGNED_OFF'", -1).length - 1);
        for (String id : List.of("cp_sb_poll_cpsdc", "cpm_sb_poll_cpsdc")) {
            GateRow r = row(id);
            assertEquals(P + "cat %s/cpsdc.rc", r.canonicalCommandKey());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass());
            assertEquals(30, r.timeoutS());
            assertTrue(sql.contains("('" + id + "', 'check_point', '" + r.platformRoleScope() + "', 'expert', 'SSH_EXEC',\n     '"
                    + r.canonicalCommandKey() + "',\n     'read', 'SIGNED_OFF', 30, 'none',"), id);
        }
        // the log tail literal was not approved
        assertFalse(sql.contains("cpsdc.log"));
    }

    @Test
    void theClassFloorIsTheMoreSevereClass() {
        assertEquals(ActionClass.CLASS_1_RECOVERY_WRITE, com.securityexpert.nexus.ui2.capability.Capability.moreSevere(ActionClass.CLASS_0_READ, ActionClass.CLASS_1_RECOVERY_WRITE));
        assertEquals(ActionClass.CLASS_1_RECOVERY_WRITE, com.securityexpert.nexus.ui2.capability.Capability.moreSevere(ActionClass.CLASS_1_RECOVERY_WRITE, null));
        assertEquals(ActionClass.CLASS_0_READ, com.securityexpert.nexus.ui2.capability.Capability.moreSevere(ActionClass.CLASS_0_READ, null));
    }
}
