package com.securityexpert.nexus.ui2.jobs.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.platform.ActionClass;

/**
 * Support bundle steps S3-S5 (PO approval 2026-11-15): exactly the approved Palo Alto, Cisco ASA and FortiGate literals are
 * gated, the fixture and V232 agree, the unapproved ASA fallback A5-A7 has no row, and {@code terminal pager 0} is nowhere.
 */
class SupportBundleVendorGateTest {

    private static List<GateRow> fixture() {
        return GateRegistryFixtureLoader.loadFromStream(SupportBundleVendorGateTest.class.getClassLoader()
                .getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    }

    private static List<GateRow> vendorRows() {
        return fixture().stream().filter(r -> r.gateId().startsWith("pan_sb_") || r.gateId().startsWith("panorama_sb_")
                || r.gateId().startsWith("asa_sb_") || r.gateId().startsWith("fgt_sb_")).toList();
    }

    private static GateRow row(String id) {
        return fixture().stream().filter(r -> r.gateId().equals(id)).findFirst().orElseThrow(() -> new AssertionError(id));
    }

    @Test
    void thereAreExactlyTheApprovedNewRows() {
        assertEquals(16, vendorRows().size());
        assertEquals(12, vendorRows().stream().filter(r -> r.vendor().equals("palo_alto")).count());
        assertEquals(4, vendorRows().stream().filter(r -> r.vendor().equals("cisco_asa")).count());
    }

    @Test
    void thePaloAltoLiteralsAreTheDesignLiteralsForBothScopesAndEveryRowResolves() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertEquals("type=op&cmd=<show><system><resources></resources></system></show>", PanSupportBundle.Cmd.SYSTEM_RESOURCES.command());
        assertEquals("type=op&cmd=<show><system><disk-space></disk-space></system></show>", PanSupportBundle.Cmd.DISK_SPACE.command());
        assertEquals("type=export&category=tech-support", PanSupportBundle.Cmd.TSF_START.command());
        assertEquals("type=export&category=tech-support&action=status&job-id=<n>", PanSupportBundle.Cmd.TSF_STATUS.command());
        assertEquals("type=export&category=tech-support&action=get&job-id=<n>", PanSupportBundle.Cmd.TSF_GET.command());
        assertEquals("type=export&category=tech-support&action=finish&job-id=<n>", PanSupportBundle.Cmd.TSF_FINISH.command());
        int[] timeouts = {30, 30, 60, 30, 1800, 30};
        for (String scope : PanSupportBundle.platformScopes()) {
            assertTrue(PanSupportBundle.allGatesKnown(scope, registry), scope);
            int i = 0;
            for (PanSupportBundle.Cmd cmd : PanSupportBundle.Cmd.values()) {
                GateRow r = row(cmd.gateId(scope));
                assertEquals(cmd.command(), r.canonicalCommandKey());
                assertEquals("palo_alto", r.vendor());
                assertEquals(scope, r.platformRoleScope());
                assertEquals("not_applicable", r.shellContext());
                assertEquals("PAN_XML_API", r.transportKind());
                assertEquals(SignOffState.SIGNED_OFF, r.signOffState());
                assertEquals(cmd.actionClass(), r.actionClass(), cmd.gateId(scope));
                assertEquals(timeouts[i++], r.timeoutS(), cmd.gateId(scope));
                assertEquals("none", r.retryRule());
            }
        }
        assertEquals(ActionClass.CLASS_1_RECOVERY_WRITE, PanSupportBundle.Cmd.TSF_START.actionClass());
        assertEquals(ActionClass.CLASS_1_RECOVERY_WRITE, PanSupportBundle.Cmd.TSF_FINISH.actionClass());
        assertTrue(row("pan_sb_tsf_start").secretOutputRisk().contains("secret-bearing"));
        assertTrue(row("pan_sb_tsf_get").secretOutputRisk().contains("secret-bearing"));
        assertFalse(row("pan_sb_tsf_status").secretOutputRisk().contains("secret-bearing"));
        assertEquals("pan_sb_tsf_status", PanSupportBundle.Cmd.TSF_STATUS.gateId("pan_firewall"));
        assertEquals("panorama_sb_tsf_status", PanSupportBundle.Cmd.TSF_STATUS.gateId("panorama"));
    }

