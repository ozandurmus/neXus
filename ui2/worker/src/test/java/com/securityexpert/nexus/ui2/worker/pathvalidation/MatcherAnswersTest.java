package com.securityexpert.nexus.ui2.worker.pathvalidation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.pathvalidation.HopPlan;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Check;
import com.securityexpert.nexus.ui2.jobs.pathvalidation.PathValidation.Result;

/**
 * Synthetic matcher answers (documentation addresses, pseudonym names) for every parser and every comparison. The shapes
 * are fixture-proven only; anything unrecognised must come out NOT_EVALUABLE, never MISMATCH.
 */
class MatcherAnswersTest {

    static final String DST = "203.0.113.10";

    // ---- Check Point ip -4 route get ---------------------------------------------------------------------------------

    @Test
    void routeGetParsesGatewayConnectedDiscardNoRouteAndTheVsenvBanner() {
        var via = MatcherAnswers.routeGet("203.0.113.10 via 192.0.2.2 dev eth2 src 192.0.2.1 uid 0 \r\n    cache \r\n", DST);
        assertEquals(new MatcherAnswers.RouteAnswer(MatcherAnswers.RouteKind.UNICAST, Optional.of("eth2"), Optional.of("192.0.2.2")),
                via.orElseThrow());
        var vsx = MatcherAnswers.routeGet("Context is set to Virtual Device FW-TANGO-04_VS2 (ID 2).\n"
                + "203.0.113.10 dev wrp128 src 192.0.2.5\n    cache\n", DST);
        assertEquals(Optional.of("wrp128"), vsx.orElseThrow().device());
        assertTrue(vsx.orElseThrow().via().isEmpty());
        assertEquals(MatcherAnswers.RouteKind.DISCARD, MatcherAnswers.routeGet("blackhole 203.0.113.10 \n", DST).orElseThrow().kind());
        assertEquals(MatcherAnswers.RouteKind.LOCAL,
                MatcherAnswers.routeGet("local 203.0.113.10 dev lo src 203.0.113.10\n", DST).orElseThrow().kind());
        assertEquals(MatcherAnswers.RouteKind.NO_ROUTE,
                MatcherAnswers.routeGet("RTNETLINK answers: Network is unreachable\n", DST).orElseThrow().kind());
    }

    @Test
    void routeGetRefusesEverythingElse() {
        for (String output : List.of("", "RTNETLINK answers: Invalid argument", "RTNETLINK answers: No route to host",
                "203.0.113.11 via 192.0.2.2 dev eth2", "203.0.113.10 via 192.0.2.2", "broadcast 203.0.113.10 dev eth2",
                "203.0.113.10 via not-an-address dev eth2", "vsenv: Virtual system 9 does not exist", "Error: inet prefix is expected"))
            assertTrue(MatcherAnswers.routeGet(output, DST).isEmpty(), output);
        assertTrue(MatcherAnswers.routeGet(null, DST).isEmpty());
    }

