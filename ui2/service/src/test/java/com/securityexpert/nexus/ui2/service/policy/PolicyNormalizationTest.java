package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Pins the existing {@link PolicyNormalization} rules for three edges: an empty or absent rule section is skipped (it is
 * never a zero-rule clean policy), a negated or untargeted cell is normalised consistently (never read as "any"), and
 * the documented null results. Test-only; no production behaviour is changed. Synthetic fixtures only: documentation
 * address blocks (192.0.2.x) and masked-style names.
 */
class PolicyNormalizationTest {
    private static final Map<String, PolicyObject> OBJECTS = Map.of(
        "any", PolicyHygieneTest.object("any", "any"),
        "FW-TANGO-04-HOST", PolicyHygieneTest.object("FW-TANGO-04-HOST", "address", "ipv4-address: 192.0.2.8"),
        "FW-TANGO-04-NET", PolicyHygieneTest.object("FW-TANGO-04-NET", "address", "ip-netmask: 192.0.2.0/24"),
        "FW-TANGO-04-SVC", PolicyHygieneTest.object("FW-TANGO-04-SVC", "service", "protocol: tcp", "port: 443"));

    private static PolicyNormalization.Budget budget() { return new PolicyNormalization.Budget(100_000); }

    private static Rule rule(Cell source, Cell application) {
        var r = PolicyHygienePartialPriorTest.pan("FW-TANGO-04-R1", "FW-TANGO-04-HOST", "any", "FW-TANGO-04-SVC", "allow");
        return new Rule(r.id(), r.uuid(), r.number(), r.name(), true, source, r.destination(), r.service(), application,
            r.action(), r.log(), "", r.extras());
    }

    private static Cell positive(String ref) { return new Cell(List.of(ref), false); }
    private static Cell negated(String ref) { return new Cell(List.of(ref), true); }
    private static final Cell ANY = positive("any");

    // ---- empty or absent rule sections ---------------------------------------------------------------------------------

    @Test
    void emptySectionIsSkippedAndNeverBecomesAFreshCleanState() {
        var base = PolicyHygieneTest.snapshot(List.of(), OBJECTS);
        var scope = new PolicyNormalization.EvaluationScope<String>(base.metadata(), () -> "fresh");

        // An empty section evaluates nothing: no state is opened for it beyond the scope's own, it stays unproven-free.
        var first = scope.advance(PolicyHygieneTest.panSection("Shared", "pre-rulebase"));
        var second = scope.advance(PolicyHygieneTest.panSection("Shared", "rulebase"));
        assertEquals("fresh", first);
        assertSame(first, second, "consecutive empty sections share the one scope state");
        assertFalse(scope.unproven, "an empty section must not mark earlier sections as unanalysed");
    }

    @Test
    void emptySectionDoesNotAdvanceThePhaseOrder() {
        var base = PolicyHygieneTest.snapshot(List.of(), OBJECTS);
        var scope = new PolicyNormalization.EvaluationScope<String>(base.metadata(), () -> "fresh");
        // A non-empty section after an empty one must not be read as out of order because of the empty one.
        var rule = PolicyHygienePartialPriorTest.pan("FW-TANGO-04-R2", "FW-TANGO-04-HOST", "any", "FW-TANGO-04-SVC", "allow");
        scope.advance(PolicyHygieneTest.panSection("Shared", "pre-rulebase"));
        var state = scope.advance(PolicyHygieneTest.panSection("Shared", "rulebase", rule));
        assertNotNull(state);
        assertFalse(scope.unproven, "an empty earlier section must not make a later section unproven");
    }

    // ---- negated and untargeted cells -----------------------------------------------------------------------------------

    @Test
    void negatedAddressCellResolvesToAComplementInShadowingOnly() {
        var resolver = PolicyNormalization.Resolver.shadowing(PolicyHygieneTest.snapshot(List.of(), OBJECTS), budget());
        var complement = resolver.cell(negated("FW-TANGO-04-NET"), "address");
        assertFalse(complement.any(), "a negated address is never read as any");
        assertTrue(complement.ranges().containsKey("ipv4"), complement.ranges().keySet().toString());
    }

