package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_COMPLIANT;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.IpRange;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.PreparedPolicy;
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.RuleUpperBound;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CellViolation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.EvaluationResult;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Zone matrix delivery step 7c ({@code docs/design/ZONE_MATRIX_CONTRACT.md} §§5.3, 5.4, 5.7 step 2, §10.1 item 7;
 * {@code docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_13.md} {@code A0-N1}, {@code A0-N2}; {@code ZM-4},
 * {@code ZM-9}, {@code ZM-19}, {@code ZM-27}): negated static IPv4 address cells evaluated by exact complement.
 *
 * <p>Synthetic RFC 5737 / RFC 1918 / RFC 3849 test ranges and masked pseudonyms only.</p>
 */
class ZoneMatrixNegatedAddressTest {
    private static final Instant NOW = Instant.parse("2026-11-26T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long IPV4_MAX = 0xFFFF_FFFFL;
    private static final String IPV6_NOT_EVALUATED = "IPv6 not evaluated";
    private static final String NEGATED_SELECTOR = "negated selector";
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");

    private static ZoneMatrixService newService(ZoneMatrixServiceTest.Memory store) {
        return new ZoneMatrixService(store, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static String createZone(
            ZoneMatrixService service, ZoneMatrixServiceTest.Memory store, String name, String role, String... blocks) {
        Long rev = store.draft().map(d -> d.revision()).orElse(null);
        return (String) service.createZone("security-1", rev, new ZoneInput(name, "Synthetic " + name, role, List.of(blocks)))
                .get("zone_id");
    }

    private static ZoneMatrixContent publish(ZoneMatrixService service, ZoneMatrixServiceTest.Memory store) {
        var draft = store.draft().orElseThrow();
        service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        return ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER);
    }

    private static Metadata meta(String id, String vendor) {
        return new Metadata(id, "src-1", "FW-TANGO-04", vendor, "dom-1", "DOM-BRAVO-02", "POL-" + id,
                "2026-11-26T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static PolicyObject object(String id, String type, String... values) {
        return new PolicyObject(id, id.toUpperCase(), type, List.of(), List.of(values), "RESOLVED");
    }

    private static PolicyObject group(String id, String type, String... members) {
        return new PolicyObject(id, id.toUpperCase(), type, List.of(members), List.of(), "RESOLVED");
    }

    private static long ip(String dotted) {
        String[] parts = dotted.split("\\.", -1);
        assertEquals(4, parts.length);
        long out = 0L;
        for (String p : parts) {
            out = (out << 8) | Integer.parseInt(p);
        }
        return out;
    }

    private static long countAddresses(List<IpRange> ranges) {
        long sum = 0L;
        for (IpRange r : ranges) {
            sum += (r.end() - r.start() + 1L);
        }
        return sum;
    }

    // ------------------------------------------------------------------ 1. complementIpv4 boundary math

    @Test
    void complementIpv4BoundaryMathCoversSingleHostEdgePrefixesOverlapAndFullSpace() {
        // Single interior host (192.0.2.10)
        long host = ip("192.0.2.10");
        List<IpRange> hostComp = ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(host, host)));
        assertEquals(List.of(new IpRange(0L, host - 1L), new IpRange(host + 1L, IPV4_MAX)), hostComp);
        assertEquals(1L << 32, 1L + countAddresses(hostComp));

        // Boundary hosts at 0.0.0.0 and 255.255.255.255
        assertEquals(List.of(new IpRange(1L, IPV4_MAX)),
                ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(0L, 0L))));
        assertEquals(List.of(new IpRange(0L, IPV4_MAX - 1L)),
                ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(IPV4_MAX, IPV4_MAX))));

        // Prefix starting at 0.0.0.0/8 (0x0000_0000..0x00FF_FFFF)
        assertEquals(List.of(new IpRange(0x0100_0000L, IPV4_MAX)),
                ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(0L, 0x00FF_FFFFL))));

        // Prefix ending at 255.255.255.255 (255.0.0.0/8 = 0xFF00_0000..0xFFFF_FFFF; no IPV4_MAX_LONG + 1 overflow)
        assertEquals(List.of(new IpRange(0L, 0xFEFF_FFFFL)),
                ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(0xFF00_0000L, IPV4_MAX))));

        // Unsorted overlapping + adjacent ranges
        List<IpRange> unmerged = List.of(
                new IpRange(ip("198.51.100.10"), ip("198.51.100.20")),
                new IpRange(ip("192.0.2.10"), ip("192.0.2.20")),
                new IpRange(ip("192.0.2.15"), ip("192.0.2.25")),
                new IpRange(ip("192.0.2.26"), ip("192.0.2.30")));
        List<IpRange> merged = ZoneMatrixPolicyBridge.mergeIpRanges(unmerged);
        List<IpRange> comp = ZoneMatrixPolicyBridge.complementIpv4(unmerged);
        assertEquals(List.of(
                new IpRange(0L, ip("192.0.2.9")),
                new IpRange(ip("192.0.2.31"), ip("198.51.100.9")),
                new IpRange(ip("198.51.100.21"), IPV4_MAX)), comp);
        assertTrue(ZoneMatrixPolicyBridge.intersectIpRanges(merged, comp).isEmpty());
        assertEquals(1L << 32, countAddresses(merged) + countAddresses(comp));

        // Full 0.0.0.0/0 (single range and two /1 halves) -> empty
        assertEquals(List.of(), ZoneMatrixPolicyBridge.complementIpv4(List.of(new IpRange(0L, IPV4_MAX))));
        assertEquals(List.of(), ZoneMatrixPolicyBridge.complementIpv4(List.of(
                new IpRange(0x8000_0000L, IPV4_MAX),
                new IpRange(0L, 0x7FFF_FFFFL))));
    }

    // ------------------------------------------------------------------ 2. CP & PAN negated static IPv4 source

    @Test
    void negatedStaticIpv4SourceProducesViolationWithWitnessOutsideExcludedHostInCheckPointAndPaloAlto() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zHost10 = createZone(service, store, "Z-HOST10", "USER", "192.0.2.10/32");
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        // Allow 192.0.2.10/32 -> Z-APP so only the complement outside 192.0.2.10 can violate Z-APP.
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zHost10, zApp, "ALLOW_ANY", List.of(), "LOW", "excluded host permitted", ""));
        ZoneMatrixContent matrix = publish(service, store);

        // Check Point: src = !192.0.2.10 -> dst = 198.51.100.0/24 (DENY_ALL from UNASSIGNED, Z-USER, Z-DB), tcp/443, Accept
        Map<String, PolicyObject> cpObjects = new LinkedHashMap<>();
        cpObjects.put("obj-any", OBJ_ANY);
        cpObjects.put("cp-host-10", object("cp-host-10", "host", "ipv4-address: 192.0.2.10"));
        cpObjects.put("cp-net-app", object("cp-net-app", "network", "subnet4: 198.51.100.0", "mask-length4: 24"));
        cpObjects.put("https", object("https", "service-tcp", "protocol: tcp", "port: 443"));
        Rule cpRule = new Rule("cp-neg-src", "uid-cp-neg-src", 1, "NegatedSourceCp", true,
                new Cell(List.of("cp-host-10"), true), new Cell(List.of("cp-net-app"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "",
                Map.of("layer-name", List.of("Network")));
        PolicySnapshot cpSnap = new PolicySnapshot(meta("pol-cp-neg", "CP"),
                List.of(new Section("s-cp", "Network", "CP access layer", null, List.of(cpRule))), cpObjects);

        EvaluationResult cpRes = ZoneMatrixEvaluator.evaluate(cpSnap, "v-7c", 1, matrix, NOW);
        EvaluationResult cpRepeat = ZoneMatrixEvaluator.evaluate(cpSnap, "v-7c", 1, matrix, NOW);
        assertEquals(cpRes.rules(), cpRepeat.rules(), "ZM-9 deterministic evaluation");
        assertEquals(cpRes.headline(), cpRepeat.headline(), "ZM-9 deterministic headline");
        assertNegatedSourceViolatesOutsideExcludedHost(cpRes.rules().get(0), zHost10, zUser, zDb, zApp);

        // Palo Alto: src = !192.0.2.10 -> dst = 198.51.100.0/24, tcp/443, allow
        Map<String, PolicyObject> panObjects = new LinkedHashMap<>();
        panObjects.put("obj-any", OBJ_ANY);
        panObjects.put("pan-host-10", object("pan-host-10", "address", "ip-netmask: 192.0.2.10/32"));
        panObjects.put("pan-net-app", object("pan-net-app", "address", "ip-netmask: 198.51.100.0/24"));
        panObjects.put("https", object("https", "service", "protocol/tcp/port: 443"));
        Rule panRule = new Rule("pan-neg-src", "uid-pan-neg-src", 1, "NegatedSourcePan", true,
                new Cell(List.of("pan-host-10"), true), new Cell(List.of("pan-net-app"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        PolicySnapshot panSnap = new PolicySnapshot(meta("pol-pan-neg", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-pan-neg", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, List.of(panRule))), panObjects);

        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panSnap, "v-7c", 1, matrix, NOW);
        assertNegatedSourceViolatesOutsideExcludedHost(panRes.rules().get(0), zHost10, zUser, zDb, zApp);
    }

    private static void assertNegatedSourceViolatesOutsideExcludedHost(
            RuleEvaluation eval, String zHost10, String zUser, String zDb, String zApp) {
        assertEquals(OUTCOME_VIOLATION, eval.outcome());
        assertFalse(eval.touchedPairs().contains(zHost10 + "->" + zApp), "excluded /32 is never touched");
        assertTrue(eval.undecidedParts().contains(IPV6_NOT_EVALUATED));

        Map<String, CellViolation> bySrcZone = new LinkedHashMap<>();
        for (CellViolation cv : eval.violatedCells()) {
            assertEquals(zApp, cv.destinationZone());
            assertNotEquals(zHost10, cv.sourceZone());
            assertNotEquals(ip("192.0.2.10"), cv.witness().sampleSourceIp(), "witness sourceIp must exclude 192.0.2.10");
            bySrcZone.put(cv.sourceZone(), cv);
        }
        assertTrue(bySrcZone.containsKey(ZoneMatrixContent.UNASSIGNED));
        assertTrue(bySrcZone.containsKey(zUser));
        assertTrue(bySrcZone.containsKey(zDb));
        assertEquals(ip("192.0.2.0"), bySrcZone.get(zUser).witness().sampleSourceIp());
        assertEquals("192.0.2.0-192.0.2.9", bySrcZone.get(zUser).witness().sourceRange());
    }

    // ------------------------------------------------------------------ 3. Negated static IPv4 destination

    @Test
    void negatedStaticIpv4DestinationViolatesDenyAllAndAllowListedZonesOutsideExcludedSubnet() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, ZoneMatrixContent.UNASSIGNED, "ALLOW_ANY", List.of(), "LOW", "egress", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zUser, "ALLOW_ANY", List.of(), "LOW", "intra-user", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_ANY", List.of(), "LOW", "app access", ""));
        // Z-USER -> Z-DB stays default DENY_ALL for the first check.
        ZoneMatrixContent denyDbMatrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("net-user", object("net-user", "address", "ip-netmask: 192.0.2.0/24"));
        objects.put("net-app", object("net-app", "address", "ip-netmask: 198.51.100.0/24"));
        objects.put("https", object("https", "service", "protocol/tcp/port: 443"));
        objects.put("ssh", object("ssh", "service", "protocol/tcp/port: 22"));

        Rule rDenyAll = new Rule("r-dst-deny", "uid-dst-deny", 1, "NegatedDestHitsDenyAll", true,
                new Cell(List.of("net-user"), false), new Cell(List.of("net-app"), true),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        PolicySnapshot snap1 = new PolicySnapshot(meta("pol-dst-1", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-dst-1", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, List.of(rDenyAll))), objects);

        RuleEvaluation evDeny = ZoneMatrixEvaluator.evaluate(snap1, "v-7c-1", 1, denyDbMatrix, NOW).rules().get(0);
        assertEquals(OUTCOME_VIOLATION, evDeny.outcome());
        assertFalse(evDeny.touchedPairs().contains(zUser + "->" + zApp), "excluded 198.51.100.0/24 is not touched");
        assertEquals(1, evDeny.violatedCells().size());
        CellViolation cvDeny = evDeny.violatedCells().get(0);
        assertEquals(zUser, cvDeny.sourceZone());
        assertEquals(zDb, cvDeny.destinationZone());
        assertEquals("DENY_ALL", cvDeny.statement());
        long dstWitness1 = cvDeny.witness().sampleDestinationIp();
        assertTrue(dstWitness1 < ip("198.51.100.0") || dstWitness1 > ip("198.51.100.255"));
        assertTrue(dstWitness1 >= ip("203.0.113.0") && dstWitness1 <= ip("203.0.113.255"));

        // Second check: Z-USER -> Z-DB is ALLOW_LISTED (tcp/443 only) while rule permits tcp/22.
        var store2 = new ZoneMatrixServiceTest.Memory();
        var service2 = newService(store2);
        String zUser2 = createZone(service2, store2, "Z-USER", "USER", "192.0.2.0/24");
        String zApp2 = createZone(service2, store2, "Z-APP", "APPLICATION", "198.51.100.0/24");
        String zDb2 = createZone(service2, store2, "Z-DB", "DATABASE", "203.0.113.0/24");
        service2.setCell("security-1", store2.draft().orElseThrow().revision(),
                new CellInput(zUser2, ZoneMatrixContent.UNASSIGNED, "ALLOW_ANY", List.of(), "LOW", "egress", ""));
        service2.setCell("security-1", store2.draft().orElseThrow().revision(),
                new CellInput(zUser2, zUser2, "ALLOW_ANY", List.of(), "LOW", "intra-user", ""));
        service2.setCell("security-1", store2.draft().orElseThrow().revision(),
                new CellInput(zUser2, zApp2, "ALLOW_ANY", List.of(), "LOW", "app access", ""));
        service2.setCell("security-1", store2.draft().orElseThrow().revision(),
                new CellInput(zUser2, zDb2, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "https only", ""));
        ZoneMatrixContent allowListedMatrix = publish(service2, store2);

        Rule rAllowListed = new Rule("r-dst-listed", "uid-dst-listed", 1, "NegatedDestExceedsAllowListed", true,
                new Cell(List.of("net-user"), false), new Cell(List.of("net-app"), true),
                new Cell(List.of("https", "ssh"), false), ANY_CELL, "allow", "Log", "", Map.of());
        PolicySnapshot snap2 = new PolicySnapshot(meta("pol-dst-2", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-dst-2", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, List.of(rAllowListed))), objects);

        RuleEvaluation evListed = ZoneMatrixEvaluator.evaluate(snap2, "v-7c-2", 1, allowListedMatrix, NOW).rules().get(0);
        assertEquals(OUTCOME_VIOLATION, evListed.outcome());
        assertEquals(1, evListed.violatedCells().size());
        CellViolation cvListed = evListed.violatedCells().get(0);
        assertEquals(zUser2, cvListed.sourceZone());
        assertEquals(zDb2, cvListed.destinationZone());
        assertEquals("ALLOW_LISTED", cvListed.statement());
        assertEquals(List.of("tcp/22"), cvListed.notAllowedServices());
        assertEquals("tcp/22", cvListed.witness().service());
        long dstWitness2 = cvListed.witness().sampleDestinationIp();
        assertTrue(dstWitness2 < ip("198.51.100.0") || dstWitness2 > ip("198.51.100.255"));
        assertTrue(dstWitness2 >= ip("203.0.113.0") && dstWitness2 <= ip("203.0.113.255"));
    }

    // ------------------------------------------------------------------ 4. Negated static IPv4 group in all-ALLOW_ANY cells

    @Test
    void negatedStaticIpv4GroupWhenEveryTouchedCellAllowsAllIsIpv6NotEvaluatedAndEmitsZeroRfc1918Gaps() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        // RFC 1918 space (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) is intentionally left in UNASSIGNED.
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        for (String src : List.of(ZoneMatrixContent.UNASSIGNED, zUser, zApp, zDb)) {
            service.setCell("security-1", store.draft().orElseThrow().revision(),
                    new CellInput(src, zUser, "ALLOW_ANY", List.of(), "LOW", "inbound allowed", ""));
        }
        for (String dst : List.of(ZoneMatrixContent.UNASSIGNED, zApp, zDb)) {
            service.setCell("security-1", store.draft().orElseThrow().revision(),
                    new CellInput(zUser, dst, "ALLOW_ANY", List.of(), "LOW", "outbound allowed", ""));
        }
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("net-user", object("net-user", "address", "ip-netmask: 192.0.2.0/24"));
        objects.put("m1", object("m1", "address", "ip-netmask: 198.51.100.0/24"));
        objects.put("m2", object("m2", "address", "ip-netmask: 203.0.113.10/32"));
        objects.put("grp-static", group("grp-static", "address-group", "m1", "m2"));
        objects.put("https", object("https", "service", "protocol/tcp/port: 443"));

        Rule negSrcGroup = new Rule("r-neg-src-grp", "uid-neg-src-grp", 1, "NegatedGroupSrc", true,
                new Cell(List.of("grp-static"), true), new Cell(List.of("net-user"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        Rule negDstGroup = new Rule("r-neg-dst-grp", "uid-neg-dst-grp", 2, "NegatedGroupDst", true,
                new Cell(List.of("net-user"), false), new Cell(List.of("grp-static"), true),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        PolicySnapshot snap = new PolicySnapshot(meta("pol-grp", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-grp", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, List.of(negSrcGroup, negDstGroup))), objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7c-grp", 1, matrix, NOW);
        assertEquals(2, res.rules().size());
        for (RuleEvaluation ev : res.rules()) {
            assertNotEquals(OUTCOME_COMPLIANT, ev.outcome(), "ZM-4 / ZM-19: negated IPv4 still matches IPv6");
            assertEquals(OUTCOME_NOT_EVALUABLE, ev.outcome());
            assertEquals(IPV6_NOT_EVALUATED, ev.reason());
            assertTrue(ev.violatedCells().isEmpty());
        }
        assertFalse(res.rules().get(0).touchedPairs().contains(zApp + "->" + zUser));
        assertTrue(res.rules().get(0).touchedPairs().contains(ZoneMatrixContent.UNASSIGNED + "->" + zUser));
        assertTrue(res.zoneDefinitionGaps().isEmpty(),
                "negated static IPv4 complement must keep explicitIpv4 empty and emit zero unassigned RFC 1918 gaps");

        // Positive control: an explicit unassigned RFC 1918 subnet on the same matrix DOES emit a zoneDefinitionGap.
        Map<String, PolicyObject> controlObjects = new LinkedHashMap<>(objects);
        controlObjects.put("rfc1918-explicit", object("rfc1918-explicit", "address", "ip-netmask: 10.20.0.0/16"));
        Rule explicitPrivate = new Rule("r-explicit-priv", "uid-explicit-priv", 1, "ExplicitPrivate", true,
                new Cell(List.of("rfc1918-explicit"), false), new Cell(List.of("net-user"), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
        PolicySnapshot controlSnap = new PolicySnapshot(meta("pol-ctrl", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-ctrl", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, List.of(explicitPrivate))), controlObjects);
        assertEquals(1, ZoneMatrixEvaluator.evaluate(controlSnap, "v-7c-grp", 1, matrix, NOW).zoneDefinitionGaps().size());
    }

    // ------------------------------------------------------------------ 5. Check Point inline layer intersection

    @Test
    void checkPointInlineLayerChildWithNegatedSourceIntersectsWithStaticIpv4Parent() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "192.0.2.0/24");
        String zExcluded = createZone(service, store, "Z-EXCLUDED", "USER", "192.0.2.10/32");
        String zDmz = createZone(service, store, "Z-DMZ", "DMZ", "198.51.100.0/24");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        // Only Z-APP -> Z-DMZ permits tcp/443; Z-EXCLUDED -> Z-DMZ, UNASSIGNED -> Z-DMZ, and anything -> Z-DB are DENY_ALL.
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zApp, zDmz, "ALLOW_LISTED", List.of("tcp/443"), "MEDIUM", "app to dmz", ""));
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("net-app", object("net-app", "network", "subnet4: 192.0.2.0", "mask-length4: 24"));
        objects.put("host-10", object("host-10", "host", "ipv4-address: 192.0.2.10"));
        objects.put("net-dmz", object("net-dmz", "network", "subnet4: 198.51.100.0", "mask-length4: 24"));
        objects.put("net-db", object("net-db", "network", "subnet4: 203.0.113.0", "mask-length4: 24"));
        objects.put("https", object("https", "service-tcp", "protocol: tcp", "port: 443"));

        Rule parent = new Rule("p1", "uid-p1", 1, "ApplyAppInline", true,
                new Cell(List.of("net-app"), false), ANY_CELL, ANY_CELL, ANY_CELL, "Apply Layer", "Log", "",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-App")));
        Map<String, List<String>> inlineExtras = Map.of("layer-name", List.of("Inline-App"), "install-on", List.of("obj-any"));
        Rule childCompliant = new Rule("c1", "uid-c1", 1, "NegatedHostToDmz", true,
                new Cell(List.of("host-10"), true), new Cell(List.of("net-dmz"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", inlineExtras);
        Rule childViolating = new Rule("c2", "uid-c2", 2, "NegatedHostToDb", true,
                new Cell(List.of("host-10"), true), new Cell(List.of("net-db"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", inlineExtras);
        Rule childCleanup = new Rule("c3", "uid-c3", 3, "InlineCleanup", true,
                ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "Drop", "Log", "", inlineExtras);

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-inline-neg", "CP"),
                List.of(
                        new Section("s-net", "Network", "CP access layer", null, List.of(parent)),
                        new Section("s-in", "Inline-App", "CP access layer", "p1",
                                List.of(childCompliant, childViolating, childCleanup))),
                objects);

        PreparedPolicy prepared = ZoneMatrixPolicyBridge.prepare(snap, NOW);
        RuleUpperBound parentBound = prepared.ruleBounds().get("p1");
        RuleUpperBound childBound = prepared.ruleBounds().get("c1");
        assertFalse(parentBound.source().hasIpv6(), "static IPv4 parent has no IPv6");
        assertTrue(childBound.source().hasIpv6(), "negated IPv4 child alone matches IPv6");
        List<IpRange> intersected = ZoneMatrixPolicyBridge.intersectIpRanges(
                childBound.source().upperIpv4(), parentBound.source().upperIpv4());
        assertEquals(List.of(
                new IpRange(ip("192.0.2.0"), ip("192.0.2.9")),
                new IpRange(ip("192.0.2.11"), ip("192.0.2.255"))), intersected);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7c-inline", 1, matrix, NOW);
        assertEquals(1, res.notApplicable().applyLayerDecidedByInline());
        assertEquals(2, res.rules().size());

        RuleEvaluation evalCompliant = res.rules().get(0);
        assertEquals("c1", evalCompliant.ruleId());
        assertEquals(OUTCOME_COMPLIANT, evalCompliant.outcome(),
                "parent restricts IPv4 to 192.0.2.0/24 and hasIpv6 to false; child excludes 192.0.2.10");
        assertEquals(List.of(zApp + "->" + zDmz), evalCompliant.touchedPairs());

        RuleEvaluation evalViolating = res.rules().get(1);
        assertEquals("c2", evalViolating.ruleId());
        assertEquals(OUTCOME_VIOLATION, evalViolating.outcome());
        assertEquals(List.of(zApp + "->" + zDb), evalViolating.touchedPairs());
        assertEquals(1, evalViolating.violatedCells().size());
        assertEquals("192.0.2.0-192.0.2.9", evalViolating.violatedCells().get(0).witness().sourceRange());
        assertFalse(evalViolating.undecidedParts().contains(IPV6_NOT_EVALUATED));
    }

    // ------------------------------------------------------------------ 6. Fail-closed "negated selector" cases

    @Test
    void failClosedNegatedSelectorPreservedForUnsupportedDynamicIpv6FullSpaceAndServiceOrAppNegation() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", object("u1", "address", "ip-netmask: 192.0.2.10/32"));
        objects.put("db1", object("db1", "address", "ip-netmask: 203.0.113.10/32"));
        objects.put("https", object("https", "service", "protocol/tcp/port: 443"));
        objects.put("unres-status", new PolicyObject("unres-status", "UNRES", "address", List.of(), List.of(), "UNRESOLVED"));
        objects.put("fqdn-obj", object("fqdn-obj", "address", "fqdn: synthetic.example.invalid"));
        objects.put("dyn-obj", new PolicyObject("dyn-obj", "DYN", "dynamic-object", List.of(), List.of(), "DYNAMIC"));
        objects.put("pan-dyn-grp", new PolicyObject("pan-dyn-grp", "DYN-GRP", "address-group",
                List.of(), List.of("filter: 'tag-app'"), "RESOLVED"));
        objects.put("region-obj", new PolicyObject("region-obj", "REG", "region", List.of(), List.of(), "DYNAMIC"));
        objects.put("role-obj", new PolicyObject("role-obj", "ROLE-CORP", "access-role",
                List.of(), List.of("networks: u1"), "DYNAMIC"));
        objects.put("v6-only", object("v6-only", "address", "ip-netmask: 2001:db8::10/128"));
        objects.put("mixed-dual", object("mixed-dual", "address",
                "ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::10/128"));
        objects.put("grp-with-mixed", group("grp-with-mixed", "address-group", "u1", "mixed-dual"));
        objects.put("grp-empty", group("grp-empty", "address-group"));
        objects.put("grp-cycle-a", group("grp-cycle-a", "address-group", "grp-cycle-b"));
        objects.put("grp-cycle-b", group("grp-cycle-b", "address-group", "grp-cycle-a"));
        objects.put("half-low", object("half-low", "address", "ip-netmask: 0.0.0.0/1"));
        objects.put("half-high", object("half-high", "address", "ip-netmask: 128.0.0.0/1"));
        objects.put("app-ssl", new PolicyObject(
                PolicySnapshot.ref("pol-fail-pan", "application", "ssl"), "ssl", "unresolved", List.of(), List.of(), "UNRESOLVED"));

        Cell posDst = new Cell(List.of("db1"), false);
        Cell posSrc = new Cell(List.of("u1"), false);
        Cell posSvc = new Cell(List.of("https"), false);

        List<Rule> panRules = List.of(
                panCase(1, "empty-refs", new Cell(List.of(), true), posDst, posSvc, ANY_CELL),
                panCase(2, "neg-any", new Cell(List.of("obj-any"), true), posDst, posSvc, ANY_CELL),
                panCase(3, "neg-missing", new Cell(List.of("missing-id"), true), posDst, posSvc, ANY_CELL),
                panCase(4, "neg-unres-status", new Cell(List.of("unres-status"), true), posDst, posSvc, ANY_CELL),
                panCase(5, "neg-fqdn", new Cell(List.of("fqdn-obj"), true), posDst, posSvc, ANY_CELL),
                panCase(6, "neg-dynamic", new Cell(List.of("dyn-obj"), true), posDst, posSvc, ANY_CELL),
                panCase(7, "neg-pan-dyn-grp", new Cell(List.of("pan-dyn-grp"), true), posDst, posSvc, ANY_CELL),
                panCase(8, "neg-region", new Cell(List.of("region-obj"), true), posDst, posSvc, ANY_CELL),
                panCase(9, "neg-v6-only", new Cell(List.of("v6-only"), true), posDst, posSvc, ANY_CELL),
                panCase(10, "neg-mixed-dual", new Cell(List.of("mixed-dual"), true), posDst, posSvc, ANY_CELL),
                panCase(11, "neg-grp-mixed", new Cell(List.of("grp-with-mixed"), true), posDst, posSvc, ANY_CELL),
                panCase(12, "neg-grp-empty", new Cell(List.of("grp-empty"), true), posDst, posSvc, ANY_CELL),
                panCase(13, "neg-grp-cycle", new Cell(List.of("grp-cycle-a"), true), posDst, posSvc, ANY_CELL),
                panCase(14, "neg-full-v4-union", new Cell(List.of("half-low", "half-high"), true), posDst, posSvc, ANY_CELL),
                panCase(15, "neg-service", posSrc, posDst, new Cell(List.of("https"), true), ANY_CELL),
                panCase(16, "neg-application", posSrc, posDst, posSvc,
                        new Cell(List.of(PolicySnapshot.ref("pol-fail-pan", "application", "ssl")), true)));

        PolicySnapshot panSnap = new PolicySnapshot(meta("pol-fail-pan", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-fail-pan", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, panRules)), objects);
        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panSnap, "v-7c-fail", 1, matrix, NOW);
        assertEquals(panRules.size(), panRes.rules().size());
        for (RuleEvaluation ev : panRes.rules()) {
            assertEquals(OUTCOME_NOT_EVALUABLE, ev.outcome(), ev.ruleId());
            assertEquals(NEGATED_SELECTOR, ev.reason(), ev.ruleId());
            assertTrue(ev.violatedCells().isEmpty(), ev.ruleId());
        }

        // Check Point source cell with negated access-role (where non-negated CP source passes allowAccessRole = true)
        Map<String, List<String>> cpExtras = Map.of("layer-name", List.of("Network"));
        Rule cpNegRole = new Rule("cp-neg-role", "uid-cp-neg-role", 1, "NegatedAccessRole", true,
                new Cell(List.of("role-obj"), true), posDst, posSvc, ANY_CELL, "Accept", "Log", "", cpExtras);
        Rule cpNegService = new Rule("cp-neg-svc", "uid-cp-neg-svc", 2, "NegatedCpService", true,
                posSrc, posDst, new Cell(List.of("https"), true), ANY_CELL, "Accept", "Log", "", cpExtras);
        Rule cpNegApp = new Rule("cp-neg-app", "uid-cp-neg-app", 3, "NegatedCpApp", true,
                posSrc, posDst, posSvc, new Cell(List.of("obj-any"), true), "Accept", "Log", "", cpExtras);
        PolicySnapshot cpSnap = new PolicySnapshot(meta("pol-fail-cp", "CP"),
                List.of(new Section("s-cp-fail", "Network", "CP access layer", null,
                        List.of(cpNegRole, cpNegService, cpNegApp))), objects);
        EvaluationResult cpRes = ZoneMatrixEvaluator.evaluate(cpSnap, "v-7c-fail", 1, matrix, NOW);
        assertEquals(3, cpRes.rules().size());
        for (RuleEvaluation ev : cpRes.rules()) {
            assertEquals(OUTCOME_NOT_EVALUABLE, ev.outcome(), ev.ruleId());
            assertEquals(NEGATED_SELECTOR, ev.reason(), ev.ruleId());
            assertTrue(ev.violatedCells().isEmpty(), ev.ruleId());
        }
    }

    private static Rule panCase(int num, String id, Cell src, Cell dst, Cell svc, Cell app) {
        return new Rule(id, "uid-" + id, num, "Case-" + id, true, src, dst, svc, app, "allow", "Log", "", Map.of());
    }
}