    @Test
    void cpRouteComparison() {
        var gw = new HopPlan.Expect("IP", "eth2", "192.0.2.2", false, null, null, null, null);
        assertEquals(Result.MATCH, cp(gw, "203.0.113.10 via 192.0.2.2 dev eth2 src 192.0.2.1").result());
        assertOutcome(Result.MISMATCH, "NEXT_HOP_DIFFERS", cp(gw, "203.0.113.10 via 192.0.2.6 dev eth2 src 192.0.2.1"));
        assertOutcome(Result.MISMATCH, "EGRESS_INTERFACE_DIFFERS", cp(gw, "203.0.113.10 via 198.51.100.130 dev eth3"));
        assertOutcome(Result.MISMATCH, "DEVICE_DISCARDS", cp(gw, "blackhole 203.0.113.10"));
        assertOutcome(Result.MISMATCH, "KERNEL_HAS_ROUTE",
                cp(new HopPlan.Expect("NO_ROUTE", null, null, false, null, null, null, null), "203.0.113.10 via 192.0.2.2 dev eth2"));
        assertOutcome(Result.MATCH, "NO_ROUTE_BOTH",
                cp(new HopPlan.Expect("NO_ROUTE", null, null, false, null, null, null, null), "RTNETLINK answers: Network is unreachable"));
        assertOutcome(Result.MATCH, "DISCARD_BOTH",
                cp(new HopPlan.Expect("DISCARD", null, null, false, null, null, null, null), "blackhole 203.0.113.10"));
        var connected = new HopPlan.Expect("CONNECTED", "eth1", null, false, null, null, null, null);
        assertOutcome(Result.MATCH, "CONNECTED_SAME_INTERFACE", cp(connected, "203.0.113.10 dev eth1 src 198.51.100.1"));
        assertOutcome(Result.MISMATCH, "DEVICE_ROUTES_VIA_GATEWAY", cp(connected, "203.0.113.10 via 198.51.100.2 dev eth1"));
        var ecmp = new HopPlan.Expect("IP", "eth2", "192.0.2.2", true, null, null, null, null);
        assertOutcome(Result.NOT_EVALUABLE, "ECMP_DEVICE_CHOICE", cp(ecmp, "203.0.113.10 via 198.51.100.130 dev eth3"));
        assertOutcome(Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED", cp(gw, "RTNETLINK answers: Invalid argument"));
        assertOutcome(Result.NOT_EVALUABLE, "DESTINATION_IS_LOCAL", cp(gw, "local 203.0.113.10 dev lo"));
        assertOutcome(Result.NOT_EVALUABLE, "ENGINE_ROUTE_UNKNOWN",
                cp(new HopPlan.Expect(null, null, null, false, null, null, null, null), "203.0.113.10 dev eth2"));
        assertOutcome(Result.NOT_EVALUABLE, "ENGINE_ROUTE_KIND_NOT_COMPARABLE",
                cp(new HopPlan.Expect("NEXT_VR", null, null, false, null, null, null, null), "203.0.113.10 dev eth2"));
    }

    private static HopComparison.Outcome cp(HopPlan.Expect expect, String output) {
        return HopComparison.cpRoute(expect, MatcherAnswers.routeGet(output, DST));
    }

    // ---- Palo Alto ---------------------------------------------------------------------------------------------------

    static String fib(String inner) {
        return "<response status=\"success\"><result>" + inner + "</result></response>";
    }

    @Test
    void fibLookupParsesAndCompares() {
        var answer = MatcherAnswers.fibLookup(fib("<nh>ip</nh><src>203.0.113.1</src><ip>192.0.2.1</ip><metric>10</metric>"
                + "<interface>ethernet1/1</interface><dp>dp0</dp>")).orElseThrow();
        assertEquals("ethernet1/1", answer.egressInterface());
        assertEquals(Optional.of("192.0.2.1"), answer.nextHop());
        var gw = new HopPlan.Expect("IP", "ethernet1/1", "192.0.2.1", false, null, null, null, null);
        assertEquals(Result.MATCH, HopComparison.panFib(gw, Optional.of(answer)).result());
        var connected = new HopPlan.Expect("CONNECTED", "ethernet1/2", null, false, null, null, null, null);
        assertOutcome(Result.MATCH, "CONNECTED_SAME_INTERFACE", HopComparison.panFib(connected,
                MatcherAnswers.fibLookup(fib("<nh>ip</nh><ip>0.0.0.0</ip><interface>ethernet1/2</interface>"))));
        assertOutcome(Result.MISMATCH, "EGRESS_INTERFACE_DIFFERS", HopComparison.panFib(connected, Optional.of(answer)));
        assertOutcome(Result.MATCH, "DISCARD_BOTH", HopComparison.panFib(
                new HopPlan.Expect("DISCARD", null, null, false, null, null, null, null), MatcherAnswers.fibLookup(fib("<nh>discard</nh>"))));
        assertOutcome(Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED", HopComparison.panFib(gw,
                MatcherAnswers.fibLookup("<response status=\"error\"><msg><line>synthetic error</line></msg></response>")));
        assertOutcome(Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED", HopComparison.panFib(gw,
                MatcherAnswers.fibLookup(fib("<metric>10</metric>"))));
        assertTrue(MatcherAnswers.fibLookup("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/hosts\">]><response status=\"success\">"
                + "<result><interface>&x;</interface></result></response>").isEmpty(), "no DTD, no entity");
        assertTrue(MatcherAnswers.fibLookup("not xml").isEmpty());
    }

    @Test
    void policyMatchParsesBothEntryFormsAndCompares() {
        String named = "<response status=\"success\"><result><rules><entry name=\"allow-https\"><from><member>ZONE-TRANSIT</member>"
                + "</from><action>allow</action></entry></rules></result></response>";
        assertEquals(List.of("allow-https"), MatcherAnswers.matchedRules(named).orElseThrow());
        String text = "<response status=\"success\"><result><rules><entry>allow-https; index: 3</entry></rules></result></response>";
        assertEquals(List.of("allow-https"), MatcherAnswers.matchedRules(text).orElseThrow());
        String none = "<response status=\"success\"><result><rules/></result></response>";
        assertEquals(List.of(), MatcherAnswers.matchedRules(none).orElseThrow());
        assertTrue(MatcherAnswers.matchedRules("<response status=\"success\"><result/></response>").isEmpty(), "no <rules>: unproven");
        assertTrue(MatcherAnswers.matchedRules("<response status=\"success\"><result><rules><x/></rules></result></response>").isEmpty());

        var allow = new HopPlan.Expect(null, null, null, false, "ALLOWED", "allow-https", null, null);
        assertOutcome(Result.MATCH, "SAME_FIRST_MATCH_RULE", HopComparison.panSecurity(allow, MatcherAnswers.matchedRules(named)));
        assertOutcome(Result.MISMATCH, "FIRST_MATCH_RULE_DIFFERS", HopComparison.panSecurity(
                new HopPlan.Expect(null, null, null, false, "DENIED", "deny-all", null, null), MatcherAnswers.matchedRules(text)));
        assertOutcome(Result.MISMATCH, "DEVICE_MATCHED_NO_RULE", HopComparison.panSecurity(allow, MatcherAnswers.matchedRules(none)));
        assertOutcome(Result.NOT_EVALUABLE, "DEFAULT_RULE_NOT_REPORTED", HopComparison.panSecurity(
                new HopPlan.Expect(null, null, null, false, "DENIED", "interzone-default", null, null), MatcherAnswers.matchedRules(none)));
        assertOutcome(Result.NOT_EVALUABLE, "ENGINE_RULE_UNKNOWN", HopComparison.panSecurity(
                new HopPlan.Expect(null, null, null, false, null, null, null, null), MatcherAnswers.matchedRules(named)));
        assertOutcome(Result.MISMATCH, "FIRST_MATCH_RULE_DIFFERS", HopComparison.panSecurity(
                new HopPlan.Expect(null, null, null, false, "ALLOWED", "Allow-HTTPS", null, null), MatcherAnswers.matchedRules(named)));

        var snat = new HopPlan.Expect(null, null, null, false, null, null, "TRANSLATED", "snat-dmz");
        String natAnswer = "<response status=\"success\"><result><rules><entry name=\"snat-dmz\"/></rules></result></response>";
        assertOutcome(Result.MATCH, "SAME_NAT_RULE", HopComparison.panNat(snat, MatcherAnswers.matchedRules(natAnswer)));
        assertOutcome(Result.MISMATCH, "DEVICE_MATCHED_NO_NAT_RULE", HopComparison.panNat(snat, MatcherAnswers.matchedRules(none)));
        var noNat = new HopPlan.Expect(null, null, null, false, null, null, "NO_TRANSLATION", null);
        assertOutcome(Result.MATCH, "NO_NAT_RULE_BOTH", HopComparison.panNat(noNat, MatcherAnswers.matchedRules(none)));
        assertOutcome(Result.MISMATCH, "DEVICE_MATCHED_NAT_RULE", HopComparison.panNat(noNat, MatcherAnswers.matchedRules(natAnswer)));
        assertOutcome(Result.NOT_EVALUABLE, "ENGINE_NAT_UNKNOWN", HopComparison.panNat(
                new HopPlan.Expect(null, null, null, false, null, null, "UNKNOWN", null), MatcherAnswers.matchedRules(none)));
    }

    @Test
    void theHopResultAndReasonsAggregateTheChecks() {
        var match = new HopComparison.Outcome(Check.PAN_FIB, Result.MATCH, "SAME_INTERFACE_AND_NEXT_HOP");
        var na = new HopComparison.Outcome(Check.PAN_NAT, Result.NOT_EVALUABLE, "ANSWER_NOT_RECOGNISED");
        var miss = new HopComparison.Outcome(Check.PAN_SECURITY, Result.MISMATCH, "FIRST_MATCH_RULE_DIFFERS");
        assertEquals(Result.MATCH, HopComparison.hop(List.of(match, na)));
        assertEquals(Result.MISMATCH, HopComparison.hop(List.of(match, miss, na)));
        assertEquals(Result.NOT_EVALUABLE, HopComparison.hop(List.of(na)));
        assertEquals(List.of("FIRST_MATCH_RULE_DIFFERS", "ANSWER_NOT_RECOGNISED"), HopComparison.reasons(List.of(match, miss, na, na)));
    }

    private static void assertOutcome(Result result, String reason, HopComparison.Outcome outcome) {
        assertEquals(result, outcome.result(), outcome.toString());
        assertEquals(reason, outcome.reason(), outcome.toString());
    }
}
