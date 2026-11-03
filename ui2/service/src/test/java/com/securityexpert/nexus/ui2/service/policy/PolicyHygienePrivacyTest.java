package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Every reason / evidence string the hygiene engine can emit must reach the replay viewer unchanged. */
class PolicyHygienePrivacyTest {
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    private static final Path SOURCES = Path.of("src/main/java/com/securityexpert/nexus/ui2/service/policy");

    private static Set<String> literals(String file, String regex) throws Exception {
        var out = new TreeSet<String>(); var m = Pattern.compile(regex).matcher(Files.readString(SOURCES.resolve(file)));
        while (m.find()) out.add(m.group(1));
        return out;
    }
    private Object mask(String text, String key) { return PolicyPrivacy.mask(text, key, names, ips); }

    @Test void everyReasonLiteralInTheSourcesIsAKnownProjectedReason() throws Exception {
        var found = new TreeSet<String>();
        for (String file : List.of("PolicyHygiene.java", "PolicyNormalization.java")) {
            found.addAll(literals(file, "new Unknown\\(\"([^\"]+)\"\\)")); found.addAll(literals(file, "\\breason = \"([^\"]+)\""));
        }
        assertTrue(found.size() >= 25, "source scan found too few reasons: " + found);
        assertTrue(found.contains("earlier sections not analysed"));
        for (String reason : found) {
            assertTrue(PolicyHygiene.REASONS.contains(reason), "reason missing from PolicyHygiene.REASONS: " + reason);
            assertEquals(reason, mask(reason, "shadowReason"), reason);
            assertEquals(reason, mask(reason, "lookupReason"), reason);
            assertEquals(reason, mask(reason, "statReason"), reason);
            assertEquals("Shadowing: " + reason + ".", mask("Shadowing: " + reason + ".", "evidence"), reason);
        }
    }
    @Test void everyFindingEvidenceLiteralInTheSourceIsKnownAndProjected() throws Exception {
        // Finding evidence is a sentence; the counter-window strings travel as counterWindow, not as evidence.
        var found = new TreeSet<>(literals("PolicyHygiene.java", "\"([A-Z][^\"]*\\.)\""));
        found.removeIf(s -> s.startsWith("Counter window") || s.startsWith("Shadowing:"));
        assertTrue(found.size() >= 15, "source scan found too few evidence strings: " + found);
        assertTrue(found.contains("Last-hit time was not collected."));
        for (String evidence : found) {
            assertTrue(PolicyHygiene.EVIDENCE.contains(evidence), "evidence missing from PolicyHygiene.EVIDENCE: " + evidence);
            assertEquals(evidence, mask(evidence, "evidence"), evidence);
        }
        for (String evidence : PolicyHygiene.EVIDENCE) assertEquals(evidence, mask(evidence, "evidence"));
        assertEquals("Evidence withheld in AIView.", mask("Not a known sentence.", "evidence"));
    }
    @Test void permissivenessReasonsFromRuleMetricsPassThrough() {
        var reasons = new ArrayList<String>(PolicyRuleMetrics.FIXED_REASONS); reasons.add("Any source"); reasons.add("Negated service needs analysis");
        var out = (Map<?, ?>) PolicyPrivacy.mask(Map.of("level", "High", "score", 3, "reasons", reasons), "permissiveness", names, ips);
        assertEquals(reasons.size(), ((List<?>) out.get("reasons")).size());
    }
    @Test void hitLevelsIncludingTheExplicitUnknownPassThrough() {
        for (String level : List.of("zero", "low", "medium", "high", "UNKNOWN")) assertEquals(level, mask(level, "level"));
        assertEquals("UNKNOWN", mask("bogus", "level"));
        var snapshot = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "host", "port", "allow", true)), PolicyHygieneTest.objects()).withHitLevels();
        assertNull(snapshot.sections().get(0).rules().get(0).hitCounts());
    }
    @Test void earlierSectionsNotAnalysedSurvivesAiviewEndToEnd() {
        var snapshot = PolicyHygieneTest.snapshot(List.of(PolicyHygieneTest.rule("r1", "host", "port", "allow", true)), PolicyHygieneTest.objects());
        // A repeated section id breaks the proven ordering: the second section cannot be analysed against the first.
        var second = new Section("s1", "Pre rules", "Shared", null, List.of(PolicyHygieneTest.rule("r2", "host", "port", "allow", true)));
        var sections = new ArrayList<>(snapshot.sections()); sections.add(second);
        var result = PolicyHygiene.analyze(new PolicySnapshot(snapshot.metadata(), sections, snapshot.objects()), 90, PolicyHygieneTest.NOW, 250000);
        var assessment = result.rules().get("r2");
        assertEquals("earlier sections not analysed", assessment.shadowReason());
        assertEquals("earlier sections not analysed", mask(assessment.shadowReason(), "shadowReason"));
        assertTrue(assessment.findings().stream().anyMatch(f -> f.evidence().equals("Shadowing: earlier sections not analysed.")));
        for (var f : assessment.findings()) assertEquals(f.evidence(), mask(f.evidence(), "evidence"), f.evidence());
    }
}
