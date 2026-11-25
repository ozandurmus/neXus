package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;

/**
 * Cisco ASA conformance: one pinned test group per entry of the FROZEN amendment
 * {@code PATH_ANALYSIS_VENDOR_SEMANTICS_AMENDMENT_2026_11_14_ASA_FORTIGATE.md} sections 8-11 and 15 (A0-ASA-1 .. A0-ASA-20,
 * 4a/4b/4c, 16a). Each group states what the entry's "model must do" column requires and fails when a refactor changes it:
 * a decided answer, UNKNOWN / NOT_EVALUABLE with its named reason, or the documented refusal. Real-environment validation
 * (the 20 masked lookups of design section 8) is a separate gate and not covered here.
 *
 * <p>Drives the platform-core parser and mapper, the lookup ({@link PolicyLookup}, through the helpers of
 * {@link AsaPolicyEvaluationTest}) and the Trace hop ({@link HopPolicyEvaluator.Prepared#asa}) where the entry is about
 * ordering. Synthetic masked names and documentation addresses only. A0-ASA-2 pins the binding semantics only; the
 * {@code asa-acl-use} value and the "unused ACL" hygiene finding are owned by another lane and deliberately not asserted.
 *
 * <pre>
 * A0 id      | test (nested class)               | status
 * -----------+-----------------------------------+----------------------------------------------------------------
 * A0-ASA-1   | A0Asa1                            | PINNED
 * A0-ASA-2   | A0Asa2                            | PINNED (binding semantics only)
 * A0-ASA-3   | A0Asa3                            | PINNED (lookup and Trace hop)
 * A0-ASA-4a  | A0Asa4a                           | PINNED (lookup and Trace hop)
 * A0-ASA-4b  | A0Asa4b                           | UNKNOWN-PINNED (ASA_SECURITY_LEVEL_UNPROVEN)
 * A0-ASA-4c  | A0Asa4c                           | UNKNOWN-PINNED (bound ACL decides; open part stays UNKNOWN)
 * A0-ASA-5   | A0Asa5                            | PINNED
 * A0-ASA-6   | A0Asa6                            | PINNED (lookup and Trace hop)
 * A0-ASA-7   | A0Asa7                            | PINNED (forward direction only)
 * A0-ASA-8   | A0Asa8                            | PINNED (inactive), UNKNOWN-PINNED (time-range)
 * A0-ASA-9   | A0Asa9                            | PINNED (listed, not evaluated)
 * A0-ASA-10  | A0Asa10                           | PINNED (refusal ASA_UNSUPPORTED_MODE)
 * A0-ASA-11  | A0Asa11                           | PINNED (any, any4); UNKNOWN-PINNED (any6)
 * A0-ASA-12  | A0Asa12                           | PINNED
 * A0-ASA-13  | A0Asa13                           | UNKNOWN-PINNED (DYNAMIC, never resolved from the name)
 * A0-ASA-14  | A0Asa14                           | PINNED (eq, range, lt, gt, neq, groups)
 * A0-ASA-15  | A0Asa15                           | PINNED; UNKNOWN-PINNED (dns, literal not in the table)
 * A0-ASA-16  | A0Asa16                           | UNKNOWN-PINNED
 * A0-ASA-16a | A0Asa16a                          | PINNED (narrows only, never decides or covers)
 * A0-ASA-17  | A0Asa17                           | PINNED
 * A0-ASA-18  | A0Asa18                           | UNKNOWN-PINNED (hop UNKNOWN when a NAT rule may touch the tuple)
 * A0-ASA-19  | A0Asa19                           | PINNED (refusal ASA_UNSUPPORTED_MODE)
 * A0-ASA-20  | A0Asa20                           | PINNED
 * </pre>
 */
class AsaA0ConformanceTest {

    // ---- helpers (the lookup path is the one AsaPolicyEvaluationTest uses) ----------------------------------------

    static PolicySnapshot asa(String body) { return AsaPolicyEvaluationTest.asa(body); }

    static Map<String, Object> run(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return AsaPolicyEvaluationTest.run(s, src, dst, service, in, out);
    }

    static String verdict(PolicySnapshot s, String src, String dst, String service, String in, String out) {
        return AsaPolicyEvaluationTest.verdict(s, src, dst, service, in, out);
    }

    /** One Trace hop through the ASA: NAT, then access on the real tuple, with the ingress and egress nameifs. */
    static HopPolicyEvaluator.Hop hop(PolicySnapshot s, String src, String dst, int port, String in, String out) {
        var device = new HopPolicyEvaluator.Device("dev-asa-1", "", null);
        return HopPolicyEvaluator.prepare(s, device, HopPolicyEvaluator.Binding.NONE)
            .asa(new HopPolicyEvaluator.Tuple(src, dst, "tcp", port), in, out);
    }

    static PolicySnapshot mapped(String config) {
        return AsaPolicyMapper.map("dev-asa-1", "Cisco ASA dev-asa-1", "2026-11-14T02:00:00Z", "artefact", AsaConfigPolicyParser.parse(config));
    }

    static Section section(PolicySnapshot s, String name) {
        return s.sections().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow(() -> new AssertionError("no section " + name));
    }

    static List<String> sectionNames(PolicySnapshot s) { return s.sections().stream().map(Section::name).toList(); }

    static PolicySnapshot.PolicyObject object(PolicySnapshot s, String id) { return s.objects().get(id); }

