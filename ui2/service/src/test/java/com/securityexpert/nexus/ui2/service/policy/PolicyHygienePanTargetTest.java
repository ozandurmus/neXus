package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Panorama targets end to end: synthetic Panorama XML through PanoramaPolicyMapper into the shadowing analysis. A rule
 * with any target shape -- devices, devices restricted to virtual systems, tags, negate (with or without a selection),
 * or a shape nobody parsed -- is never treated as untargeted: shadowing rejects it ("unsupported rule constraint") and
 * it never covers a later rule. Formerly a tag-only target without a stored negate leaf mapped to no target extras at
 * all and covered the later rule below. A documented target only restricts which firewalls of the device group get the
 * rule (PATH_ANALYSIS_VENDOR_SEMANTICS.md 4d), so such a rule is a partial prior
 * (PolicyNormalization#targetNarrows): a later rule disjoint from it on a resolved dimension is released, every other
 * later rule stays UNKNOWN. A shape nobody parsed is no partial prior. Synthetic names and serials only.
 */
class PolicyHygienePanTargetTest {
    private static final Metadata META = new Metadata("policy-1", "manager-1", "Synthetic manager", "PAN", "container-1", "Synthetic group",
            "Synthetic policy", "2026-11-03T12:00:00Z", "artifact-1", List.of(new Target("device-1", "FW-TANGO-04", "vsys1", "UNKNOWN")));

    /** r1: everything any with the given target; r2: one host on TCP 443, untargeted. */
    private static PolicySnapshot map(String target) {
        String xml = "<config><devices><entry name='localhost.localdomain'><device-group><entry name='Synthetic group'>"
                + "<address><entry name='Synthetic host'><ip-netmask>192.0.2.8/32</ip-netmask></entry></address>"
                + "<service><entry name='Synthetic https'><protocol><tcp><port>443</port></tcp></protocol></entry></service>"
                + "<pre-rulebase><security><rules>"
                + "<entry name='Synthetic broad' uuid='uuid-r1'>" + any("any", "any") + target + "<action>allow</action></entry>"
                + "<entry name='Synthetic narrow' uuid='uuid-r2'>" + any("Synthetic host", "Synthetic https") + "<action>allow</action></entry>"
                + "</rules></security></pre-rulebase></entry></device-group></entry></devices></config>";
        return new PanoramaPolicyMapper().map(META, xml, "Synthetic group", Map.of(), false);
    }
    private static String any(String source, String service) {
        return "<from><member>any</member></from><to><member>any</member></to><source><member>" + source + "</member></source>"
                + "<destination><member>any</member></destination><service><member>" + service + "</member></service>"
                + "<application><member>any</member></application>";
    }
    private static Map<String, PolicyHygiene.Assessment> byName(PolicySnapshot snapshot) {
        var result = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        Map<String, PolicyHygiene.Assessment> out = new HashMap<>();
        for (var section : snapshot.sections()) for (var rule : section.rules()) out.put(rule.name(), result.get(rule.id()));
        return out;
    }

    @Test void untargetedBroadRuleCoversTheNarrowOne() {
        var result = byName(map(""));
        assertEquals("REDUNDANT", result.get("Synthetic narrow").shadowStatus());
    }
    @Test void negateNoWithoutAnySelectionIsUntargeted() {
        // Panorama stores <negate>no</negate> on rules whose target selects nothing: the rule is added to every firewall.
        var result = byName(map("<target><negate>no</negate></target>"));
        assertEquals("NOT_SHADOWED", result.get("Synthetic broad").shadowStatus());
        assertEquals("REDUNDANT", result.get("Synthetic narrow").shadowStatus());
    }
    /** The documented target shapes: devices, devices restricted to virtual systems, tags, negate with or without a selection. */
    static final List<String> DOCUMENTED = List.of(
            "<target><tags><member>TAG-ALPHA-01</member></tags></target>",
            "<target><devices><entry name='SYNTHETIC-SERIAL-01'><vsys><entry name='vsys2'/></vsys></entry></devices></target>",
            "<target><devices><entry name='SYNTHETIC-SERIAL-01'/></devices></target>",
            "<target><negate>yes</negate></target>",
            "<target><devices><entry name='SYNTHETIC-SERIAL-01'/></devices><negate>no</negate></target>",
            "<target><devices><entry name='SYNTHETIC-SERIAL-01'/></devices><negate>yes</negate></target>");
    /** Shapes nobody parsed. */
    static final List<String> UNPARSED = List.of("<target><tags/></target>", "<target><unexpected/></target>",
            "<target><negate>maybe</negate></target>");

    @Test void everyTargetShapeIsRejectedAndNeverCovers() {
        for (String target : concat(DOCUMENTED, UNPARSED)) {
            var snapshot = map(target);
            var result = byName(snapshot);
            var broad = result.get("Synthetic broad"); var narrow = result.get("Synthetic narrow");
            assertEquals("UNKNOWN", broad.shadowStatus(), target);
            assertEquals("unsupported rule constraint", broad.shadowReason(), target);
            // r1 overlaps r2 on every dimension: covered or not depends on which firewalls r1 reaches, never proven.
            assertEquals("UNKNOWN", narrow.shadowStatus(), target);
            assertEquals(PartialPriorOracle.UNSUPPORTED, narrow.shadowReason(), target);
            assertNull(narrow.coveringRuleId(), target);
            assertTrue(narrow.findings().stream().noneMatch(f -> Set.of("shadowed", "conflict").contains(f.findingClass())), target);
            var r1 = snapshot.sections().get(0).rules().get(0);
            assertEquals(r1.id(), narrow.blockingRuleId(), target);
            var resolver = new PolicyNormalization.Resolver(snapshot.objects(), new PolicyNormalization.Budget(Integer.MAX_VALUE));
            var partial = PolicyNormalization.partial(snapshot, snapshot.sections().get(0), r1, resolver);
            // A documented target only narrows: a partial prior. A shape nobody parsed may mean anything: none.
            if (DOCUMENTED.contains(target)) assertNotNull(partial, target); else assertNull(partial, target);
        }
    }
    /** r1: one host on TCP 443 with the given target; r2: the other network on any service, untargeted. */
    private static PolicySnapshot disjoint(String target) {
        String xml = "<config><devices><entry name='localhost.localdomain'><device-group><entry name='Synthetic group'>"
                + "<address><entry name='Synthetic host'><ip-netmask>192.0.2.8/32</ip-netmask></entry>"
                + "<entry name='Synthetic other'><ip-netmask>198.51.100.0/24</ip-netmask></entry></address>"
                + "<service><entry name='Synthetic https'><protocol><tcp><port>443</port></tcp></protocol></entry></service>"
                + "<pre-rulebase><security><rules>"
                + "<entry name='Synthetic targeted' uuid='uuid-r1'>" + any("Synthetic host", "Synthetic https") + target + "<action>deny</action></entry>"
                + "<entry name='Synthetic later' uuid='uuid-r2'>" + any("Synthetic other", "any") + "<action>allow</action></entry>"
                + "</rules></security></pre-rulebase></entry></device-group></entry></devices></config>";
        return new PanoramaPolicyMapper().map(META, xml, "Synthetic group", Map.of(), false);
    }
    @Test void aLaterRuleDisjointFromADocumentedTargetedRuleIsReleased() {
        for (String target : DOCUMENTED) {
            var snapshot = disjoint(target);
            var result = byName(snapshot);
            assertEquals("unsupported rule constraint", result.get("Synthetic targeted").shadowReason(), target);
            var later = result.get("Synthetic later");
            assertEquals("NOT_SHADOWED", later.shadowStatus(), target + " " + later);
            assertNull(later.shadowReason(), target); assertNull(later.blockingRuleId(), target);
            // The former reading of the same snapshot (the target as a field of unknown meaning) kept it UNKNOWN.
            var former = SyntheticPolicies.opaqueTargets(snapshot);
            var formerLater = PolicyHygiene.analyze(former, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules().get(snapshot.sections().get(0).rules().get(1).id());
            assertEquals(PartialPriorOracle.UNSUPPORTED, formerLater.shadowReason(), target);
            assertEquals(1, PolicyHygienePanTargetTest.assertOracle(snapshot), target);
        }
        for (String target : UNPARSED) {
            var later = byName(disjoint(target)).get("Synthetic later");
            assertEquals("UNKNOWN", later.shadowStatus(), target);
            assertEquals(PartialPriorOracle.UNSUPPORTED, later.shadowReason(), target);
            assertEquals(0, PolicyHygienePanTargetTest.assertOracle(disjoint(target)), target);
        }
    }
    /**
     * Every rule against the snapshot as the former analysis read it (targets as a field of unknown meaning): identical,
     * or released from "earlier rule has unsupported constraints" and confirmed by the brute-force oracle. Returns the
     * number of released rules.
     */
    static int assertOracle(PolicySnapshot snapshot) {
        var former = PolicyHygiene.analyze(SyntheticPolicies.opaqueTargets(snapshot), 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        var actual = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals(former.keySet(), actual.keySet());
        var universe = new PartialPriorOracle.Universe(snapshot);
        int released = 0;
        for (var entry : former.entrySet()) {
            String id = entry.getKey(), context = snapshot.metadata().id() + " rule " + id;
            if (PartialPriorOracle.assertSameOrSafelyReleased(universe, PartialPriorOracle.withoutBlocker(entry.getValue()), actual.get(id), id, context)) released++;
        }
        return released;
    }
    @Test void seededSnapshotsWithPanoramaTargetsAreConfirmedByTheOracle() {
        int released = 0, kept = 0, rules = 0, snapshots = 0;
        for (long seed = 0; seed < 4000; seed++) {
            var snapshot = SyntheticPolicies.panTargets(SyntheticPolicies.random(seed, 1 + (int) (seed % 60)), seed);
            if (!snapshot.metadata().vendor().equals("PAN")) continue;
            snapshots++;
            released += assertOracle(snapshot);
            var after = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK).rules();
            kept += (int) after.values().stream().filter(a -> PartialPriorOracle.UNSUPPORTED.equals(a.shadowReason())).count();
            rules += after.size();
        }
        System.out.println("PAN TARGET ORACLE snapshots=" + snapshots + " rules=" + rules + " released=" + released + " kept=" + kept);
        assertTrue(released >= 100, "released " + released);
        assertTrue(kept >= 100, "kept " + kept);
    }
    private static List<String> concat(List<String> a, List<String> b) { var out = new ArrayList<>(a); out.addAll(b); return out; }
}
