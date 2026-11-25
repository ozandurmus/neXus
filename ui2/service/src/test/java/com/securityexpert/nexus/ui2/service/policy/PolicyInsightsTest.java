package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cell;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Policy Insights phase P1: evidence grades (design section 3.1) and package counts (section 3.2) over synthetic Check
 * Point packages. Masked names and documentation addresses (192.0.2.0/24) only.
 */
class PolicyInsightsTest {
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return report(snapshot, null, Map.of()); }
    static PolicyInsights.Report report(PolicySnapshot snapshot, PolicyDomainIndex index, Map<String, Integer> windows) {
        return PolicyInsights.analyze(snapshot, PolicyHygiene.analyze(snapshot, 90, NOW, 250000), index, windows, 90);
    }
    static List<PolicyInsights.Insight> of(PolicyInsights.Report report, String type) { return report.insights().stream().filter(i -> i.type().equals(type)).toList(); }
    static Rule withHits(Rule rule, Long hits, String lastHit) {
        return rule.withHitCounts(new HitCounts(hits, null, lastHit, "mds", NOW.toString(), hits != null && hits == 0 ? "zero" : null, List.of()));
    }

    @Test void fullShadowOfTheSameActionIsProvenI1WithTheInstallStateCaveat() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "any"), cp("r2", 2, "Accept", "host", "any")));
        var report = report(snapshot);
        var i1 = of(report, "I1");
        assertEquals(1, i1.size());
        assertEquals("PROVEN", i1.get(0).grade());
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), i1.get(0).caveats());
        assertEquals(List.of("r2", "r1"), i1.get(0).rules().stream().map(PolicyInsights.RuleRef::ruleId).toList());
        assertEquals(List.of("subject", "covering"), i1.get(0).rules().stream().map(PolicyInsights.RuleRef::insightRole).toList());
        assertEquals("PROVEN", i1.get(0).raiseGrade()); assertEquals(PolicyInsights.NONE, i1.get(0).needs());
        assertTrue(of(report, "I2").isEmpty());
    }
    @Test void fullShadowOfADifferentActionIsProvenI2NotI1() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Drop", "any", "any"), cp("r2", 2, "Accept", "host", "any")));
        var report = report(snapshot);
        assertTrue(of(report, "I1").isEmpty());
        assertEquals(List.of("PROVEN"), of(report, "I2").stream().map(PolicyInsights.Insight::grade).toList());
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), of(report, "I2").get(0).caveats());
    }
    @Test void zeroHitsWithAnUnknownWindowIsIndicatedAndNeedsCounterSamples() {
        var snapshot = cpSnapshot(cpSection("s1", null, withHits(cp("r1", 1, "Accept", "host", "any"), 0L, null)));
        var i10 = of(report(snapshot), "I10");
        assertEquals(1, i10.size());
        assertEquals("INDICATED", i10.get(0).grade());
        assertEquals("SUPPORTED", i10.get(0).raiseGrade()); assertEquals(PolicyInsights.COUNTER_SAMPLES, i10.get(0).needs());
        assertEquals(false, i10.get(0).counter().get("windowKnown"));
        assertTrue(i10.get(0).caveats().isEmpty(), "only a PROVEN insight carries the install-state caveat");
    }
    @Test void zeroHitsWithAKnownWindowAtLeastTheThresholdIsSupported() {
        var snapshot = cpSnapshot(cpSection("s1", null, withHits(cp("r1", 1, "Accept", "host", "any"), 0L, null)));
        var supported = of(report(snapshot, null, Map.of("r1", 120)), "I10").get(0);
        assertEquals("SUPPORTED", supported.grade()); assertEquals(PolicyInsights.NONE, supported.needs());
        assertEquals(120, supported.counter().get("windowDays")); assertEquals(true, supported.counter().get("windowKnown"));
        // A window shorter than the threshold establishes nothing: still INDICATED.
        assertEquals("INDICATED", of(report(snapshot, null, Map.of("r1", 30)), "I10").get(0).grade());
    }
    @Test void anOldLastHitIsIndicatedAndMissingCountersProduceNoUnusedInsight() {
        var old = cpSnapshot(cpSection("s1", null, withHits(cp("r1", 1, "Accept", "host", "any"), 4L, "2026-01-01T00:00:00Z")));
        assertEquals("INDICATED", of(report(old), "I10").get(0).grade());
        var none = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any")));
        var report = report(none);
        assertTrue(of(report, "I10").isEmpty(), "no counters is a collection gap, never an unused rule");
        assertEquals(1, report.counts().hitsMissing()); assertEquals(0, report.counts().hitsCollected());
    }
    @Test void anUnresolvedObjectIsNotDecidableAndNeverResolvable() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "any"), cp("r2", 2, "Accept", "missing", "any")));
        var counts = report(snapshot).counts();
        assertEquals(2, counts.rules());
        assertEquals(1, counts.decided());
        assertEquals(1, counts.notResolvable()); assertEquals(0, counts.logResolvable()); assertEquals(0, counts.collectionResolvable());
        assertEquals(1, counts.upToWithLogs(), "an unresolved object is not raised by logs");
        assertEquals(List.of(new PolicyInsights.Blocked("unresolved object", PolicyInsights.NOT_RESOLVABLE, 1)), counts.notDecidable());
    }
    @Test void aUserConstraintIsLogResolvableAndRaisesOnlyTheCeiling() {
        var user = edit(cp("r2", 2, "Accept", "host", "any"), cell("host"), Map.of("source-user", List.of("OBJ-USER-01")));
        // r3 is held back by r2 (an earlier rule it cannot be proven against) and takes r2's own reason.
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", "any"), user, cp("r3", 3, "Drop", "host", "any")));
        var counts = report(snapshot).counts();
        assertEquals(3, counts.rules());
        assertEquals(counts.decided() + counts.logResolvable(), counts.upToWithLogs());
        assertEquals(3, counts.decided() + counts.logResolvable() + counts.collectionResolvable() + counts.notResolvable());
        assertTrue(counts.logResolvable() >= 1);
        assertTrue(counts.upToWithLogs() > counts.decided(), "the ceiling is above what the policy alone decides");
        assertTrue(counts.notDecidable().stream().allMatch(b -> b.reasonClass().equals(PolicyInsights.LOGS)));
    }
    @Test void everyBlockingReasonIsClassifiedOnceAndAnUnlistedReasonIsNotResolvable() {
        assertEquals(PolicyInsights.LOGS, PolicyInsights.reasonClass("user or category constraint"));
        assertEquals(PolicyInsights.LOGS, PolicyInsights.reasonClass("time or content constraint"));
        assertEquals(PolicyInsights.COLLECTION, PolicyInsights.reasonClass("snapshot collection incomplete"));
        assertEquals(PolicyInsights.NOT_RESOLVABLE, PolicyInsights.reasonClass("negated selector"));
        assertEquals(PolicyInsights.NOT_RESOLVABLE, PolicyInsights.reasonClass("analysis budget"));
        assertEquals(PolicyInsights.NOT_RESOLVABLE, PolicyInsights.reasonClass("a reason nobody classified"));
        for (String reason : PolicyHygiene.REASONS) assertTrue(PolicyInsights.REASON_CLASSES.contains(PolicyInsights.reasonClass(reason)), reason);
    }
    @Test void disabledRulesAreI8AndStayOutOfTheEnabledCounts() {
        var off = edit(cp("r2", 2, "Accept", "host", "any"), cell("host"), Map.of());
        off = new Rule(off.id(), off.uuid(), off.number(), off.name(), false, off.source(), off.destination(), off.service(), off.application(),
            off.action(), off.log(), off.comment(), off.extras(), off.hitCounts());
        var report = report(cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "net", "any"), off)));
        assertEquals(List.of("PROVEN"), of(report, "I8").stream().map(PolicyInsights.Insight::grade).toList());
        assertEquals(1, report.counts().rules()); assertEquals(1, report.counts().disabledRules());
    }
    @Test void anExpiredScheduleIsProvenI9() {
        var objects = new HashMap<>(cpObjects());
        objects.put("time-old", new PolicyObject("time-old", "OBJ-TIME-01", "time", List.of(), List.of(), "RESOLVED",
            new PolicySchedule("one-time", null, "2026-01-01T00:00", List.of(), "UTC", true)));
        var rule = edit(cp("r1", 1, "Accept", "host", "any"), cell("host"), Map.of("time", List.of("time-old")));
        var base = cpSnapshot(cpSection("s1", null, rule));
        var snapshot = new PolicySnapshot(base.metadata(), base.sections(), objects);
        var i9 = of(report(snapshot), "I9");
        assertEquals(1, i9.size());
        assertEquals("PROVEN", i9.get(0).grade());
        assertEquals(List.of("Stored schedule has expired."), i9.get(0).evidence());
    }
    @Test void everyEvidenceStringAnInsightCarriesPassesTheAiviewProjection() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "any"), withHits(cp("r2", 2, "Accept", "host", "any"), 0L, null)));
        for (var insight : report(snapshot).insights()) for (String evidence : insight.evidence())
            assertTrue(PolicyHygiene.EVIDENCE.contains(evidence), evidence);
    }

    // I11 and I12 read the domain index; the objects are synthetic documentation addresses.
    static PolicyDomainIndex domainIndex(PolicySnapshot snapshot, boolean withUnusedReport) {
        var host = PolicyDomainIndexTest.item("0001", "OBJ-HOST-01", "host", List.of(), "ipv4-address: 192.0.2.8");
        var twin = PolicyDomainIndexTest.item("0002", "OBJ-HOST-02", "host", List.of(), "ipv4-address: 192.0.2.8");
        var idle = PolicyDomainIndexTest.item("0003", "OBJ-HOST-03", "host", List.of(), "ipv4-address: 192.0.2.77");
        var inventories = new ArrayList<>(List.of(PolicyDomainIndexTest.inventory("hosts", "RESOLVED", host, twin, idle)));
        if (withUnusedReport) inventories.add(PolicyDomainIndexTest.inventory("unused-objects", "RESOLVED", idle));
        return new PolicyDomainIndex(inventories, List.of(snapshot));
    }
    @Test void anUnusedObjectIsProvenOnlyWithTheManagementReportAndDuplicatesAreProven() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any")));
        var withReport = report(snapshot, domainIndex(snapshot, true), Map.of());
        var unused = of(withReport, "I11").stream().filter(i -> i.objectIds().contains("0003")).findFirst().orElseThrow();
        assertEquals("PROVEN", unused.grade()); assertEquals("domain", unused.scope());
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), unused.caveats());
        var duplicates = of(withReport, "I12");
        assertEquals(1, duplicates.size());
        assertEquals("PROVEN", duplicates.get(0).grade());
        assertEquals(List.of("0001", "0002"), duplicates.get(0).objectIds());
        // Without the management report an object nobody stored a reference to is only a lower bound.
        var withoutReport = report(snapshot, domainIndex(snapshot, false), Map.of());
        var bound = of(withoutReport, "I11").stream().filter(i -> i.objectIds().contains("0003")).findFirst().orElseThrow();
        assertEquals("INDICATED", bound.grade()); assertEquals(PolicyInsights.VENDOR_REPORT, bound.needs());
        assertTrue(bound.caveats().isEmpty());
    }
    @Test void withoutADomainIndexThereAreNoObjectInsightsAndNothingIsGuessed() {
        var report = report(cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "host", "any"))));
        assertTrue(of(report, "I11").isEmpty()); assertTrue(of(report, "I12").isEmpty());
    }
    @Test void typeAndGradeTotalsCoverEveryInsightAndTheClosedVocabulariesAreStable() {
        var snapshot = cpSnapshot(cpSection("s1", null, cp("r1", 1, "Accept", "any", "any"), cp("r2", 2, "Accept", "host", "any")));
        var report = report(snapshot);
        assertEquals(report.insights().size(), report.byType().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(report.insights().size(), report.byGrade().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(List.of("I1", "I2", "I8", "I9", "I10", "I11", "I12"), PolicyInsights.TYPES);
        assertEquals(List.of("PROVEN", "SUPPORTED", "INDICATED", "NOT_ESTABLISHED", "NOT_EVALUABLE"), PolicyInsights.GRADES);
    }
}
