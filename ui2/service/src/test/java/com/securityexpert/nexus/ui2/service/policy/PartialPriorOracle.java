package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import java.util.*;

/**
 * Brute-force check, independent of the production selector resolution and disjointness code, that a rule released
 * from "earlier rule has unsupported constraints" to NOT_SHADOWED shares no packet with any earlier rule of its scope
 * that has no full match. It interprets the stored objects itself over a small, finite universe that holds every value
 * the synthetic generators can produce (all of 192.0.2.0/24, 198.51.100.0/24 and 203.0.113.0/24 plus one outside
 * address; TCP and UDP ports 0-1100 and 65535; every zone; every install-on target plus one more), enumerates each
 * dimension's members and checks the intersection. A rule's match is the product of its dimensions, so the two rules
 * share a packet exactly when every dimension's intersection is non-empty; the witness packet is one member of each.
 * <p>Everything the oracle cannot interpret -- an unresolved, dynamic, unsupported, negated, cyclic or empty cell, a
 * zone rule type, an unknown placement -- matches the whole universe; an earlier rule with a field of unknown meaning
 * matches the whole universe in every dimension. Narrowing constraints (time, schedule, VPN, content, user, category)
 * are ignored, which only enlarges the earlier rule's match. The oracle is therefore never more willing to call two
 * rules disjoint than the real policy is.
 * <p>Palo Alto zone supersets: a rule that leaves "zone or install-on coverage is unproven" (or, for an intrazone or
 * interzone rule, "zone rule type constraint") for REDUNDANT or CONFLICT must be covered, by brute force, by the rule
 * named as covering: every dimension of the later rule, with the zones read as the set of (from, to) pairs its rule type
 * matches, lies inside the covering rule's. One that becomes NOT_SHADOWED must be covered by no earlier fully resolved
 * rule of its scope and share no packet with any unresolved one. Zone pairs follow the PAN-OS rule types: universal
 * from x to, intrazone (z, z) for z in from, interzone from x to without (z, z); any is every zone, including one no
 * rule names.
 */
final class PartialPriorOracle {
    private PartialPriorOracle() {}
    static final String UNSUPPORTED = "earlier rule has unsupported constraints";
    static final String UNPROVEN = "zone or install-on coverage is unproven", RULE_TYPE = "zone rule type constraint";
    private static final List<long[]> BLOCKS = List.of(new long[]{ip("192.0.2.0"), ip("192.0.2.255")},
        new long[]{ip("198.51.100.0"), ip("198.51.100.255")}, new long[]{ip("203.0.113.0"), ip("203.0.113.255")});
    private static final long OUTSIDE = ip("10.0.0.1");
    private static final int PORTS = 1101, HIGH_PORT = 65535;
    private static final int ADDRESS_SIZE = 3 * 256 + 1, SERVICE_SIZE = 2 * (PORTS + 1), APPLICATION_SIZE = 2;

