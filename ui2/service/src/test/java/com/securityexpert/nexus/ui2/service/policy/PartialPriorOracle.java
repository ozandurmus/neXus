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
 * matches the whole universe in every dimension. Narrowing constraints (time, schedule, VPN, content, user, category,
 * a Palo Alto Panorama target) are ignored, which only enlarges the earlier rule's match. The oracle is therefore never more willing to call two
 * rules disjoint than the real policy is.
 * <p>Palo Alto zone supersets: a rule that leaves "zone or install-on coverage is unproven" (or, for an intrazone or
 * interzone rule, "zone rule type constraint") for REDUNDANT or CONFLICT must be covered, by brute force, by the rule
 * named as covering: every dimension of the later rule, with the zones read as the set of (from, to) pairs its rule type
 * matches, lies inside the covering rule's. One that becomes NOT_SHADOWED must be covered by no earlier fully resolved
 * rule of its scope and share no packet with any unresolved one. Zone pairs follow the PAN-OS rule types: universal
 * from x to, intrazone (z, z) for z in from, interzone from x to without (z, z); any is every zone, including one no
 * rule names.
 * <p>Palo Alto application-default: service and application are one joint dimension of (application, port) pairs over
 * the applications the snapshot names by opaque reference plus one application no rule names. A rule with explicit
 * service matches its applications x its ports. An application-default rule matches each of its named applications on
 * that application's default ports, and application any with application-default matches a set of its own; both are
 * unknown, so they are the variables of a {@link World} (each non-empty, fixed per application). The engine's verdicts
 * must hold in every world:
 * <ul>
 * <li>a covering rule covers in every world ({@link Universe#coversInEveryWorld}): checked in the world where every
 * default-port set is one port no explicit service names, which refutes every cover that does not hold for all
 * assignments (each later variable then sits on a fresh point that only the same variable, or an earlier any port of
 * that application, holds), plus the widest world and seeded random worlds;</li>
 * <li>a NOT_SHADOWED rule is covered in no world ({@link Universe#coversInSomeWorld}): checked in the pair's witness
 * world, built to let the earlier rule cover -- its own variables widest, each variable of the later rule one point
 * inside the earlier rule's match (a shared variable one point, which then holds itself) -- plus the same worlds;</li>
 * <li>a released rule shares no packet with an unresolved earlier rule in any world: the widest world, since every
 * rule's match only grows with its variables.</li>
 * </ul>
 * <p>Opaque applications (application-default applications, and content-provided applications a rule with any other
 * service names): an application reference is an abstract, fixed, non-empty traffic set, and named applications may
 * overlap arbitrarily -- one may carry or include another's traffic. The joint dimension's application axis is a set of
 * traffic atoms, one per named application plus the unnamed one; application any is every atom, and each world also
 * fixes the atoms of every named application ({@link World#overlap}). The checks use the identity overlap (every
 * application its own atom: refutes every cover that is not by identity), the collapsed overlap (every named
 * application every atom: the most favourable to a cover by any earlier named application) and seeded random overlaps,
 * crossed with the default-port worlds above; the witness and widest worlds use the collapsed overlap. Every claim must
 * hold for every overlap assignment.
 * <p>Check Point access roles in a source cell ({@link Universe#sources}): the role matches the addresses of whichever
 * identities it selects, any subset of its networks' addresses; networks any or an unreadable role is the whole
 * universe. A release must share no packet with, and not be covered by, an access-role prior under every identity
 * selection checked (the widest one decides all of them, by monotonicity; the others are checked as well).
 */
final class PartialPriorOracle {
    private PartialPriorOracle() {}
    static final String UNSUPPORTED = "earlier rule has unsupported constraints";
    static final String UNPROVEN = "zone or install-on coverage is unproven", RULE_TYPE = "zone rule type constraint";
    private static final List<long[]> BLOCKS = List.of(new long[]{ip("192.0.2.0"), ip("192.0.2.255")},
        new long[]{ip("198.51.100.0"), ip("198.51.100.255")}, new long[]{ip("203.0.113.0"), ip("203.0.113.255")});
    private static final long OUTSIDE = ip("10.0.0.1");
    private static final int PORTS = 1101, HIGH_PORT = 65535;
    private static final int ADDRESS_SIZE = 3 * 256 + 1, SERVICE_SIZE = 2 * (PORTS + 1);
    /** In an application identity set: application any. */
    private static final String ANY_APP = "\u0000any", OTHER_APP = "\u0000other";

    /**
     * One assignment of the unknowns: per named application a non-empty default-port set (indices into the service
     * universe); the (atom, port) pairs that application any with application-default matches; and per application
     * (named, and the unnamed one) its non-empty set of traffic atoms (indices into {@link Universe#apps}).
     */
    record World(Map<String, BitSet> defaults, BitSet anyDefault, Map<String, BitSet> overlap) {}

    /** One snapshot's interpreted universe and scopes. */
    static final class Universe {
        final PolicySnapshot snapshot;
        final List<String> zones, targets;
        final Map<String, Rule> rules;
        final Map<String, Integer> scope, order;
        final Map<String, Section> sections;
        /** The named applications (opaque references) and, last, one no rule names; a service index no explicit service names. */
        final List<String> apps; final Map<String, Integer> appIndex = new HashMap<>(); final int fresh;
        private final List<World> worlds = new ArrayList<>();
        /** The same snapshot and scopes over other zone and target lists ({@link #pairWorld}). */
        private Universe(Universe base, List<String> zones, List<String> targets) {
            snapshot = base.snapshot; rules = base.rules; scope = base.scope; order = base.order; sections = base.sections;
            this.zones = zones; this.targets = targets;
            apps = base.apps; appIndex.putAll(base.appIndex); fresh = base.fresh; worlds.addAll(base.worlds);
        }
        Universe(PolicySnapshot snapshot) {
            this.snapshot = snapshot;
            rules = new LinkedHashMap<>(); scope = new HashMap<>(); order = new HashMap<>(); sections = new HashMap<>();
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
                if (section.rules().isEmpty()) continue;
                Object token = walk.advance(section);
                int id = tokens.computeIfAbsent(token, k -> tokens.size());
                for (var rule : section.rules()) { rules.put(rule.id(), rule); scope.put(rule.id(), id); order.put(rule.id(), n++); sections.put(rule.id(), section); }
            }
            var named = new TreeSet<String>(); var explicit = new BitSet(SERVICE_SIZE);
            for (var s : snapshot.sections()) for (var r : s.rules()) {
                var ids = identities(r.application());
                if (ids != null) for (String a : ids) if (!a.equals(ANY_APP)) named.add(a);
                var ports = cell(r.service(), "service");
                if (ports.cardinality() < SERVICE_SIZE) explicit.or(ports);
            }
            var list = new ArrayList<>(named); list.add(OTHER_APP); apps = List.copyOf(list);
            for (int i = 0; i < apps.size(); i++) appIndex.put(apps.get(i), i);
            fresh = explicit.nextClearBit(0);
            assertTrue(fresh < SERVICE_SIZE, "the oracle needs a port no explicit service names");
            var identity = identityOverlap(); var collapsed = collapsedOverlap();
            var freshPorts = freshWorld(identity); var widePorts = widestPorts(identity);
            worlds.add(freshPorts); worlds.add(widePorts);
            var random = new Random(String.valueOf(snapshot.metadata().id()).hashCode());
            List<World> randomPorts = new ArrayList<>();
            for (int i = 0; i < 3; i++) { randomPorts.add(randomWorld(random, identity)); worlds.add(randomPorts.get(i)); }
            worlds.add(with(freshPorts, collapsed)); worlds.add(with(widePorts, collapsed));
            for (int i = 0; i < 3; i++) worlds.add(with(randomPorts.get(i), randomOverlap(random)));
        }
        /** Every application its own atom. */
        private Map<String, BitSet> identityOverlap() {
            Map<String, BitSet> out = new HashMap<>(); for (String a : apps) out.put(a, point(appIndex.get(a))); return out;
        }
        /** Every named application every atom (the unnamed application stays its own atom). */
        private Map<String, BitSet> collapsedOverlap() {
            Map<String, BitSet> out = new HashMap<>(); for (String a : apps) out.put(a, a.equals(OTHER_APP) ? point(appIndex.get(a)) : full(apps.size()));
            return out;
        }
        private Map<String, BitSet> randomOverlap(Random random) {
            Map<String, BitSet> out = new HashMap<>();
            for (String a : apps) out.put(a, a.equals(OTHER_APP) ? point(appIndex.get(a)) : nonEmpty(random, apps.size()));
            return out;
        }
        private static World with(World world, Map<String, BitSet> overlap) { return new World(world.defaults(), world.anyDefault(), overlap); }
        private int sa() { return apps.size() * SERVICE_SIZE; }
        /** Every default-port set is the one port {@link #fresh}; application any's is that port of the unnamed application. */
        World freshWorld(Map<String, BitSet> overlap) {
            Map<String, BitSet> d = new HashMap<>(); var one = new BitSet(); one.set(fresh);
            for (String a : apps) d.put(a, one);
            var u = new BitSet(sa()); u.set(appIndex.get(OTHER_APP) * SERVICE_SIZE + fresh);
            return new World(d, u, overlap);
        }
        private World widestPorts(Map<String, BitSet> overlap) {
            Map<String, BitSet> d = new HashMap<>(); for (String a : apps) d.put(a, full(SERVICE_SIZE));
            return new World(d, full(sa()), overlap);
        }
        /** The world in which every rule's match is largest: widest default ports, collapsed overlap. */
        World widest() { return widestPorts(collapsedOverlap()); }
        private World randomWorld(Random random, Map<String, BitSet> overlap) {
            Map<String, BitSet> d = new HashMap<>();
            for (String a : apps) d.put(a, nonEmpty(random, SERVICE_SIZE));
            return new World(d, nonEmpty(random, sa()), overlap);
        }
        private static BitSet nonEmpty(Random random, int size) {
            var out = new BitSet(size); int density = 1 + random.nextInt(50);
            for (int i = 0; i < size; i++) if (random.nextInt(density) == 0) out.set(i);
            out.set(random.nextInt(size));
            return out;
        }
        /**
         * The service cell is the mapper's built-in application-default service: the one reference PanoramaPolicyMapper
         * mints for it, a RESOLVED service object without values or members (the stored shape, PanoramaPolicyMapper).
         */
        boolean applicationDefault(Cell service) {
            var meta = snapshot.metadata();
            if (!"PAN".equals(meta.vendor()) || meta.id() == null || service.negated() || service.refs().size() != 1) return false;
            String id = service.refs().get(0); var o = snapshot.objects().get(id);
            return id.equals(ref(meta.id(), "service", "application-default")) && o != null && "RESOLVED".equals(o.status())
                && o.type().equals("service") && o.values().isEmpty() && o.members().isEmpty();
        }
        /** The opaque applications of a cell ({@link #ANY_APP} for any), or null when they cannot be read as identities. */
        Set<String> identities(Cell cell) {
            if (cell.negated() || cell.refs().isEmpty()) return null;
            Set<String> out = new HashSet<>();
            for (String id : cell.refs()) if (!identity(id, out, 0)) return null;
            return out;
        }
        private boolean identity(String id, Set<String> out, int depth) {
            var o = snapshot.objects().get(id);
            if (o == null || depth > 40) return false;
            boolean resolved = "RESOLVED".equals(o.status());
            if (resolved && o.type().equals("any")) { out.add(ANY_APP); return true; }
            if (resolved && o.type().equals("application-group")) {
                if (o.members().isEmpty() || !o.values().isEmpty()) return false;
                for (String m : o.members()) if (!identity(m, out, depth + 1)) return false;
                return true;
            }
            if (o.members().isEmpty() && (resolved && o.type().equals("application") || "UNRESOLVED".equals(o.status()) && o.type().equals("unresolved"))) {
                out.add(id); return true;
            }
            return false;
        }
        /** The rule's application-default variables: its applications, or null when its joint dimension does not depend on the world. */
        private Set<String> variables(Rule rule) {
            if (!applicationDefault(rule.service())) return null;
            var ids = identities(rule.application());
            if (ids == null) return null;
            return ids.contains(ANY_APP) ? Set.of(ANY_APP) : ids;
        }
        /**
         * The rule names a content-provided application directly while its service is not application-default: an
         * UNRESOLVED object of type unresolved, without values or members, in its application cell.
         */
        static boolean content(Universe universe, Rule rule) {
            if (universe.applicationDefault(rule.service())) return false;
            for (String id : rule.application().refs()) {
                var o = universe.snapshot.objects().get(id);
                if (o != null && "UNRESOLVED".equals(o.status()) && o.type().equals("unresolved") && o.members().isEmpty() && o.values().isEmpty()) return true;
            }
            return false;
        }
        /** The rule's match depends on the overlap of named applications: it names one (without any) on its application axis. */
        private boolean named(Rule rule) {
            if (snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty()) return false;
            var ids = identities(rule.application());
            return ids != null && !ids.contains(ANY_APP) && !ids.isEmpty();
        }
        /** The traffic atoms of an application cell of a rule with explicit service in a world: any, or the union of its applications' atoms. */
        private BitSet atoms(Cell cell, World world) {
            var ids = identities(cell);
            if (ids == null || ids.contains(ANY_APP)) return full(apps.size());
            var out = new BitSet(apps.size());
            for (String a : ids) out.or(world.overlap().get(a));
            return out;
        }
        /** The joint (application, port) dimension of a rule in a world. */
        private BitSet serviceApplication(Rule rule, World world) {
            var app = rule.application(); boolean cp = snapshot.metadata().vendor().equals("CP");
            if (applicationDefault(rule.service())) {
                var ids = identities(app);
                if (ids == null) return full(sa());
                if (ids.contains(ANY_APP)) return (BitSet) world.anyDefault().clone();
                var out = new BitSet(sa());
                for (String a : ids) {
                    var d = world.defaults().get(a); var atoms = world.overlap().get(a);
                    for (int x = atoms.nextSetBit(0); x >= 0; x = atoms.nextSetBit(x + 1))
                        for (int p = d.nextSetBit(0); p >= 0; p = d.nextSetBit(p + 1)) out.set(x * SERVICE_SIZE + p);
                }
                return out;
            }
            BitSet ports = cell(rule.service(), "service");
            // A content-provided (or any other opaque) application is its atoms in this world; whatever the oracle cannot
            // read as identities is every atom.
            BitSet applications = cp && app.refs().isEmpty() && !app.negated() ? full(apps.size()) : atoms(app, world);
            var out = new BitSet(sa());
            for (int a = applications.nextSetBit(0); a >= 0; a = applications.nextSetBit(a + 1))
                for (int p = ports.nextSetBit(0); p >= 0; p = ports.nextSetBit(p + 1)) out.set(a * SERVICE_SIZE + p);
            return out;
        }
        /**
         * The world most favourable to {@code prior} covering {@code later}: the collapsed overlap (every named application
         * is every atom, so the prior's named applications hold whatever the later rule names); variables the later rule
         * does not have, and those both rules share, widest (a shared variable brings the same pairs to both); each other
         * variable of the later rule a single port at which the prior's match holds every atom, or a single pair inside
         * the prior's match for application any.
         */
        World witness(Rule prior, Rule later) {
            var mine = variables(later); var theirs = variables(prior); var wide = widest();
            if (mine == null) return wide;
            Map<String, BitSet> d = new HashMap<>(wide.defaults()); BitSet u = wide.anyDefault();
            BitSet theirMatch = serviceApplication(prior, wide);
            if (mine.contains(ANY_APP)) {
                if (theirs == null || !theirs.contains(ANY_APP)) { int x = theirMatch.nextSetBit(0); u = point(Math.max(x, 0)); }
            } else {
                int column = 0;
                for (int p = 0; p < SERVICE_SIZE; p++) {
                    boolean all = true;
                    for (int x = 0; x < apps.size() && all; x++) all = theirMatch.get(x * SERVICE_SIZE + p);
                    if (all) { column = p; break; }
                }
                for (String a : mine) if (theirs == null || !theirs.contains(a)) d.put(a, point(column));
            }
            return new World(d, u, wide.overlap());
        }
        private static BitSet point(int at) { var b = new BitSet(); b.set(at); return b; }
        /** Every world the checks use for this pair; one when neither rule depends on default ports or on the overlap. */
        private List<World> worlds(Rule prior, Rule later) {
            if (variables(prior) == null && variables(later) == null && !named(prior) && !named(later)) return List.of(worlds.get(1));
            var out = new ArrayList<>(worlds); out.add(witness(prior, later)); return out;
        }
        boolean coversInEveryWorld(Rule prior, Rule later) {
            for (var world : worlds(prior, later)) for (var identities : sources(prior)) if (!covers(prior, identities, later, world)) return false;
            return true;
        }
        boolean coversInSomeWorld(Rule prior, Rule later) {
            for (var world : worlds(prior, later)) for (var identities : sources(prior)) if (covers(prior, identities, later, world)) return true;
            return false;
        }
        /**
         * Check Point access roles in a source cell: the role matches the addresses of whichever identities it selects,
         * which may be any subset of its networks' addresses (Resolver#accessRoleBound gives the vendor sources). The
         * oracle reads the role itself, independently of the production code: the mapper's stored shape (type
         * access-role, status DYNAMIC, no members, only {@code networks: } values); networks any, an unresolvable
         * network or any other shape is the whole universe (unbounded). Anything else in the cell is read as usual.
         * <p>Every source set the rule may match, one per identity selection checked: the cell with every role at its
         * widest (all of its networks), with no identity selected at all, with each single address of the roles'
         * networks, every selection exhaustively when the roles' networks add at most {@link #EXHAUSTIVE} addresses to the
         * cell, and seeded random selections otherwise. Sharing a packet and covering are both monotone in the prior's
         * source set, so the widest selection alone already decides every subset; the others confirm it directly. A
         * rule without such a role has one source set, exactly as before.
         */
        List<BitSet> sources(Rule rule) {
            var source = rule.source();
            BitSet widest = cell(source, "address");
            if (!snapshot.metadata().vendor().equals("CP") || source.negated() || source.refs().isEmpty()) return List.of(widest);
            BitSet fixed = new BitSet(ADDRESS_SIZE), roles = new BitSet(ADDRESS_SIZE); boolean role = false;
            for (String id : source.refs()) {
                var o = snapshot.objects().get(id);
                if (o != null && o.type().equals("access-role") && "DYNAMIC".equals(o.status())) { role = true; roles.or(networks(o)); }
                else fixed.or(object(id, "address", ADDRESS_SIZE, 0));
            }
            if (!role) return List.of(widest);
            var all = (BitSet) fixed.clone(); all.or(roles);
            var optional = (BitSet) roles.clone(); optional.andNot(fixed);
            List<BitSet> out = new ArrayList<>(List.of(all, fixed));
            int[] addresses = optional.stream().toArray();
            if (addresses.length <= EXHAUSTIVE) {
                for (int mask = 1; mask < (1 << addresses.length) - 1; mask++) {
                    var subset = (BitSet) fixed.clone();
                    for (int b = 0; b < addresses.length; b++) if ((mask >> b & 1) == 1) subset.set(addresses[b]);
                    out.add(subset);
                }
            } else {
                if (addresses.length <= 256) for (int a : addresses) { var one = (BitSet) fixed.clone(); one.set(a); out.add(one); }
                var random = new Random(rule.id().hashCode());
                for (int i = 0; i < 4; i++) {
                    var subset = (BitSet) fixed.clone();
                    for (int a : addresses) if (random.nextBoolean()) subset.set(a);
                    out.add(subset);
                }
            }
            return out;
        }
        static final int EXHAUSTIVE = 8;
        /** The addresses an access role's networks hold, or the whole universe where they give no bound. */
        private BitSet networks(PolicyObject role) {
            if (!role.members().isEmpty() || role.values().isEmpty()) return full(ADDRESS_SIZE);
            var out = new BitSet(ADDRESS_SIZE);
            for (String value : role.values()) {
                if (!value.startsWith("networks: ")) return full(ADDRESS_SIZE);
                String network = value.substring("networks: ".length());
                if (network.equals("any")) return full(ADDRESS_SIZE);
                out.or(object(network, "address", ADDRESS_SIZE, 0));
            }
            return out;
        }
        /**
         * The smallest world two rules can be evaluated in: the zones either of them names (a rule names only zones
         * that exist, as the engine assumes) and the atomic install-on targets either of them reaches, nothing more;
         * a lone any (zone or target) is then exactly those. The vsys zones and the gateways behind any are not
         * collected, so this world may be the real one, and it is the world most favourable to the prior covering the
         * later rule: the prior's named zones and targets are fixed, while the later rule's any shrinks to them.
         */
        Universe pairWorld(Rule prior, Rule later) {
            var zoneSet = new TreeSet<String>(); var targetSet = new TreeSet<String>();
            for (Rule r : List.of(prior, later)) {
                for (String key : List.of("from", "to")) for (String z : r.extras().getOrDefault(key, List.of())) if (!z.equals("any")) zoneSet.add(z);
                for (String t : r.extras().getOrDefault("install-on", List.of())) reach(t, targetSet, 0);
            }
            if (zoneSet.isEmpty()) zoneSet.add("ZONE-OUTSIDE-99");
            if (targetSet.isEmpty()) targetSet.add("gw-outside-99");
            return new Universe(this, List.copyOf(zoneSet), List.copyOf(targetSet));
        }
        /** The atomic targets {@link #target} marks for an install-on reference (none for one it reads as every target). */
        private void reach(String id, Set<String> out, int depth) {
            var o = snapshot.objects().get(id);
            if (o == null || !"RESOLVED".equals(o.status()) || o.type().equals("any") || depth > 40) return;
            if (o.members().isEmpty()) out.add(id);
            else for (String m : o.members()) reach(m, out, depth + 1);
        }
        /** The earlier rules of the rule's scope, enabled or possibly enabled, that have no full match. */
        List<Rule> unresolvedPriors(String ruleId) {
            var resolver = PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(Integer.MAX_VALUE));
            List<Rule> out = new ArrayList<>();
            for (var rule : rules.values()) {
                if (order.get(rule.id()) >= order.get(ruleId) || !scope.get(rule.id()).equals(scope.get(ruleId))) continue;
                if (Boolean.FALSE.equals(rule.enabled())) continue;
                if (PolicyNormalization.normalize(snapshot, sections.get(rule.id()), rule, resolver).problem() != null) out.add(rule);
            }
            return out;
        }
        /**
         * A packet (source, destination, (application, port), from, to, target) both rules match in some world, or null:
         * the widest world, as every match only grows with its default-port variables.
         */
        String sharedPacket(Rule prior, Rule later) {
            var world = widest();
            var b = dimensions(later, sections.get(later.id()), world, sources(later).get(0));
            // Every identity selection of an access-role prior (sources), each its own check.
            for (var identities : sources(prior)) {
                var a = dimensions(prior, sections.get(prior.id()), world, identities);
                List<Integer> witness = new ArrayList<>();
                for (int d = 0; d < a.size(); d++) {
                    var both = (BitSet) a.get(d).clone(); both.and(b.get(d));
                    if (both.isEmpty()) { witness = null; break; }
                    witness.add(both.nextSetBit(0));
                }
                if (witness != null) return witness.toString();
            }
            return null;
        }
        private List<BitSet> dimensions(Rule rule, Section section, World world, BitSet source) {
            var meta = snapshot.metadata(); boolean cp = meta.vendor().equals("CP");
            List<BitSet> all = List.of(full(ADDRESS_SIZE), full(ADDRESS_SIZE), full(sa()),
                full(zones.size()), full(zones.size()), full(targets.size()));
            if (!Set.of("CP", "PAN").contains(meta.vendor()) || PolicySnapshot.outsideSecurityEvaluation(meta, section)) return all;
            for (String key : rule.extras().keySet())
                if (!known(key, rule, cp)) return all;
            boolean universal = rule.extras().getOrDefault("rule-type", List.of("universal")).equals(List.of("universal"));
            return List.of(source, cell(rule.destination(), "address"), serviceApplication(rule, world),
                universal ? zones(rule.extras().get("from")) : full(zones.size()), universal ? zones(rule.extras().get("to")) : full(zones.size()),
                targets(rule.extras().get("install-on")));
        }
        /** The earlier rules of the rule's scope, enabled, with a full match: the only rules that may cover it. */
        List<Rule> resolvedPriors(String ruleId) {
            var resolver = PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(Integer.MAX_VALUE));
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
        /** In this world, every packet the later rule matches, the prior matches too: per dimension, zones as (from, to) pairs. */
        boolean covers(Rule prior, BitSet priorSource, Rule later, World world) {
            var a = coverDimensions(prior, world, priorSource); var b = coverDimensions(later, world, sources(later).get(0));
            for (int d = 0; d < a.size(); d++) {
                var outside = (BitSet) b.get(d).clone(); outside.andNot(a.get(d));
                if (!outside.isEmpty()) return false;
            }
            return true;
        }
        /** Every dimension but the joint (application, port) one holds the later rule's. */
        boolean coversExceptServiceApplication(Rule prior, Rule later) {
            var world = widest(); var a = coverDimensions(prior, world, sources(prior).get(0)); var b = coverDimensions(later, world, sources(later).get(0));
            for (int d = 0; d < a.size(); d++) {
                if (d == 2) continue;
                var outside = (BitSet) b.get(d).clone(); outside.andNot(a.get(d));
                if (!outside.isEmpty()) return false;
            }
            return true;
        }
        private List<BitSet> coverDimensions(Rule rule, World world, BitSet source) {
            boolean cp = snapshot.metadata().vendor().equals("CP");
            // A field of unknown meaning may widen the match: the whole universe (as the disjointness oracle does).
            for (String key : rule.extras().keySet())
                if (!known(key, rule, cp)) return List.of(full(ADDRESS_SIZE), full(ADDRESS_SIZE), full(sa()),
                    full(zones.size() * zones.size()), full(targets.size()));
            return List.of(source, cell(rule.destination(), "address"), serviceApplication(rule, world),
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
        /**
         * Fields the oracle may ignore: they say nothing about what matches, or only narrow it. A Palo Alto Panorama target
         * (devices, tags, virtual systems, negate yes or no) only restricts which firewalls of the device group get the
         * rule (PATH_ANALYSIS_VENDOR_SEMANTICS.md 4d), so ignoring it reads the rule as present on every firewall: a
         * larger match, never a smaller one. A target shape nobody parsed and any other negate value stay unknown.
         * That is conservative only for an earlier rule: a targeted rule is never a released or covered later rule today
         * (it stays "unsupported rule constraint"); judging one as a later rule would need a firewall dimension instead.
         */
        private static boolean known(String key, Rule rule, boolean cp) {
            return Set.of("time", "schedule", "vpn", "content", "source-user", "category", "from", "to", "install-on", "rule-type",
                    "tag", "layer-name", "last-modified", "last-modifier", "log-setting").contains(key)
                || key.startsWith("track-") || key.startsWith("profile-setting/")
                || key.equals("inline-layer") && cp && rule.action().equals("Apply Layer")
                || !cp && Set.of("target-selectors", "target-tags", "target-vsys").contains(key)
                || !cp && key.equals("target/negate") && Set.of(List.of("yes"), List.of("no")).contains(rule.extras().get(key));
        }
        private BitSet cell(Cell cell, String family) {
            int size = family.equals("address") ? ADDRESS_SIZE : family.equals("service") ? SERVICE_SIZE : apps.size();
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
        assertTrue(universe.coversInEveryWorld(prior, later), context + ": " + coveringId + " does not cover " + ruleId + " by brute force in every default-port world");
        boolean same = prior.action().equals(later.action());
        assertEquals(same ? "REDUNDANT" : "CONFLICT", status, context);
    }
    /**
     * No single earlier rule of the scope (enabled or possibly enabled, resolved or not; whatever the oracle cannot
     * interpret is the whole universe) covers the rule by brute force, in the open universe (any holds a zone and a
     * target no rule names) nor in the pair's smallest world ({@link Universe#pairWorld}). The engine cannot tell
     * which world is real, so a NOT_SHADOWED release must hold in every one.
     */
    static void assertNoCover(Universe universe, String ruleId, String context) {
        var later = universe.rules.get(ruleId);
        for (var prior : universe.earlierRules(ruleId)) {
            assertFalse(universe.coversInSomeWorld(prior, later), context + ": NOT_SHADOWED rule is covered by " + prior.id() + " by brute force");
            // Also in the two rules' own smallest world, where an any is only what they name: this is what confirms
            // that a prior naming zones (or targets) was rightly passed over for a later rule of any.
            assertFalse(universe.pairWorld(prior, later).coversInSomeWorld(prior, later),
                context + ": NOT_SHADOWED rule is covered by " + prior.id() + " by brute force in their smallest world");
        }
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
    static final String DEFAULT_PORTS = "application default ports are unknown";
    /** The rule's service is application-default, or that of an earlier enabled (or possibly enabled) rule of its scope. */
    static boolean involvesApplicationDefault(Universe universe, String ruleId) {
        var rule = universe.rules.get(ruleId);
        if (rule == null) return false; // outside security evaluation (NAT, default rules): not analysed
        if (universe.applicationDefault(rule.service())) return true;
        for (var prior : universe.earlierRules(ruleId)) if (universe.applicationDefault(prior.service())) return true;
        return false;
    }
    /**
     * One rule of a snapshot with application-default rules, against {@code reference}: the same analysis of the same
     * snapshot with the mapper's application-default service not recognised -- exactly the former treatment, "missing
     * service values". Identical, or the rule involves application-default ({@link #involvesApplicationDefault}) and its
     * new verdict holds in every default-port world: a covering rule covers in every world, a NOT_SHADOWED rule is
     * covered in none and shares no packet with an unresolved earlier rule in any, and "application default ports are
     * unknown" names a pair that covers in some world. Any other UNKNOWN claims nothing. Everything but the shadowing
     * verdict and its finding is unchanged. Returns the changed verdict ("status / reason"), or null when unchanged.
     */
    static String assertDefaultPortsChangeIsSafe(Universe universe, PolicyHygiene.Assessment reference, PolicyHygiene.Assessment got, String ruleId, String context) {
        if (got.equals(reference)) return null;
        assertTrue(involvesApplicationDefault(universe, ruleId), context + ": changed without application-default: " + reference + " -> " + got);
        return assertChangeHoldsInEveryWorld(universe, reference, got, ruleId, context);
    }
    static final String OVERLAP = "application overlap is unknown";
    /**
     * A rule names a content-provided application directly in its application cell while its service is not
     * application-default: an UNRESOLVED object of type unresolved, without values or members (the stored shape,
     * PanoramaPolicyMapper; the oracle does not check the reference itself).
     */
    /** The rule names a content-provided application ({@link Universe#content}), or an earlier enabled (or possibly enabled) rule of its scope does. */
    static boolean involvesContentApplication(Universe universe, String ruleId) {
        var rule = universe.rules.get(ruleId);
        if (rule == null) return false; // outside security evaluation (NAT, default rules): not analysed
        if (Universe.content(universe, rule)) return true;
        for (var prior : universe.earlierRules(ruleId)) if (Universe.content(universe, prior)) return true;
        return false;
    }
    /**
     * One rule against {@code reference}, the same analysis with every content-provided application of a rule with
     * explicit or any service unrecognised (SyntheticPolicies#unrecognisedApplications: the former "unresolved object"
     * treatment). Identical, or the rule involves a content-provided application ({@link #involvesContentApplication})
     * and its new verdict holds in every world, for every overlap of the named applications and every assignment of
     * default ports ({@link #assertChangeHoldsInEveryWorld}). Returns the changed verdict, or null when unchanged.
     */
    static String assertContentApplicationChangeIsSafe(Universe universe, PolicyHygiene.Assessment reference, PolicyHygiene.Assessment got, String ruleId, String context) {
        if (got.equals(reference)) return null;
        assertTrue(involvesContentApplication(universe, ruleId), context + ": changed without a content-provided application: " + reference + " -> " + got);
        return assertChangeHoldsInEveryWorld(universe, reference, got, ruleId, context);
    }
    /**
     * A changed verdict holds in every world: a covering rule covers in every world, a NOT_SHADOWED rule is covered in
     * none and shares no packet with an unresolved earlier rule in any, and "application default ports are unknown" /
     * "application overlap is unknown" name an earlier resolved rule -- with application-default, respectively an opaque
     * application without application-default, on one side -- that covers the rule in some world but not in every one.
     * Any other UNKNOWN claims nothing. Everything but the shadowing verdict and its finding is unchanged.
     */
    private static String assertChangeHoldsInEveryWorld(Universe universe, PolicyHygiene.Assessment reference, PolicyHygiene.Assessment got, String ruleId, String context) {
        var verdict = verdictOnly(universe, got, ruleId, context);
        assertEquals(List.of(reference.permissiveness(), reference.timeStatus(), reference.expiring(), reference.counterWindow(), String.valueOf(reference.hitSource()),
            String.valueOf(reference.hitsCollectedAt()), otherFindings(reference)), List.of(got.permissiveness(), got.timeStatus(), got.expiring(), got.counterWindow(),
            String.valueOf(got.hitSource()), String.valueOf(got.hitsCollectedAt()), otherFindings(got)), context);
        var shadow = got.findings().stream().filter(f -> !otherFindings(got).contains(f)).toList();
        String status = verdict.shadowStatus(), reason = verdict.shadowReason();
        boolean incomplete = !universe.snapshot.failures().isEmpty();
        switch (status) {
            case "REDUNDANT", "CONFLICT" -> {
                assertNull(reason, context);
                assertCoverIsSafe(universe, ruleId, verdict.coveringRuleId(), status, context);
                assertEquals(List.of(status.equals("CONFLICT") ? new PolicyHygiene.Finding("conflict", "HIGH", "Earlier enabled rule fully covers this match with a different action.")
                    : new PolicyHygiene.Finding("shadowed", "MEDIUM", "Earlier enabled rule fully covers this match with the same action.")), shadow, context);
            }
            case "NOT_SHADOWED" -> {
                assertFalse(incomplete, context);
                assertNull(reason, context); assertNull(verdict.coveringRuleId(), context); assertEquals(List.of(), shadow, context);
                assertNoCover(universe, ruleId, context); assertReleaseIsSafe(universe, ruleId, context);
            }
            default -> {
                assertEquals("UNKNOWN", status, context); assertNull(verdict.coveringRuleId(), context);
                assertEquals(List.of(new PolicyHygiene.Finding("unknown", "LOW", "Shadowing: " + reason + ".")), shadow, context);
                if (reason.equals("snapshot collection incomplete")) {
                    assertTrue(incomplete, context);
                    assertNoCover(universe, ruleId, context); assertReleaseIsSafe(universe, ruleId, context);
                }
                if (reason.equals(DEFAULT_PORTS) || reason.equals(OVERLAP)) {
                    var later = universe.rules.get(ruleId); boolean ports = reason.equals(DEFAULT_PORTS);
                    // An earlier rule that covers the rule in some world (some overlap of the named applications, some
                    // default ports) and not in every one, with application-default on one side for the default-ports
                    // reason and without it for the overlap reason.
                    assertTrue(universe.resolvedPriors(ruleId).stream().anyMatch(prior -> (universe.applicationDefault(prior.service()) || universe.applicationDefault(later.service())) == ports
                            && universe.coversExceptServiceApplication(prior, later) && universe.coversInSomeWorld(prior, later) && !universe.coversInEveryWorld(prior, later)),
                        context + ": no earlier rule leaves the cover to " + (ports ? "default ports" : "the application overlap"));
                }
            }
        }
        return status + (reason == null ? "" : " / " + reason);
    }
    /** The findings that are not the shadowing verdict's own. */
    private static List<PolicyHygiene.Finding> otherFindings(PolicyHygiene.Assessment a) {
        return a.findings().stream().filter(f -> !f.evidence().startsWith("Shadowing: ") && !Set.of("shadowed", "conflict").contains(f.findingClass())).toList();
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
