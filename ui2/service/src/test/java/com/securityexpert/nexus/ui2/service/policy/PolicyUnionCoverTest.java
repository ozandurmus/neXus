package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneCheckPointScopeTest.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.NOW;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cell;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.cpSection;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.edit;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygieneTest.object;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/**
 * Insight I3 (covered by several earlier rules together), Check Point fixtures: the covering set, the action relation,
 * fixed anchors (Q3), never across a layer (Q9), placement, a rule-complete snapshot, and budget exhaustion as NOT_PROVEN
 * (Q8). Masked names and documentation addresses (192.0.2.0/24) only.
 */
class PolicyUnionCoverTest {
    /** The two halves of the documentation /24, each a rule of its own. */
    static PolicySnapshot snapshot(Section... sections) {
        var base = cpSnapshot(sections);
        var objects = new HashMap<>(base.objects());
        objects.put("lo", object("lo", "address", "ip-netmask: 192.0.2.0/25"));
        objects.put("hi", object("hi", "address", "ip-netmask: 192.0.2.128/25"));
        objects.put("hi-a", object("hi-a", "address", "ip-netmask: 192.0.2.128/26"));
        objects.put("t1", new PolicyObject("t1", "OBJ-TIME-01", "time", List.of(), List.of(), "RESOLVED"));
        return new PolicySnapshot(base.metadata(), base.sections(), objects, base.failures());
    }
    static PolicyInsights.Report report(PolicySnapshot snapshot) { return PolicyInsightsTest.report(snapshot); }
    static List<PolicyInsights.Insight> i3(PolicySnapshot snapshot) { return PolicyInsightsTest.of(report(snapshot), "I3"); }
    static List<String> ids(PolicyInsights.Insight insight) { return insight.rules().stream().map(PolicyInsights.RuleRef::ruleId).toList(); }
    static Rule timed(Rule rule) { return edit(rule, rule.source(), Map.of("time", List.of("t1"))); }