    @Test
    void aPanGateOfTheWrongScopeOrClassDoesNotResolve() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertTrue(PanSupportBundle.gate(PanSupportBundle.Cmd.TSF_GET, "pan_firewall", registry).isPresent());
        assertTrue(PanSupportBundle.gate(PanSupportBundle.Cmd.TSF_GET, "pan_gateway", registry).isEmpty());
        List<GateRow> without = rows.stream().filter(r -> !r.gateId().equals("pan_sb_tsf_get")).toList();
        assertTrue(PanSupportBundle.gate(PanSupportBundle.Cmd.TSF_GET, "pan_firewall", k -> without.stream().filter(r -> r.key().equals(k)).toList()).isEmpty());
    }

    @Test
    void theCiscoAsaLiteralsAreA1ToA4AndNothingElse() {
        List<GateRow> rows = fixture();
        GateRegistryPort registry = key -> rows.stream().filter(r -> r.key().equals(key)).toList();
        assertEquals(List.of("show cpu usage", "changeto system", "show crashinfo", "show tech-support"),
                java.util.Arrays.stream(AsaSupportBundle.Cmd.values()).map(AsaSupportBundle.Cmd::command).toList());
        assertTrue(AsaSupportBundle.allGatesKnown(registry));
        int[] timeouts = {30, 10, 120, 1200};
        int i = 0;
        for (AsaSupportBundle.Cmd cmd : AsaSupportBundle.Cmd.values()) {
            GateRow r = row(cmd.gateId());
            assertEquals(cmd.command(), r.canonicalCommandKey());
            assertEquals("cisco_asa", r.vendor());
            assertEquals("cisco_asa_firewall", r.platformRoleScope());
            assertEquals("SSH_INTERACTIVE", r.transportKind());
            assertEquals(ActionClass.CLASS_0_READ, r.actionClass());
            assertEquals(timeouts[i++], r.timeoutS());
            assertEquals(SignOffState.SIGNED_OFF, r.signOffState());
        }
        assertTrue(row("asa_sb_show_tech_support").secretOutputRisk().contains("secret-bearing"));
        assertEquals("<--- More --->", AsaSupportBundle.PAGER_MARKER);
        assertEquals(4, vendorRows().stream().filter(r -> r.gateId().startsWith("asa_sb_")).count(), "A5-A7 are not approved");
        for (GateRow r : fixture()) {
            assertFalse(r.gateId().startsWith("asa_sb_") && (r.canonicalCommandKey().contains("disk0") || r.canonicalCommandKey().contains("delete")
                    || r.canonicalCommandKey().contains("file") || r.canonicalCommandKey().contains("scp")), r.gateId());
        }
    }

    @Test
    void noSupportBundleRowOrSourceSendsTerminalPager() throws IOException {
        for (GateRow r : vendorRows()) assertFalse(r.canonicalCommandKey().contains("pager"), r.gateId());
        for (String module : List.of("../worker/src/main/java/com/securityexpert/nexus/ui2/worker/supportbundle", "../job-engine/src/main/java/com/securityexpert/nexus/ui2/jobs/supportbundle")) {
            try (Stream<Path> files = Files.walk(Path.of(module))) {
                for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                    String text = Files.readString(file);
                    assertFalse(text.contains("terminal pager") && !text.contains("never") && !text.contains("No {@code terminal pager"), file.toString());
                    assertFalse(text.contains("\"terminal pager 0\"") || text.contains("TERMINAL_PAGER_0"), file.toString());
                }
            }
        }
    }

    @Test
    void v232SeedsTheSameRowsAsTheFixtureAndV230IsNotTheirHome() throws IOException {
        String sql = Files.readString(Path.of("../service/src/main/resources/db/migration/V232__support_bundle_vendor_gates.sql"));
        assertTrue(sql.contains("set_config('app.actor_fingerprint'") && sql.contains("ON CONFLICT (gate_id) DO NOTHING"));
        for (GateRow r : vendorRows()) {
            assertTrue(sql.contains("('" + r.gateId() + "', '" + r.vendor() + "', '" + r.platformRoleScope() + "', '" + r.shellContext()
                    + "', '" + r.transportKind() + "',\n     '" + r.canonicalCommandKey().replace("'", "''") + "',\n     '"
                    + r.actionClass().id() + "', 'SIGNED_OFF', " + r.timeoutS() + ", 'none',"), r.gateId());
        }
        assertEquals(16, sql.split("'SIGNED_OFF'", -1).length - 1);
        String v230 = Files.readString(Path.of("../service/src/main/resources/db/migration/V230__support_bundle_collection.sql"));
        for (GateRow r : vendorRows()) assertFalse(v230.contains("'" + r.gateId() + "'"), r.gateId());
    }

    @Test
    void theVendorKindsAreEnabledAndTickedAsQ7Recommends() {
        for (SupportBundleKind kind : List.of(SupportBundleKind.PAN_TSF, SupportBundleKind.ASA_CRASHINFO, SupportBundleKind.ASA_SHOW_TECH)) {
            assertTrue(kind.enabled(), kind.id());
            assertTrue(kind.defaultTicked(), kind.id());
            assertFalse(kind.heavy(), kind.id());
        }
        assertFalse(SupportBundleKind.FGT_TAC_REPORT.enabled());
        assertFalse(SupportBundleKind.CP_CPINFO.enabled());
        assertFalse(SupportBundleKind.CP_HCP_RUN.enabled());
        assertFalse(SupportBundleKind.CP_CPINFO.defaultTicked());
    }

    @Test
    void theJobNumberPatternAcceptsDigitsOnly() {
        assertTrue(PanSupportBundle.JOB_ID.matcher("42").matches());
        assertTrue(PanSupportBundle.JOB_ID.matcher("123456789012").matches());
        assertFalse(PanSupportBundle.JOB_ID.matcher("1234567890123").matches());
        assertFalse(PanSupportBundle.JOB_ID.matcher("4x2").matches());
        assertFalse(PanSupportBundle.JOB_ID.matcher("42&action=finish").matches());
        assertFalse(PanSupportBundle.JOB_ID.matcher("").matches());
    }
}
