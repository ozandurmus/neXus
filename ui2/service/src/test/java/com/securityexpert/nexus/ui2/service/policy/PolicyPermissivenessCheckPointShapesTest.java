package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Check Point shapes behind "Unresolved: object without values" and the PO 2026-10-20 addendum (access role 0 points,
 * security zone 1 point). Synthetic documentation addresses only.
 */
class PolicyPermissivenessCheckPointShapesTest {
    private static final Cell EMPTY = new Cell(List.of(), false);
    private static PolicyObject object(String id, String name, String type, String status, List<String> members, String... values) {
        return new PolicyObject(id, name, type, members, List.of(values), status);
    }
    private static Map<String, PolicyObject> base() {
        var objects = new HashMap<String, PolicyObject>();
        objects.put("any", object("any", "ANY", "any", "RESOLVED", List.of()));
        objects.put("host", object("host", "OBJ-ADDRESS-01", "address", "RESOLVED", List.of(), "ipv4-address: 192.0.2.8"));
        objects.put("https", object("https", "OBJ-SERVICE-01", "service", "RESOLVED", List.of(), "protocol: tcp", "port: 443"));
        return objects;
    }
    private static Rule rule(String source, String destination, String service) {
        return new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of(source), false), new Cell(List.of(destination), false),
            new Cell(List.of(service), false), EMPTY, "Accept", "Log", "", Map.of());
    }
    private static Map<String, Object> cp(Rule rule, Map<String, PolicyObject> objects) {
        return PolicyRuleMetrics.permissiveness(rule, objects, new int[]{PolicyRuleMetrics.RULE_BUDGET}, "CP");
    }
    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> verdict) { return (List<String>) verdict.get("reasons"); }

    @Test void addressRangesInTheCheckPointRepresentationAreAssessable() {
        var objects = base();
        objects.put("range", object("range", "OBJ-ADDRESS-02", "address", "RESOLVED", List.of(),
            "ipv4-address-first: 192.0.2.10", "ipv4-address-last: 192.0.2.20"));
        objects.put("range6", object("range6", "OBJ-ADDRESS-03", "address", "RESOLVED", List.of(),
            "ipv6-address-first: 2001:db8::1", "ipv6-address-last: 2001:db8::9"));
        objects.put("bare", object("bare", "OBJ-ADDRESS-04", "address", "RESOLVED", List.of()));
        for (String source : List.of("range", "range6")) {
            var verdict = cp(rule(source, "host", "https"), objects);
            assertEquals("Low", verdict.get("level"), source + " " + reasons(verdict));
            assertEquals(0, verdict.get("score"));
        }
        // An address object that truly carries no values keeps the specific cause.
        var bare = cp(rule("bare", "host", "https"), objects);
        assertEquals("Unknown", bare.get("level"));
        assertTrue(reasons(bare).contains(PolicyRuleMetrics.NO_VALUES));
    }

    @Test void shadowingAndRuleSearchReadTheCheckPointRangeRepresentation() {
        var objects = base();
        objects.put("wide", object("wide", "OBJ-ADDRESS-05", "address", "RESOLVED", List.of(),
            "ipv4-address-first: 192.0.2.0", "ipv4-address-last: 192.0.2.255"));
        objects.put("narrow", object("narrow", "OBJ-ADDRESS-06", "address", "RESOLVED", List.of(),
            "ipv4-address-first: 192.0.2.10", "ipv4-address-last: 192.0.2.20"));
        objects.put("target", object("target", "GW-TANGO-01", "gateway", "RESOLVED", List.of()));
        objects.put("always", object("always", "ANY", "any", "RESOLVED", List.of()));
        var extras = Map.of("install-on", List.of("target"), "time", List.of("always"), "layer-name", List.of("OBJ-LAYER-01"));
        var first = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of("wide"), false), new Cell(List.of("host"), false),
            new Cell(List.of("https"), false), EMPTY, "Accept", "Log", "", extras);
        var second = new Rule("r2", "uuid-r2", 2, "OBJ-RULE-02", true, new Cell(List.of("narrow"), false), new Cell(List.of("host"), false),
            new Cell(List.of("https"), false), EMPTY, "Accept", "Log", "", extras);
        var meta = new Metadata("p1", "s1", "MGR-BRAVO-01", "CP", "c1", "DOM-TANGO-01", "POL-ALPHA-01", "2026-10-20T00:00:00Z", "a1", List.of());
        var snapshot = new PolicySnapshot(meta, List.of(new Section("l1", "OBJ-LAYER-01", "CP access layer", null, List.of(first, second))), objects);
        var result = PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-10-20T12:00:00Z"), 250_000);
        assertEquals("REDUNDANT", result.rules().get("r2").shadowStatus(), result.rules().get("r2").shadowReason());
        assertEquals("r1", result.rules().get("r2").coveringRuleId());
        // Rule search: the range is a proven interval.
        assertTrue(PolicyRuleQuery.compile("source.ip='192.0.2.15'", objects, Instant.parse("2026-10-20T12:00:00Z")).test(second));
        assertFalse(PolicyRuleQuery.compile("source.ip='192.0.2.30'", objects, Instant.parse("2026-10-20T12:00:00Z")).test(second));
    }

    @Test void servicesWithoutPortsAreSpecificProtocolSelections() {
        var objects = base();
        objects.put("icmp", object("icmp", "OBJ-SERVICE-02", "service", "RESOLVED", List.of(), "protocol: icmp", "icmp-type: 8"));
        objects.put("other", object("other", "OBJ-SERVICE-03", "service", "RESOLVED", List.of(), "protocol: other"));
        objects.put("dce", object("dce", "OBJ-SERVICE-04", "service", "RESOLVED", List.of(), "protocol: dce-rpc"));
        for (String service : List.of("icmp", "other", "dce")) {
            var verdict = cp(rule("host", "host", service), objects);
            assertEquals("Low", verdict.get("level"), service);
            assertEquals(0, verdict.get("score"), service);
        }
    }

    @Test void wildcardObjectsNameTheirOwnCause() {
        var objects = base();
        objects.put("wild", object("wild", "OBJ-ADDRESS-07", "unresolved", "UNSUPPORTED", List.of(),
            "ipv4-address: 192.0.2.0", "native-type: wildcard"));
        objects.put("future", object("future", "OBJ-ADDRESS-08", "unresolved", "UNSUPPORTED", List.of(), "native-type: CpmiSyntheticFuture"));
        var wild = cp(rule("wild", "host", "https"), objects);
        assertEquals("Unknown", wild.get("level"));
        assertTrue(reasons(wild).contains("Unresolved: wildcard object"), reasons(wild).toString());
        assertTrue(reasons(cp(rule("future", "host", "https"), objects)).contains(PolicyRuleMetrics.UNSUPPORTED));
    }

    /** PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md, addendum. */
    @Test void accessRolesAreSpecificAndSecurityZonesAddOnePoint() {
        var objects = base();
        objects.put("role", object("role", "OBJ-ROLE-01", "access-role", "DYNAMIC", List.of()));
        objects.put("zone", object("zone", "OBJ-ZONE-01", "security-zone", "DYNAMIC", List.of()));
        objects.put("zone2", object("zone2", "OBJ-ZONE-02", "security-zone", "DYNAMIC", List.of()));
        objects.put("dyn", object("dyn", "OBJ-DYN-01", "dynamic-object", "DYNAMIC", List.of()));

        var role = cp(rule("role", "host", "https"), objects);
        assertEquals("Low", role.get("level"), reasons(role).toString());
        assertEquals(0, role.get("score"));
        assertTrue(reasons(role).contains("Access role (identity-based)"));

        var zone = cp(rule("zone", "host", "https"), objects);
        assertEquals("Medium", zone.get("level"), reasons(zone).toString());
        assertEquals(1, zone.get("score"));
        assertTrue(reasons(zone).contains("Zone-based selection"));
        // Zones on both sides are one zone-based selection (one fixed reason, one point).
        assertEquals(1, cp(rule("zone", "zone2", "https"), objects).get("score"));
        // Any service (3) plus a zone (1): High.
        var broad = cp(rule("zone", "host", "any"), objects);
        assertEquals(4, broad.get("score"));
        assertEquals("High", broad.get("level"));
        // Other dynamic classes stay Unknown with their own cause.
        var dyn = cp(rule("dyn", "host", "https"), objects);
        assertEquals("Unknown", dyn.get("level"));
        assertTrue(reasons(dyn).contains("Unresolved: dynamic object"));
        for (String reason : List.of("Access role (identity-based)", "Zone-based selection", "Unresolved: wildcard object",
                "Object values resolved from the domain object inventory"))
            assertTrue(PolicyRuleMetrics.FIXED_REASONS.contains(reason), reason);
        // Shadowing still treats the dynamic selection as unevaluable.
        var meta = new Metadata("p1", "s1", "MGR-BRAVO-01", "CP", "c1", "DOM-TANGO-01", "POL-ALPHA-01", "2026-10-20T00:00:00Z", "a1", List.of());
        objects.put("target", object("target", "GW-TANGO-01", "gateway", "RESOLVED", List.of()));
        var roleRule = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of("role"), false), new Cell(List.of("host"), false),
            new Cell(List.of("https"), false), EMPTY, "Accept", "Log", "", Map.of("install-on", List.of("target"), "time", List.of("any"),
                "layer-name", List.of("OBJ-LAYER-01")));
        var snapshot = new PolicySnapshot(meta, List.of(new Section("l1", "OBJ-LAYER-01", "CP access layer", null, List.of(roleRule))), objects);
        var assessment = PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-10-20T12:00:00Z"), 250_000).rules().get("r1");
        assertEquals("UNKNOWN", assessment.shadowStatus());
        assertEquals("unresolved object", assessment.shadowReason());
        assertEquals("Low", assessment.permissiveness().get("level"));
    }

    @Test void theAddendumReasonsSurviveTheMaskedProjection() {
        var masked = new ObjectMapper().valueToTree(PolicyPrivacy.mask(Map.of("permissiveness", Map.of("level", "Medium", "score", 1,
            "reasons", List.of("Access role (identity-based)", "Zone-based selection", "Unresolved: wildcard object",
                "Object values resolved from the domain object inventory"))), "",
            new TopologyNamePseudonymizer(new byte[32]), new SubnetPreservingIpMasker(new byte[32])));
        assertEquals(4, masked.path("permissiveness").path("reasons").size());
    }

    private static CpObjectInventory.Item item(String uid, String type, List<String> members, String... values) {
        return new CpObjectInventory.Item(ref("source-1", "domain-1", "object", uid), uid, "OBJ-" + uid, type, members, List.of(values), null, List.of());
    }
    @Test void dictionaryObjectsWithoutValuesTakeTheirValuesFromTheDomainInventory() {
        var meta = new Metadata("policy-1", "source-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-10-05T00:00:00Z", "artifact-1", List.of());
        String range = ref(meta.id(), "object", "range-uid"), bare = ref(meta.id(), "object", "bare-uid"), group = ref(meta.id(), "object", "group-uid");
        String other = ref(meta.id(), "object", "other-uid"), any = ref(meta.id(), "object", "any-uid"), net = ref(meta.id(), "object", "net-uid");
        // A snapshot stored before the mapper read ipv4-address-first/-last: the range is RESOLVED without values.
        Map<String, PolicyObject> dictionary = new HashMap<>(Map.of(
            range, object(range, "OBJ-RANGE-01", "address", "RESOLVED", List.of()),
            bare, object(bare, "OBJ-ADDRESS-09", "address", "RESOLVED", List.of()),
            group, object(group, "OBJ-GROUP-01", "group", "RESOLVED", List.of()),
            other, object(other, "other-uid", "unresolved", "UNRESOLVED", List.of()),
            any, object(any, "ANY", "any", "RESOLVED", List.of())));
        var rule = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of(range, group), false), new Cell(List.of(any), false),
            new Cell(List.of(other), false), EMPTY, "Accept", "Log", "", Map.of());
        var bareRule = new Rule("r2", "uuid-r2", 2, "OBJ-RULE-02", true, new Cell(List.of(bare), false), new Cell(List.of(any), false),
            new Cell(List.of(other), false), EMPTY, "Accept", "Log", "", Map.of());
        var snapshot = new PolicySnapshot(meta, List.of(new Section("layer-1", "OBJ-LAYER-01", "CP access layer", null, List.of(rule, bareRule))), dictionary);
        var index = new PolicyDomainIndex(List.of(
            new CpObjectInventory("source-1", "domain-1", "address-ranges", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("range-uid", "address-range", List.of(), "ipv4-address-first: 192.0.2.10", "ipv4-address-last: 192.0.2.20"),
                item("bare-uid", "address-range", List.of())), 1, 1),
            new CpObjectInventory("source-1", "domain-1", "networks", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("net-uid", "network", List.of(), "subnet4: 198.51.100.0", "mask-length4: 24")), 1, 1),
            new CpObjectInventory("source-1", "domain-1", "groups", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("group-uid", "group", List.of("net-uid"))), 1, 1),
            // show-services-other: no captured field; the joined row keeps its protocol class as the mapper does.
            new CpObjectInventory("source-1", "domain-1", "services-other", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("other-uid", "service-other", List.of())), 1, 1)), List.of(snapshot));

        var before = PolicyRuleMetrics.permissiveness(rule, dictionary);
        assertEquals("Unknown", before.get("level"));
        assertTrue(reasons(before).contains(PolicyRuleMetrics.NO_VALUES));

        var joined = PolicyQueryService.breadthObjects(snapshot, index);
        assertEquals(List.of("ipv4-address-first: 192.0.2.10", "ipv4-address-last: 192.0.2.20"), joined.get(range).values());
        assertEquals("OBJ-RANGE-01", joined.get(range).name(), "the dictionary name is kept");
        assertEquals(List.of(net), joined.get(group).members());
        assertEquals(List.of("protocol: other"), joined.get(other).values());
        assertSame(dictionary.get(bare), joined.get(bare), "an inventory row that is itself empty changes nothing");

        var result = PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-10-09T12:00:00Z"), 250_000, joined).rules();
        var verdict = result.get("r1").permissiveness();
        assertEquals("Medium", verdict.get("level"), reasons(verdict).toString());
        assertEquals(3, verdict.get("score"));
        assertTrue(reasons(verdict).containsAll(List.of("Any destination", "Object values resolved from the domain object inventory",
            "Group members resolved from the domain object inventory")), reasons(verdict).toString());
        var still = result.get("r2").permissiveness();
        assertEquals("Unknown", still.get("level"));
        assertTrue(reasons(still).contains(PolicyRuleMetrics.NO_VALUES));
    }
}