    @Test void aRuleCoveredByTwoEarlierRulesTogetherIsProvenI3WithTheCoveringSetListed() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertEquals("NOT_SHADOWED", at(snapshot, "r3").shadowStatus(), "no single earlier rule covers it: not I1");
        var found = i3(snapshot);
        assertEquals(1, found.size());
        var insight = found.get(0);
        assertEquals("PROVEN", insight.grade());
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        assertEquals(List.of("subject", "covering", "covering"), insight.rules().stream().map(PolicyInsights.RuleRef::insightRole).toList());
        assertEquals(List.of(PolicyInsights.I3_SAME), insight.evidence());
        assertEquals(PolicyUnionCover.SAME, insight.counter().get("coverAction"));
        assertEquals(2, insight.counter().get("coveringRules"));
        assertEquals(List.of(PolicyInsights.INSTALL_STATE_NOT_VERIFIED), insight.caveats());
        assertEquals("PROVEN", insight.raiseGrade()); assertEquals(PolicyInsights.NONE, insight.needs());
        assertEquals(1, report(snapshot).byType().get("I3"));
    }
    @Test void onlyTheRulesThatTakePartAreListed() {
        // r2 lies inside r1's region: once r1 is subtracted it takes nothing, so it is not part of the covering set.
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "lo", "any"),
            cp("r3", 3, "Accept", "hi-a", "any"), cp("r4", 4, "Accept", "hi", "any"), cp("r5", 5, "Accept", "net", "any")));
        var r5 = i3(snapshot).stream().filter(i -> ids(i).get(0).equals("r5")).findFirst().orElseThrow();
        assertEquals(List.of("r5", "r1", "r3", "r4"), ids(r5));
    }
    @Test void aCoveringSetWithAnotherActionIsStillProvenAndSaysSo() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Drop", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i3(snapshot).get(0);
        assertEquals("PROVEN", insight.grade(), "the rule is never reached whatever the actions are");
        assertEquals(PolicyUnionCover.DIFFERENT, insight.counter().get("coverAction"));
        assertEquals(List.of(PolicyInsights.I3_DIFFERENT), insight.evidence());
    }
    @Test void applyLayerRulesInTheCoveringSetAreNamedAsSuch() {
        var snapshot = snapshot(cpSection("s1", null, applyLayer("r1", 1, "lo", "inline-a"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var insight = i3(snapshot).get(0);
        assertEquals(PolicyUnionCover.INLINE_LAYER, insight.counter().get("coverAction"));
        assertEquals(List.of(PolicyInsights.I3_INLINE), insight.evidence());
    }
    @Test void aPartialUnionIsNoInsightAndASingleRuleCoverStaysI1() {
        var partial = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi-a", "any"), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(partial).isEmpty(), "192.0.2.192/26 is left: not covered");
        var single = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "net", "any"), cp("r3", 3, "Accept", "hi", "any")));
        var report = report(single);
        assertTrue(PolicyInsightsTest.of(report, "I3").isEmpty(), "r2 alone covers r3: that is I1, never I3");
        assertEquals(List.of("r3", "r2"), ids(PolicyInsightsTest.of(report, "I1").get(0)));
    }
    @Test void anchorsNeverCoverAndNeverBlockACover() {
        // r2 has a time constraint (an anchor, design Q3): it takes no part in a cover, so r1 alone leaves the upper half open.
        var anchorOnly = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), timed(cp("r2", 2, "Accept", "hi", "any")), cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(anchorOnly).isEmpty());
        // An unreadable rule between the covering rules and the subject does not weaken the proof.
        var between = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("rx", 2, "Accept", "missing", "any"),
            timed(cp("ry", 3, "Drop", "any", "any")), cp("r2", 4, "Accept", "hi", "any"), cp("r3", 5, "Accept", "net", "any")));
        var insight = i3(between).get(0);
        assertEquals(List.of("r3", "r1", "r2"), ids(insight));
        // An unreadable subject is never an I3 subject.
        var subject = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), timed(cp("r3", 3, "Accept", "net", "any"))));
        assertTrue(i3(subject).isEmpty());
        var union = PolicyUnionCover.analyze(subject, PolicyHygiene.analyze(subject, 90, NOW, 250000));
        assertEquals(Map.of(PolicyUnionCover.NOT_RESOLVED, 1), union.notEvaluable());
    }
    @Test void earlierRulesOfAnotherLayerNeverCover() {
        // Two ordered layers: the second starts its own scope, so the first layer's rules are no part of its covers (Q9).
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")),
            cpSection("s2", null, inLayer(cp("b1", 1, "Accept", "net", "any"), "LAYER-BRAVO")));
        assertEquals("NOT_SHADOWED", at(snapshot, "b1").shadowStatus());
        assertTrue(i3(snapshot).isEmpty());
        // The same rules in one layer, two sections: the layer continues and the cover holds.
        var sections = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any")),
            cpSection("s2", null, cp("r3", 3, "Accept", "net", "any")));
        assertEquals(List.of("r3", "r1", "r2"), ids(i3(sections).get(0)));
    }
    @Test void anotherInstallOnTakesNoPartAndADisabledRuleNeither() {
        var installOn = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "targets"), cp("r2", 2, "Accept", "hi", "targets-2"), cp("r3", 3, "Accept", "net", "targets")));
        assertTrue(i3(installOn).isEmpty());
        var r2 = cp("r2", 2, "Accept", "hi", "any");
        var disabled = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"),
            new Rule(r2.id(), r2.uuid(), r2.number(), r2.name(), false, r2.source(), r2.destination(), r2.service(), r2.application(), r2.action(), r2.log(), r2.comment(), r2.extras()),
            cp("r3", 3, "Accept", "net", "any")));
        assertTrue(i3(disabled).isEmpty());
    }
    @Test void anIncompleteSnapshotEvaluatesNothing() {
        var complete = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var incomplete = new PolicySnapshot(complete.metadata(), complete.sections(), complete.objects(), List.of(new CollectionFailure("layer", "COLLECTION_PENDING")));
        assertTrue(i3(incomplete).isEmpty());
        var union = PolicyUnionCover.analyze(incomplete, PolicyHygiene.analyze(incomplete, 90, NOW, 250000));
        assertEquals(0, union.evaluated());
        assertEquals(Map.of(PolicyUnionCover.INCOMPLETE, 3), union.notEvaluable());
    }
    @Test void anExhaustedBudgetIsNotEvaluableNeverProven() {
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Accept", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250000);
        boolean sawNotProven = false;
        for (int work = 0; work < 400; work++) {
            var union = PolicyUnionCover.analyze(snapshot, hygiene, work, work);
            var report = PolicyInsights.analyze(snapshot, hygiene, null, Map.of(), 90, union);
            for (var insight : PolicyInsightsTest.of(report, "I3")) {
                if (insight.grade().equals("PROVEN")) assertEquals(List.of("r3", "r1", "r2"), ids(insight), "work " + work);
                else {
                    sawNotProven = true;
                    assertEquals("NOT_EVALUABLE", insight.grade());
                    assertEquals(List.of(PolicyInsights.I3_BUDGET), insight.evidence());
                    assertEquals(PolicyUnionCover.BUDGET, insight.counter().get("unionReason"));
                    assertTrue(insight.caveats().isEmpty());
                }
            }
            assertEquals(union.notProven(), report.unionCover().notProven());
        }
        assertTrue(sawNotProven);
    }
    @Test void theInsightsRouteCarriesI3AndTheAiviewProjectionKeepsItsVocabulary() {
        var mapper = new ObjectMapper();
        var names = new TopologyNamePseudonymizer(new byte[32]); var ips = new SubnetPreservingIpMasker(new byte[32]);
        var query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);
        var snapshot = snapshot(cpSection("s1", null, cp("r1", 1, "Accept", "lo", "any"), cp("r2", 2, "Drop", "hi", "any"), cp("r3", 3, "Accept", "net", "any")));
        JsonNode body = mapper.valueToTree(query.insights(snapshot, 0, "I3", "", 90).body());
        assertEquals(1, body.path("total").asInt());
        assertEquals(1, body.path("unionCover").path("covered").asInt());
        JsonNode masked = mapper.valueToTree(PolicyPrivacy.mask(mapper.convertValue(query.insights(snapshot, 0, "I3", "", 90).body(), Map.class), "", names, ips));
        var row = masked.path("insights").get(0);
        assertEquals("I3", row.path("insightType").asText());
        assertEquals(PolicyInsights.I3_DIFFERENT, row.path("evidence").get(0).asText());
        assertEquals(PolicyUnionCover.DIFFERENT, row.path("counter").path("coverAction").asText());
        assertEquals(2, row.path("counter").path("coveringRules").asInt());
        assertEquals(3, row.path("rules").size());
        assertFalse(masked.toString().contains("OBJ-RULE-"), "rule names are pseudonymised for aiview");
        var detail = query.insightDetail(snapshot, body.path("insights").get(0).path("insightId").asText(), 90).orElseThrow();
        assertEquals(3, ((List<?>) detail.body().get("rules")).size());
    }

    // ---- Palo Alto shared test fixtures & brute-force oracle helpers ----

    static final String PAN_ID = "pan-snap-01";
    static final String ZA = "ZONE-ALPHA-01", ZB = "ZONE-BRAVO-02", ZC = "ZONE-CHARLIE-03", Z_OUT = "ZONE-OUTSIDE";
    static final List<String> PAN_WORLD_EXT = List.of(ZA, ZB, ZC, Z_OUT);
    static final Map<String, Set<Integer>> PAN_ADDR_POINTS = new LinkedHashMap<>();
    static final Map<String, Set<List<Integer>>> PAN_SVC_POINTS = new LinkedHashMap<>();
    static final Map<String, PolicyObject> PAN_OBJECTS = buildPanObjects();

    private static Map<String, PolicyObject> buildPanObjects() {
        Map<String, PolicyObject> m = new LinkedHashMap<>();
        m.put("any", object("any", "any"));
        PAN_ADDR_POINTS.put("any", Set.of(-1, 0, 1, 2, 3, 10));
        for (int h = 0; h < 4; h++) {
            m.put("h" + h, object("h" + h, "address", "ip-netmask: 192.0.2." + h + "/32"));
            PAN_ADDR_POINTS.put("h" + h, Set.of(h));
        }
        m.put("lo", object("lo", "address", "ip-netmask: 192.0.2.0/31"));
        PAN_ADDR_POINTS.put("lo", Set.of(0, 1));
        m.put("hi", object("hi", "address", "ip-netmask: 192.0.2.2/31"));
        PAN_ADDR_POINTS.put("hi", Set.of(2, 3));
        m.put("net", object("net", "address", "ip-netmask: 192.0.2.0/30"));
        PAN_ADDR_POINTS.put("net", Set.of(0, 1, 2, 3));
        m.put("other", object("other", "address", "ip-netmask: 198.51.100.0/30"));
        PAN_ADDR_POINTS.put("other", Set.of(10));
        m.put("ag0", new PolicyObject("ag0", "OBJ-GROUP-00", "address-group", List.of("h1", "h2"), List.of(), "RESOLVED"));
        PAN_ADDR_POINTS.put("ag0", Set.of(1, 2));

        Set<List<Integer>> allSvc = new LinkedHashSet<>();
        allSvc.add(List.of(-1, -1));
        for (int sp : List.of(0, 1)) for (int dp : List.of(1, 2)) allSvc.add(List.of(sp, dp));
        PAN_SVC_POINTS.put("any", Collections.unmodifiableSet(allSvc));

        m.put("svc-p1", object("svc-p1", "service", "protocol/tcp/port: 1"));
        PAN_SVC_POINTS.put("svc-p1", Set.of(List.of(0, 1), List.of(1, 1)));
        m.put("svc-p2", object("svc-p2", "service", "protocol/tcp/port: 2"));
        PAN_SVC_POINTS.put("svc-p2", Set.of(List.of(0, 2), List.of(1, 2)));
        m.put("svc-p12", object("svc-p12", "service", "protocol/tcp/port: 1-2"));
        PAN_SVC_POINTS.put("svc-p12", Set.of(List.of(0, 1), List.of(1, 1), List.of(0, 2), List.of(1, 2)));
        m.put("svc-p1-s-lo", object("svc-p1-s-lo", "service", "protocol/tcp/port: 1", "protocol/tcp/source-port: 0-1023"));
        PAN_SVC_POINTS.put("svc-p1-s-lo", Set.of(List.of(0, 1)));
        m.put("svc-p1-s-hi", object("svc-p1-s-hi", "service", "protocol/tcp/port: 1", "protocol/tcp/source-port: 1024-65535"));
        PAN_SVC_POINTS.put("svc-p1-s-hi", Set.of(List.of(1, 1)));
        m.put("svc-p12-s-hi", object("svc-p12-s-hi", "service", "protocol/tcp/port: 1-2", "protocol/tcp/source-port: 1024-65535"));
        PAN_SVC_POINTS.put("svc-p12-s-hi", Set.of(List.of(1, 1), List.of(1, 2)));

        String appDefault = ref(PAN_ID, "service", "application-default");
        m.put(appDefault, new PolicyObject(appDefault, "application-default", "service", List.of(), List.of(), "RESOLVED"));
        String appAlpha = ref(PAN_ID, "application", "APP-ALPHA-01");
        m.put(appAlpha, new PolicyObject(appAlpha, "APP-ALPHA-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        m.put("sched-1", new PolicyObject("sched-1", "OBJ-SCHED-01", "schedule", List.of(), List.of(), "RESOLVED"));
        return Collections.unmodifiableMap(m);
    }

    static String panAppDefaultRef() { return ref(PAN_ID, "service", "application-default"); }
    static String panContentAppRef() { return ref(PAN_ID, "application", "APP-ALPHA-01"); }

    static Section panSection(String source, String phase, Rule... rules) {
        String name = switch (phase) {
            case "pre-rulebase" -> "Pre rules";
            case "post-rulebase" -> "Post rules";
            default -> PAN_LOCAL_RULES;
        };
        return new Section(ref(PAN_ID, source, phase), name, source, null, List.of(rules));
    }

    static PolicySnapshot panSnapshot(Section... sections) {
        var meta = new Metadata(PAN_ID, "mgr-pan", "MGR-BRAVO-01", "PAN", "dg-tango-01", "DG-TANGO-01", "POL-ALPHA-01",
            NOW.toString(), "a1", List.of());
        return new PolicySnapshot(meta, List.of(sections), PAN_OBJECTS);
    }

    static Rule panRule(String id, int number, String action, List<String> from, List<String> to,
            String src, String dst, String svc, Map<String, List<String>> extraOverrides) {
        return panRuleCells(id, number, true, action, "start=no, end=yes", from, to,
            List.of(src), List.of(dst), List.of(svc), List.of("any"), extraOverrides);
    }

    static Rule panRuleCells(String id, int number, Boolean enabled, String action, String log,
            List<String> from, List<String> to, List<String> src, List<String> dst, List<String> svc, List<String> app,
            Map<String, List<String>> extraOverrides) {
        Map<String, List<String>> extras = new LinkedHashMap<>();
        extras.put("from", from);
        extras.put("to", to);
        extras.putAll(extraOverrides);
        return new Rule(id, "uuid-" + id, number, "OBJ-RULE-" + id, enabled,
            new Cell(src, false), new Cell(dst, false), new Cell(svc, false), new Cell(app, false),
            action, log, "COMMENT-" + id, extras);
    }

    static PolicySnapshot unsupportedVendorSnapshot(String vendor) {
        String sectionSource = "ASA".equals(vendor) ? "ASA access-group" : "FGT firewall policy";
        String action = "ASA".equals(vendor) ? "permit" : "accept";
        Map<String, List<String>> extras = Map.of("from", List.of("IF-INSIDE"), "to", List.of("IF-OUTSIDE"));
        Rule r1 = new Rule("r1", "uuid-r1", 1, "OBJ-RULE-r1", true, cell("lo"), cell("any"), cell("any"), new Cell(List.of(), false), action, "Log", "", extras);
        Rule r2 = new Rule("r2", "uuid-r2", 2, "OBJ-RULE-r2", true, cell("net"), cell("any"), cell("any"), new Cell(List.of(), false), action, "Log", "", extras);
        var meta = new Metadata("unsup-" + vendor, "src-1", "FW-TANGO-04", vendor, "ctr-1", "DOM-TANGO-01", "POL-ALPHA-01", NOW.toString(), "a1", List.of());
        return new PolicySnapshot(meta, List.of(new Section("s1", "OBJ-SECTION-01", sectionSource, null, List.of(r1, r2))), PAN_OBJECTS);
    }

    record PanPacket(String fromZone, String toZone, int src, int dst, int sport, int dport) {}

    enum PanKind { READABLE, APP_DEFAULT, CONTENT_APP, USER, SCHEDULE, TARGETED, INTRAZONE, MIXED_ZONE, UNRESOLVED, DISABLED }

    record PanSynthetic(Rule rule, PanKind kind, int sectionIndex) {
        boolean enabled() { return kind != PanKind.DISABLED; }
        boolean readable() { return kind == PanKind.READABLE; }
        boolean anchor() { return enabled() && !readable(); }
    }

    static Set<Integer> expandAddrCell(Cell cell) {
        Set<Integer> out = new LinkedHashSet<>();
        for (String ref : cell.refs()) out.addAll(PAN_ADDR_POINTS.getOrDefault(ref, Set.of(0)));
        return out;
    }

    static Set<List<Integer>> expandSvcCell(Cell cell) {
        Set<List<Integer>> out = new LinkedHashSet<>();
        for (String ref : cell.refs()) out.addAll(PAN_SVC_POINTS.getOrDefault(ref, Set.of(List.of(0, 1))));
        return out;
    }

    static Set<String> expandZones(List<String> zones, Collection<String> world) {
        if (zones.equals(List.of("any"))) return new LinkedHashSet<>(world);
        Set<String> out = new LinkedHashSet<>();
        for (String z : zones) if (world.contains(z)) out.add(z);
        return out;
    }

    static Set<PanPacket> panRulePackets(Rule rule, Collection<String> fromWorld, Collection<String> toWorld) {
        Set<String> fz = expandZones(rule.extras().get("from"), fromWorld);
        Set<String> tz = expandZones(rule.extras().get("to"), toWorld);
        Set<Integer> srcs = expandAddrCell(rule.source());
        Set<Integer> dsts = expandAddrCell(rule.destination());
        Set<List<Integer>> svcs = expandSvcCell(rule.service());
        Set<PanPacket> out = new HashSet<>();
        for (String f : fz) for (String t : tz) for (int s : srcs) for (int d : dsts)
            for (List<Integer> spDp : svcs) out.add(new PanPacket(f, t, s, d, spDp.get(0), spDp.get(1)));
        return out;
    }

    record RealizedPan(PolicySnapshot snapshot, List<PanSynthetic> items) {}

    static List<PanSynthetic> randomPanRulebase(Random random, boolean coarse, boolean multiSection) {
        List<String> srcPool = coarse ? List.of("lo", "hi", "net", "ag0")
            : List.of("any", "h0", "h1", "h2", "h3", "lo", "hi", "net", "other", "ag0");
        List<String> dstPool = coarse ? List.of("any", "any", "any", "lo")
            : List.of("any", "h0", "h1", "h2", "h3", "lo", "hi", "net", "other", "ag0");
        List<String> svcPool = coarse ? List.of("svc-p1", "svc-p1", "svc-p1-s-lo", "svc-p1-s-hi", "svc-p12")
            : List.of("any", "svc-p1", "svc-p2", "svc-p12", "svc-p1-s-lo", "svc-p1-s-hi", "svc-p12-s-hi");
        List<List<String>> fromPool = coarse ? List.of(List.of(ZA), List.of(ZA), List.of(ZB), List.of(ZA, ZB), List.of("any"))
            : List.of(List.of(ZA), List.of(ZB), List.of(ZA, ZB), List.of("any"));
        List<List<String>> toPool = coarse ? List.of(List.of(ZB), List.of(ZB), List.of(ZB), List.of("any"))
            : List.of(List.of(ZA), List.of(ZB), List.of(ZA, ZB), List.of("any"));
        List<String> logPool = List.of(
            "start=no, end=yes", "start=no, end=yes", "start=no, end=yes", "start=no, end=yes",
            "start=yes, end=yes", "start=UNKNOWN, end=yes");
        int n = 6 + random.nextInt(7);
        List<PanSynthetic> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int sectionIdx = !multiSection ? 0 : Math.min(3, (i * 4) / n);
            int roll = random.nextInt(coarse ? 45 : 28);
            PanKind kind = switch (roll) {
                case 0 -> PanKind.DISABLED;
                case 1 -> PanKind.APP_DEFAULT;
                case 2 -> PanKind.CONTENT_APP;
                case 3 -> PanKind.USER;
                case 4 -> PanKind.SCHEDULE;
                case 5 -> PanKind.TARGETED;
                case 6 -> PanKind.INTRAZONE;
                case 7 -> PanKind.MIXED_ZONE;
                case 8 -> PanKind.UNRESOLVED;
                default -> PanKind.READABLE;
            };
            List<String> from = kind == PanKind.MIXED_ZONE ? List.of("any", ZA) : fromPool.get(random.nextInt(fromPool.size()));
            List<String> to = kind == PanKind.INTRAZONE ? from : toPool.get(random.nextInt(toPool.size()));
            String src = kind == PanKind.UNRESOLVED ? "missing" : srcPool.get(random.nextInt(srcPool.size()));
            String dst = dstPool.get(random.nextInt(dstPool.size()));
            String svc = kind == PanKind.APP_DEFAULT ? panAppDefaultRef() : svcPool.get(random.nextInt(svcPool.size()));
            String app = kind == PanKind.CONTENT_APP ? panContentAppRef()
                : kind == PanKind.APP_DEFAULT && random.nextBoolean() ? panContentAppRef() : "any";
            Map<String, List<String>> extras = new LinkedHashMap<>();
            if (kind == PanKind.USER) extras.put("source-user", List.of("OBJ-USER-01"));
            else if (kind == PanKind.SCHEDULE) extras.put("schedule", List.of("sched-1"));
            else if (kind == PanKind.TARGETED) extras.put("target-selectors", List.of("SERIAL-ALPHA-01"));
            else if (kind == PanKind.INTRAZONE) extras.put("rule-type", List.of("intrazone"));
            else if (random.nextInt(6) == 0) extras.put("target/negate", List.of("no")); // untargeted: stays READABLE
            int profRoll = random.nextInt(coarse ? 8 : 6);
            if (profRoll > 0) extras.put("profile-setting/group", List.of(profRoll == 1 ? "PROF-BRAVO" : "PROF-ALPHA"));
            if (random.nextInt(coarse ? 10 : 6) == 0) extras.put("log-setting", List.of("LOG-FWD-ALPHA"));
            String action = random.nextInt(4) == 0 ? "deny" : "allow";
            String log = logPool.get(random.nextInt(logPool.size()));
            if (coarse && kind == PanKind.READABLE && !out.isEmpty() && random.nextInt(3) == 0) {
                PanSynthetic prev = out.get(Math.max(0, out.size() - (random.nextInt(3) == 0 && out.size() >= 2 ? 2 : 1)));
                if (prev.readable() && prev.sectionIndex() == sectionIdx) {
                    Rule pr = prev.rule();
                    from = pr.extras().get("from");
                    to = pr.extras().get("to");
                    action = pr.action();
                    log = "start=no, end=yes";
                    extras.clear();
                    for (var entry : pr.extras().entrySet()) {
                        if (!entry.getKey().equals("from") && !entry.getKey().equals("to")) extras.put(entry.getKey(), entry.getValue());
                    }
                    int vary = random.nextInt(3);
                    src = vary == 0 ? srcPool.get(random.nextInt(srcPool.size())) : pr.source().refs().get(0);
                    dst = vary == 1 ? dstPool.get(random.nextInt(dstPool.size())) : pr.destination().refs().get(0);
                    svc = vary == 2 ? svcPool.get(random.nextInt(svcPool.size())) : pr.service().refs().get(0);
                }
            }
            Rule r = panRuleCells("r" + i, i + 1, kind != PanKind.DISABLED, action, log,
                from, to, List.of(src), List.of(dst), List.of(svc), List.of(app), extras);
            out.add(new PanSynthetic(r, kind, sectionIdx));
        }
        return out;
    }

    static RealizedPan panRealize(List<PanSynthetic> raw) {
        String[][] defs = {
            {"Shared", "pre-rulebase"},
            {"DG-TANGO-01", "pre-rulebase"},
            {"DG-TANGO-01", "post-rulebase"},
            {"Shared", "post-rulebase"}
        };
        List<Section> sections = new ArrayList<>();
        Map<String, Rule> numberedById = new HashMap<>();
        for (int s = 0; s < defs.length; s++) {
            final int idx = s;
            List<Rule> inSec = new ArrayList<>();
            int num = 1;
            for (PanSynthetic item : raw) {
                if (item.sectionIndex() != idx) continue;
                Rule r = item.rule();
                Rule numbered = new Rule(r.id(), r.uuid(), num++, r.name(), r.enabled(), r.source(), r.destination(),
                    r.service(), r.application(), r.action(), r.log(), r.comment(), r.extras());
                inSec.add(numbered);
                numberedById.put(r.id(), numbered);
            }
            if (!inSec.isEmpty()) sections.add(panSection(defs[s][0], defs[s][1], inSec.toArray(Rule[]::new)));
        }
        List<PanSynthetic> items = raw.stream()
            .map(it -> new PanSynthetic(numberedById.get(it.rule().id()), it.kind(), it.sectionIndex()))
            .toList();
        return new RealizedPan(panSnapshot(sections.toArray(Section[]::new)), items);
    }

    @Test void paloAltoZoneSupersetsMultiZoneSplitsSourcePortCoversPhaseChainsAndAnchors() {
        // 1. Zone superset union cover: r1 ([ZA, ZB], lo) + r2 ([ZA], hi) -> covers r3 ([ZA], net).
        var superset = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase",
            panRule("r1", 1, "allow", List.of(ZA, ZB), List.of(ZC), "lo", "any", "svc-p1", Map.of()),
            panRule("r2", 2, "allow", List.of(ZA), List.of("any"), "hi", "any", "svc-p1", Map.of()),
            panRule("r3", 3, "allow", List.of(ZA), List.of(ZC), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r3", "r1", "r2"), ids(i3(superset).get(0)));

        // 2. Multi-zone split union cover: r1 ([ZA], any, net) + r2 ([ZB], any, net) -> covers r3 ([ZA, ZB], any, net).
        var multiZone = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase",
            panRule("r1", 1, "allow", List.of(ZA), List.of("any"), "net", "any", "svc-p1", Map.of()),
            panRule("r2", 2, "deny", List.of(ZB), List.of("any"), "net", "any", "svc-p1", Map.of()),
            panRule("r3", 3, "allow", List.of(ZA, ZB), List.of("any"), "net", "any", "svc-p1", Map.of())));
        var mzInsight = i3(multiZone).get(0);
        assertEquals(List.of("r3", "r1", "r2"), ids(mzInsight));
        assertEquals(PolicyUnionCover.DIFFERENT, mzInsight.counter().get("coverAction"));

        // 3. Uncollected zone universe fail-closed: named zones [ZA] + [ZB] never cover subject zone "any".
        var uncollectedZones = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase",
            panRule("r1", 1, "allow", List.of(ZA), List.of(ZC), "net", "any", "svc-p1", Map.of()),
            panRule("r2", 2, "allow", List.of(ZB), List.of(ZC), "net", "any", "svc-p1", Map.of()),
            panRule("r3", 3, "allow", List.of("any"), List.of(ZC), "net", "any", "svc-p1", Map.of())));
        assertTrue(i3(uncollectedZones).isEmpty());

        // 4. Source-port family union cover: 0..1023 + 1024..65535 covers unrestricted tcp port 1.
        var sourcePorts = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase",
            panRule("r1", 1, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1-s-lo", Map.of()),
            panRule("r2", 2, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1-s-hi", Map.of()),
            panRule("r3", 3, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of())));
        assertEquals(List.of("r3", "r1", "r2"), ids(i3(sourcePorts).get(0)));

        // 5. Panorama chain (Shared pre -> DG pre -> Local -> DG post -> Shared post) + ignored NAT/default sections.
        Section nat = new Section(ref(PAN_ID, "DG-TANGO-01", "pre-rulebase", "nat"), PAN_PRE_NAT, "DG-TANGO-01", null,
            List.of(panRule("nat1", 1, "source-nat", List.of(ZA), List.of(ZB), "any", "any", "any", Map.of())));
        Section def = new Section(ref(PAN_ID, "Shared", "post-rulebase", "default-security-rules"), PAN_POST_DEFAULT, "Shared", null,
            List.of(panRule("def1", 1, "deny", List.of("any"), List.of("any"), "any", "any", "any", Map.of("rule-type", List.of("interzone")))));
        var chain = panSnapshot(
            panSection("Shared", "pre-rulebase", panRule("r1", 1, "allow", List.of(ZA), List.of(ZB), "lo", "any", "svc-p1", Map.of())),
            panSection("DG-TANGO-01", "rulebase", panRule("r2", 1, "allow", List.of(ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of())),
            panSection("Shared", "post-rulebase", panRule("r3", 1, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of())),
            nat, def);
        assertEquals(List.of("r3", "r1", "r2"), ids(i3(chain).get(0)));

        // Out-of-order PAN phases (post-rulebase before pre-rulebase) fail closed to ORDER_UNPROVEN.
        var outOfOrder = panSnapshot(
            panSection("DG-TANGO-01", "post-rulebase", panRule("r1", 1, "allow", List.of(ZA), List.of(ZB), "lo", "any", "svc-p1", Map.of())),
            panSection("DG-TANGO-01", "pre-rulebase",
                panRule("r2", 1, "allow", List.of(ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of()),
                panRule("r3", 2, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of())));
        var outUnion = PolicyUnionCover.analyze(outOfOrder, PolicyHygiene.analyze(outOfOrder, 90, NOW, 250_000));
        assertEquals(0, outUnion.covered());
        assertEquals(Map.of(PolicyUnionCover.ORDER_UNPROVEN, 2), outUnion.notEvaluable());

        // 6. PAN anchors (application-default, content App-ID, user, schedule, targeted, intrazone, mixed zones) fail closed.
        Rule appDef = panRule("ad", 2, "allow", List.of(ZA), List.of(ZB), "hi", "any", panAppDefaultRef(), Map.of());
        Rule contentApp = panRuleCells("ca", 2, true, "allow", "start=no, end=yes", List.of(ZA), List.of(ZB),
            List.of("hi"), List.of("any"), List.of("svc-p1"), List.of(panContentAppRef()), Map.of());
        Rule userRule = panRule("ur", 2, "allow", List.of(ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of("source-user", List.of("OBJ-USER-01")));
        Rule schedRule = panRule("sr", 2, "allow", List.of(ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of("schedule", List.of("sched-1")));
        Rule targeted = panRule("tr", 2, "allow", List.of(ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of("target-selectors", List.of("SERIAL-ALPHA-01")));
        Rule intrazone = panRule("iz", 2, "allow", List.of(ZA), List.of(ZA), "hi", "any", "svc-p1", Map.of("rule-type", List.of("intrazone")));
        Rule mixedZones = panRule("mz", 2, "allow", List.of("any", ZA), List.of(ZB), "hi", "any", "svc-p1", Map.of());
        for (Rule anchor : List.of(appDef, contentApp, userRule, schedRule, targeted, intrazone, mixedZones)) {
            var withAnchor = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase",
                panRule("r1", 1, "allow", List.of(ZA), List.of(ZB), "lo", "any", "svc-p1", Map.of()),
                anchor,
                panRule("r3", 3, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of())));
            assertTrue(i3(withAnchor).isEmpty(), "anchor must never contribute to cover: " + anchor.id());
        }
        // Other unmapped vendors (ASA, FGT) stay UNSUPPORTED_VENDOR.
        for (String v : List.of("ASA", "FGT")) {
            var unsup = unsupportedVendorSnapshot(v);
            var res = PolicyUnionCover.analyze(unsup, PolicyHygiene.analyze(unsup, 90, NOW, 250_000));
            assertEquals(0, res.evaluated(), v);
            assertEquals(Set.of(PolicyUnionCover.UNSUPPORTED_VENDOR), res.notEvaluable().keySet(), v);
        }
    }

    @Test void paloAltoI3EqualsBruteForceUnionCoverOnRandomRulebases() {
        Random random = new Random(2026112601L);
        int unionCovers = 0, singleCovers = 0, budgetStops = 0;
        for (int trial = 0; trial < 480; trial++) {
            var realized = panRealize(randomPanRulebase(random, trial % 2 == 0, trial % 3 != 0));
            List<PanSynthetic> items = realized.items();
            PolicySnapshot snapshot = realized.snapshot();
            var hygiene = PolicyHygiene.analyze(snapshot, 90, NOW, 250_000);
            var union = PolicyUnionCover.analyze(snapshot, hygiene);

            Map<String, Set<PanPacket>> pts = new HashMap<>();
            for (PanSynthetic item : items) {
                if (item.readable()) pts.put(item.rule().id(), panRulePackets(item.rule(), PAN_WORLD_EXT, PAN_WORLD_EXT));
            }
            Set<String> expectedI3 = new TreeSet<>(), expectedSingle = new TreeSet<>();
            for (int k = 0; k < items.size(); k++) {
                PanSynthetic sub = items.get(k);
                if (!sub.readable()) continue;
                Set<PanPacket> subPts = pts.get(sub.rule().id());
                Set<PanPacket> acc = new HashSet<>();
                boolean single = false;
                for (int j = 0; j < k; j++) {
                    PanSynthetic prior = items.get(j);
                    if (!prior.readable()) continue;
                    Set<PanPacket> priorPts = pts.get(prior.rule().id());
                    acc.addAll(priorPts);
                    if (priorPts.containsAll(subPts)) single = true;
                }
                if (single) expectedSingle.add(sub.rule().id());
                else if (acc.containsAll(subPts)) expectedI3.add(sub.rule().id());
            }

            Set<String> actualI3 = new TreeSet<>();
            for (var entry : union.rules().entrySet()) {
                assertEquals(PolicyUnionCover.COVERED, entry.getValue().status(), "trial " + trial);
                actualI3.add(entry.getKey());
                Set<PanPacket> coverUnion = new HashSet<>();
                for (Rule c : entry.getValue().covering()) coverUnion.addAll(pts.get(c.id()));
                assertTrue(coverUnion.containsAll(pts.get(entry.getKey())), "covering set must cover subject in trial " + trial);
            }
            assertEquals(expectedI3, actualI3, "PAN I3 mismatch in trial " + trial);
            for (String id : expectedSingle) {
                assertTrue(Set.of("REDUNDANT", "CONFLICT").contains(hygiene.rules().get(id).shadowStatus()), "single cover in trial " + trial + ": " + id);
            }
            unionCovers += expectedI3.size();
            singleCovers += expectedSingle.size();

            var limited = PolicyUnionCover.analyze(snapshot, hygiene, 20 + random.nextInt(120), 10 + random.nextInt(60));
            for (var e : limited.rules().entrySet()) {
                if (PolicyUnionCover.NOT_PROVEN.equals(e.getValue().status())) {
                    budgetStops++;
                    assertEquals(PolicyUnionCover.BUDGET, e.getValue().reason());
                } else {
                    assertTrue(expectedI3.contains(e.getKey()), "limited budget produced false PAN I3 in trial " + trial);
                }
            }
        }
        assertTrue(unionCovers >= 25, "PAN union covers exercised: " + unionCovers);
        assertTrue(singleCovers >= 25, "PAN single covers exercised: " + singleCovers);
        assertTrue(budgetStops >= 15, "PAN budget stops exercised: " + budgetStops);
    }

    @Test void paloAltoBudgetAppliesToDisjointBucketsAndSingleSliceSubjects() {
        List<Rule> rules = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            rules.add(panRule("d" + i, i + 1, "allow", List.of("ZONE-DISJOINT-" + i), List.of(ZC), "lo", "any", "svc-p1", Map.of()));
        }
        rules.add(panRule("sub", 13, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of()));
        var snap = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase", rules.toArray(Rule[]::new)));
        var hygiene = PolicyHygiene.analyze(snap, 90, NOW, 250_000);
        var zero = PolicyUnionCover.analyze(snap, hygiene, 0, 0);
        assertEquals(PolicyUnionCover.NOT_PROVEN, zero.rules().get("sub").status());
        assertEquals(PolicyUnionCover.BUDGET, zero.rules().get("sub").reason());
        // With many buckets matching sliceFrom (ZA) but differing on sliceTo, budget must be charged per skipped bucket.
        List<Rule> skipped = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            skipped.add(panRule("s" + i, i + 1, "allow", List.of(ZA), List.of("ZONE-OTHER-" + i), "lo", "any", "svc-p1", Map.of()));
        }
        skipped.add(panRule("sub", 21, "allow", List.of(ZA), List.of(ZB), "net", "any", "svc-p1", Map.of()));
        var skipSnap = panSnapshot(panSection("DG-TANGO-01", "pre-rulebase", skipped.toArray(Rule[]::new)));
        var skipHygiene = PolicyHygiene.analyze(skipSnap, 90, NOW, 250_000);
        var tight = PolicyUnionCover.analyze(skipSnap, skipHygiene, 10_000, 1);
        assertEquals(PolicyUnionCover.NOT_PROVEN, tight.rules().get("sub").status());
        assertEquals(PolicyUnionCover.BUDGET, tight.rules().get("sub").reason());
    }
}