    @Test
    void negatedCellIsUnresolvedForPlainResolverAndForNonAddressFamilies() {
        var plain = new PolicyNormalization.Resolver(OBJECTS, budget());
        var ex = assertThrows(PolicyNormalization.Unknown.class, () -> plain.cell(negated("FW-TANGO-04-NET"), "address"));
        assertEquals("negated selector", ex.getMessage());

        var shadowing = PolicyNormalization.Resolver.shadowing(PolicyHygieneTest.snapshot(List.of(), OBJECTS), budget());
        var service = assertThrows(PolicyNormalization.Unknown.class, () -> shadowing.cell(negated("FW-TANGO-04-SVC"), "service"));
        assertEquals("negated selector", service.getMessage());
        var application = assertThrows(PolicyNormalization.Unknown.class, () -> shadowing.applications(negated("any")));
        assertEquals("negated selector", application.getMessage());
    }

    @Test
    void negatedSourceNormalisesToComplementWhileNegatedApplicationStaysUnknown() {
        var negatedSource = rule(negated("FW-TANGO-04-NET"), ANY);
        var snapshot = PolicyHygieneTest.snapshot(List.of(negatedSource), OBJECTS);
        var resolver = PolicyNormalization.Resolver.shadowing(snapshot, budget());
        var match = PolicyNormalization.normalize(snapshot, snapshot.sections().get(0), negatedSource, resolver);
        assertNull(match.problem(), "a negated address is a resolvable complement in shadowing");
        assertFalse(match.selectors().get(0).any());
    }

    @Test
    void negatedApplicationIsNeverTreatedAsAny() {
        var snapshot = PolicyHygieneTest.snapshot(List.of(rule(ANY, negated("any"))), OBJECTS);
        var match = PolicyNormalization.normalize(snapshot, snapshot.sections().get(0), snapshot.sections().get(0).rules().get(0),
            PolicyNormalization.Resolver.shadowing(snapshot, budget()));
        assertEquals("negated selector", match.problem());
    }

    @Test
    void untargetedNegateNoIsNormalisedAsNoTargetOnPanOnly() {
        var r = rule(ANY, ANY);
        var untargeted = PolicyHygienePartialPriorTest.with(r, Map.of("target/negate", List.of("no")));
        var pan = PolicyHygieneTest.snapshot(List.of(untargeted), OBJECTS);
        assertTrue(PolicyNormalization.untargeted(pan, untargeted, "target/negate"),
            "PAN target/negate no with no other target shape is the same as no target");

        var cpSnapshot = PolicyHygieneTest.sections("CP", PolicyHygieneTest.panSection("Shared", "rulebase", untargeted));
        assertFalse(PolicyNormalization.untargeted(cpSnapshot, untargeted, "target/negate"), "untargeted is Palo Alto only");
    }

    @Test
    void targetNegateYesOrOtherValuesStayUnsupportedForShadowing() {
        var r = rule(ANY, ANY);
        var yes = PolicyHygienePartialPriorTest.with(r, Map.of("target/negate", List.of("yes")));
        var snapshot = PolicyHygieneTest.snapshot(List.of(yes), OBJECTS);
        var match = PolicyNormalization.normalize(snapshot, snapshot.sections().get(0), yes, PolicyNormalization.Resolver.shadowing(snapshot, budget()));
        assertEquals("unsupported rule constraint", match.problem(), "negate yes without a device selection is not untargeted");
    }

    // ---- null results ---------------------------------------------------------------------------------------------------

    @Test
    void typedReturnsNullOutsidePaloAltoAsDocumented() {
        var r = rule(ANY, ANY);
        var cp = PolicyHygieneTest.sections("CP", PolicyHygieneTest.panSection("Shared", "rulebase", r));
        assertNull(PolicyNormalization.typed(cp, cp.sections().get(0), r, PolicyNormalization.Resolver.shadowing(cp, budget())));
    }

