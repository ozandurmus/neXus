package com.securityexpert.nexus.ui2.worker.staticroute;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.BeforePathObservation;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreCheckVerdict;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.PreviewPlanSet;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidationException;
import com.securityexpert.nexus.ui2.jobs.staticroute.StaticRoutePreview.ValidatedRequest;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.ConfiguredRoutesParse;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.CpClusterState;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanEffectiveRunningResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanHaResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PanPendingChangesResult;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.PreCheckEvaluation;
import com.securityexpert.nexus.ui2.worker.staticroute.StaticRouteR0Parsers.RibParse;

class StaticRouteR0ParsersTest {

    // ---- 1. Check Point Gaia Synthetic Fixtures & Fail-Closed Tests ----

    @Test
    void checkPointClusterStateAcceptsStandaloneAndClusterXlAndRefusesCloningGroupOrUnknown() {
        CpClusterState standalone = StaticRouteR0Parsers.parseCpClusterState("ClusterXL is not enabled on this machine.");
        assertEquals("PASS", standalone.status());
        assertEquals("STANDALONE", standalone.reasonCode());

        String clusterXlHa = """
                Cluster Mode:   High Availability (Active Up) with IGMP Membership

                ID         Unique Address  Assigned Load   State          Name
                1 (local)  192.0.2.11      100%            ACTIVE         FW-TANGO-04
                2          192.0.2.12      0%              STANDBY        FW-JULIET-06
                """;
        CpClusterState ha = StaticRouteR0Parsers.parseCpClusterState(clusterXlHa);
        assertEquals("PASS", ha.status());
        assertEquals("ACTIVE", ha.localRole());

        String cloningGroup = """
                Cluster Mode:   Load Sharing Unicast (Cloning Group)
                1 (local)  192.0.2.11      50%             ACTIVE         FW-TANGO-04
                """;
        CpClusterState cloning = StaticRouteR0Parsers.parseCpClusterState(cloningGroup);
        assertEquals("FAIL", cloning.status());
        assertEquals("CLONING_GROUP_UNSUPPORTED", cloning.reasonCode());

        String unrecognisedMode = """
                Cluster Mode:   Experimental Custom Mode
                1 (local)  192.0.2.11      100%            ACTIVE         FW-TANGO-04
                """;
        CpClusterState unknownMode = StaticRouteR0Parsers.parseCpClusterState(unrecognisedMode);
        assertEquals("FAIL", unknownMode.status());
        assertEquals("CLONING_GROUP_UNSUPPORTED", unknownMode.reasonCode());

        assertEquals("UNKNOWN", StaticRouteR0Parsers.parseCpClusterState("").status());
    }

    @Test
    void checkPointStaticRoutesParsesTableAndClishFormsDetectsDbLockAndFlagsUnsupportedAttributes() {
        ConfiguredRoutesParse locked = StaticRouteR0Parsers.parseCpStaticRoutes("CLINFR0329  Database is locked by admin.");
        assertEquals("FAIL", locked.status());
        assertEquals("GAIA_DB_LOCKED", locked.reasonCode());

        ConfiguredRoutesParse empty = StaticRouteR0Parsers.parseCpStaticRoutes("");
        assertEquals("PASS", empty.status());
        assertTrue(empty.routes().isEmpty());
        assertNotNull(empty.tableSha256());

        String showRouteStaticOutput = """
                Codes: C - Connected, S - Static, R - RIP, B - BGP, O - OSPF
                S 192.0.2.128/25 via 192.0.2.1, eth0, priority 2
                S 192.0.2.64/26 blackhole
                """;
        ConfiguredRoutesParse parsed = StaticRouteR0Parsers.parseCpStaticRoutes(showRouteStaticOutput);
        assertEquals("PASS", parsed.status());
        assertEquals(2, parsed.routes().size());
        assertEquals("192.0.2.128/25", parsed.routes().get(0).destinationCidr());
        assertEquals("192.0.2.1", parsed.routes().get(0).nextHopIp());
        assertEquals(2, parsed.routes().get(0).priority());
        assertFalse(parsed.routes().get(0).unsupportedAttribute());
        assertTrue(parsed.routes().get(1).unsupportedAttribute());

        ConfiguredRoutesParse unrecognised = StaticRouteR0Parsers.parseCpStaticRoutes("unexpected garbage line from device");
        assertEquals("UNKNOWN", unrecognised.status());
        assertEquals("ANSWER_NOT_RECOGNISED", unrecognised.reasonCode());
    }

