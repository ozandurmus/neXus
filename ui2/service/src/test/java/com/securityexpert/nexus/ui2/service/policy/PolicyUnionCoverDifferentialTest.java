package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Design section 9, phase P2 "differential tests" end to end: random small Check Point rulebases over eight
 * documentation hosts (192.0.2.0/29) and four TCP ports, with anchors (a time constraint, an unresolved object) and
 * disabled rules. A brute-force enumeration over that universe -- plus one address and one service outside it, which only
 * {@code any} matches -- decides which rules the union of earlier readable rules covers while no single earlier rule
 * does; the I3 insights must be exactly those rules. It also holds the shadow engine to the same oracle: every rule a
 * single earlier rule covers is REDUNDANT or CONFLICT there, and never I3. Synthetic values only.
 */
class PolicyUnionCoverDifferentialTest {
    static final int HOSTS = 8, PORTS = 4, OUTSIDE = -1;
    /** Address or port sets per object id; null for any. */
    static final Map<String, Set<Integer>> ADDRESSES = new LinkedHashMap<>(), SERVICES = new LinkedHashMap<>();
    static final Map<String, PolicyObject> OBJECTS = new HashMap<>();
    static {
        OBJECTS.put("any", object("any", "any"));
        ADDRESSES.put("any", null); SERVICES.put("any", null);
        for (int h = 0; h < HOSTS; h++) { OBJECTS.put("h" + h, object("h" + h, "address", "ipv4-address: 192.0.2." + h)); ADDRESSES.put("h" + h, Set.of(h)); }
        OBJECTS.put("n0", object("n0", "address", "ip-netmask: 192.0.2.0/30")); ADDRESSES.put("n0", Set.of(0, 1, 2, 3));
        OBJECTS.put("n1", object("n1", "address", "ip-netmask: 192.0.2.4/30")); ADDRESSES.put("n1", Set.of(4, 5, 6, 7));
        OBJECTS.put("n8", object("n8", "address", "ip-netmask: 192.0.2.0/29")); ADDRESSES.put("n8", Set.of(0, 1, 2, 3, 4, 5, 6, 7));
        OBJECTS.put("g0", new PolicyObject("g0", "OBJ-GROUP-00", "group", List.of("h1", "h6"), List.of(), "RESOLVED")); ADDRESSES.put("g0", Set.of(1, 6));
        OBJECTS.put("g1", new PolicyObject("g1", "OBJ-GROUP-01", "group", List.of("n0", "h5"), List.of(), "RESOLVED")); ADDRESSES.put("g1", Set.of(0, 1, 2, 3, 5));
        for (int p = 1; p <= PORTS; p++) { OBJECTS.put("p" + p, object("p" + p, "service", "protocol: tcp", "port: " + p)); SERVICES.put("p" + p, Set.of(p)); }
        OBJECTS.put("p12", object("p12", "service", "protocol: tcp", "port: 1-2")); SERVICES.put("p12", Set.of(1, 2));
        OBJECTS.put("p34", object("p34", "service", "protocol: tcp", "port: 3-4")); SERVICES.put("p34", Set.of(3, 4));
        OBJECTS.put("p14", object("p14", "service", "protocol: tcp", "port: 1-4")); SERVICES.put("p14", Set.of(1, 2, 3, 4));
        OBJECTS.put("t1", new PolicyObject("t1", "OBJ-TIME-01", "time", List.of(), List.of(), "RESOLVED"));
    }
    record Synthetic(Rule rule, boolean readable) {}

