package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Extended shadowing resolution (negated address cells, Palo Alto source ports, Check Point ICMP codes) checked against
 * brute force. Every object uses a small set of values, so the full packet space falls into finitely many classes:
 * 16 hosts, "any other IPv4 address", "any IPv6 address"; destination ports 1000-1007 and "any other port"; source
 * ports 3000-3007 and "any other port"; TCP and UDP. One representative per class decides every selector exactly, so
 * enumerating the representatives is the ground truth. Every rule here resolves, so the engine must decide every rule
 * exactly as brute force does: the same status and the earliest covering rule.
 */
class PolicyHygieneExtendedResolutionTest {
    static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    static final int OTHER_V4 = 16, V6 = 17;
    static final int[] DPORTS = {1000, 1001, 1002, 1003, 1004, 1005, 1006, 1007, 2000};
    static final int[] SPORTS = {3000, 3001, 3002, 3003, 3004, 3005, 3006, 3007, 4000};

    record Svc(String protocol, Set<Integer> dports, Set<Integer> sports) {}
    record Spec(String id, Set<Integer> source, boolean sourceNegated, Set<Integer> destination, boolean destinationNegated,
            List<Svc> services, String action) {}

    static boolean address(Set<Integer> set, boolean negated, int host) {
        if (set == null) return true;
        boolean in = host < 16 && set.contains(host);
        return negated ? !in : in;
    }
    static boolean matches(Spec r, int src, int dst, String protocol, int sport, int dport) {
        if (!address(r.source(), r.sourceNegated(), src) || !address(r.destination(), r.destinationNegated(), dst)) return false;
        if (r.services() == null) return true;
        for (Svc s : r.services())
            if (s.protocol().equals(protocol) && s.dports().contains(dport) && (s.sports() == null || s.sports().contains(sport))) return true;
        return false;
    }
    /** Earlier rule {@code a} matches every packet {@code b} matches. */
    static boolean covers(Spec a, Spec b) {
        for (int src = 0; src <= V6; src++) for (int dst = 0; dst <= V6; dst++) for (String p : List.of("tcp", "udp"))
            for (int sp : SPORTS) for (int dp : DPORTS)
                if (matches(b, src, dst, p, sp, dp) && !matches(a, src, dst, p, sp, dp)) return false;
        return true;
    }

    static Set<Integer> subset(Random random, int size, int max) {
        Set<Integer> out = new TreeSet<>();
        while (out.size() < size) out.add(random.nextInt(max));
        return out;
    }
    static Spec spec(Random random, int i) {
        Set<Integer> source = random.nextInt(4) == 0 ? null : subset(random, 1 + random.nextInt(6), 16);
        Set<Integer> destination = random.nextInt(4) == 0 ? null : subset(random, 1 + random.nextInt(6), 16);
        List<Svc> services = null;
        if (random.nextInt(4) != 0) {
            services = new ArrayList<>();
            for (int k = 0, n = 1 + random.nextInt(3); k < n; k++) {
                Set<Integer> dports = new TreeSet<>(); for (int d : subset(random, 1 + random.nextInt(5), 8)) dports.add(1000 + d);
                Set<Integer> sports = null;
                if (random.nextBoolean()) { sports = new TreeSet<>(); for (int s : subset(random, 1 + random.nextInt(6), 8)) sports.add(3000 + s); }
                services.add(new Svc(random.nextBoolean() ? "tcp" : "udp", dports, sports));
            }
        }
        return new Spec("r" + i, source, source != null && random.nextInt(3) == 0, destination, destination != null && random.nextInt(3) == 0,
            services, random.nextBoolean() ? "allow" : "deny");
    }