    @Test
    void checkPointRibAndRouteGetParseValidAnswersAndReturnUnknownOnUnrecognised() {
        String ribText = """
                default via 192.0.2.254 dev eth0 proto 7
                192.0.2.0/25 dev eth0 proto kernel scope link src 192.0.2.2
                192.0.2.128/25 via 192.0.2.1 dev eth0 proto 7
                """;
        RibParse rib = StaticRouteR0Parsers.parseCpRib(ribText);
        assertEquals("PASS", rib.status());
        assertEquals(3, rib.routes().size());

        BeforePathObservation path = StaticRouteR0Parsers.parseCpRouteGet(
                "192.0.2.129 via 192.0.2.1 dev eth0 src 192.0.2.2",
                "192.0.2.129",
                rib.routes());
        assertEquals("OBSERVED", path.verdict());
        assertEquals("192.0.2.128/25", path.selectedPrefix());
        assertEquals("192.0.2.1", path.nextHopIp());
        assertEquals("eth0", path.egressInterface());
        assertEquals("static", path.protocol());
        assertNotNull(path.digest());

        BeforePathObservation unreachable = StaticRouteR0Parsers.parseCpRouteGet(
                "RTNETLINK answers: Network is unreachable",
                "192.0.2.129",
                rib.routes());
        assertEquals("UNKNOWN", unreachable.verdict());
        assertNull(unreachable.selectedPrefix());

        BeforePathObservation garbage = StaticRouteR0Parsers.parseCpRouteGet(
                "some unexpected kernel output",
                "192.0.2.129",
                rib.routes());
        assertEquals("UNKNOWN", garbage.verdict());
    }

    // ---- 2. Palo Alto Networks Synthetic Fixtures & Fail-Closed Tests ----

    @Test
    void paloAltoPendingChangesHaStateAndEffectiveRunningFailClosed() {
        PanPendingChangesResult clean = StaticRouteR0Parsers.parsePanPendingChanges(
                "<response status=\"success\"><result>no</result></response>");
        assertEquals("PASS", clean.status());

        PanPendingChangesResult dirty = StaticRouteR0Parsers.parsePanPendingChanges(
                "<response status=\"success\"><result>yes</result></response>");
        assertEquals("FAIL", dirty.status());
        assertEquals("PENDING_CHANGES_PRESENT", dirty.reasonCode());

        PanPendingChangesResult unknown = StaticRouteR0Parsers.parsePanPendingChanges(
                "<response status=\"success\"><result><unexpected/></result></response>");
        assertEquals("UNKNOWN", unknown.status());

        String haXml = """
                <response status="success"><result>
                  <enabled>yes</enabled>
                  <group>
                    <mode>Active-Passive</mode>
                    <local-info><state>active</state><serial-num>00112233</serial-num></local-info>
                    <peer-info><state>passive</state><serial-num>00112234</serial-num><conn-status>up</conn-status></peer-info>
                    <running-sync>synchronized</running-sync>
                    <running-sync-enabled>yes</running-sync-enabled>
                  </group>
                </result></response>
                """;
        PanHaResult ha = StaticRouteR0Parsers.parsePanHaState(haXml);
        assertEquals("PASS", ha.status());

        String effLocalXml = """
                <response status="success"><result><config><devices><entry name="localhost.localdomain">
                  <network><virtual-router><entry name="default">
                    <routing-table><ip><static-route>
                      <entry name="r1"><destination>192.0.2.128/25</destination></entry>
                    </static-route></ip></routing-table>
                  </entry></virtual-router></network>
                </entry></devices></config></result></response>
                """;
        PanEffectiveRunningResult effOk = StaticRouteR0Parsers.parsePanEffectiveRunning(
                effLocalXml, "default", "192.0.2.128/25", "r1");
        assertEquals("PASS", effOk.status());

        String effTplXml = """
                <response status="success"><result><config><devices><entry name="localhost.localdomain">
                  <network><virtual-router><entry name="default" src="tpl">
                  </entry></virtual-router></network>
                </entry></devices></config></result></response>
                """;
        PanEffectiveRunningResult effTpl = StaticRouteR0Parsers.parsePanEffectiveRunning(
                effTplXml, "default", "192.0.2.128/25", "r1");
        assertEquals("FAIL", effTpl.status());
        assertEquals("PANORAMA_TEMPLATE_MANAGED", effTpl.reasonCode());
    }

