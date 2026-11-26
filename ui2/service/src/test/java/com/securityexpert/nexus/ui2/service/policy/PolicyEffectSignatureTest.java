package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Unit tests for {@link PolicyEffectSignature} (G3 of POLICY_INSIGHTS_DESIGN_2026_11_24.md, FROZEN; Q2): Palo Alto
 * {@code profile-setting/*}, {@code log-setting} and {@link Rule#log()}, Check Point parity, and unmapped vendors failing
 * closed to {@link PolicyEffectSignature.Part#UNKNOWN}. Synthetic masked names and RFC 5737 documentation addresses only.
 */
class PolicyEffectSignatureTest {
    private static Cell cell(String ref) { return new Cell(List.of(ref), false); }

    private static Rule panRule(String id, String action, String log, Map<String, List<String>> extras) {
        Map<String, List<String>> merged = new LinkedHashMap<>(Map.of("from", List.of("ZONE-ALPHA-01"), "to", List.of("ZONE-BRAVO-02")));
        merged.putAll(extras);
        return new Rule(id, "uuid-" + id, 1, "OBJ-RULE-" + id, true, cell("any"), cell("any"), cell("any"), cell("any"),
            action, log, "", merged);
    }

    private static Rule cpRule(String id, String action, String log, Map<String, List<String>> extras) {
        Map<String, List<String>> merged = new LinkedHashMap<>(Map.of("install-on", List.of("any"), "time", List.of("any"), "layer-name", List.of("LAYER-ALPHA")));
        merged.putAll(extras);
        return new Rule(id, "uuid-" + id, 1, "OBJ-RULE-" + id, true, cell("any"), cell("any"), cell("any"), new Cell(List.of(), false),
            action, log, "", merged);
    }

    @Test void paloAltoProfileGroupIsKnownWhileAbsentOrIndividualProfilesFailClosed() {
        Rule plainA = panRule("r1", "allow", "start=no, end=yes", Map.of());
        Rule plainB = panRule("r2", "allow", "start=no, end=yes", Map.of());
        var sigA = PolicyEffectSignature.of("PAN", plainA);
        assertFalse(sigA.profiles().known(), "absent PAN profile-setting/* fails closed because data-filtering is not collected");
        assertFalse(PolicyEffectSignature.sameFull("PAN", plainA, plainB));

        Rule groupAlpha = panRule("r3", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA")));
        Rule groupBravo = panRule("r4", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-BRAVO")));
        assertTrue(PolicyEffectSignature.of("PAN", groupAlpha).profiles().known());
        assertFalse(PolicyEffectSignature.sameFull("PAN", plainA, groupAlpha));
        assertFalse(PolicyEffectSignature.sameFull("PAN", groupAlpha, groupBravo));
        assertTrue(PolicyEffectSignature.sameFull("PAN", groupAlpha, panRule("r5", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA")))));

        Map<String, List<String>> indiv = new LinkedHashMap<>();
        indiv.put("profile-setting/profiles/wildfire-analysis", List.of("WF-ALPHA"));
        indiv.put("profile-setting/profiles/virus", List.of("AV-ALPHA"));
        indiv.put("profile-setting/profiles/spyware", List.of("AS-ALPHA"));
        indiv.put("profile-setting/profiles/vulnerability", List.of("VP-ALPHA"));
        indiv.put("profile-setting/profiles/url-filtering", List.of("URL-ALPHA"));
        indiv.put("profile-setting/profiles/file-blocking", List.of("FB-ALPHA"));

        Rule indivA = panRule("r6", "allow", "start=no, end=yes", indiv);
        Rule indivB = panRule("r7", "allow", "start=no, end=yes", indiv);
        assertFalse(PolicyEffectSignature.of("PAN", indivA).profiles().known(), "individual profiles omit data-filtering and fail closed");
        assertFalse(PolicyEffectSignature.sameFull("PAN", indivA, indivB));
        assertFalse(PolicyEffectSignature.sameFull("PAN", groupAlpha, indivA));

        Rule mixedGroupAndIndiv = panRule("r8", "allow", "start=no, end=yes", Map.of(
            "profile-setting/group", List.of("PROF-ALPHA"),
            "profile-setting/profiles/virus", List.of("AV-ALPHA")));
        assertFalse(PolicyEffectSignature.of("PAN", mixedGroupAndIndiv).profiles().known());
        assertFalse(PolicyEffectSignature.sameFull("PAN", mixedGroupAndIndiv, mixedGroupAndIndiv));
    }

    @Test void paloAltoLogStartEndAndLogSettingForwardingAreComparedAndFailClosedOnUnknown() {
        Map<String, List<String>> grp = Map.of("profile-setting/group", List.of("PROF-ALPHA"));
        Rule base = panRule("r1", "allow", "start=no, end=yes", grp);
        Rule startYes = panRule("r2", "allow", "start=yes, end=yes", grp);
        Rule endNo = panRule("r3", "allow", "start=no, end=no", grp);
        assertEquals(List.of("type=start=no, end=yes"), PolicyEffectSignature.of("PAN", base).log().values());
        assertFalse(PolicyEffectSignature.sameFull("PAN", base, startYes));
        assertFalse(PolicyEffectSignature.sameFull("PAN", base, endNo));
        assertTrue(PolicyEffectSignature.sameVerdict(base, startYes));

        Rule fwdAlpha1 = panRule("r4", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", List.of("LOG-FWD-ALPHA")));
        Rule fwdAlpha2 = panRule("r5", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", List.of("LOG-FWD-ALPHA")));
        Rule fwdBravo = panRule("r6", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", List.of("LOG-FWD-BRAVO")));
        assertEquals(List.of("type=start=no, end=yes", "log-setting=LOG-FWD-ALPHA"), PolicyEffectSignature.of("PAN", fwdAlpha1).log().values());
        assertFalse(PolicyEffectSignature.sameFull("PAN", base, fwdAlpha1), "log-forwarding presence changes full signature");
        assertTrue(PolicyEffectSignature.sameFull("PAN", fwdAlpha1, fwdAlpha2));
        assertFalse(PolicyEffectSignature.sameFull("PAN", fwdAlpha1, fwdBravo));

        for (String unknownLog : Arrays.asList(
                null, "", "   ", "UNKNOWN", "unknown", "not collected", "Log", "none", "yes",
                "start=UNKNOWN, end=yes", "start=no, end=UNKNOWN", "start=UNKNOWN, end=UNKNOWN",
                "start=maybe, end=yes", "start=no")) {
            Rule u1 = panRule("u1", "allow", unknownLog, grp);
            Rule u2 = panRule("u2", "allow", unknownLog, grp);
            assertFalse(PolicyEffectSignature.of("PAN", u1).log().known(), "must fail closed: " + unknownLog);
            assertFalse(PolicyEffectSignature.sameFull("PAN", u1, u2), "UNKNOWN never equals UNKNOWN: " + unknownLog);
        }

        for (List<String> invalidExtra : List.of(List.<String>of(), List.of(""), List.of("   "), List.of("UNKNOWN"), List.of("PROF-ALPHA", "UNKNOWN"))) {
            Rule badProf = panRule("bp", "allow", "start=no, end=yes", Map.of("profile-setting/group", invalidExtra));
            assertFalse(PolicyEffectSignature.of("PAN", badProf).profiles().known());
            assertFalse(PolicyEffectSignature.sameFull("PAN", badProf, badProf));

            Rule badLogFwd = panRule("bl", "allow", "start=no, end=yes", Map.of("profile-setting/group", List.of("PROF-ALPHA"), "log-setting", invalidExtra));
            assertFalse(PolicyEffectSignature.of("PAN", badLogFwd).log().known());
            assertFalse(PolicyEffectSignature.sameFull("PAN", badLogFwd, badLogFwd));
        }
    }

    @Test void paloAltoVerdictActionsCheckPointParityPanoramaMapperShapesAndUnmappedVendors() {
        Map<String, List<String>> grp = Map.of("profile-setting/group", List.of("PROF-ALPHA"));
        List<String> panActions = List.of("allow", "deny", "drop", "reset-client", "reset-server", "reset-both");
        for (int i = 0; i < panActions.size(); i++) {
            for (int j = 0; j < panActions.size(); j++) {
                Rule a = panRule("a", panActions.get(i), "start=no, end=yes", grp);
                Rule b = panRule("b", panActions.get(j), "start=no, end=yes", grp);
                assertEquals(i == j, PolicyEffectSignature.sameVerdict(a, b));
                assertEquals(i == j, PolicyEffectSignature.sameFull("PAN", a, b));
            }
        }
        Rule unknownAction = panRule("ua", "UNKNOWN", "start=no, end=yes", grp);
        assertFalse(PolicyEffectSignature.sameVerdict(unknownAction, unknownAction));
        assertFalse(PolicyEffectSignature.sameFull("PAN", unknownAction, unknownAction));

        // Check Point parity: absent profile-setting/* is UNKNOWN on CP, exempted in sameFull("CP", ...) when neither rule has one.
        Rule cpA = cpRule("c1", "Accept", "Log", Map.of("track-per-session", List.of("true")));
        Rule cpB = cpRule("c2", "Accept", "Log", Map.of("track-per-session", List.of("true")));
        assertFalse(PolicyEffectSignature.of("CP", cpA).profiles().known());
        assertTrue(PolicyEffectSignature.sameFull("CP", cpA, cpB));

        // Unmapped vendors (ASA, FGT, OTHER) keep UNKNOWN profile and log parts and never report sameFull.
        for (String vendor : List.of("ASA", "FGT", "OTHER")) {
            Rule r = panRule("v1", "permit", "Log", Map.of());
            var sig = PolicyEffectSignature.of(vendor, r);
            assertFalse(sig.profiles().known(), vendor);
            assertFalse(sig.log().known(), vendor);
            assertFalse(PolicyEffectSignature.sameFull(vendor, r, r), vendor);
        }

        // End-to-end PanoramaPolicyMapper XML shapes, including uncollected individual data-filtering (DLP) profile.
        String xml = "<config><shared/><devices><entry name='localhost.localdomain'><device-group><entry name='DG-TANGO-01'>"
            + "<pre-rulebase><security><rules>"
            + "<entry name='RULE-FULL-A' uuid='u-1'><from><member>ZONE-ALPHA-01</member></from><to><member>ZONE-BRAVO-02</member></to>"
            + "<source><member>192.0.2.0/25</member></source><destination><member>any</member></destination>"
            + "<service><member>service-https</member></service><application><member>any</member></application>"
            + "<action>allow</action><log-start>no</log-start><log-end>yes</log-end><log-setting>LOG-FWD-ALPHA</log-setting>"
            + "<profile-setting><group><member>PROF-STRICT</member></group></profile-setting></entry>"
            + "<entry name='RULE-FULL-B' uuid='u-2'><from><member>ZONE-ALPHA-01</member></from><to><member>ZONE-BRAVO-02</member></to>"
            + "<source><member>192.0.2.128/25</member></source><destination><member>any</member></destination>"
            + "<service><member>service-https</member></service><application><member>any</member></application>"
            + "<action>allow</action><log-start>no</log-start><log-end>yes</log-end><log-setting>LOG-FWD-ALPHA</log-setting>"
            + "<profile-setting><group><member>PROF-STRICT</member></group></profile-setting></entry>"
            + "<entry name='RULE-OMITTED-LOG-START' uuid='u-3'><from><member>ZONE-ALPHA-01</member></from><to><member>ZONE-BRAVO-02</member></to>"
            + "<source><member>192.0.2.0/24</member></source><destination><member>any</member></destination>"
            + "<service><member>service-https</member></service><application><member>any</member></application>"
            + "<action>allow</action><log-end>yes</log-end>"
            + "<profile-setting><group><member>PROF-STRICT</member></group></profile-setting></entry>"
            + "<entry name='RULE-DLP' uuid='u-4'><from><member>ZONE-ALPHA-01</member></from><to><member>ZONE-BRAVO-02</member></to>"
            + "<source><member>192.0.2.0/25</member></source><destination><member>any</member></destination>"
            + "<service><member>service-https</member></service><application><member>any</member></application>"
            + "<action>allow</action><log-start>no</log-start><log-end>yes</log-end>"
            + "<profile-setting><profiles><data-filtering><member>DLP-STRICT</member></data-filtering></profiles></profile-setting></entry>"
            + "<entry name='RULE-NO-PROF' uuid='u-5'><from><member>ZONE-ALPHA-01</member></from><to><member>ZONE-BRAVO-02</member></to>"
            + "<source><member>192.0.2.128/25</member></source><destination><member>any</member></destination>"
            + "<service><member>service-https</member></service><application><member>any</member></application>"
            + "<action>allow</action><log-start>no</log-start><log-end>yes</log-end></entry>"
            + "</rules></security></pre-rulebase>"
            + "</entry></device-group></entry></devices></config>";
        PolicySnapshot mapped = new PanoramaPolicyMapper().map(
            new Metadata("snap-1", "mgr-1", "MGR-BRAVO-01", "PAN", "dg-1", "DG-TANGO-01", "POL-ALPHA-01", "2026-10-09T12:00:00Z", "a1", List.of()),
            xml, "DG-TANGO-01", Map.of(), false);
        List<Rule> mappedRules = mapped.sections().stream().filter(s -> !s.rules().isEmpty()).findFirst().orElseThrow().rules();
        assertTrue(PolicyEffectSignature.sameFull("PAN", mappedRules.get(0), mappedRules.get(1)));
        assertFalse(PolicyEffectSignature.of("PAN", mappedRules.get(2)).log().known(), "omitted log-start maps to start=UNKNOWN and fails closed");
        assertFalse(PolicyEffectSignature.sameFull("PAN", mappedRules.get(2), mappedRules.get(2)));
        assertFalse(PolicyEffectSignature.of("PAN", mappedRules.get(3)).profiles().known(), "uncollected data-filtering fails closed");
        assertFalse(PolicyEffectSignature.of("PAN", mappedRules.get(4)).profiles().known(), "absent profile-setting fails closed");
        assertFalse(PolicyEffectSignature.sameFull("PAN", mappedRules.get(3), mappedRules.get(4)), "DLP rule must never match unprofiled rule");
    }
}