    static String ports(Set<Integer> ports) {
        StringJoiner out = new StringJoiner(",");
        for (int p : ports) out.add(String.valueOf(p));
        return out.toString();
    }
    static PolicySnapshot snapshot(List<Spec> specs) {
        Map<String, PolicyObject> objects = new HashMap<>();
        objects.put("any", new PolicyObject("any", "any", "any", List.of(), List.of(), "RESOLVED"));
        for (int h = 0; h < 16; h++) objects.put("h" + h, new PolicyObject("h" + h, "ADDR-" + h, "address", List.of(), List.of("ip-netmask: 192.0.2." + h + "/32"), "RESOLVED"));
        List<Rule> rules = new ArrayList<>();
        int number = 1;
        for (Spec r : specs) {
            Cell source = addressCell(objects, r.id() + "-src", r.source(), r.sourceNegated());
            Cell destination = addressCell(objects, r.id() + "-dst", r.destination(), r.destinationNegated());
            Cell service;
            if (r.services() == null) service = new Cell(List.of("any"), false);
            else {
                List<String> refs = new ArrayList<>();
                for (int k = 0; k < r.services().size(); k++) {
                    Svc s = r.services().get(k); String id = r.id() + "-svc" + k;
                    List<String> values = new ArrayList<>(List.of("protocol/" + s.protocol() + "/port: " + ports(s.dports())));
                    if (s.sports() != null) values.add("protocol/" + s.protocol() + "/source-port: " + ports(s.sports()));
                    objects.put(id, new PolicyObject(id, "SVC-" + id, "service", List.of(), values, "RESOLVED"));
                    refs.add(id);
                }
                service = new Cell(refs, false);
            }
            rules.add(new Rule(r.id(), r.id(), number++, "RULE-" + r.id(), true, source, destination, service, new Cell(List.of("any"), false),
                r.action(), "Log", "", Map.of("from", List.of("any"), "to", List.of("any"))));
        }
        var metadata = new Metadata("policy-synthetic", "source-synthetic", "MGR-BRAVO-01", "PAN", "dg-synthetic", "DG-TANGO-01",
            "POL-ALPHA-01", "2026-11-13T00:00:00Z", "a1", List.of());
        return new PolicySnapshot(metadata, List.of(new Section("sec-1", "Security", "Shared", null, rules)), objects);
    }
    static Cell addressCell(Map<String, PolicyObject> objects, String id, Set<Integer> hosts, boolean negated) {
        if (hosts == null) return new Cell(List.of("any"), false);
        List<String> members = new ArrayList<>(); for (int h : hosts) members.add("h" + h);
        objects.put(id, new PolicyObject(id, "GRP-" + id, "address-group", members, List.of(), "RESOLVED"));
        return new Cell(List.of(id), negated);
    }