    @Test
    void paloAltoVrStaticRoutesRibAndFibLookupParseAndFailClosed() {
        String vrStaticXml = """
                <response status="success"><result><static-route>
                  <entry name="nxs-partner-1">
                    <destination>192.0.2.128/25</destination>
                    <nexthop><ip-address>192.0.2.1</ip-address></nexthop>
                    <interface>ethernet1/1</interface>
                    <metric>10</metric>
                    <admin-dist>15</admin-dist>
                  </entry>
                </static-route></result></response>
                """;
        ConfiguredRoutesParse parsed = StaticRouteR0Parsers.parsePanVrStaticRoutes(vrStaticXml);
        assertEquals("PASS", parsed.status());
        assertEquals(1, parsed.routes().size());
        assertEquals("nxs-partner-1", parsed.routes().get(0).routeName());
        assertEquals(15, parsed.routes().get(0).distance());
        assertEquals(10, parsed.routes().get(0).metric());

        String ribXml = """
                <response status="success"><result>
                  <flags>A:active, C:connect, S:static</flags>
                  <entry>
                    <virtual-router>default</virtual-router>
                    <destination>192.0.2.0/25</destination>
                    <nexthop>0.0.0.0</nexthop>
                    <metric>0</metric>
                    <flags>A C</flags>
                    <interface>ethernet1/1</interface>
                  </entry>
                  <entry>
                    <virtual-router>default</virtual-router>
                    <destination>192.0.2.128/25</destination>
                    <nexthop>192.0.2.1</nexthop>
                    <metric>10</metric>
                    <flags>A S</flags>
                    <interface>ethernet1/1</interface>
                  </entry>
                </result></response>
                """;
        RibParse rib = StaticRouteR0Parsers.parsePanRib(ribXml, "default");
        assertEquals("PASS", rib.status());
        assertEquals(2, rib.routes().size());

        String fibXml = """
                <response status="success"><result>
                  <dp>dp0</dp>
                  <interface>ethernet1/1</interface>
                  <nh>192.0.2.1</nh>
                  <ip>192.0.2.1</ip>
                </result></response>
                """;
        BeforePathObservation fib = StaticRouteR0Parsers.parsePanFibLookup(fibXml, "192.0.2.129", rib.routes());
        assertEquals("OBSERVED", fib.verdict());
        assertEquals("192.0.2.128/25", fib.selectedPrefix());
        assertEquals("192.0.2.1", fib.nextHopIp());
        assertEquals("ethernet1/1", fib.egressInterface());
        assertEquals("static", fib.protocol());

        BeforePathObservation badFib = StaticRouteR0Parsers.parsePanFibLookup("<response status=\"error\"/>", "192.0.2.129", rib.routes());
        assertEquals("UNKNOWN", badFib.verdict());
    }

