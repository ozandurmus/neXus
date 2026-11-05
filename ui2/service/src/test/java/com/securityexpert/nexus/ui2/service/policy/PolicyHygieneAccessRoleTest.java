package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Check Point access-role sources as partial priors (PolicyNormalization.Resolver#accessRoleBound): the role's networks
 * (CheckPointPolicyMapper#accessRoleNetworks) bound the source addresses an earlier access-role rule can match, so a
 * later rule whose source is disjoint from them is no longer kept UNKNOWN "earlier rule has unsupported constraints" by
 * it. The bound only proves disjointness: an access-role rule never covers. Networks any, missing, unresolvable or of
 * any other shape give no bound. Every verdict is compared with the same snapshot as mapped before (roles without
 * networks values) and confirmed by the brute-force oracle for every identity selection of the networks checked.
 * Synthetic fixtures: documentation addresses and masked names only.
 */
class PolicyHygieneAccessRoleTest {
    // This class pins the disjointness bound of an access role. X1 (pass-over by non-containment) would also release some
    // of its fixtures for a different reason, so every verdict here is read with X1 off; X1 over access roles is in
    // PolicyHygieneNonContainmentTest.
    static PolicyHygiene.Assessment assessment(PolicySnapshot snapshot, String id) { return PolicyHygieneTest.assessmentWithoutX1(snapshot, id); }
    static void assertUnknown(PolicySnapshot snapshot, String id, String reason) {
        var result = assessment(snapshot, id);
        assertEquals("UNKNOWN", result.shadowStatus(), id + " " + result);
        assertEquals(reason, result.shadowReason(), id);
        assertNull(result.coveringRuleId(), id);
    }
    static void assertClean(PolicySnapshot snapshot, String id) {
        var result = assessment(snapshot, id);
        assertEquals("NOT_SHADOWED", result.shadowStatus(), id + " " + result);
        assertNull(result.shadowReason()); assertNull(result.coveringRuleId());
    }
    static final String UNSUPPORTED = PolicyHygiene.EARLIER_UNSUPPORTED;
    static PolicyObject role(String id, String... networks) {
        return new PolicyObject(id, "OBJ-ROLE-" + id, "access-role", List.of(), Arrays.stream(networks).map(n -> "networks: " + n).toList(), "DYNAMIC");
    }
    static Map<String, PolicyObject> roleObjects() {
        var objects = PolicyHygienePartialPriorTest.partialObjects();
        objects.put("far-net", object("far-net", "network", "subnet4: 203.0.113.0", "mask-length4: 24"));
        objects.put("far-host", object("far-host", "host", "ipv4-address: 203.0.113.9"));
        objects.put("v6", object("v6", "network", "subnet6: 2001:db8::", "mask-length6: 64"));
        objects.put("role-net", role("role-net", "net"));
        objects.put("role-hosts", role("role-hosts", "host", "other-host"));
        objects.put("role-group", role("role-group", "group"));
        objects.put("role-any", role("role-any", "any"));
        objects.put("role-net-any", role("role-net-any", "net", "any"));
        objects.put("role-none", role("role-none"));
        objects.put("role-unres", role("role-unres", "missing"));
        objects.put("role-net-unres", role("role-net-unres", "net", "missing"));
        objects.put("role-v6", role("role-v6", "v6"));
        objects.put("role-cycle", role("role-cycle", "role-cycle"));
        objects.put("role-other-kind", new PolicyObject("role-other-kind", "OBJ-ROLE-X", "access-role", List.of(), List.of("networks: net", "users: OBJ-USER-01"), "DYNAMIC"));
        objects.put("role-members", new PolicyObject("role-members", "OBJ-ROLE-M", "access-role", List.of("net"), List.of("networks: net"), "DYNAMIC"));
        objects.put("role-resolved", new PolicyObject("role-resolved", "OBJ-ROLE-R", "access-role", List.of(), List.of("networks: net"), "RESOLVED"));
        return objects;
    }
    static PolicySnapshot cp(Rule... rules) {
        var base = sections("CP", cpSection("s1", null, rules));
        return new PolicySnapshot(base.metadata(), base.sections(), roleObjects());
    }
    static Rule cp(String id, int number, String action, String source, String destination, String service) {
        return PolicyHygienePartialPriorTest.cp(id, number, action, source, destination, service);
    }
    static Rule source(Rule r, List<String> refs, boolean negated) {
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), r.enabled(), new Cell(refs, negated), r.destination(), r.service(), r.application(),
            r.action(), r.log(), r.comment(), r.extras(), r.hitCounts());
    }
    /** Every rule against the snapshot as mapped before (no networks values), each change confirmed by the oracle. */
    static int assertOracle(PolicySnapshot snapshot) {
        var former = PolicyHygieneTest.withoutX1(SyntheticPolicies.withoutNetworks(snapshot), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        var actual = PolicyHygieneTest.withoutX1(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals(former.keySet(), actual.keySet());
        var universe = new PartialPriorOracle.Universe(snapshot);
        int released = 0;
        for (var entry : former.entrySet()) {
            String id = entry.getKey(), context = snapshot.metadata().id() + " rule " + id;
            if (PartialPriorOracle.assertSameOrSafelyReleased(universe, PartialPriorOracle.withoutBlocker(entry.getValue()), actual.get(id), id, context)) released++;
        }
        return released;
    }
    static void released(PolicySnapshot snapshot, String id, String blocker) {
        var before = assessment(SyntheticPolicies.withoutNetworks(snapshot), id);
        assertEquals(UNSUPPORTED, before.shadowReason(), id + " " + before);
        assertEquals(blocker, before.blockingRuleId());
        assertClean(snapshot, id);
        assertEquals(1, assertOracle(snapshot));
        PartialPriorOracle.assertNoCover(new PartialPriorOracle.Universe(snapshot), id, id);
    }
    static void blocked(PolicySnapshot snapshot, String id, String blocker) {
        assertUnknown(snapshot, id, UNSUPPORTED);
        assertEquals(blocker, assessment(snapshot, id).blockingRuleId());
        assertEquals(0, assertOracle(snapshot));
    }

    @Test void anAccessRoleWhoseNetworksAreDisjointFromTheLaterSourceReleasesIt() {
        // 192.0.2.0/24 against 198.51.100.0/24: no identity of the role can be the later rule's source.
        released(cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "other-net", "host", "port")), "r2", "r1");
        // Several networks, and a group of networks, bound the same way.
        released(cp(cp("r1", 1, "Drop", "role-hosts", "any", "any"), cp("r2", 2, "Accept", "far-net", "any", "port")), "r2", "r1");
        released(cp(cp("r1", 1, "Accept", "role-group", "any", "any"), cp("r2", 2, "Accept", "other-host", "any", "any")), "r2", "r1");
        // The role's own verdict is unchanged: an access role is no address.
        var snapshot = cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "other-net", "host", "port"));
        assertUnknown(snapshot, "r1", "unresolved object");
        assertEquals(assessment(SyntheticPolicies.withoutNetworks(snapshot), "r1"), assessment(snapshot, "r1"));
    }
    @Test void networksThatMayHoldTheLaterSourceKeepItUnknown() {
        // 198.51.100.7 (a role network) lies inside 198.51.100.0/24; 192.0.2.8 inside 192.0.2.0/24.
        blocked(cp(cp("r1", 1, "Accept", "role-hosts", "any", "any"), cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
        blocked(cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "host", "any", "port")), "r2", "r1");
        blocked(cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "any", "any", "port")), "r2", "r1");
    }
    @Test void anyMissingOrUnresolvableNetworksGiveNoBound() {
        for (String role : List.of("role-any", "role-net-any", "role-none", "role-unres", "role-net-unres", "role-v6", "role-cycle", "role-other-kind",
                "role-members", "role-resolved"))
            blocked(cp(cp("r1", 1, "Accept", role, "any", "any"), cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
        // An access role that is not in the dictionary at all is the usual unresolved object.
        blocked(cp(cp("r1", 1, "Accept", "role-absent", "any", "any"), cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
    }
    @Test void aSourceMixingRolesAndAddressesIsBoundedByTheirUnion() {
        var mixed = source(cp("r1", 1, "Accept", "role-net", "any", "any"), List.of("role-net", "far-host"), false);
        released(cp(mixed, cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
        blocked(cp(mixed, cp("r2", 2, "Accept", "far-net", "any", "port")), "r2", "r1");
        // Two roles: both bound it.
        var two = source(cp("r1", 1, "Accept", "role-net", "any", "any"), List.of("role-net", "role-hosts"), false);
        released(cp(two, cp("r2", 2, "Accept", "far-net", "any", "port")), "r2", "r1");
        blocked(cp(two, cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
        // One unbounded role or one unresolvable address leaves no bound.
        for (var refs : List.of(List.of("role-net", "role-any"), List.of("role-net", "unresolved-address"), List.of("role-net", "any")))
            blocked(cp(source(cp("r1", 1, "Accept", "role-net", "any", "any"), refs, false), cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
    }
    @Test void aNegatedRoleCellOrADestinationRoleIsNotBounded() {
        var negated = source(cp("r1", 1, "Accept", "role-net", "any", "any"), List.of("role-net"), true);
        blocked(cp(negated, cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
        // An access role in the destination column: no bound is used there.
        blocked(cp(cp("r1", 1, "Accept", "any", "role-net", "any"), cp("r2", 2, "Accept", "any", "other-net", "port")), "r2", "r1");
    }
    @Test void anAccessRoleRuleNeverCovers() {
        // The role's networks hold r2's source and r1 holds every other dimension: still no REDUNDANT or CONFLICT.
        for (String action : List.of("Accept", "Drop")) {
            var snapshot = cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, action, "host", "host", "port"));
            blocked(snapshot, "r2", "r1");
            assertTrue(assessment(snapshot, "r2").findings().stream().noneMatch(f -> Set.of("shadowed", "conflict").contains(f.findingClass())));
        }
        // A later full cover behind it is still found, and still by the fully resolved rule.
        var behind = cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Drop", "any", "net", "any"), cp("r3", 3, "Accept", "host", "host", "port"));
        assertEquals("CONFLICT", assessment(behind, "r3").shadowStatus());
        assertEquals("r2", assessment(behind, "r3").coveringRuleId());
        assertOracle(behind);
    }
    @Test void anAccessRoleRuleWithNothingElseResolvedBecomesAPartialPrior() {
        // Without install-on evidence and with destination and service any, r1 used to be an earlier unknown; its
        // networks now make it a partial prior whose bound releases r2.
        var extras = new HashMap<String, List<String>>(); extras.put("install-on", null); extras.put("from", null); extras.put("to", null);
        var r1 = SyntheticPolicies.withExtras(cp("r1", 1, "Accept", "role-net", "any", "any"), "Accept", extras);
        var snapshot = cp(r1, cp("r2", 2, "Accept", "other-net", "any", "port"));
        assertUnknown(snapshot, "r1", "unresolved object");
        var former = SyntheticPolicies.withoutNetworks(snapshot);
        assertNull(PolicyNormalization.partial(former, former.sections().get(0), r1, PolicyNormalization.Resolver.shadowing(former, new PolicyNormalization.Budget(10_000))));
        assertNotNull(PolicyNormalization.partial(snapshot, snapshot.sections().get(0), r1, PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(10_000))));
        released(snapshot, "r2", "r1");
        var anyRole = SyntheticPolicies.withExtras(cp("r1", 1, "Accept", "role-any", "any", "any"), "Accept", extras);
        blocked(cp(anyRole, cp("r2", 2, "Accept", "other-net", "any", "port")), "r2", "r1");
    }
    @Test void everyPartialPriorMustStillBeDisjoint() {
        var snapshot = cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "role-hosts", "any", "any"),
            cp("r3", 3, "Accept", "other-net", "any", "port"));
        blocked(snapshot, "r3", "r2");
        assertEquals("r1", assessment(SyntheticPolicies.withoutNetworks(snapshot), "r3").blockingRuleId());
        released(cp(cp("r1", 1, "Accept", "role-net", "any", "any"), cp("r2", 2, "Accept", "role-group", "any", "any"),
            cp("r3", 3, "Accept", "other-net", "any", "port")), "r3", "r1");
    }
    @Test void onlyShadowingPartialPriorsReadTheBound() {
        var snapshot = cp(cp("r1", 1, "Accept", "role-net", "any", "any"));
        var section = snapshot.sections().get(0); var r1 = section.rules().get(0);
        for (var resolver : List.of(PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(10_000)),
                new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(10_000)))) {
            assertEquals("unresolved object", PolicyNormalization.normalize(snapshot, section, r1, resolver).problem());
            assertEquals("unresolved object", PolicyNormalization.normalize(snapshot, section, r1, resolver, true).problem());
        }
        var partial = PolicyNormalization.partial(snapshot, section, r1, PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(10_000)));
        assertNotNull(partial.selectors().get(0));
        assertFalse(partial.selectors().get(0).any());
        // Palo Alto has no access roles; an object of that shape there is not read.
        var pan = PolicyHygieneTest.snapshot(List.of(PolicyHygienePartialPriorTest.pan("r1", "role-net", "any", "any", "allow"),
            PolicyHygienePartialPriorTest.pan("r2", "other-net", "any", "port", "allow")), roleObjects());
        assertUnknown(pan, "r2", UNSUPPORTED);
    }
    @Test void seededSnapshotsWithAccessRolesAreConfirmedByTheOracle() {
        int released = 0, kept = 0, rules = 0;
        for (long seed = 0; seed < 1500; seed++) {
            var base = seed % 3 == 0 ? SyntheticPolicies.inline(seed, 1 + (int) (seed % 40)) : SyntheticPolicies.random(seed, 1 + (int) (seed % 50));
            var snapshot = SyntheticPolicies.accessRoles(base, seed);
            if (!snapshot.metadata().vendor().equals("CP")) continue;
            released += assertOracle(snapshot);
            var after = PolicyHygieneTest.withoutX1(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
            kept += (int) after.values().stream().filter(a -> UNSUPPORTED.equals(a.shadowReason())).count();
            rules += after.size();
        }
        System.out.println("ACCESS ROLE ORACLE rules=" + rules + " released=" + released + " kept=" + kept);
        assertTrue(released >= 100, "released " + released);
        assertTrue(kept >= 100, "kept " + kept);
    }
}
