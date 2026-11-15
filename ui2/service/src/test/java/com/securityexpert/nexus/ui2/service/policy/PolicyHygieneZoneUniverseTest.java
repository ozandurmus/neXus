package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.pan;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.with;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneZoneSupersetTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneRepository;
import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneSet;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;

/**
 * PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md (DECIDED): a Palo Alto rule whose zone is a lone {@code any} is decided against an
 * earlier list of zone names once the zones of its targets are known -- and stays UNKNOWN when they are not. End to end
 * through {@link PolicyQueryService} (stored zone sets joined to the snapshot's targets), through the hygiene engine
 * directly, and against the same policy written out with explicit zone lists. Synthetic names only.
 */
class PolicyHygieneZoneUniverseTest {
    static final ZoneUniverse AB = ZoneUniverse.of(List.of(Set.of(A, B)));
    static final ZoneUniverse ABC = ZoneUniverse.of(List.of(Set.of(A, B, C)));

    static PolicyHygiene.Assessment at(PolicySnapshot snapshot, String id, ZoneUniverse zones) {
        return PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, 250000, snapshot.objects(), zones).rules().get(id);
    }
    /** The 481: an earlier rule lists names, a later rule is a lone any with the same selectors. */
    static PolicySnapshot listThenAny() {
        return pan(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), zoned("r2", "net", "allow", List.of("any"), List.of("any")));
    }

    @Test void aLoneAnyRuleIsCoveredWhenTheEarlierListNamesEveryZoneOfTheTargets() {
        var snapshot = listThenAny();
        assertEquals(UNPROVEN, at(snapshot, "r2", ZoneUniverse.UNKNOWN).shadowReason(), "without the zone sets it is the former UNKNOWN");
        var covered = at(snapshot, "r2", AB);
        assertEquals("REDUNDANT", covered.shadowStatus());
        assertEquals("r1", covered.coveringRuleId());
        assertNull(covered.shadowReason());
        // The rule that lists the zones is not affected.
        assertEquals("NOT_SHADOWED", at(snapshot, "r1", AB).shadowStatus());
        // A different action is a conflict.
        var conflict = pan(zoned("r1", "net", "deny", List.of(A, B), List.of(A, B)), zoned("r2", "net", "allow", List.of("any"), List.of("any")));
        assertEquals("CONFLICT", at(conflict, "r2", AB).shadowStatus());
        assertEquals("r1", at(conflict, "r2", AB).coveringRuleId());
    }
    @Test void aLoneAnyRuleIsNotCoveredWhenATargetHasAZoneTheListLacks() {
        var snapshot = listThenAny();
        var clean = at(snapshot, "r2", ABC);
        assertEquals("NOT_SHADOWED", clean.shadowStatus());
        assertNull(clean.shadowReason());
        assertNull(clean.coveringRuleId());
    }
    @Test void oneSideAnyAndTheOtherNamesDecidesOnTheAnySideOnly() {
        // from is a lone any (decided against the universe), to is names (decided as before).
        var covered = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C)), zoned("r2", "net", "allow", List.of("any"), List.of(C)));
        assertEquals("REDUNDANT", at(covered, "r2", AB).shadowStatus());
        assertEquals(UNPROVEN, at(covered, "r2", ZoneUniverse.UNKNOWN).shadowReason());
        // to names a zone the earlier list lacks: no cover whatever the universe says on from.
        var noCover = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(C)), zoned("r2", "net", "allow", List.of("any"), List.of(D)));
        assertEquals("NOT_SHADOWED", at(noCover, "r2", AB).shadowStatus());
    }
    @Test void anAnyMixedWithNamesStaysUndecidedWhateverTheUniverse() {
        var snapshot = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), zoned("r2", "net", "allow", List.of("any", A), List.of("any")));
        assertEquals(UNPROVEN, at(snapshot, "r2", AB).shadowReason());
        var earlierMixed = pan(zoned("r1", "net", "allow", List.of("any", A), List.of(A, B)), zoned("r2", "net", "allow", List.of("any"), List.of(A, B)));
        assertEquals(UNPROVEN, at(earlierMixed, "r2", AB).shadowReason());
    }
    @Test void anEarlierAnyStillCoversNamedZonesAndInstallOnStaysExact() {
        var snapshot = pan(zoned("r1", "net", "allow", List.of("any"), List.of("any")), zoned("r2", "net", "allow", List.of(A), List.of(B)));
        assertEquals("REDUNDANT", at(snapshot, "r2", AB).shadowStatus());
        assertEquals("REDUNDANT", at(snapshot, "r2", ZoneUniverse.UNKNOWN).shadowStatus());
        var otherTarget = pan(with(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), Map.of("install-on", List.of("gw-a"))),
            zoned("r2", "net", "allow", List.of("any"), List.of("any")));
        assertNotEquals("REDUNDANT", at(otherTarget, "r2", AB).shadowStatus(), "unequal install-on is still unproven, not covered");
    }
    @Test void aLaterIntrazoneOrInterzoneRuleIsCoveredByAListThatNamesEveryZone() {
        var intrazone = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), zoned("r2", "net", "allow", List.of("any"), List.of("any"), "intrazone"));
        assertEquals("REDUNDANT", at(intrazone, "r2", AB).shadowStatus());
        assertEquals("UNKNOWN", at(intrazone, "r2", ZoneUniverse.UNKNOWN).shadowStatus());
        var interzone = pan(zoned("r1", "net", "allow", List.of(A, B), List.of(A, B)), zoned("r2", "net", "allow", List.of("any"), List.of("any"), "interzone"));
        assertEquals("REDUNDANT", at(interzone, "r2", AB).shadowStatus());
        // A zone of the targets outside the list: not covered (a typed rule is never a clean verdict, so it stays UNKNOWN).
        assertNotEquals("REDUNDANT", at(intrazone, "r2", ABC).shadowStatus());
        assertNotEquals("REDUNDANT", at(interzone, "r2", ABC).shadowStatus());
    }
    @Test void aKnownUniverseNeverChangesAnotherVendor() {
        var base = PolicyHygieneTest.sections("CP", PolicyHygieneTest.cpSection("s1", null, PolicyHygieneTest.cpRule("r1", "Accept"), PolicyHygieneTest.cpRule("r2", "Accept")));
        var without = PolicyHygiene.analyze(base, 90, PolicyHygieneTest.NOW, 250000, base.objects(), ZoneUniverse.UNKNOWN);
        var with = PolicyHygiene.analyze(base, 90, PolicyHygieneTest.NOW, 250000, base.objects(), AB);
        assertEquals(without, with);
    }

    // ---- end to end: stored zone sets joined to the snapshot's targets ----

    static final Instant NOW = Instant.now();
    static PolicySnapshot withTargets(PolicySnapshot snapshot, Target... targets) {
        var m = snapshot.metadata();
        return new PolicySnapshot(new Metadata(m.id(), m.sourceId(), m.sourceName(), m.vendor(), m.containerId(), m.containerName(), m.name(),
            m.collectedAt(), m.artefactRef(), List.of(targets)), snapshot.sections(), snapshot.objects(), snapshot.failures());
    }
    static Target target(String device, String context) { return new Target(device, "FW-TANGO-01", context, "IN_SYNC"); }
    static PanVsysZoneRepository zones(PanVsysZoneSet... sets) {
        Map<String, Map<String, PanVsysZoneSet>> stored = new HashMap<>();
        for (var s : sets) stored.computeIfAbsent(s.deviceId(), k -> new HashMap<>()).put(s.vsys(), s);
        return new PanVsysZoneRepository() {
            @Override public int replace(String d, Instant at, List<PanVsysZoneSet> s, String a, String b) { throw new AssertionError(); }
            @Override public Map<String, Map<String, PanVsysZoneSet>> findForDevices(Collection<String> ids) {
                Map<String, Map<String, PanVsysZoneSet>> out = new HashMap<>();
                ids.forEach(id -> { if (stored.containsKey(id)) out.put(id, stored.get(id)); });
                return out;
            }
        };
    }
    static PolicyQueryService service(PanVsysZoneRepository repository) {
        var query = new PolicyQueryService(mock(TransactionBoundary.class), new ObjectMapper(), new TopologyNamePseudonymizer(new byte[32]), null,
            new SubnetPreservingIpMasker(new byte[32]));
        if (repository != null) query.setPanVsysZoneRepository(repository);
        return query;
    }
    static PanVsysZoneSet row(String device, String vsys, Instant readAt, String... names) { return new PanVsysZoneSet(device, vsys, Set.of(names), readAt); }

    @Test void theLookupOfAnInventoriedFirewallDecidesTheLoneAnyRule() {
        var snapshot = withTargets(listThenAny(), target("d1", "vsys1"));
        var fresh = Instant.now().minus(Duration.ofHours(2));
        // Before: no stored zones, or no repository at all -- the former UNKNOWN.
        assertEquals(UNPROVEN, service(null).analysis(snapshot, 90).rules().get("r2").shadowReason());
        assertEquals(UNPROVEN, service(zones()).analysis(snapshot, 90).rules().get("r2").shadowReason());
        // The list names every zone of the one target: decided.
        var decided = service(zones(row("d1", "vsys1", fresh, A, B))).analysis(snapshot, 90).rules().get("r2");
        assertEquals("REDUNDANT", decided.shadowStatus());
        assertEquals("r1", decided.coveringRuleId());
        // The target has a third zone, one that has no interface: not covered, and no longer unknown.
        var notCovered = service(zones(row("d1", "vsys1", fresh, A, B, "ZONE-EMPTY-09"))).analysis(snapshot, 90).rules().get("r2");
        assertEquals("NOT_SHADOWED", notCovered.shadowStatus());
        assertNull(notCovered.shadowReason());
    }
    @Test void aStaleMissingOrUnmatchedTargetKeepsTheRuleUnknown() {
        var fresh = Instant.now().minus(Duration.ofHours(2));
        var good = row("d1", "vsys1", fresh, A, B);
        var rows = zones(good, row("d2", "vsys1", Instant.now().minus(Duration.ofHours(25)), A, B));
        for (var targets : List.of(
                new Target[] {target("d1", "vsys1"), target("d2", "vsys1")},                  // one read older than 24 hours
                new Target[] {target("d1", "vsys1"), target("d3", "vsys1")},                  // a firewall neXus does not inventory
                new Target[] {target("d1", "vsys1"), target("d1", "vsys2")},                  // a virtual system with no stored set
                new Target[] {target("d1", "")},                                              // Panorama named no virtual system
                new Target[] {target("d1", "vsys1"), target("ref-unmatched-member", "vsys1")},
                new Target[] {})) {                                                           // nothing to judge against
            assertEquals(UNPROVEN, service(rows).analysis(withTargets(listThenAny(), targets), 90).rules().get("r2").shadowReason(), Arrays.toString(targets));
        }
        // A failing read of the zone sets leaves the verdict undecided; it never fails the analysis.
        var failing = new PanVsysZoneRepository() {
            @Override public int replace(String d, Instant at, List<PanVsysZoneSet> s, String a, String b) { return 0; }
            @Override public Map<String, Map<String, PanVsysZoneSet>> findForDevices(Collection<String> ids) { throw new IllegalStateException("synthetic"); }
        };
        assertEquals(UNPROVEN, service(failing).analysis(withTargets(listThenAny(), target("d1", "vsys1")), 90).rules().get("r2").shadowReason());
    }
    @Test void everyTargetOfAMultiVsysDeviceGroupMustBeHeldByTheList() {
        var fresh = Instant.now().minus(Duration.ofHours(1));
        var snapshot = withTargets(listThenAny(), target("d1", "vsys1"), target("d1", "vsys2"), target("d2", "vsys1"));
        var allHeld = zones(row("d1", "vsys1", fresh, A, B), row("d1", "vsys2", fresh, B), row("d2", "vsys1", fresh, A));
        assertEquals("REDUNDANT", service(allHeld).analysis(snapshot, 90).rules().get("r2").shadowStatus());
        var oneLacks = zones(row("d1", "vsys1", fresh, A, B), row("d1", "vsys2", fresh, B), row("d2", "vsys1", fresh, A, C));
        assertEquals("NOT_SHADOWED", service(oneLacks).analysis(snapshot, 90).rules().get("r2").shadowStatus(), "only d2 has a zone the list lacks");
    }

    // ---- the same policy written out: a lone any is the targets' zone list ----

    /**
     * Seeded policies whose zones are drawn from the targets' zones. Analysed with the zones known, each must give the
     * verdict (status, covering rule, reason) of the same policy with every lone any written out as the targets' zone list,
     * which the engine decides without any universe. Sets mixing any with names are not interpreted, so they are left out.
     */
    @Test void aKnownUniverseGivesTheVerdictOfTheExplicitZoneLists() {
        int pans = 0, rules = 0, decided = 0, covered = 0, notCovered = 0;
        for (long seed = 0; pans < 3000; seed++) {
            var base = SyntheticPolicies.random(seed + 900_000, 4 + (int) (seed % 40));
            if (!base.metadata().vendor().equals("PAN")) continue;
            pans++;
            var random = new Random(seed);
            var all = new ArrayList<>(List.of(A, B, C, D, E));
            Collections.shuffle(all, random);
            var targets = new TreeSet<>(all.subList(0, 2 + random.nextInt(4)));
            var universe = ZoneUniverse.of(List.of(targets));
            var shaped = lone(ruleTypes(base, seed), targets);
            var written = written(shaped, targets);
            var expected = PolicyHygiene.analyze(written, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK, written.objects(), ZoneUniverse.UNKNOWN);
            var actual = PolicyHygiene.analyze(shaped, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK, shaped.objects(), universe);
            var former = PolicyHygiene.analyze(shaped, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK, shaped.objects(), ZoneUniverse.UNKNOWN);
            assertFalse(actual.budgetReached());
            for (var entry : expected.rules().entrySet()) {
                var want = entry.getValue(); var got = actual.rules().get(entry.getKey());
                String context = "seed " + seed + " rule " + entry.getKey();
                // Covered or not, and why not, are the same. Which of several covering rules is named (and so REDUNDANT or
                // CONFLICT) is not: the engine prefers a prior with the rule's own placement key over an earlier superset.
                boolean wantCovered = want.coveringRuleId() != null, gotCovered = got.coveringRuleId() != null;
                assertEquals(wantCovered, gotCovered, context);
                assertEquals(want.shadowReason(), got.shadowReason(), context);
                if (!wantCovered) assertEquals(want.shadowStatus(), got.shadowStatus(), context);
                rules++;
                var before = former.rules().get(entry.getKey());
                if (UNPROVEN.equals(before.shadowReason()) && !UNPROVEN.equals(got.shadowReason())) {
                    decided++;
                    if (got.coveringRuleId() != null) covered++; else notCovered++;
                }
            }
        }
        // The seeds must exercise the change in both directions, or the equality above proves little.
        assertTrue(decided >= 200 && covered >= 20 && notCovered >= 100, "decided " + decided + " covered " + covered + " not covered " + notCovered);
        System.out.println("ZONE UNIVERSE " + pans + " PAN snapshots, " + rules + " rules: " + decided + " former UNKNOWN now decided ("
            + covered + " covered, " + notCovered + " not covered)");
    }
    /** Each rule's zones mapped onto the targets' zones; a set mixing any with names becomes a lone any. */
    private static PolicySnapshot lone(PolicySnapshot snapshot, Set<String> targets) {
        var names = new ArrayList<>(targets);
        List<Section> sections = new ArrayList<>();
        for (Section section : snapshot.sections()) {
            List<Rule> rules = new ArrayList<>();
            for (Rule rule : section.rules()) {
                var extras = new HashMap<String, List<String>>();
                for (String key : List.of("from", "to")) {
                    var values = rule.extras().get(key);
                    if (values == null) continue;
                    if (values.contains("any")) extras.put(key, List.of("any"));
                    else extras.put(key, values.stream().map(z -> names.get(List.of(A, B, C, D, E).indexOf(z) % names.size())).distinct().sorted().toList());
                }
                rules.add(SyntheticPolicies.withExtras(rule, rule.action(), extras));
            }
            sections.add(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
        }
        return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
    }
    private static PolicySnapshot written(PolicySnapshot snapshot, Set<String> targets) {
        var list = targets.stream().sorted().toList();
        List<Section> sections = new ArrayList<>();
        for (Section section : snapshot.sections()) {
            List<Rule> rules = new ArrayList<>();
            for (Rule rule : section.rules()) {
                var extras = new HashMap<String, List<String>>();
                for (String key : List.of("from", "to")) if (List.of("any").equals(rule.extras().get(key))) extras.put(key, list);
                rules.add(SyntheticPolicies.withExtras(rule, rule.action(), extras));
            }
            sections.add(new Section(section.id(), section.name(), section.source(), section.parentRuleId(), rules));
        }
        return new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects(), snapshot.failures());
    }
}
