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

/** Permissiveness Unknown names its cause; resolvable selectors (named apps, application-default, CP inventory members) get a level. */
class PolicyPermissivenessCausesTest {
    private static final Cell EMPTY = new Cell(List.of(), false);
    private static PolicyObject object(String id, String name, String type, String status, List<String> members, String... values) {
        return new PolicyObject(id, name, type, members, List.of(values), status);
    }
    private static Map<String, PolicyObject> base() {
        var objects = new HashMap<String, PolicyObject>();
        objects.put("any", object("any", "ANY", "any", "RESOLVED", List.of()));
        objects.put("host", object("host", "OBJ-ADDRESS-01", "address", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32"));
        objects.put("https", object("https", "OBJ-SERVICE-01", "service", "RESOLVED", List.of(), "protocol/tcp/port: 443"));
        return objects;
    }
    private static Rule rule(String source, String service, String application) {
        return new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of(source), false), new Cell(List.of("host"), false),
            new Cell(List.of(service), false), application == null ? EMPTY : new Cell(List.of(application), false), "allow", "Log", "", Map.of());
    }
    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> verdict) { return (List<String>) verdict.get("reasons"); }

    @Test void eachUnassessableSelectorNamesItsCause() {
        var objects = base();
        objects.put("dyn", object("dyn", "OBJ-DYN-01", "dynamic-object", "DYNAMIC", List.of()));
        objects.put("role", object("role", "OBJ-ROLE-01", "access-role", "DYNAMIC", List.of()));
        objects.put("dag", object("dag", "OBJ-DAG-01", "address-group", "DYNAMIC", List.of(), "dynamic/filter: 'tag-a'"));
        objects.put("edl", object("edl", "OBJ-EDL-01", "external-list", "DYNAMIC", List.of(), "type: ip"));
        objects.put("fqdn", object("fqdn", "OBJ-FQDN-01", "address", "RESOLVED", List.of(), "fqdn: host.example.test"));
        objects.put("region", object("region", "OBJ-REGION-01", "region", "RESOLVED", List.of()));
        objects.put("excl", object("excl", "OBJ-GROUP-01", "group", "UNSUPPORTED", List.of()));
        objects.put("future", object("future", "OBJ-FUTURE-01", "unresolved", "UNSUPPORTED", List.of(), "native-type: CpmiSyntheticFuture"));
        objects.put("prec", object("prec", "OBJ-ADDRESS-02", "address", "UNKNOWN", List.of()));
        objects.put("missing", object("missing", "uid-missing", "unresolved", "UNRESOLVED", List.of()));
        objects.put("empty", object("empty", "OBJ-GROUP-02", "address-group", "RESOLVED", List.of()));
        objects.put("app", object("app", "web-app", "unresolved", "UNRESOLVED", List.of()));
        objects.put("filter", object("filter", "OBJ-FILTER-01", "application-filter", "DYNAMIC", List.of(), "risk: 5"));
        objects.put("svc", object("svc", "OBJ-SERVICE-02", "unresolved", "UNRESOLVED", List.of()));
        Map<Rule, String> expected = new LinkedHashMap<>();
        expected.put(rule("dyn", "https", null), "Unresolved: dynamic object");
        // An access role is no longer a gap for Check Point (PO 2026-10-20 addendum); see PolicyPermissivenessCheckPointShapesTest.
        expected.put(rule("dag", "https", null), "Unresolved: dynamic address group");
        expected.put(rule("edl", "https", null), "Unresolved: external dynamic list");
        expected.put(rule("fqdn", "https", null), "Unresolved: FQDN object");
        expected.put(rule("region", "https", null), "Unresolved: region object");
        expected.put(rule("excl", "https", null), "Unresolved: group with exclusion");
        expected.put(rule("future", "https", null), "Unresolved: unsupported object type");
        expected.put(rule("prec", "https", null), "Unresolved: object precedence unknown");
        expected.put(rule("missing", "https", null), "Unresolved: object not in collected evidence");
        expected.put(rule("empty", "https", null), "Unresolved: empty group");
        expected.put(rule("host", "https", "app"), "Unresolved: application not in collected configuration");
        expected.put(rule("host", "https", "filter"), "Unresolved: application filter");
        expected.put(rule("host", "svc", null), "Unresolved: service not in collected configuration");
        expected.forEach((rule, cause) -> {
            var verdict = PolicyRuleMetrics.permissiveness(rule, objects);
            assertEquals("Unknown", verdict.get("level"), cause);
            assertTrue(reasons(verdict).contains(cause), cause + " in " + reasons(verdict));
            assertFalse(reasons(verdict).contains("Incomplete objects; assessment requires analysis"));
            assertTrue(PolicyRuleMetrics.FIXED_REASONS.contains(cause));
        });
        // Broad reasons are still reported next to the cause; the verdict stays Unknown.
        var mixed = PolicyRuleMetrics.permissiveness(rule("any", "https", "app"), objects);
        assertEquals("Unknown", mixed.get("level"));
        assertTrue(reasons(mixed).contains("Any source"));
        var exhausted = PolicyRuleMetrics.permissiveness(rule("host", "https", null), objects, new int[]{1});
        assertEquals("Unknown", exhausted.get("level"));
        assertTrue(reasons(exhausted).contains("Analysis budget reached"));
    }

    @Test void namedApplicationsGatewaysAndApplicationDefaultAreAssessable() {
        var objects = base();
        objects.put("site", object("site", "OBJ-APP-01", "application", "RESOLVED", List.of()));
        objects.put("apps", object("apps", "OBJ-APPGROUP-01", "application-group", "RESOLVED", List.of("site")));
        objects.put("gw", object("gw", "OBJ-GATEWAY-01", "gateway", "RESOLVED", List.of()));
        objects.put("appdefault", object("appdefault", "application-default", "service", "RESOLVED", List.of()));
        // A Check Point application/site in the service column (the CP access rule has no application column).
        var cp = PolicyRuleMetrics.permissiveness(rule("host", "site", null), objects);
        assertEquals("Low", cp.get("level"));
        assertTrue(reasons(cp).contains("No broad selectors in resolved objects"));
        assertEquals("Low", PolicyRuleMetrics.permissiveness(rule("gw", "https", "apps"), objects).get("level"));
        // Palo Alto: application any on application-default ports scores 0 (PO 2026-10-20).
        var pan = PolicyRuleMetrics.permissiveness(rule("host", "appdefault", "any"), objects);
        assertEquals("Low", pan.get("level"));
        assertEquals(0, pan.get("score"));
        assertTrue(reasons(pan).containsAll(List.of("Any application (0 points: service is specific)", "Service: application default ports")));
        assertFalse(reasons(pan).contains("No broad selectors in resolved objects"));
        // A user object named like the keyword but carrying ports is an ordinary service.
        objects.put("named", object("named", "application-default", "service", "RESOLVED", List.of(), "protocol/tcp/port: 1-65535"));
        assertTrue(reasons(PolicyRuleMetrics.permissiveness(rule("host", "named", "any"), objects)).contains("Broad service range"));
    }

    @Test void shadowingBudgetNoLongerBlanksPermissivenessOrSchedule() {
        var second = rule("host", "https", "any");
        var rules = List.of(rule("any", "any", "any"), new Rule("r2", "uuid-r2", 2, "OBJ-RULE-02", true, second.source(),
            second.destination(), second.service(), second.application(), "allow", "Log", "", Map.of()));
        var snapshot = new PolicySnapshot(new Metadata("p1", "s1", "MGR-BRAVO-01", "PAN", "c1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-10-09T12:00:00Z", "a1", List.of()), List.of(new Section("s1", "Pre rules", "Shared", null, rules)), base());
        var result = PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-10-09T12:00:00Z"), 0);
        assertTrue(result.budgetReached());
        result.rules().values().forEach(a -> assertEquals("analysis budget", a.shadowReason()));
        assertEquals("High", result.rules().get("r1").permissiveness().get("level"));
        assertEquals("Low", result.rules().get("r2").permissiveness().get("level"));
        assertFalse(reasons(result.rules().get("r1").permissiveness()).contains("Analysis budget reached"));
    }

    /** PO_DECISION_RECORD_2026_10_20_POLICY_PERMISSIVENESS_SCORING.md, decision 1. */
    @Test void scoringTableFollowsThePoDecision() {
        var objects = base();
        objects.put("big", object("big", "OBJ-ADDRESS-03", "address", "RESOLVED", List.of(), "ip-netmask: 198.51.100.0/16"));
        objects.put("range", object("range", "OBJ-SERVICE-03", "service", "RESOLVED", List.of(), "protocol/tcp/port: 1-65535"));
        Map<Rule, List<Object>> expected = new LinkedHashMap<>();
        // Application any alone, with a specific service: 0 points, Low.
        expected.put(rule("host", "https", "any"), List.of(0, "Low"));
        // Application any with Any service: 3 + 1.
        expected.put(rule("host", "any", "any"), List.of(4, "High"));
        // Any source alone: 3, Medium; Any source and Any service: 6, High.
        expected.put(rule("any", "https", null), List.of(3, "Medium"));
        expected.put(rule("any", "any", null), List.of(6, "High"));
        // Large CIDR 1, Broad service range 1.
        expected.put(rule("big", "https", null), List.of(1, "Medium"));
        expected.put(rule("big", "range", null), List.of(2, "Medium"));
        expected.put(rule("host", "https", null), List.of(0, "Low"));
        expected.forEach((rule, want) -> {
            var verdict = PolicyRuleMetrics.permissiveness(rule, objects, new int[]{PolicyRuleMetrics.RULE_BUDGET}, "PAN");
            assertEquals(want.get(0), verdict.get("score"), reasons(verdict).toString());
            assertEquals(want.get(1), verdict.get("level"), reasons(verdict).toString());
        });
        var destination = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of("host"), false), new Cell(List.of("any"), false),
            new Cell(List.of("https"), false), EMPTY, "allow", "Log", "", Map.of());
        assertEquals(3, PolicyRuleMetrics.permissiveness(destination, objects).get("score"));
    }

    /** Decision 2: a Palo Alto predefined App-ID or service is a specific selection with its own reason. */
    @Test void paloAltoPredefinedApplicationsAndServicesAreSpecific() {
        var objects = base();
        objects.put("ssl", object("ssl", "ssl", "unresolved", "UNRESOLVED", List.of()));
        objects.put("svc-https", object("svc-https", "service-https", "unresolved", "UNRESOLVED", List.of()));
        objects.put("apps", object("apps", "OBJ-APPGROUP-01", "application-group", "RESOLVED", List.of("ssl")));
        objects.put("addr", object("addr", "OBJ-ADDRESS-09", "unresolved", "UNRESOLVED", List.of()));
        int[] budget = {PolicyRuleMetrics.RULE_BUDGET};
        for (Rule rule : List.of(rule("host", "https", "ssl"), rule("host", "svc-https", "ssl"), rule("host", "svc-https", "apps"))) {
            var verdict = PolicyRuleMetrics.permissiveness(rule, objects, new int[]{PolicyRuleMetrics.RULE_BUDGET}, "PAN");
            assertEquals("Low", verdict.get("level"), reasons(verdict).toString());
            assertEquals(0, verdict.get("score"));
            assertTrue(reasons(verdict).contains("Predefined App-ID/service, ports not collected"));
        }
        // Application any on a predefined service: the service is specific, so 0 points.
        var anyApp = PolicyRuleMetrics.permissiveness(rule("host", "svc-https", "any"), objects, budget, "PAN");
        assertEquals(0, anyApp.get("score"));
        assertTrue(reasons(anyApp).contains("Any application (0 points: service is specific)"));
        // An unresolved address is not covered by the decision; a Check Point dictionary miss is not a predefined object.
        assertEquals("Unknown", PolicyRuleMetrics.permissiveness(rule("addr", "https", null), objects, new int[]{2048}, "PAN").get("level"));
        var cp = PolicyRuleMetrics.permissiveness(rule("host", "svc-https", null), objects, new int[]{2048}, "CP");
        assertEquals("Unknown", cp.get("level"));
        assertTrue(reasons(cp).contains("Unresolved: service not in collected configuration"));
        assertTrue(PolicyRuleMetrics.FIXED_REASONS.contains("Predefined App-ID/service, ports not collected"));
    }

    private static CpObjectInventory.Item item(String uid, String type, List<String> members, String... values) {
        return new CpObjectInventory.Item(ref("source-1", "domain-1", "object", uid), uid, "OBJ-" + uid, type, members, List.of(values), null, List.of());
    }
    @Test void checkPointGroupMembersMissingFromTheDictionaryAreJoinedFromTheDomainInventory() {
        var meta = new Metadata("policy-1", "source-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-10-05T00:00:00Z", "artifact-1", List.of());
        String group = ref(meta.id(), "object", "group-uid"), host = ref(meta.id(), "object", "host-uid"), net = ref(meta.id(), "object", "net-uid");
        String any = ref(meta.id(), "object", "any-uid"), lost = ref(meta.id(), "object", "lost-uid");
        // The rulebase dictionary carried the group but not its members (the mapper's UNRESOLVED placeholders).
        Map<String, PolicyObject> dictionary = new HashMap<>(Map.of(
            group, object(group, "OBJ-GROUP-01", "group", "RESOLVED", List.of(host, lost)),
            host, object(host, "host-uid", "unresolved", "UNRESOLVED", List.of()),
            lost, object(lost, "lost-uid", "unresolved", "UNRESOLVED", List.of()),
            any, object(any, "ANY", "any", "RESOLVED", List.of())));
        var rule = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-01", true, new Cell(List.of(group), false), new Cell(List.of(any), false),
            new Cell(List.of(any), false), EMPTY, "Accept", "Log", "", Map.of());
        var snapshot = new PolicySnapshot(meta, List.of(new Section("layer-1", "OBJ-LAYER-01", "CP access layer", null, List.of(rule))), dictionary);
        var index = new PolicyDomainIndex(List.of(
            new CpObjectInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("host-uid", "host", List.of(), "ipv4-address: 192.0.2.8"),
                item("net-uid", "network", List.of(), "subnet4: 198.51.100.0", "mask-length4: 16")), 1, 1),
            new CpObjectInventory("source-1", "domain-1", "groups", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(
                item("lost-uid", "group", List.of("net-uid"))), 1, 1)), List.of(snapshot));

        // Before the join the member gap makes the verdict Unknown with a specific cause.
        var before = PolicyRuleMetrics.permissiveness(rule, dictionary);
        assertEquals("Unknown", before.get("level"));
        assertTrue(reasons(before).contains("Unresolved: object not in collected evidence"));

        var joined = PolicyQueryService.breadthObjects(snapshot, index);
        assertEquals("RESOLVED", joined.get(host).status());
        assertEquals("address", joined.get(host).type());
        assertEquals(List.of(net), joined.get(lost).members(), "nested inventory members are followed");
        assertEquals("RESOLVED", joined.get(net).status());
        assertSame(dictionary.get(group), joined.get(group), "dictionary objects are never replaced");

        var result = PolicyHygiene.analyze(snapshot, 90, Instant.parse("2026-10-09T12:00:00Z"), 250_000, joined).rules().get("r1");
        assertEquals("High", result.permissiveness().get("level"));
        assertTrue(reasons(result.permissiveness()).containsAll(List.of("Any destination", "Any service", "Large CIDR in source",
            "Group members resolved from the domain object inventory")));
        // Without an index (Palo Alto, or the inventory is unavailable) nothing changes.
        assertSame(snapshot.objects(), PolicyQueryService.breadthObjects(snapshot, null));
        // A member in neither the dictionary nor the inventory stays UNRESOLVED.
        var partial = new PolicyDomainIndex(List.of(new CpObjectInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z",
            "RESOLVED", "", List.of(item("host-uid", "host", List.of(), "ipv4-address: 192.0.2.8")), 1, 1)), List.of(snapshot));
        var still = PolicyRuleMetrics.permissiveness(rule, PolicyQueryService.breadthObjects(snapshot, partial));
        assertEquals("Unknown", still.get("level"));
        assertTrue(reasons(still).contains("Unresolved: object not in collected evidence"));
    }

    @Test void theMaskedProjectionKeepsTheFixedCausesAndDropsAnythingElse() {
        var names = new TopologyNamePseudonymizer(new byte[32]);
        var masked = new ObjectMapper().valueToTree(PolicyPrivacy.mask(Map.of("permissiveness", Map.of("level", "Unknown", "score", 0,
            "reasons", List.of("Unresolved: dynamic object", "Service: application default ports",
                "Group members resolved from the domain object inventory", "Unresolved: OBJ-LEAK-01"))), "", names,
            new SubnetPreservingIpMasker(new byte[32])));
        var reasons = new ArrayList<String>();
        masked.path("permissiveness").path("reasons").forEach(r -> reasons.add(r.asText()));
        assertEquals(List.of("Unresolved: dynamic object", "Service: application default ports",
            "Group members resolved from the domain object inventory"), reasons);
    }
}