    @Test void everyVerdictMatchesBruteForceOverTheFullPacketSpace() {
        int decided = 0, covered = 0, negatedRules = 0, sourcePortRules = 0;
        for (int seed = 1; seed <= 300; seed++) {
            var random = new Random(seed);
            List<Spec> specs = new ArrayList<>();
            for (int i = 0, n = 3 + random.nextInt(6); i < n; i++) specs.add(spec(random, i));
            var result = PolicyHygiene.analyze(snapshot(specs), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            for (int j = 0; j < specs.size(); j++) {
                Spec later = specs.get(j);
                if (later.sourceNegated() || later.destinationNegated()) negatedRules++;
                if (later.services() != null && later.services().stream().anyMatch(s -> s.sports() != null)) sourcePortRules++;
                String expectedCover = null;
                for (int i = 0; i < j && expectedCover == null; i++) if (covers(specs.get(i), later)) expectedCover = specs.get(i).id();
                var a = result.get(later.id());
                String where = "seed " + seed + " rule " + later.id();
                assertNotEquals("UNKNOWN", a.shadowStatus(), where + " " + a.shadowReason());
                decided++;
                if (expectedCover == null) assertEquals("NOT_SHADOWED", a.shadowStatus(), where);
                else {
                    covered++;
                    assertEquals(expectedCover, a.coveringRuleId(), where);
                    Spec prior = specs.get(Integer.parseInt(expectedCover.substring(1)));
                    assertEquals(prior.action().equals(later.action()) ? "REDUNDANT" : "CONFLICT", a.shadowStatus(), where);
                }
            }
        }
        assertTrue(covered > 100 && decided - covered > 100, "both outcomes exercised: covered " + covered + " of " + decided);
        assertTrue(negatedRules > 100 && sourcePortRules > 100, "constructs exercised: negated " + negatedRules + ", source port " + sourcePortRules);
    }

    @Test void formerResolutionLeavesTheExtendedConstructsUnknown() {
        var specs = List.of(new Spec("r0", null, false, null, false, null, "allow"),
            new Spec("r1", Set.of(1, 2), true, null, false, null, "allow"),
            new Spec("r2", null, false, null, false, List.of(new Svc("tcp", Set.of(1000), Set.of(3000))), "deny"));
        PolicyNormalization.Resolver.EXTENDED.set(false);
        try {
            var result = PolicyHygiene.analyze(snapshot(specs), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            assertEquals("negated selector", result.get("r1").shadowReason());
            assertEquals("unsupported service constraint", result.get("r2").shadowReason());
        } finally { PolicyNormalization.Resolver.EXTENDED.remove(); }
        var result = PolicyHygiene.analyze(snapshot(specs), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("REDUNDANT", result.get("r1").shadowStatus());
        assertEquals("CONFLICT", result.get("r2").shadowStatus());
    }

    @Test void checkPointIcmpResolvesOnlyWithAStoredCode() {
        var budget = new PolicyNormalization.Budget(100_000);
        Map<String, PolicyObject> objects = Map.of(
            "echo", new PolicyObject("echo", "SVC-ECHO", "service", List.of(), List.of("protocol: icmp", "icmp-type: 8", "icmp-code: 0"), "RESOLVED"),
            "unreach", new PolicyObject("unreach", "SVC-UNREACH", "service", List.of(), List.of("protocol: icmp", "icmp-type: 3"), "RESOLVED"),
            "echo6", new PolicyObject("echo6", "SVC-ECHO6", "service", List.of(), List.of("protocol: icmp6", "icmp-type: 128", "icmp-code: 0"), "RESOLVED"),
            "greater", new PolicyObject("greater", "SVC-HIGH", "service", List.of(), List.of("protocol: tcp", "port: >1023"), "RESOLVED"));
        var snapshot = new PolicySnapshot(new Metadata("p", "s", "MGR", "CP", "c", "DOM", "POL", "2026-11-13T00:00:00Z", "a", List.of()), List.of(), objects);
        var resolver = PolicyNormalization.Resolver.shadowing(snapshot, budget);
        var echo = resolver.cell(new Cell(List.of("echo"), false), "service");
        assertEquals(Map.of("icmp", List.of(new PolicyNormalization.Range(8 * 256, 8 * 256))), echo.ranges());
        assertEquals(Set.of("icmp6"), resolver.cell(new Cell(List.of("echo6"), false), "service").ranges().keySet());
        // No stored code: the API default is code 0, not "every code", so the object stays unresolved.
        assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(new Cell(List.of("unreach"), false), "service"));
        // The comparison port forms are not described by the vendor: unresolved.
        assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(new Cell(List.of("greater"), false), "service"));
        // ICMP and TCP never overlap; ICMP and ICMPv6 neither.
        var tcp = new PolicyNormalization.Selector(false, Map.of("tcp", List.of(new PolicyNormalization.Range(0, 65535))));
        assertTrue(PolicyNormalization.disjoint(echo, tcp, budget));
        assertTrue(PolicyNormalization.disjoint(echo, resolver.cell(new Cell(List.of("echo6"), false), "service"), budget));
        // The plain resolver (lookup, NAT, path analysis) keeps them unresolved.
        var plain = new PolicyNormalization.Resolver(objects, budget);
        assertThrows(PolicyNormalization.Unknown.class, () -> plain.cell(new Cell(List.of("echo"), false), "service"));
    }
}
