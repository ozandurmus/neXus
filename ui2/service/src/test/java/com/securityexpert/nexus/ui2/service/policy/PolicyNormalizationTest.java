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
}