    /** {@code coarse}: halves and wholes only (n0, n1, n8; p12, p34, p14), so unions of earlier rules often cover a later one. */
    static Synthetic rule(Random random, int i, boolean coarse) {
        List<String> addresses = coarse ? List.of("n0", "n1", "n8", "g1") : new ArrayList<>(ADDRESSES.keySet());
        List<String> services = coarse ? List.of("p12", "p34", "p14") : new ArrayList<>(SERVICES.keySet());
        String source = !coarse && random.nextInt(4) == 0 ? "any" : addresses.get(random.nextInt(addresses.size()));
        String destination = !coarse && random.nextInt(3) == 0 ? "any" : addresses.get(random.nextInt(addresses.size()));
        String service = !coarse && random.nextInt(4) == 0 ? "any" : services.get(random.nextInt(services.size()));
        int kind = random.nextInt(14);
        // 0: time constraint (anchor), 1: unresolved object (anchor), 2: disabled; otherwise a readable rule.
        if (kind == 1) source = "missing";
        Map<String, List<String>> extras = new HashMap<>(Map.of("time", List.of(kind == 0 ? "t1" : "any"), "install-on", List.of("any"), "layer-name", List.of("LAYER-ALPHA")));
        var rule = new Rule("r" + i, "uuid-r" + i, i + 1, "OBJ-RULE-" + i, kind != 2, new Cell(List.of(source), false), new Cell(List.of(destination), false),
            new Cell(List.of(service), false), new Cell(List.of(), false), random.nextInt(3) == 0 ? "Drop" : "Accept", "Log", "", extras);
        return new Synthetic(rule, kind > 2);
    }
    static Set<Integer> axis(Map<String, Set<Integer>> table, Cell cell, int size, boolean ports) {
        var values = table.get(cell.refs().get(0));
        if (values != null) return values;
        Set<Integer> all = new TreeSet<>(); all.add(OUTSIDE);
        for (int v = ports ? 1 : 0; v < (ports ? size + 1 : size); v++) all.add(v);
        return all;
    }
    /** Every (source, destination, port) point a readable rule matches, OUTSIDE standing for every value no object names. */
    static Set<List<Integer>> points(Rule rule) {
        Set<List<Integer>> out = new HashSet<>();
        for (int s : axis(ADDRESSES, rule.source(), HOSTS, false)) for (int d : axis(ADDRESSES, rule.destination(), HOSTS, false))
            for (int p : axis(SERVICES, rule.service(), PORTS, true)) out.add(List.of(s, d, p));
        return out;
    }

    @Test void i3IsExactlyTheBruteForceUnionCoverOnRandomCheckPointRulebases() {
        var random = new Random(1125L);
        int covered = 0, single = 0;
        for (int trial = 0; trial < 400; trial++) {
            List<Synthetic> rules = new ArrayList<>();
            boolean coarse = trial % 2 == 0;
            for (int i = 0, n = 3 + random.nextInt(9); i < n; i++) rules.add(rule(random, i, coarse));
            var section = new Section("s1", "OBJ-SECTION-01", "CP access layer", null, rules.stream().map(Synthetic::rule).toList());
            var base = PolicyHygieneTest.sections("CP", section);
            var snapshot = new PolicySnapshot(base.metadata(), base.sections(), OBJECTS);
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90);
            Set<String> expected = new TreeSet<>(), singles = new TreeSet<>();
            for (int k = 0; k < rules.size(); k++) {
                if (!rules.get(k).readable()) continue;
                var subject = points(rules.get(k).rule());
                Set<List<Integer>> union = new HashSet<>(); boolean one = false;
                for (int j = 0; j < k; j++) {
                    if (!rules.get(j).readable()) continue;
                    var prior = points(rules.get(j).rule());
                    union.addAll(prior); one |= prior.containsAll(subject);
                }
                String id = rules.get(k).rule().id();
                if (one) singles.add(id); else if (union.containsAll(subject)) expected.add(id);
            }
            Set<String> found = new TreeSet<>();
            for (var i : PolicyInsightsTest.of(report, "I3")) {
                assertEquals("PROVEN", i.grade(), "trial " + trial);
                found.add(i.rules().get(0).ruleId());
                // The listed covering set alone covers the subject, by brute force.
                Set<List<Integer>> union = new HashSet<>();
                for (var ref : i.rules().subList(1, i.rules().size()))
                    union.addAll(points(rules.stream().filter(r -> r.rule().id().equals(ref.ruleId())).findFirst().orElseThrow().rule()));
                assertTrue(union.containsAll(points(rules.stream().filter(r -> r.rule().id().equals(i.rules().get(0).ruleId())).findFirst().orElseThrow().rule())), "trial " + trial);
            }
            assertEquals(expected, found, "trial " + trial);
            for (String id : singles) assertTrue(Set.of("REDUNDANT", "CONFLICT").contains(hygiene.rules().get(id).shadowStatus()), "trial " + trial + " " + id);
            covered += expected.size(); single += singles.size();
        }
        assertTrue(covered >= 40, "union covers exercised: " + covered);
        assertTrue(single >= 40, "single covers exercised: " + single);
    }
}