    // ---- 3. Cisco ASA Synthetic Fixtures & Fail-Closed Tests ----

    @Test
    void ciscoAsaPromptRunningRoutesAndShowRouteHostParseAndFailClosed() {
        assertTrue(StaticRouteR0Parsers.verifyAsaContextPrompt("FW-TANGO-04/ctx-alpha#", "ctx-alpha"));
        assertFalse(StaticRouteR0Parsers.verifyAsaContextPrompt("FW-TANGO-04/other#", "ctx-alpha"));
        assertFalse(StaticRouteR0Parsers.verifyAsaContextPrompt("FW-TANGO-04#", "ctx-alpha"));

        String runRoutes = """
                route outside 192.0.2.128 255.255.255.128 192.0.2.1 1
                route outside 192.0.2.64 255.255.255.192 192.0.2.1 1 track 10
                """;
        ConfiguredRoutesParse parsed = StaticRouteR0Parsers.parseAsaRunningConfigRoutes(runRoutes);
        assertEquals("PASS", parsed.status());
        assertEquals(2, parsed.routes().size());
        assertFalse(parsed.routes().get(0).unsupportedAttribute());
        assertTrue(parsed.routes().get(1).unsupportedAttribute());

        ConfiguredRoutesParse badAsa = StaticRouteR0Parsers.parseAsaRunningConfigRoutes("unrecognised line");
        assertEquals("UNKNOWN", badAsa.status());

        String detailedShowRouteHost = """
                Routing entry for 192.0.2.128 255.255.255.128
                  Known via "static", distance 1, metric 0
                  Routing Descriptor Blocks:
                  * 192.0.2.1, via outside
                """;
        BeforePathObservation detailed = StaticRouteR0Parsers.parseAsaRouteHost(detailedShowRouteHost, "192.0.2.129");
        assertEquals("OBSERVED", detailed.verdict());
        assertEquals("192.0.2.128/25", detailed.selectedPrefix());
        assertEquals("192.0.2.1", detailed.nextHopIp());
        assertEquals("outside", detailed.egressInterface());
        assertEquals("static", detailed.protocol());

        BeforePathObservation notInTable = StaticRouteR0Parsers.parseAsaRouteHost("% Subnet not in table", "192.0.2.129");
        assertEquals("UNKNOWN", notInTable.verdict());
        assertEquals("ROUTE_NOT_IN_TABLE", notInTable.reasonCode());
    }

    // ---- 4. Pre-Check Logic, Validation & Command Plan Determinism ----

    @Test
    void validationEnforcesDefaultRouteGuardAsaDistanceAndCommandPlanDeterminism() {
        ValidationException defaultRefused = assertThrows(ValidationException.class, () ->
                StaticRoutePreview.validateRequest(
                        "11111111-2222-4333-8444-555555555555",
                        "check_point",
                        "gaia-default",
                        "ADD",
                        "0.0.0.0/0",
                        "192.0.2.1",
                        null, null, null, null, null, null, null, null, null));
        assertEquals("DEFAULT_ROUTE_FORBIDDEN", defaultRefused.code());

        ValidationException asaDist255 = assertThrows(ValidationException.class, () ->
                StaticRoutePreview.validateRequest(
                        "11111111-2222-4333-8444-555555555555",
                        "cisco_asa",
                        "single",
                        "ADD",
                        "192.0.2.128/25",
                        "192.0.2.1",
                        "outside",
                        255,
                        null, null, null, null, null, null, null));
        assertEquals("INVALID_DISTANCE", asaDist255.code());

        ValidationException fgtRefused = assertThrows(ValidationException.class, () ->
                StaticRoutePreview.validateRequest(
                        "11111111-2222-4333-8444-555555555555",
                        "fortinet",
                        "root",
                        "ADD",
                        "192.0.2.128/25",
                        "192.0.2.1",
                        "port1",
                        10,
                        null, null, null, null, null, null, null));
        assertEquals("FORTIMANAGER_MANAGED", fgtRefused.code());

        ValidatedRequest req = StaticRoutePreview.validateRequest(
                "11111111-2222-4333-8444-555555555555",
                "check_point",
                "gaia-default",
                "ADD",
                "192.0.2.128/25",
                "192.0.2.1",
                null, null, null, 2, null, "cutover-route", null, "CHG-101", "Partner route preview");

        PreviewPlanSet plan1 = StaticRoutePreview.compilePreviewPlans(
                req, List.of("member-standby", "member-active"), Optional.empty(), Optional.empty());
        PreviewPlanSet plan2 = StaticRoutePreview.compilePreviewPlans(
                req, List.of("member-standby", "member-active"), Optional.empty(), Optional.empty());
        assertFalse(plan1.mutationPossible());
        assertEquals(plan1.requestDigest(), plan2.requestDigest());
        assertEquals(plan1.approvedCommandSha256Set(), plan2.approvedCommandSha256Set());
        assertEquals(plan1.inverseCommandSha256Set(), plan2.inverseCommandSha256Set());
    }