    @Test
    void partialReturnsNullForAnUnevaluatedVendorAsDocumented() {
        var r = rule(ANY, ANY);
        var other = PolicyHygieneTest.sections("OTHER", PolicyHygieneTest.panSection("Shared", "rulebase", r));
        assertNull(PolicyNormalization.partial(other, other.sections().get(0), r, PolicyNormalization.Resolver.shadowing(other, budget())));
    }

    @Test
    void unevaluatedVendorNormalisesToUnknownNotClean() {
        var r = rule(ANY, ANY);
        var other = PolicyHygieneTest.sections("OTHER", PolicyHygieneTest.panSection("Shared", "rulebase", r));
        var match = PolicyNormalization.normalize(other, other.sections().get(0), r, PolicyNormalization.Resolver.shadowing(other, budget()));
        assertEquals("unsupported rulebase", match.problem());
        assertTrue(match.selectors().isEmpty());
    }

    // ---- object type vs status & source-port fail-closed soundness ------------------------------------------------------

    @Test
    void objectTypeCheckedBeforeStatusDistinguishesIdentityDynamicAndMissingObjects() {
        assertFalse(CutoverAssessment.NARROWING_REASONS.contains(PolicyHygiene.ACCESS_ROLE_CONSTRAINT),
            "CutoverAssessment must not treat an access-role selector as a narrowing extra");
        assertTrue(PolicyHygiene.REASONS.contains(PolicyHygiene.ACCESS_ROLE_CONSTRAINT));

        Map<String, PolicyObject> objects = new LinkedHashMap<>(OBJECTS);
        for (String status : List.of("DYNAMIC", "RESOLVED", "UNSUPPORTED"))
            objects.put("role-" + status, new PolicyObject("role-" + status, "OBJ-ROLE-" + status, "access-role", List.of(), List.of(), status));
        objects.put("dug", new PolicyObject("dug", "OBJ-DUG-01", "dynamic-user-group", List.of(), List.of(), "DYNAMIC"));
        objects.put("dns", new PolicyObject("dns", "OBJ-DNS-01", "dns-domain", List.of(), List.of(), "DYNAMIC"));
        objects.put("upd", new PolicyObject("upd", "OBJ-UPD-01", "updatable-object", List.of(), List.of(), "DYNAMIC"));
        objects.put("dyn", new PolicyObject("dyn", "OBJ-DYN-01", "dynamic-object", List.of(), List.of(), "DYNAMIC"));
        objects.put("zone", new PolicyObject("zone", "OBJ-ZONE-01", "security-zone", List.of(), List.of(), "DYNAMIC"));
        objects.put("wild", new PolicyObject("wild", "OBJ-WILD-01", "wildcard", List.of(), List.of(), "UNSUPPORTED"));
        objects.put("host-unres", new PolicyObject("host-unres", "OBJ-HOST-UNRES", "host", List.of(), List.of(), "UNRESOLVED"));
        objects.put("unres", new PolicyObject("unres", "OBJ-UNRES-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));

        var resolver = new PolicyNormalization.Resolver(objects, budget());
        for (String id : List.of("role-DYNAMIC", "role-RESOLVED", "role-UNSUPPORTED", "dug")) {
            var ex = assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(positive(id), "address"), id);
            assertEquals(PolicyHygiene.ACCESS_ROLE_CONSTRAINT, ex.getMessage(), id);
        }
        for (String id : List.of("dns", "upd", "dyn", "zone", "wild")) {
            var ex = assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(positive(id), "address"), id);
            assertEquals("dynamic or unsupported object type", ex.getMessage(), id);
        }
        for (String id : List.of("missing-id", "host-unres", "unres")) {
            var ex = assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(positive(id), "address"), id);
            assertEquals("unresolved object", ex.getMessage(), id);
        }
    }

    @Test
    void serviceWithSourcePortRestrictionFailsClosedAndNeverProducesFalseProven() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>(PolicyHygienePartialPriorTest.partialObjects());
        objects.put("svc-cp-src", PolicyHygieneTest.object("svc-cp-src", "service-tcp", "protocol: tcp", "port: 443", "source-port: 1024-65535"));
        objects.put("svc-cp-all", PolicyHygieneTest.object("svc-cp-all", "service-tcp", "protocol: tcp", "port: 443", "source-port: 0-65535"));
        objects.put("svc-cp-cmp", PolicyHygieneTest.object("svc-cp-cmp", "service-tcp", "protocol: tcp", "port: >1023", "source-port: >1023"));
        objects.put("svc-cp-empty", PolicyHygieneTest.object("svc-cp-empty", "service-tcp", "protocol: tcp", "port: 443", "source-port: "));
        objects.put("svc-pan-shape-on-cp", PolicyHygieneTest.object("svc-pan-shape-on-cp", "service",
            "protocol/tcp/port: 443", "protocol/tcp/source-port: 1024-65535"));
        objects.put("half-a", PolicyHygieneTest.object("half-a", "network", "ip-netmask: 192.0.2.0/25"));
        objects.put("half-b", PolicyHygieneTest.object("half-b", "network", "ip-netmask: 192.0.2.128/25"));

        var base = PolicyHygieneTest.sections("CP", PolicyHygieneTest.cpSection("s1", null,
            PolicyHygienePartialPriorTest.cp("r1", 1, "Accept", "half-a", "net", "svc-cp-src"),
            PolicyHygienePartialPriorTest.cp("r2", 2, "Drop", "host", "net", "port"),
            PolicyHygienePartialPriorTest.cp("r3", 3, "Accept", "half-b", "net", "port"),
            PolicyHygienePartialPriorTest.cp("r4", 4, "Accept", "net", "net", "port"),
            PolicyHygienePartialPriorTest.cp("r5", 5, "Accept", "any", "other-host", "port")));
        var snapshot = new PolicySnapshot(base.metadata(), base.sections(), objects);

        var resolver = PolicyNormalization.Resolver.shadowing(snapshot, budget());
        for (String svc : List.of("svc-cp-src", "svc-cp-all", "svc-cp-cmp", "svc-cp-empty", "svc-pan-shape-on-cp")) {
            var ex = assertThrows(PolicyNormalization.Unknown.class, () -> resolver.cell(positive(svc), "service"), svc);
            assertEquals("unsupported service constraint", ex.getMessage(), svc);
        }

        // Were r1 widened to source ports 0..65535, r1 would shadow r2 (I2 CONFLICT), r1+r3 would union-cover r4 (I3),
        // and r1 would partly shadow same-action r4 (I4). Failing closed prevents every false PROVEN while r4 (wider
        // source than half-a) and r5 (disjoint destination 198.51.100.7) are never covered by r1.
        var hygiene = PolicyHygiene.analyze(snapshot, 90, PolicyHygieneTest.NOW, PolicyHygiene.TOTAL_WORK);
        assertEquals("unsupported service constraint", hygiene.rules().get("r1").shadowReason());
        assertEquals("UNKNOWN", hygiene.rules().get("r2").shadowStatus());
        assertNull(hygiene.rules().get("r2").coveringRuleId());
        assertEquals("NOT_SHADOWED", hygiene.rules().get("r4").shadowStatus());
        assertNull(hygiene.rules().get("r4").coveringRuleId());
        assertEquals("NOT_SHADOWED", hygiene.rules().get("r5").shadowStatus());

        var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90);
        for (String type : List.of("I1", "I2", "I3"))
            assertTrue(PolicyInsightsTest.of(report, type).isEmpty(), "no PROVEN " + type + " may rest on a source-port service");
        // r4 is partly shadowed only by r3 (half-b), never by r1.
        for (var i4 : PolicyInsightsTest.of(report, "I4"))
            assertFalse(i4.rules().stream().anyMatch(ref -> ref.ruleId().equals("r1")), i4.toString());
    }
}