    /** One snapshot's interpreted universe and scopes. */
    static final class Universe {
        final PolicySnapshot snapshot;
        final List<String> zones, targets;
        final Map<String, Rule> rules = new LinkedHashMap<>();
        final Map<String, Integer> scope = new HashMap<>(), order = new HashMap<>();
        final Map<String, Section> sections = new HashMap<>();
        Universe(PolicySnapshot snapshot) {
            this.snapshot = snapshot;
            var zoneSet = new TreeSet<>(SyntheticPolicies.ZONES); var targetSet = new TreeSet<String>();
            for (var o : snapshot.objects().values()) if (Set.of("simple-gateway", "gateway", "host").contains(o.type())) targetSet.add(o.id());
            for (var s : snapshot.sections()) for (var r : s.rules()) {
                for (String key : List.of("from", "to")) for (String z : r.extras().getOrDefault(key, List.of())) if (!z.equals("any")) zoneSet.add(z);
                for (String t : r.extras().getOrDefault("install-on", List.of())) targetSet.add(t);
            }
            zoneSet.add("ZONE-OUTSIDE-99"); targetSet.add("gw-outside-99");
            zones = List.copyOf(zoneSet); targets = List.copyOf(targetSet);
            // Scope membership is the shared, separately tested evaluation-scope walk; one token per scope.
            var walk = new PolicyNormalization.EvaluationScope<Object>(snapshot.metadata(), Object::new);
            Map<Object, Integer> tokens = new IdentityHashMap<>(); int n = 0;
            for (var section : snapshot.sections()) {
                if (PolicySnapshot.outsideSecurityEvaluation(snapshot.metadata(), section)) continue;
                Object token = walk.advance(section);
                int id = tokens.computeIfAbsent(token, k -> tokens.size());
                for (var rule : section.rules()) { rules.put(rule.id(), rule); scope.put(rule.id(), id); order.put(rule.id(), n++); sections.put(rule.id(), section); }
            }
        }
        /** The earlier rules of the rule's scope, enabled or possibly enabled, that have no full match. */
        List<Rule> unresolvedPriors(String ruleId) {
            var resolver = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(Integer.MAX_VALUE));
            List<Rule> out = new ArrayList<>();
            for (var rule : rules.values()) {
                if (order.get(rule.id()) >= order.get(ruleId) || !scope.get(rule.id()).equals(scope.get(ruleId))) continue;
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                if (PolicyNormalization.normalize(snapshot, sections.get(rule.id()), rule, resolver).problem() != null) out.add(rule);
            }
            return out;
        }
        /** A packet (source, destination, service, application, from, to, target) both rules match, or null. */
        String sharedPacket(Rule prior, Rule later) {
            var a = dimensions(prior, sections.get(prior.id())); var b = dimensions(later, sections.get(later.id()));
            List<Integer> witness = new ArrayList<>();
            for (int d = 0; d < a.size(); d++) {
                var both = (BitSet) a.get(d).clone(); both.and(b.get(d));
                if (both.isEmpty()) return null;
                witness.add(both.nextSetBit(0));
            }
            return witness.toString();
        }
        private List<BitSet> dimensions(Rule rule, Section section) {
            var meta = snapshot.metadata(); boolean cp = meta.vendor().equals("CP");
            List<BitSet> all = List.of(full(ADDRESS_SIZE), full(ADDRESS_SIZE), full(SERVICE_SIZE), full(APPLICATION_SIZE),
                full(zones.size()), full(zones.size()), full(targets.size()));
            if (!Set.of("CP", "PAN").contains(meta.vendor()) || PolicySnapshot.outsideSecurityEvaluation(meta, section)) return all;
            for (String key : rule.extras().keySet())
                if (!known(key, rule, cp)) return all;
            boolean universal = rule.extras().getOrDefault("rule-type", List.of("universal")).equals(List.of("universal"));
            var app = rule.application();
            return List.of(cell(rule.source(), "address"), cell(rule.destination(), "address"), cell(rule.service(), "service"),
                cp && app.refs().isEmpty() && !app.negated() ? full(APPLICATION_SIZE) : cell(app, "application"),
                universal ? zones(rule.extras().get("from")) : full(zones.size()), universal ? zones(rule.extras().get("to")) : full(zones.size()),
                targets(rule.extras().get("install-on")));
        }
        /** The earlier rules of the rule's scope, enabled, with a full match: the only rules that may cover it. */
        List<Rule> resolvedPriors(String ruleId) {
            var resolver = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(Integer.MAX_VALUE));
            List<Rule> out = new ArrayList<>();
            for (var rule : rules.values()) {
                if (order.get(rule.id()) >= order.get(ruleId) || !scope.get(rule.id()).equals(scope.get(ruleId))) continue;
                if (!Boolean.TRUE.equals(rule.enabled())) continue;
                if (PolicyNormalization.normalize(snapshot, sections.get(rule.id()), rule, resolver).problem() == null) out.add(rule);
            }
            return out;
        }
        /** The earlier rules of the rule's scope that are enabled or possibly enabled, resolved or not. */
        List<Rule> earlierRules(String ruleId) {
            List<Rule> out = new ArrayList<>();
            for (var rule : rules.values())
                if (order.get(rule.id()) < order.get(ruleId) && scope.get(rule.id()).equals(scope.get(ruleId)) && !Boolean.FALSE.equals(rule.enabled())) out.add(rule);
            return out;
        }
        /** Every packet the later rule matches, the prior matches too: per dimension, zones as (from, to) pairs. */
        boolean covers(Rule prior, Rule later) {
            var a = coverDimensions(prior); var b = coverDimensions(later);
            for (int d = 0; d < a.size(); d++) {
                var outside = (BitSet) b.get(d).clone(); outside.andNot(a.get(d));
                if (!outside.isEmpty()) return false;
            }
            return true;
        }
        private List<BitSet> coverDimensions(Rule rule) {
            var app = rule.application(); boolean cp = snapshot.metadata().vendor().equals("CP");
            // A field of unknown meaning may widen the match: the whole universe (as the disjointness oracle does).
            for (String key : rule.extras().keySet())
                if (!known(key, rule, cp)) return List.of(full(ADDRESS_SIZE), full(ADDRESS_SIZE), full(SERVICE_SIZE), full(APPLICATION_SIZE),
                    full(zones.size() * zones.size()), full(targets.size()));
            return List.of(cell(rule.source(), "address"), cell(rule.destination(), "address"), cell(rule.service(), "service"),
                cp && app.refs().isEmpty() && !app.negated() ? full(APPLICATION_SIZE) : cell(app, "application"),
                pairs(rule), targets(rule.extras().get("install-on")));
        }
        private BitSet pairs(Rule rule) {
            int n = zones.size(); var out = new BitSet(n * n);
            var type = rule.extras().getOrDefault("rule-type", List.of("universal"));
            BitSet from = zones(rule.extras().get("from")), to = zones(rule.extras().get("to"));
            for (int s = 0; s < n; s++) for (int d = 0; d < n; d++) {
                boolean match = type.equals(List.of("universal")) ? from.get(s) && to.get(d)
                    : type.equals(List.of("intrazone")) ? s == d && from.get(s)
                    : type.equals(List.of("interzone")) ? s != d && from.get(s) && to.get(d) : true;
                if (match) out.set(s * n + d);
            }
            return out;
        }
        private static boolean known(String key, Rule rule, boolean cp) {
            return Set.of("time", "schedule", "vpn", "content", "source-user", "category", "from", "to", "install-on", "rule-type",
                    "tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(key)
                || key.startsWith("track-") || key.startsWith("profile-setting/")
                || key.equals("inline-layer") && cp && rule.action().equals("Apply Layer");
        }
        private BitSet cell(Cell cell, String family) {
            int size = family.equals("address") ? ADDRESS_SIZE : family.equals("service") ? SERVICE_SIZE : APPLICATION_SIZE;
            if (cell.negated() || cell.refs().isEmpty()) return full(size);
            var out = new BitSet(size);
            for (String id : cell.refs()) out.or(object(id, family, size, 0));
            return out;
        }
        private BitSet object(String id, String family, int size, int depth) {
            var o = snapshot.objects().get(id);
            if (o == null || !"RESOLVED".equals(o.status()) || depth > 40) return full(size);
            if (o.type().equals("any")) return full(size);
            if (Set.of("group", "address-group", "service-group", "application-group").contains(o.type())) {
                if (o.members().isEmpty() || !o.values().isEmpty()) return full(size);
                var out = new BitSet(size);
                for (String member : o.members()) out.or(object(member, family, size, depth + 1));
                return out;
            }
            if (!o.members().isEmpty()) return full(size);
            if (family.equals("address") && Set.of("address", "host", "network", "address-range").contains(o.type())) return address(o.values());
            if (family.equals("service") && Set.of("service", "service-tcp", "service-udp").contains(o.type())) return service(o.values());
            return full(size);
        }
        private static BitSet address(List<String> values) {
            var out = new BitSet(ADDRESS_SIZE); Map<String, String> f = new HashMap<>();
            for (String v : values) { int i = v.indexOf(": "); f.put(i < 0 ? "literal" : v.substring(0, i), i < 0 ? v : v.substring(i + 2)); }
            for (var e : f.entrySet()) {
                String k = e.getKey(), v = e.getValue();
                switch (k) {
                    case "ipv4-address" -> mark(out, ip(v), ip(v));
                    case "ip-netmask", "literal" -> { if (v.contains("/")) cidr(out, v); else if (v.contains("-")) span(out, v); else mark(out, ip(v), ip(v)); }
                    case "ip-range" -> span(out, v);
                    case "subnet4" -> cidr(out, v + "/" + f.get("mask-length4"));
                    case "mask-length4" -> {}
                    case "ipv4-address-first", "ip-address-first" -> mark(out, ip(v), ip(f.get(k.replace("first", "last"))));
                    case "ipv4-address-last", "ip-address-last" -> {}
                    default -> { return full(ADDRESS_SIZE); }
                }
            }
            return out.isEmpty() ? full(ADDRESS_SIZE) : out;
        }
        private static BitSet service(List<String> values) {
            var out = new BitSet(SERVICE_SIZE); Map<String, String> f = new HashMap<>();
            for (String v : values) { int i = v.indexOf(": "); if (i < 0) return full(SERVICE_SIZE); f.put(v.substring(0, i), v.substring(i + 2)); }
            for (var e : f.entrySet()) {
                String k = e.getKey(); if (k.equals("protocol")) continue;
                String protocol = k.equals("port") ? f.get("protocol") : k.startsWith("protocol/") && k.endsWith("/port") ? k.split("/")[1] : null;
                if (!"tcp".equals(protocol) && !"udp".equals(protocol)) return full(SERVICE_SIZE);
                int base = protocol.equals("tcp") ? 0 : PORTS + 1;
                for (String part : e.getValue().split(",")) {
                    String[] ends = part.trim().split("-"); int lo = Integer.parseInt(ends[0]), hi = Integer.parseInt(ends[ends.length - 1]);
                    for (int p = lo; p <= hi; p++) if (p < PORTS) out.set(base + p); else if (p == HIGH_PORT) out.set(base + PORTS);
                }
            }
            return out.isEmpty() ? full(SERVICE_SIZE) : out;
        }
        private BitSet zones(List<String> values) {
            if (values == null || values.isEmpty() || values.contains("any")) return full(zones.size());
            var out = new BitSet(zones.size()); for (String z : values) out.set(zones.indexOf(z)); return out;
        }
        private BitSet targets(List<String> values) {
            if (values == null || values.isEmpty()) return full(targets.size());
            var out = new BitSet(targets.size());
            for (String id : values) out.or(target(id, 0));
            return out;
        }
        private BitSet target(String id, int depth) {
            var o = snapshot.objects().get(id);
            if (o == null || !"RESOLVED".equals(o.status()) || o.type().equals("any") || depth > 40) return full(targets.size());
            if (!o.members().isEmpty()) { var out = new BitSet(); for (String m : o.members()) out.or(target(m, depth + 1)); return out; }
            var out = new BitSet(targets.size()); int at = targets.indexOf(id); if (at < 0) return full(targets.size()); out.set(at); return out;
        }
        private static void cidr(BitSet out, String cidr) {
            String[] parts = cidr.split("/"); long base = ip(parts[0]); int bits = Integer.parseInt(parts[1]);
            long size = 1L << (32 - bits); long start = base & ~(size - 1); mark(out, start, start + size - 1);
        }
        private static void span(BitSet out, String range) { String[] ends = range.split("-"); mark(out, ip(ends[0]), ip(ends[1])); }
        private static void mark(BitSet out, long lo, long hi) {
            for (int b = 0; b < BLOCKS.size(); b++) {
                long s = Math.max(lo, BLOCKS.get(b)[0]), e = Math.min(hi, BLOCKS.get(b)[1]);
                for (long a = s; a <= e; a++) out.set(b * 256 + (int) (a - BLOCKS.get(b)[0]));
            }
            if (lo <= OUTSIDE && OUTSIDE <= hi) out.set(3 * 256);
        }
    }
    private static BitSet full(int size) { var b = new BitSet(size); b.set(0, size); return b; }
    private static long ip(String dotted) {
        long out = 0; for (String part : dotted.trim().split("\\.")) out = out * 256 + Integer.parseInt(part); return out;
    }

    /** The covering rule is an earlier, enabled, fully resolved rule of the scope that covers the rule by brute force, with the right verdict. */
    static void assertCoverIsSafe(Universe universe, String ruleId, String coveringId, String status, String context) {
        var later = universe.rules.get(ruleId); var prior = universe.rules.get(coveringId);
        assertNotNull(prior, context);
        assertTrue(universe.resolvedPriors(ruleId).contains(prior), context + ": covering rule " + coveringId + " is no earlier resolved rule of the scope");
        assertEquals(List.of("universal"), prior.extras().getOrDefault("rule-type", List.of("universal")), context);
        assertTrue(universe.covers(prior, later), context + ": " + coveringId + " does not cover " + ruleId + " by brute force");
        boolean same = prior.action().equals(later.action());
        assertEquals(same ? "REDUNDANT" : "CONFLICT", status, context);
    }
    /**
     * No single earlier rule of the scope (enabled or possibly enabled, resolved or not; whatever the oracle cannot
     * interpret is the whole universe) covers the rule by brute force.
     */
    static void assertNoCover(Universe universe, String ruleId, String context) {
        var later = universe.rules.get(ruleId);
        for (var prior : universe.earlierRules(ruleId))
            assertFalse(universe.covers(prior, later), context + ": NOT_SHADOWED rule is covered by " + prior.id() + " by brute force");
    }
    /** Which way a zone-superset change went, for the tests' counters; null when the rule is unchanged. */
    enum ZoneChange { COVERED, RELEASED, OTHER_UNKNOWN }
    /**
     * A rule the former analysis left "zone or install-on coverage is unproven" (or, Palo Alto, "zone rule type
     * constraint") may now be: covered (REDUNDANT/CONFLICT, confirmed by {@link #assertCoverIsSafe}); for the unproven
     * reason also released (NOT_SHADOWED or "snapshot collection incomplete", confirmed by {@link #assertNoCover} and
     * {@link #assertReleaseIsSafe}) or UNKNOWN "earlier rule has unsupported constraints". Everything else of the
     * assessment is exactly the former one.
     */
    static ZoneChange assertZoneChangeIsSafe(Universe universe, PolicyHygiene.Assessment expected, PolicyHygiene.Assessment gotWithBlocker, String ruleId, String context) {
        var got = verdictOnly(universe, gotWithBlocker, ruleId, context);
        String reason = expected.shadowReason();
        if (got.coveringRuleId() != null) {
            assertEquals(withCover(expected, got.shadowStatus(), got.coveringRuleId()), got, context);
            assertCoverIsSafe(universe, ruleId, got.coveringRuleId(), got.shadowStatus(), context);
            return ZoneChange.COVERED;
        }
        assertEquals(UNPROVEN, reason, context + ": a rule type constraint may only become covered");
        if (UNSUPPORTED.equals(got.shadowReason())) {
            assertEquals(replaced(expected, "UNKNOWN", UNSUPPORTED, null, new PolicyHygiene.Finding("unknown", "LOW", "Shadowing: " + UNSUPPORTED + ".")), got, context);
            return ZoneChange.OTHER_UNKNOWN;
        }
        boolean incomplete = !universe.snapshot.failures().isEmpty();
        assertEquals(released(expected, incomplete, UNPROVEN), got, context);
        assertNoCover(universe, ruleId, context);
        assertReleaseIsSafe(universe, ruleId, context);
        return ZoneChange.RELEASED;
    }
    private static PolicyHygiene.Assessment withCover(PolicyHygiene.Assessment a, String status, String covering) {
        var finding = status.equals("CONFLICT") ? new PolicyHygiene.Finding("conflict", "HIGH", "Earlier enabled rule fully covers this match with a different action.")
            : new PolicyHygiene.Finding("shadowed", "MEDIUM", "Earlier enabled rule fully covers this match with the same action.");
        return replaced(a, status, null, covering, finding);
    }
    /** The assessment with its "Shadowing: reason." finding replaced in place (or removed when {@code finding} is null). */
    private static PolicyHygiene.Assessment replaced(PolicyHygiene.Assessment a, String status, String reason, String covering, PolicyHygiene.Finding finding) {
        var findings = new ArrayList<PolicyHygiene.Finding>();
        for (var f : a.findings()) {
            if (!f.evidence().equals("Shadowing: " + a.shadowReason() + ".")) findings.add(f);
            else if (finding != null) findings.add(finding);
        }
        return new PolicyHygiene.Assessment(status, reason, covering, List.copyOf(findings), a.permissiveness(), a.timeStatus(), a.expiring(),
            a.counterWindow(), a.hitSource(), a.hitsCollectedAt());
    }

    /** Asserts the brute-force condition (b) for a released rule. */
    static void assertReleaseIsSafe(Universe universe, String ruleId, String context) {
        var later = universe.rules.get(ruleId);
        for (var prior : universe.unresolvedPriors(ruleId)) {
            String packet = universe.sharedPacket(prior, later);
            assertNull(packet, context + ": released rule shares packet " + packet + " with partial prior " + prior.id());
        }
    }

    /**
     * The assessment without the blocker the former analysis did not have, after checking it: present exactly where the rule
     * is UNKNOWN "earlier rule has unsupported constraints", and then an earlier enabled rule of the same scope. Every verdict
     * comparison above and below then runs on the unchanged verdict fields.
     */
    static PolicyHygiene.Assessment verdictOnly(Universe universe, PolicyHygiene.Assessment got, String ruleId, String context) {
        boolean blocked = "UNKNOWN".equals(got.shadowStatus()) && UNSUPPORTED.equals(got.shadowReason());
        if (got.blockingRuleId() == null) {
            assertNull(got.blockingRuleNumber(), context);
            assertFalse(blocked, context + ": \"" + UNSUPPORTED + "\" without a named blocker");
        } else {
            assertTrue(blocked, context + ": a blocker is named only for \"" + UNSUPPORTED + "\"");
            var blocker = universe.earlierRules(ruleId).stream().filter(r -> r.id().equals(got.blockingRuleId())).findFirst();
            assertTrue(blocker.isPresent(), context + ": blocker " + got.blockingRuleId() + " is not an earlier enabled rule of the scope");
            assertEquals(blocker.get().number(), got.blockingRuleNumber(), context);
        }
        return withoutBlocker(got);
    }
    /** The verdict fields only: what the former analysis (which named no blocker) can be compared with. */
    static PolicyHygiene.Assessment withoutBlocker(PolicyHygiene.Assessment got) {
        return new PolicyHygiene.Assessment(got.shadowStatus(), got.shadowReason(), got.coveringRuleId(), got.findings(), got.permissiveness(), got.timeStatus(),
            got.expiring(), got.counterWindow(), got.hitSource(), got.hitsCollectedAt());
    }
    /**
     * Compares one rule's new assessment with the former one: (a) anything but "earlier rule has unsupported
     * constraints" must be identical; (b) where that reason is gone, the rule is exactly the former assessment released
     * (NOT_SHADOWED, or UNKNOWN "snapshot collection incomplete" when the snapshot has collection failures) and the
     * brute-force check confirms it shares no packet with any unresolved earlier rule of its scope.
     * Returns true when the rule was released.
     */
    static boolean assertSameOrSafelyReleased(Universe universe, PolicyHygiene.Assessment expected, PolicyHygiene.Assessment gotWithBlocker, String ruleId, String context) {
        var got = verdictOnly(universe, gotWithBlocker, ruleId, context);
        boolean zone = UNPROVEN.equals(expected.shadowReason()) || RULE_TYPE.equals(expected.shadowReason()) && universe.snapshot.metadata().vendor().equals("PAN");
        if (zone && !got.equals(expected)) { assertZoneChangeIsSafe(universe, expected, gotWithBlocker, ruleId, context); return false; }
        if (!UNSUPPORTED.equals(expected.shadowReason()) || got.equals(expected)) { assertEquals(expected, got, context); return false; }
        boolean incomplete = !universe.snapshot.failures().isEmpty();
        assertEquals(released(expected, incomplete), got, context);
        assertReleaseIsSafe(universe, ruleId, context);
        assertNoCover(universe, ruleId, context);
        return true;
    }
    static PolicyHygiene.Assessment released(PolicyHygiene.Assessment a, boolean incomplete) { return released(a, incomplete, UNSUPPORTED); }
    static PolicyHygiene.Assessment released(PolicyHygiene.Assessment a, boolean incomplete, String reason) {
        var findings = new ArrayList<PolicyHygiene.Finding>();
        for (var f : a.findings()) {
            if (!f.evidence().equals("Shadowing: " + reason + ".")) findings.add(f);
            else if (incomplete) findings.add(new PolicyHygiene.Finding("unknown", "LOW", "Shadowing: snapshot collection incomplete."));
        }
        return new PolicyHygiene.Assessment(incomplete ? "UNKNOWN" : "NOT_SHADOWED", incomplete ? "snapshot collection incomplete" : null, null,
            List.copyOf(findings), a.permissiveness(), a.timeStatus(), a.expiring(), a.counterWindow(), a.hitSource(), a.hitsCollectedAt());
    }
}