    // ---- A0-ASA-1 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-1")
    class A0Asa1 {
        @Test void firstMatchingAceDecidesAndAnImplicitDenyEndsTheList() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 22
                    access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq 22
                    access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq 22
                    access-list ACL-OUT extended permit tcp any host 198.51.100.21 eq 22
                    access-group ACL-OUT in interface outside
                    """);
            var first = run(s, "203.0.113.5", "198.51.100.20", "tcp/22", "outside", "inside");
            assertEquals("ALLOWED", first.get("verdict"), "the earlier permit wins over the later deny");
            assertEquals("first matching rule", first.get("lookupReason"));
            assertEquals(1, ((Map<?, ?>) first.get("decidingRule")).get("number"));
            var second = run(s, "203.0.113.5", "198.51.100.21", "tcp/22", "outside", "inside");
            assertEquals("DENIED", second.get("verdict"), "the earlier deny wins over the later permit");
            assertEquals(3, ((Map<?, ?>) second.get("decidingRule")).get("number"));
            var none = run(s, "203.0.113.5", "198.51.100.22", "tcp/22", "outside", "inside");
            assertEquals("DENIED", none.get("verdict"));
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, none.get("lookupReason"));
            assertNull(none.get("decidingRule"), "the implicit deny is no stored rule");
        }

        @Test void theTraceHopReportsTheSameFirstMatchAndImplicitDeny() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var allowed = hop(s, "203.0.113.5", "198.51.100.20", 443, "outside", "inside");
            assertEquals("ALLOWED", allowed.verdict());
            assertEquals(HopPolicyEvaluator.FIRST_MATCHING_RULE, allowed.reason());
            var denied = hop(s, "203.0.113.5", "198.51.100.20", 8443, "outside", "inside");
            assertEquals("DENIED", denied.verdict());
            assertEquals(HopPolicyEvaluator.ASA_IMPLICIT_DENY, denied.reason());
        }
    }

    // ---- A0-ASA-2 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-2")
    class A0Asa2 {
        @Test void oneSectionPerAccessGroupBindingAndTheGlobalBindingAppliesToInboundOnly() {
            var s = asa("""
                    access-list ACL-SHARED extended permit tcp any any eq 80
                    access-list ACL-EGRESS extended permit tcp any any eq 443
                    access-list ACL-GLOBAL extended permit tcp any any eq 8080
                    access-group ACL-SHARED in interface outside
                    access-group ACL-SHARED in interface dmz
                    access-group ACL-EGRESS out interface inside
                    access-group ACL-GLOBAL global
                    """);
            var access = sectionNames(s).subList(0, 4);
            assertEquals(List.of("outside in", "dmz in", "inside out", "global"), access);
            assertEquals(List.of("in"), section(s, "outside in").rules().get(0).extras().get("asa-direction"));
            assertEquals(List.of("out"), section(s, "inside out").rules().get(0).extras().get("asa-direction"));
            assertEquals(List.of("global"), section(s, "global").rules().get(0).extras().get("asa-direction"));
            assertNotEquals(section(s, "outside in").rules().get(0).id(), section(s, "dmz in").rules().get(0).id(),
                "an ACL bound twice is two independent sections");
            // The global list is consulted for inbound traffic only: a packet leaving through "inside" meets the egress
            // list there, never the global list a second time.
            var egressOnly = run(s, "198.51.100.5", "192.0.2.9", "tcp/8080", "dmz", "inside");
            assertEquals("DENIED", egressOnly.get("verdict"), "dmz in permits only 80, global permits 8080, egress list permits only 443");
            var steps = (List<?>) egressOnly.get("layers");
            assertEquals(2, steps.size(), "inbound step (interface list then global) and one outbound step");
        }

        @Test void anAclWithoutAnAccessGroupIsNoSectionAndNotEnforcedAsAnAccessRule() {
            var s = asa("""
                    access-list ACL-ORPHAN extended deny ip any any
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals(List.of("outside in"), sectionNames(s).stream().filter(n -> n.endsWith(" in") || n.endsWith(" out") || n.equals("global")).toList());
            // inside has no bound list: the unbound deny-all is not enforced, the level default decides (A0-ASA-4a)
            var r = run(s, "198.51.100.5", "203.0.113.9", "tcp/80", "inside", "outside");
            assertEquals("ALLOWED", r.get("verdict"));
            assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, r.get("lookupReason"));
        }
    }

    // ---- A0-ASA-3 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-3")
    class A0Asa3 {
        static final String LISTS = """
                access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq 22
                access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
                access-list ACL-GLOBAL extended permit tcp any host 198.51.100.20 eq 22
                access-list ACL-GLOBAL extended permit tcp any host 198.51.100.21 eq 443
                access-list ACL-OTHER extended permit tcp any host 198.51.100.22 eq 443
                access-group ACL-OUT in interface outside
                access-group ACL-OTHER in interface dmz
                access-group ACL-GLOBAL global
                """;

        @Test void theInterfaceListIsProcessedBeforeTheGlobalListThenTheImplicitDeny() {
            var s = asa(LISTS);
            assertEquals("DENIED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/22", "outside", "inside"), "interface deny before the global permit");
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"), "interface permit");
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"), "no interface match: the global list follows");
            var end = run(s, "203.0.113.5", "198.51.100.23", "tcp/443", "outside", "inside");
            assertEquals("DENIED", end.get("verdict"));
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, end.get("lookupReason"), "the implicit deny comes after the global rules");
        }

        @Test void anotherInterfacesListNeverAppliesAndAGlobalListEndsTheLevelDefault() {
            var s = asa(LISTS);
            // ACL-OTHER permits .22 on dmz only; the packet enters on outside
            assertEquals("DENIED", verdict(s, "203.0.113.5", "198.51.100.22", "tcp/443", "outside", "inside"));
            // inside has no "in" list but the global list is bound: its implicit deny replaces the level default
            var inside = run(s, "198.51.100.5", "203.0.113.9", "tcp/443", "inside", "outside");
            assertEquals("DENIED", inside.get("verdict"));
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, inside.get("lookupReason"));
        }

        @Test void theTraceHopFollowsTheSameChain() {
            var s = asa(LISTS);
            assertEquals("DENIED", hop(s, "203.0.113.5", "198.51.100.20", 22, "outside", "inside").verdict());
            var viaGlobal = hop(s, "203.0.113.5", "198.51.100.21", 443, "outside", "inside");
            assertEquals("ALLOWED", viaGlobal.verdict());
            assertEquals(HopPolicyEvaluator.FIRST_MATCHING_RULE, viaGlobal.reason());
            var end = hop(s, "203.0.113.5", "198.51.100.23", 443, "outside", "inside");
            assertEquals("DENIED", end.verdict());
            assertEquals(HopPolicyEvaluator.ASA_IMPLICIT_DENY, end.reason());
        }
    }

    // ---- A0-ASA-4a / 4b / 4c ----------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-4a")
    class A0Asa4a {
        @Test void higherToLowerWithoutAnyListIsAllowedByDefault() {
            var s = asa("");
            for (String[] path : new String[][] {{"inside", "outside"}, {"inside", "dmz"}, {"dmz", "outside"}}) {
                var r = run(s, "192.0.2.5", "203.0.113.9", "tcp/443", path[0], path[1]);
                assertEquals("ALLOWED", r.get("verdict"), path[0] + " -> " + path[1]);
                assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, r.get("lookupReason"));
            }
            var hop = hop(s, "192.0.2.5", "203.0.113.9", 443, "inside", "outside");
            assertEquals("ALLOWED", hop.verdict());
            assertEquals(HopPolicyEvaluator.ASA_SECURITY_LEVEL_DEFAULT, hop.reason());
        }

        @Test void theDefaultIsUsedOnlyOnAnInterfaceWithoutAnInboundListWhileAnotherInterfaceHasOne() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            // a list on the outside interface does not remove the default for inside; no global list is bound
            assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, run(s, "192.0.2.5", "203.0.113.9", "tcp/80", "inside", "dmz").get("lookupReason"));
        }
    }

    @Nested @DisplayName("A0-ASA-4b")
    class A0Asa4b {
        @Test void lowerToHigherWithoutAnyListStaysUnknownNeverAllowedOrDenied() {
            var s = asa("");
            for (String[] path : new String[][] {{"outside", "inside"}, {"outside", "dmz"}, {"dmz", "inside"}}) {
                var r = run(s, "203.0.113.5", "192.0.2.9", "tcp/443", path[0], path[1]);
                assertEquals("UNKNOWN", r.get("verdict"), path[0] + " -> " + path[1]);
                assertEquals(PolicyLookup.ASA_LEVEL_UNPROVEN, r.get("lookupReason"));
            }
            var hop = hop(s, "203.0.113.5", "192.0.2.9", 443, "outside", "inside");
            assertEquals("UNKNOWN", hop.verdict());
            assertEquals(HopPolicyEvaluator.ASA_SECURITY_LEVEL_UNPROVEN, hop.reason());
        }

        @Test void aBoundListMakesTheFallThroughTheImplicitDenyOfA0Asa1() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 192.0.2.9 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var r = run(s, "203.0.113.5", "192.0.2.9", "tcp/22", "outside", "inside");
            assertEquals("DENIED", r.get("verdict"));
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, r.get("lookupReason"));
        }
    }

    @Nested @DisplayName("A0-ASA-4c")
    class A0Asa4c {
        @Test void anInboundListOnTheHigherInterfaceReplacesTheHigherToLowerPermitWithItsImplicitDeny() {
            var s = asa("""
                    access-list ACL-IN extended permit tcp any host 203.0.113.9 eq 443
                    access-group ACL-IN in interface inside
                    """);
            var allowed = run(s, "192.0.2.5", "203.0.113.9", "tcp/443", "inside", "outside");
            assertEquals("ALLOWED", allowed.get("verdict"));
            assertEquals("first matching rule", allowed.get("lookupReason"));
            var fall = run(s, "192.0.2.5", "203.0.113.9", "tcp/80", "inside", "outside");
            assertEquals("DENIED", fall.get("verdict"), "the bound list decides; the level default is not consulted");
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, fall.get("lookupReason"));
        }

        @Test void theOpenPartStaysUnknownUntilAnInboundListOrGlobalListIsBound() {
            // no inbound or global list on the ingress interface: the open part (levels only) is never turned into a verdict
            var s = asa("""
                    access-list ACL-EGRESS extended permit tcp any any eq 443
                    access-group ACL-EGRESS out interface inside
                    """);
            var r = run(s, "203.0.113.5", "192.0.2.9", "tcp/443", "outside", "inside");
            assertEquals("UNKNOWN", r.get("verdict"), "an outbound list alone settles nothing about the ingress side");
        }
    }

    // ---- A0-ASA-5 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-5")
    class A0Asa5 {
        static final String OPEN_DMZ = "access-list ACL-DMZ extended permit ip any any\naccess-group ACL-DMZ in interface dmz\n";

        @Test void sameLevelIsDeniedBeforeAnyAccessRuleWithoutThePermit() {
            var s = asa(OPEN_DMZ);
            var inter = run(s, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2");
            assertEquals("DENIED", inter.get("verdict"), "a permit ip any any is not reached");
            assertEquals(PolicyLookup.ASA_SAME_INTER, inter.get("lookupReason"));
            var intra = run(s, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz");
            assertEquals("DENIED", intra.get("verdict"));
            assertEquals(PolicyLookup.ASA_SAME_INTRA, intra.get("lookupReason"));
            assertEquals(HopPolicyEvaluator.ASA_SAME_SECURITY_INTER_DENIED, hop(s, "192.0.2.5", "192.0.2.9", 443, "dmz", "dmz2").reason());
            assertEquals(HopPolicyEvaluator.ASA_SAME_SECURITY_INTRA_DENIED, hop(s, "192.0.2.5", "192.0.2.9", 443, "dmz", "dmz").reason());
        }

        @Test void eachPermitEnablesOnlyItsOwnKindAndThenTheAccessRulesDecide() {
            var inter = asa("same-security-traffic permit inter-interface\n" + OPEN_DMZ);
            assertEquals("ALLOWED", verdict(inter, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2"));
            assertEquals(PolicyLookup.ASA_SAME_INTRA, run(inter, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz").get("lookupReason"));
            var intra = asa("same-security-traffic permit intra-interface\n" + OPEN_DMZ);
            assertEquals("ALLOWED", verdict(intra, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz"));
            assertEquals(PolicyLookup.ASA_SAME_INTER, run(intra, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2").get("lookupReason"));
            var denyList = asa("same-security-traffic permit inter-interface\naccess-list ACL-DMZ extended deny ip any any\naccess-group ACL-DMZ in interface dmz\n");
            assertEquals("DENIED", verdict(denyList, "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2"), "with the permit the ACL decides");
        }
    }

    // ---- A0-ASA-6 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-6")
    class A0Asa6 {
        static final String NAT = """
                object network OBJ-SERVER
                 host 192.0.2.10
                 nat (inside,outside) static 198.51.100.10
                access-list ACL-OUT extended permit tcp any host 192.0.2.10 eq 443
                access-group ACL-OUT in interface outside
                """;

        @Test void theAccessListMatchesTheRealAddressNotTheTranslatedOne() {
            var s = asa(NAT);
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "192.0.2.10", "tcp/443", "outside", "inside"), "real destination matches");
            var mappedAddress = run(s, "203.0.113.5", "198.51.100.10", "tcp/443", "outside", "inside");
            assertEquals("DENIED", mappedAddress.get("verdict"), "the mapped address is not what the access rule names");
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, mappedAddress.get("lookupReason"));
        }

        @Test void natRulesAreNeverPartOfTheAccessEvaluationAndTheHopKeepsTheRealTupleAccessAsContext() {
            var s = asa(NAT);
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(s.metadata(), section(s, "ASA NAT section 2")));
            var hop = hop(s, "203.0.113.5", "192.0.2.10", 443, "outside", "inside");
            assertEquals("ALLOWED", hop.preNatAccess().verdict(), "access evaluated on the real destination");
            assertEquals(HopPolicyEvaluator.FIRST_MATCHING_RULE, hop.preNatAccess().reason());
        }
    }

    // ---- A0-ASA-7 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-7")
    class A0Asa7 {
        @Test void onlyTheForwardDirectionIsEvaluatedReturnTrafficIsNotCheckedAgainstTheOtherInterfacesList() {
            var s = asa("""
                    access-list ACL-OUT extended deny ip any any
                    access-group ACL-OUT in interface outside
                    """);
            // inside -> outside: the deny-all on outside "in" would block the reply if it were evaluated; it is not
            var r = run(s, "192.0.2.5", "203.0.113.9", "tcp/443", "inside", "outside");
            assertEquals("ALLOWED", r.get("verdict"));
            assertEquals(PolicyLookup.ASA_LEVEL_ALLOW, r.get("lookupReason"));
            assertEquals(1, ((List<?>) r.get("layers")).size(), "one forward step, no reverse step");
            assertEquals("ALLOWED", hop(s, "192.0.2.5", "203.0.113.9", 443, "inside", "outside").verdict());
        }
    }

    // ---- A0-ASA-8 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-8")
    class A0Asa8 {
        @Test void inactiveDisablesTheAceAndTheLookupSkipsIt() {
            var s = asa("""
                    access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq 443 inactive
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var rules = section(s, "outside in").rules();
            assertEquals(Boolean.FALSE, rules.get(0).enabled());
            assertEquals(Boolean.TRUE, rules.get(1).enabled());
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"));
        }

        @Test void aTimeRangedAceCarriesItsTimeConstraintAndIsUnknownWhereItCouldMatch() {
            var s = asa("""
                    access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq 443 time-range TR-OFFICE
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals(List.of("TR-OFFICE"), section(s, "outside in").rules().get(0).extras().get("time"));
            var r = run(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside");
            assertEquals("UNKNOWN", r.get("verdict"), "never ALLOWED or DENIED by a rule that may be outside its time range");
            assertEquals("earlier rule could match", r.get("lookupReason"));
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.30", "tcp/443", "outside", "inside"), "another destination passes over it");
        }
    }

    // ---- A0-ASA-9 -------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-9")
    class A0Asa9 {
        @Test void controlPlaneAccessGroupsAreListedNotEvaluatedAndDoNotChangeTransitVerdicts() {
            var s = asa("""
                    access-list ACL-CP extended deny ip any any
                    access-group ACL-CP in interface outside control-plane
                    """);
            assertEquals(List.of("ASA security-level default", "ASA not evaluated"), sectionNames(s).subList(0, 2),
                "no access section for the control-plane binding");
            var listed = section(s, "ASA not evaluated").rules().stream().filter(r -> r.name().equals("ACL-CP")).findFirst().orElseThrow();
            assertEquals("UNKNOWN", listed.action());
            assertTrue(PolicySnapshot.outsideSecurityEvaluation(s.metadata(), section(s, "ASA not evaluated")));
            // no implicit deny for transit traffic from a management rule list: the level defaults still apply
            assertEquals("ALLOWED", verdict(s, "192.0.2.5", "203.0.113.9", "tcp/443", "inside", "outside"));
            var low = run(s, "203.0.113.5", "192.0.2.9", "tcp/443", "outside", "inside");
            assertEquals("UNKNOWN", low.get("verdict"));
            assertEquals(PolicyLookup.ASA_LEVEL_UNPROVEN, low.get("lookupReason"));
        }
    }

    // ---- A0-ASA-10 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-10")
    class A0Asa10 {
        @Test void aTransparentUnitIsRefusedWithNoSectionsAndNeverDecided() {
            var s = mapped("""
                    ASA Version 9.18(4)22
                    firewall transparent
                    interface GigabitEthernet0/0
                     nameif outside
                     security-level 0
                    interface GigabitEthernet0/1
                     nameif inside
                     security-level 100
                    access-list ACL-OUT extended permit ip any any
                    access-group ACL-OUT in interface outside
                    """);
            assertTrue(s.sections().isEmpty());
            assertEquals(1, s.failures().size());
            assertEquals("ASA_UNSUPPORTED_MODE", s.failures().get(0).reason());
            var r = run(s, "203.0.113.5", "192.0.2.9", "tcp/443", "outside", "inside");
            assertEquals("UNKNOWN", r.get("verdict"), "a refused unit is never ALLOWED or DENIED");
        }
    }

    // ---- A0-ASA-11 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-11")
    class A0Asa11 {
        @Test void anyAndAny4CoverTheIpv4UniverseAny6IsNotAnIpv4Address() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any4 host 198.51.100.20 eq 443
                    access-list ACL-OUT extended permit tcp any host 198.51.100.21 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"));
        }

        @Test void any6IsUnsupportedByTheIpv4EngineNeverDecidedAsAnIpv4Match() {
            var parsed = AsaConfigPolicyParser.parse(AsaPolicyEvaluationTest.BASE + """
                    access-list ACL-OUT extended permit tcp any6 host 198.51.100.20 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var s = AsaPolicyMapper.map("dev-asa-1", "Cisco ASA dev-asa-1", "2026-11-14T02:00:00Z", "artefact", parsed);
            var source = object(s, section(s, "outside in").rules().get(0).source().refs().get(0));
            assertEquals("UNSUPPORTED", source.status());
            assertNotEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"),
                "an IPv6-only source must not produce an allow for an IPv4 packet");
        }
    }

    // ---- A0-ASA-12 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-12")
    class A0Asa12 {
        @Test void hostSubnetAndRangeObjectsResolveAndNestedGroupsExpand() {
            var s = asa("""
                    object network OBJ-HOST
                     host 192.0.2.10
                    object network OBJ-SUBNET
                     subnet 198.51.100.0 255.255.255.0
                    object network OBJ-RANGE
                     range 203.0.113.10 203.0.113.20
                    object-group network OG-INNER
                     network-object object OBJ-HOST
                     network-object object OBJ-SUBNET
                    object-group network OG-OUTER
                     group-object OG-INNER
                     network-object object OBJ-RANGE
                    access-list ACL-OUT extended permit tcp any object-group OG-OUTER eq 443
                    access-group ACL-OUT in interface outside
                    """);
            for (String dst : List.of("192.0.2.10", "198.51.100.77", "203.0.113.15"))
                assertEquals("ALLOWED", verdict(s, "10.0.0.1", dst, "tcp/443", "outside", "inside"), dst);
            assertEquals("DENIED", verdict(s, "10.0.0.1", "203.0.113.21", "tcp/443", "outside", "inside"), "outside the range");
            assertEquals("DENIED", verdict(s, "10.0.0.1", "192.0.2.11", "tcp/443", "outside", "inside"), "next to the host");
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.101.1", "tcp/443", "outside", "inside"), "outside the subnet");
        }

        @Test void aNonContiguousDottedMaskIsUnsupportedNeverGuessedAndACycleNeverHangsOrDecides() {
            var s = asa("""
                    object network OBJ-HOLEY
                     subnet 192.0.2.0 255.255.0.255
                    object-group network OG-A
                     group-object OG-B
                    object-group network OG-B
                     group-object OG-A
                    access-list ACL-OUT extended deny tcp any object OBJ-HOLEY eq 443
                    access-list ACL-OUT extended deny tcp any object-group OG-A eq 22
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var holey = section(s, "outside in").rules().get(0);
            assertEquals("UNSUPPORTED", object(s, holey.destination().refs().get(0)).status());
            assertEquals("UNKNOWN", verdict(s, "10.0.0.1", "192.0.2.5", "tcp/443", "outside", "inside"), "the unsupported deny could match");
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.5", "tcp/22", "outside", "inside"), "the cyclic group could match");
        }
    }

    // ---- A0-ASA-13 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-13")
    class A0Asa13 {
        @Test void anFqdnObjectIsDynamicNeverResolvedFromItsNameAndAnAceWithItIsUnknown() {
            var s = asa("""
                    object network OBJ-FQDN
                     fqdn host.example.test
                    access-list ACL-OUT extended deny tcp any object OBJ-FQDN eq 443
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var fqdn = object(s, section(s, "outside in").rules().get(0).destination().refs().get(0));
            assertEquals("DYNAMIC", fqdn.status());
            assertTrue(fqdn.values().isEmpty(), "no address is derived from the name");
            var r = run(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside");
            assertEquals("UNKNOWN", r.get("verdict"), "the later permit must not be taken while the FQDN deny may match");
            assertEquals("earlier rule could match", r.get("lookupReason"));
        }
    }

    // ---- A0-ASA-14 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-14")
    class A0Asa14 {
        @Test void serviceObjectsAndGroupsMixProtocolsWithEqAndRange() {
            var s = asa("""
                    object service SVC-WEB
                     service tcp destination eq https
                    object-group service OG-MIX
                     service-object tcp destination range 8000 8010
                     service-object udp destination eq domain
                     service-object object SVC-WEB
                    access-list ACL-OUT extended permit object-group OG-MIX any any
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/443", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/8005", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "udp/53", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/53", "outside", "inside"), "the group's domain entry is UDP only");
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/8011", "outside", "inside"));
        }

        @Test void aTcpUdpLegacyPortGroupCoversBothFamilies() {
            var s = asa("""
                    object-group service OG-LEGACY tcp-udp
                     port-object eq www
                    access-list ACL-OUT extended permit object-group OG-LEGACY any any
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/80", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "udp/80", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/81", "outside", "inside"));
        }

        @Test void lessThanGreaterThanAndNotEqualNeverDecideAnAllowForAPortTheyExclude() {
            // Fail-closed guard that holds today and after the defect below is fixed.
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 neq 23
                    access-list ACL-OUT extended permit tcp any host 198.51.100.21 lt 1024
                    access-list ACL-OUT extended permit tcp any host 198.51.100.22 gt 1023
                    access-group ACL-OUT in interface outside
                    """);
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/23", "outside", "inside"));
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.21", "tcp/2000", "outside", "inside"));
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.22", "tcp/80", "outside", "inside"));
        }

        @Test void lessThanGreaterThanAndNotEqualAreDecidedRanges() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 neq 23
                    access-list ACL-OUT extended permit tcp any host 198.51.100.21 lt 1024
                    access-list ACL-OUT extended permit tcp any host 198.51.100.22 gt 1023
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/22", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/24", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/23", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.21", "tcp/1023", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.21", "tcp/1024", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.22", "tcp/1024", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.22", "tcp/1023", "outside", "inside"));
        }

        @Test void comparisonOperatorBoundsAreExactAndEmptyRangesStayUnknown() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 lt 1
                    access-list ACL-OUT extended permit tcp any host 198.51.100.21 gt 65535
                    access-list ACL-OUT extended permit tcp any host 198.51.100.22 neq 0
                    access-list ACL-OUT extended permit tcp any host 198.51.100.23 neq 1
                    access-list ACL-OUT extended permit tcp any host 198.51.100.24 neq 65535
                    access-group ACL-OUT in interface outside
                    """);
            // an empty range or a port-0 operand is never decided as an allow
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/80", "outside", "inside"));
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.21", "tcp/80", "outside", "inside"));
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.22", "tcp/80", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.23", "tcp/2", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.23", "tcp/65535", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.23", "tcp/1", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.24", "tcp/65534", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.24", "tcp/65535", "outside", "inside"));
        }

        @Test void comparisonOperatorsTakeLiteralOperandsAndUdpAndSourcePorts() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 lt www
                    access-list ACL-OUT extended permit udp any host 198.51.100.21 neq domain
                    access-list ACL-OUT extended permit tcp any gt 1023 host 198.51.100.22 eq https
                    access-list ACL-OUT extended permit tcp any host 198.51.100.23 lt snmp
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/79", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/80", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.21", "udp/54", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.21", "udp/53", "outside", "inside"));
            // a TCP query on the UDP entry never matches
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.21", "tcp/54", "outside", "inside"));
            // a UDP-only literal on a TCP entry stays unresolved (A0-ASA-15)
            assertNotEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.23", "tcp/80", "outside", "inside"));
        }
    }

    // ---- A0-ASA-15 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-15")
    class A0Asa15 {
        @Test void portLiteralsMapToTheNumbersOfCiscosTableAndDomainIsDns() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq www
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq https
                    access-list ACL-OUT extended permit udp any host 198.51.100.20 eq domain
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/80", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/443", "outside", "inside"));
            assertEquals("ALLOWED", verdict(s, "10.0.0.1", "198.51.100.20", "udp/53", "outside", "inside"));
            assertEquals("DENIED", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/53", "outside", "inside"), "domain was written for UDP only");
        }

        @Test void aLiteralNotInTheTableIsUnresolvedNeverGuessed() {
            // "dns" is not a literal (the table's literal is "domain"); "not-a-cisco-port" is in no table
            var s = asa("""
                    access-list ACL-OUT extended deny udp any host 198.51.100.20 eq dns
                    access-list ACL-OUT extended permit udp any any eq domain
                    access-list ACL-OUT extended deny tcp any host 198.51.100.20 eq not-a-cisco-port
                    access-list ACL-OUT extended permit tcp any any eq https
                    access-group ACL-OUT in interface outside
                    """);
            var r = run(s, "10.0.0.1", "198.51.100.20", "udp/53", "outside", "inside");
            assertEquals("UNKNOWN", r.get("verdict"), "the deny with an unknown literal could match");
            assertEquals("UNKNOWN", verdict(s, "10.0.0.1", "198.51.100.20", "tcp/443", "outside", "inside"));
        }
    }

    // ---- A0-ASA-16 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-16")
    class A0Asa16 {
        @Test void userUserGroupAndSecurityGroupCriteriaKeepTheAceUnknownNeverAllowOrDeny() {
            var identities = List.of("user LAB\\\\alice", "user-group LAB\\\\Ops", "security-group name SG-USERS");
            for (String criterion : identities) {
                var permit = asa("access-list ACL-OUT extended permit tcp " + criterion + " any host 198.51.100.20 eq 443\naccess-group ACL-OUT in interface outside\n");
                var deny = asa("access-list ACL-OUT extended deny tcp " + criterion + " any host 198.51.100.20 eq 443\naccess-group ACL-OUT in interface outside\n");
                assertEquals("UNKNOWN", verdict(permit, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"), criterion);
                assertEquals("UNKNOWN", verdict(deny, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"), criterion);
            }
        }

        @Test void theCriteriaAreRecordedOnTheRule() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp user-group LAB\\\\Ops security-group name SG-USERS any host 198.51.100.20 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var extras = section(s, "outside in").rules().get(0).extras();
            assertEquals(List.of("LAB\\\\Ops"), extras.get("user"));
            assertEquals(List.of("SG-USERS"), extras.get("security-group"));
        }
    }

    @Nested @DisplayName("A0-ASA-16a")
    class A0Asa16a {
        @Test void anIdentityAceOnlyNarrowsSoOtherFlowsPassOverItAndItsOwnFlowStaysUnknown() {
            var s = asa("""
                    access-list ACL-OUT extended deny tcp user-group LAB\\\\Ops any host 198.51.100.20 eq 443
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.21", "tcp/443", "outside", "inside"), "disjoint from the ACE's bound: passed over");
            var otherPort = run(s, "203.0.113.5", "198.51.100.20", "tcp/80", "outside", "inside");
            assertEquals("DENIED", otherPort.get("verdict"), "other port: the identity ACE is passed over, nothing else matches");
            assertEquals(PolicyLookup.ASA_IMPLICIT_DENY, otherPort.get("lookupReason"));
            assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"), "inside the bound: never decided by identity");
        }

        @Test void forShadowingTheBoundProvesNotShadowedWhenDisjointButNeverCoversAnotherRule() {
            var s = asa("""
                    access-list ACL-OUT extended permit tcp user-group LAB\\\\Ops any host 198.51.100.20 eq 443
                    access-list ACL-OUT extended deny tcp any host 198.51.100.21 eq 443
                    access-list ACL-OUT extended permit tcp any host 198.51.100.20 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            var result = PolicyHygiene.analyze(s, 90, Instant.parse("2026-11-14T12:00:00Z"), 250000);
            var rules = section(s, "outside in").rules();
            assertEquals("UNKNOWN", result.rules().get(rules.get(0).id()).shadowStatus(), "the identity ACE itself stays UNKNOWN");
            assertEquals("NOT_SHADOWED", result.rules().get(rules.get(1).id()).shadowStatus(), "disjoint: provably not covered by it");
            assertEquals("UNKNOWN", result.rules().get(rules.get(2).id()).shadowStatus(), "it may cover, but never proves covering");
        }
    }

    // ---- A0-ASA-17 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-17")
    class A0Asa17 {
        @Test void sectionsOneTwoThreeAreOrderedAndSectionOneKeepsConfigurationOrder() {
            var s = asa("""
                    object network OBJ-A
                     host 192.0.2.1
                    object network OBJ-A-MAPPED
                     host 198.51.100.1
                    object network OBJ-B
                     host 192.0.2.2
                    object network OBJ-B-MAPPED
                     host 198.51.100.2
                    object network OBJ-AUTO
                     host 192.0.2.3
                     nat (inside,outside) static 198.51.100.3
                    nat (inside,outside) source static OBJ-B OBJ-B-MAPPED
                    nat (inside,outside) source static OBJ-A OBJ-A-MAPPED
                    nat (inside,outside) after-auto source dynamic any interface
                    """);
            var names = sectionNames(s).stream().filter(n -> n.startsWith("ASA NAT section")).toList();
            assertEquals(List.of("ASA NAT section 1", "ASA NAT section 2", "ASA NAT section 3"), names);
            var first = section(s, "ASA NAT section 1").rules();
            assertEquals(List.of("OBJ-B", "OBJ-A"), first.stream().map(r -> object(s, r.source().refs().get(0)).name()).toList(),
                "section 1: first match in configuration order, not name order");
            assertEquals(1, section(s, "ASA NAT section 2").rules().size());
            assertEquals(1, section(s, "ASA NAT section 3").rules().size());
        }

        @Test void sectionTwoIsOrderedStaticBeforeDynamicThenFewestRealAddressesThenLowestIpThenName() {
            var s = asa("""
                    object network OBJ-DYN-HOST
                     host 192.0.2.5
                     nat (inside,outside) dynamic interface
                    object network OBJ-STATIC-SUBNET
                     subnet 192.0.2.0 255.255.255.0
                     nat (inside,outside) static 203.0.113.0
                    object network OBJ-STATIC-HIGH
                     host 192.0.2.20
                     nat (inside,outside) static 203.0.113.20
                    object network OBJ-STATIC-LOW-B
                     host 192.0.2.10
                     nat (inside,outside) static 203.0.113.11
                    object network OBJ-STATIC-LOW-A
                     host 192.0.2.10
                     nat (inside,outside) static 203.0.113.10
                    """);
            assertEquals(List.of("OBJ-STATIC-LOW-A", "OBJ-STATIC-LOW-B", "OBJ-STATIC-HIGH", "OBJ-STATIC-SUBNET", "OBJ-DYN-HOST"),
                section(s, "ASA NAT section 2").rules().stream().map(r -> r.name().substring(0, r.name().lastIndexOf(':'))).toList());
            assertTrue(s.failures().isEmpty());
        }
    }

    // ---- A0-ASA-18 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-18")
    class A0Asa18 {
        @Test void aNatRuleThatMayTouchTheTupleMakesTheHopUnknownAndOneThatCannotDoesNot() {
            var base = """
                    access-list ACL-OUT extended permit tcp any any eq 443
                    access-group ACL-OUT in interface outside
                    """;
            var touching = asa(base + "object network OBJ-SERVER\n host 192.0.2.10\n nat (inside,outside) static 198.51.100.10\n");
            var hop = hop(touching, "203.0.113.5", "192.0.2.10", 443, "outside", "inside");
            assertEquals("UNKNOWN", hop.verdict(), "the order of un-translation, access and route lookup on 9.x is not established");
            assertEquals(HopPolicyEvaluator.NAT_UNKNOWN, hop.reason());
            assertEquals("ALLOWED", hop.preNatAccess().verdict(), "access on the real tuple is kept as context only");
            var elsewhere = asa(base + "object network OBJ-SERVER\n host 192.0.2.99\n nat (inside,outside) static 198.51.100.99\n");
            var clear = hop(elsewhere, "203.0.113.5", "192.0.2.10", 443, "outside", "inside");
            assertEquals("ALLOWED", clear.verdict());
            assertEquals(HopNatEvaluator.NAT_NO_RULE_MATCHED, clear.nat().reason());
        }

        @Test void theAccessListVerdictDoesNotDependOnTheNatOrder() {
            var withNat = asa("""
                    access-list ACL-OUT extended permit tcp any host 192.0.2.10 eq 443
                    access-group ACL-OUT in interface outside
                    object network OBJ-SERVER
                     host 192.0.2.10
                     nat (inside,outside) static 198.51.100.10
                    """);
            var withoutNat = asa("""
                    access-list ACL-OUT extended permit tcp any host 192.0.2.10 eq 443
                    access-group ACL-OUT in interface outside
                    """);
            assertEquals(verdict(withoutNat, "203.0.113.5", "192.0.2.10", "tcp/443", "outside", "inside"),
                verdict(withNat, "203.0.113.5", "192.0.2.10", "tcp/443", "outside", "inside"));
        }
    }

    // ---- A0-ASA-19 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-19")
    class A0Asa19 {
        @Test void aMultipleContextUnitIsRefusedBothInTheSystemSpaceAndByModeAndNeverDecided() {
            for (String text : List.of("mode multiple\nadmin-context admin\n", "context CTX-A\n allocate-interface GigabitEthernet0/1\n")) {
                var s = mapped("ASA Version 9.18(4)22\n" + text);
                assertTrue(s.sections().isEmpty(), text);
                assertEquals(1, s.failures().size());
                assertEquals("ASA_UNSUPPORTED_MODE", s.failures().get(0).reason());
                assertEquals("UNKNOWN", verdict(s, "203.0.113.5", "192.0.2.9", "tcp/443", "outside", "inside"));
            }
        }
    }

    // ---- A0-ASA-20 ------------------------------------------------------------------------------------------------

    @Nested @DisplayName("A0-ASA-20")
    class A0Asa20 {
        @Test void anAbsentCommandMeansItsDefaultSameSecurityTrafficIsDisabled() {
            var parsed = AsaConfigPolicyParser.parse(AsaPolicyEvaluationTest.BASE);
            assertFalse(parsed.sameSecurityInter());
            assertFalse(parsed.sameSecurityIntra());
            assertEquals("DENIED", verdict(asa(""), "192.0.2.5", "192.0.2.9", "tcp/443", "dmz", "dmz2"));
        }

        @Test void secretLinesNeverReachTheParsedPolicyOrTheSnapshot() {
            // runtime-built values: no secret-shaped literal in the source
            String token = "pw" + UUID.randomUUID().toString().replace("-", "");
            String key = "ck" + UUID.randomUUID().toString().replace("-", "");
            String community = "sc" + UUID.randomUUID().toString().replace("-", "");
            String config = AsaPolicyEvaluationTest.BASE + "enable password " + token + " encrypted\n"
                + "username admin-synthetic password " + token + " encrypted privilege 15\n"
                + "snmp-server community " + community + "\n"
                + "tunnel-group TG-SYNTH ipsec-attributes\n ikev1 pre-shared-key " + key + "\n"
                + "access-list ACL-OUT extended permit tcp any any eq 443\naccess-group ACL-OUT in interface outside\n";
            var parsed = AsaConfigPolicyParser.parse(config);
            var s = AsaPolicyMapper.map("dev-asa-1", "Cisco ASA dev-asa-1", "2026-11-14T02:00:00Z", "artefact", parsed);
            String everything = parsed + "\n" + s;
            for (String secret : List.of(token, key, community))
                assertFalse(everything.contains(secret), "a secret value must not be retained");
            assertEquals("ALLOWED", verdict(s, "203.0.113.5", "198.51.100.20", "tcp/443", "outside", "inside"));
        }
    }
}