    @Test
    void stageBPreChecksDetectConnectedSubnetOverlapControlPlaneLockoutAndCheckPointWarning() {
        ValidatedRequest req = StaticRoutePreview.validateRequest(
                "11111111-2222-4333-8444-555555555555",
                "check_point",
                "gaia-default",
                "ADD",
                "192.0.2.128/26",
                "192.0.2.1",
                null, null, null, 2, null, null, null, null, null);

        InventoryInterface eth0 = new InventoryInterface(
                "if-1", "eth0", Optional.empty(), InventoryInterface.KIND_PHYSICAL, InventoryInterface.STATE_UP,
                List.of(new InventoryAddress("a-1", "192.0.2.2/26", InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));

        ConfiguredRoutesParse emptyConfig = StaticRouteR0Parsers.parseCpStaticRoutes("");
        RibParse rib = StaticRouteR0Parsers.parseCpRib("""
                default via 192.0.2.1 dev eth0 proto 7
                192.0.2.0/26 dev eth0 proto kernel scope link src 192.0.2.2
                """);

        PreCheckVerdict c1 = new PreCheckVerdict(1, StaticRouteR0Parsers.CHECK_1_TARGET_READY, "STAGE_B", "PASS", "OK", "ok");
        PreCheckVerdict c2 = new PreCheckVerdict(2, StaticRouteR0Parsers.CHECK_2_IDENTITY_HA_MGMT, "STAGE_B", "PASS", "OK", "ok");

        PreCheckEvaluation passEval = StaticRouteR0Parsers.evaluatePreChecks(
                "STAGE_B", req, c1, c2, emptyConfig, rib, List.of(eth0), List.of("192.0.2.2"));
        assertEquals("PASS", passEval.overallVerdict());
        assertEquals("WARN", passEval.checks().get(5).status());
        assertEquals("CP_ANTISPOOFING_DEFINED_BY_ROUTES_WARNING", passEval.checks().get(5).reasonCode());

        // Control-plane management lockout risk when destinationPrefix covers a protected management IP
        PreCheckEvaluation lockoutEval = StaticRouteR0Parsers.evaluatePreChecks(
                "STAGE_B", req, c1, c2, emptyConfig, rib, List.of(eth0), List.of("192.0.2.130"));
        assertEquals("FAIL", lockoutEval.overallVerdict());
        assertEquals("MANAGEMENT_LOCKOUT_RISK", lockoutEval.stopCode());

        // Masked shape line never leaks raw IPs or hostnames
        String shape = StaticRouteR0Parsers.formatMaskedShapeLine(
                "check_point", "cp_route_ip_route_get", "192.0.2.129 via 192.0.2.1 dev eth0 src 192.0.2.2", true);
        assertFalse(shape.contains("192.0.2.129"));
        assertFalse(shape.contains("eth0"));
    }
}
