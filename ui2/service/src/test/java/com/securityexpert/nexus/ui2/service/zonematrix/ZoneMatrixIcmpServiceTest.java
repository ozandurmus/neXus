package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_COMPLIANT;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.securityexpert.nexus.ui2.service.policy.ZoneMatrixPolicyBridge.PortRange;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.CellViolation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.EvaluationResult;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.View;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Zone matrix delivery step 7d ({@code docs/design/ZONE_MATRIX_CONTRACT.md} §§4.2, 5.4, 5.5, 5.6, 5.7 step 4, §10.1
 * item 7; {@code docs/design/PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_13.md} {@code A0-I1}; {@code ZM-4},
 * {@code ZM-5}, {@code ZM-9}, {@code ZM-14}, {@code ZM-16}, {@code ZM-17}, {@code ZM-19}): Check Point
 * {@code service-icmp} objects carrying stored RFC 792 {@code icmp-type} and {@code icmp-code} in {@code 0..255}.
 *
 * <p>Synthetic pseudonyms and RFC 5737 / RFC 3849 documentation address ranges only.</p>
 */
class ZoneMatrixIcmpServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-26T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
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
                "2026-11-26T11:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static PolicyObject object(String id, String type, String... values) {
        return new PolicyObject(id, id.toUpperCase(), type, List.of(), List.of(values), "RESOLVED");
    }

    private static PolicyObject group(String id, String type, String... members) {
        return new PolicyObject(id, id.toUpperCase(), type, List.of(members), List.of(), "RESOLVED");
    }

    private static Rule cpRule(String id, int num, String src, String dst, String svc, String action, Map<String, List<String>> extras) {
        return new Rule(id, "uid-" + id, num, "Rule-" + id, true,
                new Cell(List.of(src), false), new Cell(List.of(dst), false),
                new Cell(List.of(svc), false), ANY_CELL, action, "Log", "", extras);
    }

    // ------------------------------------------------------------------ 1. ServiceEntry vocabulary & sets

    @Test
    @SuppressWarnings("unchecked")
    void icmpServiceEntriesAndSetsAreEvaluableWhileIcmpv6AndIpProtoStayNonEvaluable() {
        assertNull(ServiceEntry.parse("icmp/8").notEvaluableReason());
        assertNull(ServiceEntry.parse("icmp/8/0").notEvaluableReason());
        assertNull(ServiceEntry.parse("icmp/0/0").notEvaluableReason());
        assertNull(ServiceEntry.parse("icmp/255/255").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("icmp6/128").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("icmp6/128/0").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("ipproto/50").notEvaluableReason());

        assertEquals(Set.of("icmp", "tcp", "udp"), ZoneMatrixPolicyBridge.allPorts().keySet());
        assertEquals(List.of(new PortRange(0, 65535)), ZoneMatrixPolicyBridge.allPorts().get("icmp"));

        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        service.saveServiceSet("security-1", store.draft().orElseThrow().revision(), "ping-v4", List.of("icmp/8", "icmp/0/0"));
        service.saveServiceSet("security-1", store.draft().orElseThrow().revision(), "ping-v6", List.of("icmp/8", "icmp6/128"));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_LISTED", List.of("set:ping-v4", "set:ping-v6", "ipproto/50"), "MEDIUM", "", ""));
        publish(service, store);

        String versionId = store.active().orElseThrow().versionId();
        var versionPayload = service.version(versionId, View.NAMES, "viewer-1", false);
        var matrixView = (Map<String, Object>) versionPayload.get("matrix");
        var serviceSets = (List<Map<String, Object>>) matrixView.get("service_sets");
        Map<String, List<Map<String, Object>>> entriesBySet = new LinkedHashMap<>();
        for (Map<String, Object> s : serviceSets) {
            entriesBySet.put((String) s.get("name"), (List<Map<String, Object>>) s.get("entries"));
        }
        assertTrue(entriesBySet.get("ping-v4").stream().allMatch(e -> Boolean.TRUE.equals(e.get("evaluable"))));
        assertTrue(entriesBySet.get("ping-v6").stream()
                .anyMatch(e -> "icmp6/128".equals(e.get("entry")) && Boolean.FALSE.equals(e.get("evaluable"))));

        var cellServices = (List<Map<String, Object>>) ((List<Map<String, Object>>) matrixView.get("cells")).get(0).get("services");
        Map<String, Boolean> evaluableByEntry = new LinkedHashMap<>();
        for (Map<String, Object> row : cellServices) {
            evaluableByEntry.put((String) row.get("entry"), (Boolean) row.get("evaluable"));
        }
        assertEquals(Boolean.FALSE, evaluableByEntry.get("set:ping-v6"));
        assertEquals(Boolean.FALSE, evaluableByEntry.get("ipproto/50"));

        ZoneMatrixContent published = ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER);
        assertEquals(List.of("icmp/0/0", "icmp/8", "icmp6/128", "ipproto/50"),
                published.effective(zUser, zApp).services());
        assertNull(ServiceEntry.parse("set:ping-v4").notEvaluableReason());
    }

    // ------------------------------------------------------------------ 2. CP service-icmp compliant & violating cells

    @Test
    void checkPointServiceIcmpEvaluatesAgainstAllowListedSetsAndDenyAllWithCanonicalSyntax() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zExact = createZone(service, store, "Z-EXACT", "DMZ", "198.51.100.0/26");
        String zType = createZone(service, store, "Z-TYPE", "APPLICATION", "198.51.100.64/26");
        String zSet = createZone(service, store, "Z-SET", "MANAGEMENT", "198.51.100.128/26");
        String zTcpOnly = createZone(service, store, "Z-TCP", "APPLICATION", "198.51.100.192/26");
        String zWrongCode = createZone(service, store, "Z-CODE1", "PARTNER", "203.0.113.0/26");
        String zWrongType = createZone(service, store, "Z-TYPE0", "PARTNER", "203.0.113.64/26");
        String zDbDeny = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.128/25");

        service.saveServiceSet("security-1", store.draft().orElseThrow().revision(), "diag-icmp", List.of("icmp/8"));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zExact, "ALLOW_LISTED", List.of("icmp/8/0", "icmp/0/0", "icmp/255/255"), "LOW", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zType, "ALLOW_LISTED", List.of("icmp/8"), "LOW", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zSet, "ALLOW_LISTED", List.of("set:diag-icmp"), "LOW", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zTcpOnly, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zWrongCode, "ALLOW_LISTED", List.of("icmp/8/1"), "HIGH", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zWrongType, "ALLOW_LISTED", List.of("icmp/0/0"), "HIGH", "", ""));
        // zUser -> zDbDeny remains default DENY_ALL.
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        objects.put("d-exact", object("d-exact", "host", "ipv4-address: 198.51.100.10"));
        objects.put("d-type", object("d-type", "host", "ipv4-address: 198.51.100.70"));
        objects.put("d-set", object("d-set", "host", "ipv4-address: 198.51.100.140"));
        objects.put("d-tcp", object("d-tcp", "host", "ipv4-address: 198.51.100.200"));
        objects.put("d-code1", object("d-code1", "host", "ipv4-address: 203.0.113.10"));
        objects.put("d-type0", object("d-type0", "host", "ipv4-address: 203.0.113.70"));
        objects.put("d-db", object("d-db", "host", "ipv4-address: 203.0.113.200"));

        // Native "service-icmp" (with cosmetic color:) and CheckPointPolicyMapper-shaped "service" ("protocol: icmp")
        objects.put("echo-req", object("echo-req", "service-icmp",
                "color: forest green", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        objects.put("echo-mapped", object("echo-mapped", "service",
                "color: blue", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        objects.put("icmp-min", object("icmp-min", "service-icmp", "protocol: icmp", "icmp-type: 0", "icmp-code: 0"));
        objects.put("icmp-max", object("icmp-max", "service-icmp", "protocol: icmp", "icmp-type: 255", "icmp-code: 255"));

        // Full type 8 via 256 members (0..255) to exercise canonical full-type vs partial-type formatting
        List<String> allType8Members = new ArrayList<>(256);
        for (int c = 0; c < 256; c++) {
            String mid = "t8-c" + c;
            objects.put(mid, object(mid, "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: " + c));
            allType8Members.add(mid);
        }
        objects.put("grp-type8-all", new PolicyObject("grp-type8-all", "GRP-T8-ALL", "service-group",
                allType8Members, List.of(), "RESOLVED"));

        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        List<Rule> rules = List.of(
                cpRule("r-exact", 1, "u1", "d-exact", "echo-req", "Accept", cpEx),
                cpRule("r-type", 2, "u1", "d-type", "echo-mapped", "Accept", cpEx),
                cpRule("r-set", 3, "u1", "d-set", "echo-req", "Accept", cpEx),
                cpRule("r-minmax", 4, "u1", "d-exact", "icmp-min", "Accept", cpEx),
                cpRule("r-max", 5, "u1", "d-exact", "icmp-max", "Accept", cpEx),
                cpRule("r-deny", 6, "u1", "d-db", "echo-req", "Accept", cpEx),
                cpRule("r-tcponly", 7, "u1", "d-tcp", "echo-req", "Accept", cpEx),
                cpRule("r-code1", 8, "u1", "d-code1", "echo-req", "Accept", cpEx),
                cpRule("r-type0", 9, "u1", "d-type0", "echo-mapped", "Accept", cpEx),
                cpRule("r-full-type-deny", 10, "u1", "d-db", "grp-type8-all", "Accept", cpEx),
                cpRule("r-full-type-minus-zero", 11, "u1", "d-exact", "grp-type8-all", "Accept", cpEx),
                cpRule("r-any-svc", 12, "u1", "d-type", "obj-any", "Accept", cpEx));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-icmp", "CP"),
                List.of(new Section("s-net", "Network", "CP access layer", null, rules)), objects);
        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7d-2", 1, matrix, NOW);
        Map<String, RuleEvaluation> byId = new HashMap<>();
        res.rules().forEach(r -> byId.put(r.ruleId(), r));

        for (String okId : List.of("r-exact", "r-type", "r-set", "r-minmax", "r-max")) {
            assertEquals(OUTCOME_COMPLIANT, byId.get(okId).outcome(), okId);
            assertTrue(byId.get(okId).violatedCells().isEmpty(), okId);
        }

        for (String badId : List.of("r-deny", "r-tcponly", "r-code1", "r-type0")) {
            RuleEvaluation ev = byId.get(badId);
            assertEquals(OUTCOME_VIOLATION, ev.outcome(), badId);
            assertEquals(1, ev.violatedCells().size(), badId);
            CellViolation cv = ev.violatedCells().get(0);
            assertEquals(List.of("icmp/8/0"), cv.notAllowedServices(), badId);
            assertEquals("icmp/8/0", cv.witness().service(), badId);
        }

        // Full [8*256, 8*256+255] interval formats strictly as canonical "icmp/8"
        CellViolation fullDeny = byId.get("r-full-type-deny").violatedCells().get(0);
        assertEquals(List.of("icmp/8"), fullDeny.notAllowedServices());
        assertEquals("icmp/8/0", fullDeny.witness().service());

        // Partial [8*256+1, 8*256+255] interval formats strictly as canonical individual "icmp/8/<code>" entries
        CellViolation partialAllow = byId.get("r-full-type-minus-zero").violatedCells().get(0);
        assertEquals(255, partialAllow.notAllowedServices().size());
        assertEquals("icmp/8/1", partialAllow.notAllowedServices().get(0));
        assertEquals("icmp/8/255", partialAllow.notAllowedServices().get(254));
        assertEquals("icmp/8/1", partialAllow.witness().service());
        for (String entry : partialAllow.notAllowedServices()) {
            assertEquals(entry, ServiceEntry.parse(entry).text());
        }

        // ZM-5: service = any in ALLOW_LISTED (icmp/8) keeps identical lowerAny behaviour
        CellViolation anyViolation = byId.get("r-any-svc").violatedCells().get(0);
        assertEquals(List.of("any", "tcp/0-65535", "udp/0-65535"), anyViolation.notAllowedServices());
        assertEquals("tcp/0", anyViolation.witness().service());
    }

    // ------------------------------------------------------------------ 3. Mixed service-group (tcp + udp + icmp)

    @Test
    void mixedServiceGroupEvaluatesTcpUdpAndIcmpCleanlyAndReportsOnlyDisallowedParts() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zBothAllowed = createZone(service, store, "Z-BOTH", "APPLICATION", "198.51.100.0/26");
        String zTcpUdpOnly = createZone(service, store, "Z-TCPUDP", "APPLICATION", "198.51.100.64/26");
        String zIcmpOnly = createZone(service, store, "Z-ICMP", "DMZ", "198.51.100.128/26");
        String zDbDeny = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");

        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zBothAllowed, "ALLOW_LISTED", List.of("tcp/443", "udp/53", "icmp/8/0"), "LOW", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zTcpUdpOnly, "ALLOW_LISTED", List.of("tcp/443", "udp/53"), "HIGH", "", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zIcmpOnly, "ALLOW_LISTED", List.of("icmp/8"), "HIGH", "", ""));
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        objects.put("d-both", object("d-both", "host", "ipv4-address: 198.51.100.10"));
        objects.put("d-tcpudp", object("d-tcpudp", "host", "ipv4-address: 198.51.100.70"));
        objects.put("d-icmp", object("d-icmp", "host", "ipv4-address: 198.51.100.140"));
        objects.put("d-db", object("d-db", "host", "ipv4-address: 203.0.113.10"));
        objects.put("https", object("https", "service-tcp", "protocol: tcp", "port: 443"));
        objects.put("dns", object("dns", "service-udp", "protocol: udp", "port: 53"));
        objects.put("echo-req", object("echo-req", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        objects.put("grp-web-ping", group("grp-web-ping", "service-group", "https", "echo-req"));
        objects.put("grp-triple", group("grp-triple", "group", "https", "dns", "echo-req"));

        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        List<Rule> rules = List.of(
                cpRule("r-both-ok", 1, "u1", "d-both", "grp-triple", "Accept", cpEx),
                cpRule("r-only-tcp-allowed", 2, "u1", "d-tcpudp", "grp-web-ping", "Accept", cpEx),
                cpRule("r-only-icmp-allowed", 3, "u1", "d-icmp", "grp-web-ping", "Accept", cpEx),
                cpRule("r-deny-all", 4, "u1", "d-db", "grp-triple", "Accept", cpEx));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-grp", "CP"),
                List.of(new Section("s-net", "Network", "CP access layer", null, rules)), objects);
        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7d-3", 1, matrix, NOW);

        assertEquals(OUTCOME_COMPLIANT, res.rules().get(0).outcome());

        RuleEvaluation onlyTcpAllowed = res.rules().get(1);
        assertEquals(OUTCOME_VIOLATION, onlyTcpAllowed.outcome());
        assertEquals(List.of("icmp/8/0"), onlyTcpAllowed.violatedCells().get(0).notAllowedServices());
        assertEquals("icmp/8/0", onlyTcpAllowed.violatedCells().get(0).witness().service());

        RuleEvaluation onlyIcmpAllowed = res.rules().get(2);
        assertEquals(OUTCOME_VIOLATION, onlyIcmpAllowed.outcome());
        assertEquals(List.of("tcp/443"), onlyIcmpAllowed.violatedCells().get(0).notAllowedServices());
        assertEquals("tcp/443", onlyIcmpAllowed.violatedCells().get(0).witness().service());

        RuleEvaluation denyAll = res.rules().get(3);
        assertEquals(OUTCOME_VIOLATION, denyAll.outcome());
        assertEquals(List.of("tcp/443", "udp/53", "icmp/8/0"), denyAll.violatedCells().get(0).notAllowedServices());
        assertEquals("tcp/443", denyAll.violatedCells().get(0).witness().service());
    }

    // ------------------------------------------------------------------ 4. Mixed IPv4+IPv6 address with service-icmp (ZM-4 / ZM-19)

    @Test
    void mixedIpv4AndIpv6AddressWithServiceIcmpReportsIpv4ViolationOrIpv6NotEvaluableNeverCompliant() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24", "2001:db8:1::/48");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24", "2001:db8:2::/48");
        createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_LISTED", List.of("icmp/8/0"), "LOW", "", ""));
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("dual-user", object("dual-user", "host",
                "ipv4-address: 192.0.2.10", "ipv6-address: 2001:db8:1::10"));
        objects.put("app-v4", object("app-v4", "host", "ipv4-address: 198.51.100.10"));
        objects.put("db-v4", object("db-v4", "host", "ipv4-address: 203.0.113.10"));
        objects.put("echo-req", object("echo-req", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));

        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        Rule rViolates = cpRule("r-dual-viol", 1, "dual-user", "db-v4", "echo-req", "Accept", cpEx);
        Rule rIpv4Allowed = cpRule("r-dual-allow", 2, "dual-user", "app-v4", "echo-req", "Accept", cpEx);

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-dual", "CP"),
                List.of(new Section("s-net", "Network", "CP access layer", null, List.of(rViolates, rIpv4Allowed))), objects);
        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7d-4", 1, matrix, NOW);

        RuleEvaluation evViol = res.rules().get(0);
        assertEquals(OUTCOME_VIOLATION, evViol.outcome());
        assertEquals(List.of("icmp/8/0"), evViol.violatedCells().get(0).notAllowedServices());
        assertEquals("icmp/8/0", evViol.violatedCells().get(0).witness().service());
        assertTrue(evViol.undecidedParts().contains("IPv6 not evaluated"));

        RuleEvaluation evUndecided = res.rules().get(1);
        assertNotEquals(OUTCOME_COMPLIANT, evUndecided.outcome(), "ZM-4/ZM-19: never COMPLIANT while IPv6 is not evaluated");
        assertEquals(OUTCOME_NOT_EVALUABLE, evUndecided.outcome());
        assertEquals("IPv6 not evaluated", evUndecided.reason());
    }

    // ------------------------------------------------------------------ 5. Fail-closed A0-I1 pins

    @Test
    void failClosedA0I1PinsRejectMissingCodeOutOfRangeExtraFieldsIcmp6NegationAndPaloAltoIcmp() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        // Even an ALLOW_ANY cell must NEVER turn an unresolvable service selector into COMPLIANT (§5.3, ZM-4).
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_ANY", List.of(), "LOW", "", ""));
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> cpObjects = new LinkedHashMap<>();
        cpObjects.put("obj-any", OBJ_ANY);
        cpObjects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        cpObjects.put("a1", object("a1", "host", "ipv4-address: 198.51.100.10"));
        cpObjects.put("https", object("https", "service-tcp", "protocol: tcp", "port: 443"));
        cpObjects.put("valid-echo", object("valid-echo", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        cpObjects.put("no-code", object("no-code", "service-icmp", "protocol: icmp", "icmp-type: 8"));
        cpObjects.put("mapped-no-code", object("mapped-no-code", "service", "protocol: icmp", "icmp-type: 8"));
        cpObjects.put("no-type", object("no-type", "service-icmp", "protocol: icmp", "icmp-code: 0"));
        cpObjects.put("type-neg", object("type-neg", "service-icmp", "protocol: icmp", "icmp-type: -1", "icmp-code: 0"));
        cpObjects.put("type-256", object("type-256", "service-icmp", "protocol: icmp", "icmp-type: 256", "icmp-code: 0"));
        cpObjects.put("code-neg", object("code-neg", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: -1"));
        cpObjects.put("code-256", object("code-256", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 256"));
        cpObjects.put("non-num", object("non-num", "service-icmp", "protocol: icmp", "icmp-type: echo", "icmp-code: 0"));
        cpObjects.put("with-exp", object("with-exp", "service-icmp",
                "protocol: icmp", "icmp-type: 8", "icmp-code: 0", "exp: icmp_error"));
        cpObjects.put("with-srcport", object("with-srcport", "service-icmp",
                "protocol: icmp", "icmp-type: 8", "icmp-code: 0", "source-port: 1024"));
        cpObjects.put("dup-code", object("dup-code", "service-icmp",
                "protocol: icmp", "icmp-type: 8", "icmp-code: 0", "icmp-code: 1"));
        cpObjects.put("wrong-proto", object("wrong-proto", "service-icmp",
                "protocol: tcp", "icmp-type: 8", "icmp-code: 0"));
        cpObjects.put("icmp6-native", object("icmp6-native", "service-icmp6",
                "protocol: icmp6", "icmp-type: 128", "icmp-code: 0"));
        cpObjects.put("icmp6-mapped", object("icmp6-mapped", "service",
                "protocol: icmp6", "icmp-type: 128", "icmp-code: 0"));
        cpObjects.put("grp-bad-member", group("grp-bad-member", "service-group", "https", "no-code"));

        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        List<String> unsupportedIds = List.of(
                "no-code", "mapped-no-code", "no-type", "type-neg", "type-256", "code-neg", "code-256",
                "non-num", "with-exp", "with-srcport", "dup-code", "wrong-proto", "icmp6-native", "icmp6-mapped",
                "grp-bad-member");
        List<Rule> cpRules = new ArrayList<>();
        int n = 1;
        for (String svcId : unsupportedIds) {
            cpRules.add(cpRule("cp-" + svcId, n++, "u1", "a1", svcId, "Accept", cpEx));
        }
        cpRules.add(new Rule("cp-neg-icmp", "uid-cp-neg-icmp", n, "Rule-cp-neg-icmp", true,
                new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                new Cell(List.of("valid-echo"), true), ANY_CELL, "Accept", "Log", "", cpEx));

        PolicySnapshot cpSnap = new PolicySnapshot(meta("pol-cp-fail", "CP"),
                List.of(new Section("s-cp", "Network", "CP access layer", null, cpRules)), cpObjects);
        EvaluationResult cpRes = ZoneMatrixEvaluator.evaluate(cpSnap, "v-7d-5", 1, matrix, NOW);
        assertEquals(cpRules.size(), cpRes.rules().size());
        for (int i = 0; i < unsupportedIds.size(); i++) {
            RuleEvaluation ev = cpRes.rules().get(i);
            assertEquals(OUTCOME_NOT_EVALUABLE, ev.outcome(), ev.ruleId());
            assertEquals("unsupported service constraint", ev.reason(), ev.ruleId());
        }
        RuleEvaluation negEv = cpRes.rules().get(unsupportedIds.size());
        assertEquals(OUTCOME_NOT_EVALUABLE, negEv.outcome());
        assertEquals("negated selector", negEv.reason());

        // Palo Alto (cp == false) has no frozen A0 citation for ICMP services: fail closed in every shape.
        Map<String, PolicyObject> panObjects = new LinkedHashMap<>();
        panObjects.put("obj-any", OBJ_ANY);
        panObjects.put("u1", object("u1", "address", "ip-netmask: 192.0.2.10/32"));
        panObjects.put("a1", object("a1", "address", "ip-netmask: 198.51.100.10/32"));
        panObjects.put("pan-icmp-cp-shape", object("pan-icmp-cp-shape", "service-icmp",
                "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        panObjects.put("pan-icmp-svc-shape", object("pan-icmp-svc-shape", "service",
                "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        panObjects.put("pan-icmp-xpath", object("pan-icmp-xpath", "service",
                "protocol/icmp/type: 8", "protocol/icmp/code: 0"));

        List<Rule> panRules = List.of(
                new Rule("pan-1", "uid-pan-1", 1, "PanIcmp1", true,
                        new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                        new Cell(List.of("pan-icmp-cp-shape"), false), ANY_CELL, "allow", "Log", "", Map.of()),
                new Rule("pan-2", "uid-pan-2", 2, "PanIcmp2", true,
                        new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                        new Cell(List.of("pan-icmp-svc-shape"), false), ANY_CELL, "allow", "Log", "", Map.of()),
                new Rule("pan-3", "uid-pan-3", 3, "PanIcmp3", true,
                        new Cell(List.of("u1"), false), new Cell(List.of("a1"), false),
                        new Cell(List.of("pan-icmp-xpath"), false), ANY_CELL, "allow", "Log", "", Map.of()));

        PolicySnapshot panSnap = new PolicySnapshot(meta("pol-pan-fail", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-pan-fail", "PAN device-group", "pre-rulebase"),
                        "Pre rules", "PAN device-group", null, panRules)), panObjects);
        EvaluationResult panRes = ZoneMatrixEvaluator.evaluate(panSnap, "v-7d-5", 1, matrix, NOW);
        for (RuleEvaluation ev : panRes.rules()) {
            assertEquals(OUTCOME_NOT_EVALUABLE, ev.outcome(), ev.ruleId());
            assertEquals("unsupported service constraint", ev.reason(), ev.ruleId());
        }
    }

    // ------------------------------------------------------------------ 6. NAT dual view (§5.6, ZM-14)

    @Test
    void natDualViewTranslatesIcmpRuleUnderAnyOrMatchingIcmpNatAndSkipsDisjointTcpOnlyDnat() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/25");
        String zDmz = createZone(service, store, "Z-DMZ", "DMZ", "192.0.2.128/25");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zDmz, "ALLOW_LISTED", List.of("icmp/8/0", "tcp/443"), "LOW", "", ""));
        // Z-USER -> Z-DB remains default DENY_ALL.
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        objects.put("vip-dmz", object("vip-dmz", "host", "ipv4-address: 192.0.2.200"));
        objects.put("real-db", object("real-db", "host", "ipv4-address: 203.0.113.50"));
        objects.put("https", object("https", "service-tcp", "protocol: tcp", "port: 443"));
        objects.put("echo-req", object("echo-req", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));

        Rule icmpRule = cpRule("sec-icmp", 1, "u1", "vip-dmz", "echo-req", "Accept",
                Map.of("layer-name", List.of("Network")));

        // 6a) any-service DNAT translates vip-dmz -> real-db (DENY_ALL): VIOLATION + NAT_MAY_APPLY
        Rule natAny = new Rule("nat-any", "uid-nat-any", 1, "AnyDnatToDb", true,
                ANY_CELL, new Cell(List.of("vip-dmz"), false), ANY_CELL, new Cell(List.of(), false),
                "static", "None", "", Map.of("translated-destination", List.of("real-db")));
        PolicySnapshot snapAnyNat = new PolicySnapshot(meta("pol-cp-nat-any", "CP"),
                List.of(
                        new Section("s-sec", "Network", "CP access layer", null, List.of(icmpRule)),
                        new Section("s-nat", "NAT", "CP NAT rulebase", null, List.of(natAny))),
                objects);
        RuleEvaluation evAnyNat = ZoneMatrixEvaluator.evaluate(snapAnyNat, "v-7d-6", 1, matrix, NOW).rules().get(0);
        assertEquals(OUTCOME_VIOLATION, evAnyNat.outcome());
        assertTrue(evAnyNat.flags().contains("NAT_MAY_APPLY"));
        assertEquals("TRANSLATED", evAnyNat.natViewSummary());
        assertEquals(zDb, evAnyNat.violatedCells().get(0).destinationZone());
        assertEquals(List.of("icmp/8/0"), evAnyNat.violatedCells().get(0).notAllowedServices());
        assertEquals("icmp/8/0", evAnyNat.violatedCells().get(0).witness().service());

        // 6b) tcp/443-only DNAT is provably disjoint from icmp/8/0: skipped -> COMPLIANT
        Rule natTcpOnly = new Rule("nat-tcp", "uid-nat-tcp", 1, "TcpOnlyDnatToDb", true,
                ANY_CELL, new Cell(List.of("vip-dmz"), false), new Cell(List.of("https"), false), new Cell(List.of(), false),
                "static", "None", "", Map.of("translated-destination", List.of("real-db")));
        PolicySnapshot snapTcpNat = new PolicySnapshot(meta("pol-cp-nat-tcp", "CP"),
                List.of(
                        new Section("s-sec", "Network", "CP access layer", null, List.of(icmpRule)),
                        new Section("s-nat", "NAT", "CP NAT rulebase", null, List.of(natTcpOnly))),
                objects);
        RuleEvaluation evTcpNat = ZoneMatrixEvaluator.evaluate(snapTcpNat, "v-7d-6", 1, matrix, NOW).rules().get(0);
        assertEquals(OUTCOME_COMPLIANT, evTcpNat.outcome());
        assertFalse(evTcpNat.flags().contains("NAT_MAY_APPLY"));
        assertEquals("ORIGINAL", evTcpNat.natViewSummary());

        // 6c) icmp/8/0-specific DNAT matches icmp/8/0: VIOLATION + NAT_MAY_APPLY
        Rule natIcmp = new Rule("nat-icmp", "uid-nat-icmp", 1, "IcmpDnatToDb", true,
                ANY_CELL, new Cell(List.of("vip-dmz"), false), new Cell(List.of("echo-req"), false), new Cell(List.of(), false),
                "static", "None", "", Map.of("translated-destination", List.of("real-db")));
        PolicySnapshot snapIcmpNat = new PolicySnapshot(meta("pol-cp-nat-icmp", "CP"),
                List.of(
                        new Section("s-sec", "Network", "CP access layer", null, List.of(icmpRule)),
                        new Section("s-nat", "NAT", "CP NAT rulebase", null, List.of(natIcmp))),
                objects);
        RuleEvaluation evIcmpNat = ZoneMatrixEvaluator.evaluate(snapIcmpNat, "v-7d-6", 1, matrix, NOW).rules().get(0);
        assertEquals(OUTCOME_VIOLATION, evIcmpNat.outcome());
        assertTrue(evIcmpNat.flags().contains("NAT_MAY_APPLY"));
    }

    // ------------------------------------------------------------------ 7. Ordered layers (ZM-16) & inline Apply Layer (ZM-17)

    @Test
    void multipleOrderedLayersFlagIcmpWitnessOutsideHeadlineAndInlineLayerIntersectsIcmpWithParent() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        String zDb = createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_LISTED", List.of("icmp/8"), "LOW", "", ""));
        // Z-USER -> Z-DB remains default DENY_ALL.
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("policy-targets", new PolicyObject("policy-targets", "Policy Targets", "targets",
                List.of(), List.of("native-type: Global"), "UNSUPPORTED"));
        objects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        objects.put("a1", object("a1", "host", "ipv4-address: 198.51.100.10"));
        objects.put("d1", object("d1", "host", "ipv4-address: 203.0.113.10"));
        objects.put("echo-req", object("echo-req", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        objects.put("echo-rep", object("echo-rep", "service-icmp", "protocol: icmp", "icmp-type: 0", "icmp-code: 0"));

        // Part A: 2 ordered layers (§5.5, ZM-16) — ICMP has no TCP/UDP witness, so lookup yields OTHER_ORDERED_LAYERS_UNKNOWN
        Map<String, List<String>> l1Ex = Map.of(
                "layer-name", List.of("Network"), "install-on", List.of("policy-targets"), "time", List.of("obj-any"));
        Map<String, List<String>> l2Ex = Map.of(
                "layer-name", List.of("Application"), "install-on", List.of("policy-targets"), "time", List.of("obj-any"));
        Rule l1IcmpViol = cpRule("l1-icmp", 1, "u1", "d1", "echo-req", "Accept", l1Ex);
        Rule l1Clean = cpRule("l1-clean", 2, "obj-any", "obj-any", "obj-any", "Drop", l1Ex);
        Rule l2AllowApp = cpRule("l2-app", 1, "u1", "a1", "echo-req", "Accept", l2Ex);
        Rule l2Clean = cpRule("l2-clean", 2, "obj-any", "obj-any", "obj-any", "Drop", l2Ex);

        PolicySnapshot twoLayerSnap = new PolicySnapshot(meta("pol-cp-2layer", "CP"),
                List.of(
                        new Section("sec-l1", "Network", "CP access layer", null, List.of(l1IcmpViol, l1Clean)),
                        new Section("sec-l2", "Application", "CP access layer", null, List.of(l2AllowApp, l2Clean))),
                objects);
        EvaluationResult twoLayerRes = ZoneMatrixEvaluator.evaluate(twoLayerSnap, "v-7d-7a", 1, matrix, NOW);
        Map<String, RuleEvaluation> twoLayerById = new HashMap<>();
        twoLayerRes.rules().forEach(r -> twoLayerById.put(r.ruleId(), r));

        RuleEvaluation evL1 = twoLayerById.get("l1-icmp");
        assertEquals(OUTCOME_VIOLATION, evL1.outcome());
        assertTrue(evL1.flags().contains("OTHER_ORDERED_LAYERS_UNKNOWN"));
        assertEquals(0, twoLayerRes.headline().distinctViolatingRules());
        assertEquals(1, twoLayerRes.headline().outsideHeadlineOrderedLayer());
        assertEquals(OUTCOME_COMPLIANT, twoLayerById.get("l2-app").outcome());

        // Part B: Inline Apply Layer parent & child intersection (§5.2, ZM-17)
        Rule pCompliant = cpRule("p-ok", 1, "u1", "a1", "echo-req", "Apply Layer",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-1")));
        Rule pScoped = cpRule("p-scoped", 2, "u1", "d1", "echo-req", "Apply Layer",
                Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"), "inline-layer", List.of("Inline-2")));
        Map<String, List<String>> inEx = Map.of("layer-name", List.of("Inline-2"), "install-on", List.of("obj-any"));
        Rule cDisjointIcmp = cpRule("c-disjoint", 1, "obj-any", "obj-any", "echo-rep", "Accept", inEx);
        Rule cIntersectIcmp = cpRule("c-viol", 2, "obj-any", "obj-any", "obj-any", "Accept", inEx);
        Rule cCleanup = cpRule("c-clean", 3, "obj-any", "obj-any", "obj-any", "Drop", inEx);

        PolicySnapshot inlineSnap = new PolicySnapshot(meta("pol-cp-inline-icmp", "CP"),
                List.of(
                        new Section("s-main", "Network", "CP access layer", null, List.of(pCompliant, pScoped)),
                        new Section("s-in2", "Inline-2", "CP access layer", "p-scoped",
                                List.of(cDisjointIcmp, cIntersectIcmp, cCleanup))),
                objects);
        EvaluationResult inlineRes = ZoneMatrixEvaluator.evaluate(inlineSnap, "v-7d-7b", 1, matrix, NOW);
        assertEquals(1, inlineRes.notApplicable().applyLayerDecidedByInline());
        Map<String, RuleEvaluation> inlineById = new HashMap<>();
        inlineRes.rules().forEach(r -> inlineById.put(r.ruleId(), r));

        assertEquals(OUTCOME_COMPLIANT, inlineById.get("p-ok").outcome());
        assertEquals(OUTCOME_COMPLIANT, inlineById.get("c-disjoint").outcome(),
                "echo-reply (0/0) intersected with parent echo-request (8/0) is empty");
        RuleEvaluation childViol = inlineById.get("c-viol");
        assertEquals(OUTCOME_VIOLATION, childViol.outcome());
        assertEquals(List.of(zUser + "->" + zDb), childViol.touchedPairs());
        assertEquals(List.of("icmp/8/0"), childViol.violatedCells().get(0).notAllowedServices());
        assertEquals("icmp/8/0", childViol.violatedCells().get(0).witness().service());
    }

    // ------------------------------------------------------------------ 8. Determinism (ZM-9)

    @Test
    void determinismProducesIdenticalFindingsAndHeadlineAcrossRepeatedEvaluations() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = newService(store);
        String zUser = createZone(service, store, "Z-USER", "USER", "192.0.2.0/24");
        String zApp = createZone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        createZone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.setCell("security-1", store.draft().orElseThrow().revision(),
                new CellInput(zUser, zApp, "ALLOW_LISTED", List.of("icmp/8/0", "tcp/443"), "MEDIUM", "", ""));
        ZoneMatrixContent matrix = publish(service, store);

        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("u1", object("u1", "host", "ipv4-address: 192.0.2.10"));
        objects.put("a1", object("a1", "host", "ipv4-address: 198.51.100.10"));
        objects.put("d1", object("d1", "host", "ipv4-address: 203.0.113.10"));
        objects.put("echo-req", object("echo-req", "service-icmp", "protocol: icmp", "icmp-type: 8", "icmp-code: 0"));
        objects.put("echo-no-code", object("echo-no-code", "service-icmp", "protocol: icmp", "icmp-type: 8"));

        Map<String, List<String>> cpEx = Map.of("layer-name", List.of("Network"));
        List<Rule> rules = List.of(
                cpRule("r1", 1, "u1", "a1", "echo-req", "Accept", cpEx),
                cpRule("r2", 2, "u1", "d1", "echo-req", "Accept", cpEx),
                cpRule("r3", 3, "u1", "a1", "echo-no-code", "Accept", cpEx));

        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp-det", "CP"),
                List.of(new Section("s-net", "Network", "CP access layer", null, rules)), objects);

        EvaluationResult first = ZoneMatrixEvaluator.evaluate(snap, "v-7d-8", 1, matrix, NOW);
        EvaluationResult second = ZoneMatrixEvaluator.evaluate(snap, "v-7d-8", 1, matrix, NOW);
        assertEquals(first.version(), second.version());
        assertEquals(first.outcomeCounts(), second.outcomeCounts());
        assertEquals(first.rules(), second.rules());
        assertEquals(first.headline(), second.headline());
        assertEquals(List.of(OUTCOME_COMPLIANT, OUTCOME_VIOLATION, OUTCOME_NOT_EVALUABLE),
                first.rules().stream().map(RuleEvaluation::outcome).toList());
    }
}
