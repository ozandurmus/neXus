package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Zone matrix delivery step 7b: Palo Alto applications by name ({@code docs/design/ZONE_MATRIX_CONTRACT.md}, FROZEN
 * rev 2, sections 4.2, 4.3, 5.4 and 10.1 item 7). An {@code app:pan:<name>} cell entry decides COMPLIANT only for a
 * Palo Alto {@code application-default} rule whose every application name is listed by exact string; every other case
 * stays NOT_EVALUABLE with a reason from the fixed vocabulary, and a DENY_ALL cell stays a violation.
 *
 * <p>Synthetic zones, pseudonyms and RFC 5737 documentation addresses only.</p>
 */
class ZoneMatrixPanApplicationsTest {
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PAN_ID = "pol-pan-apps";
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final String APP_DEFAULT = PolicySnapshot.ref(PAN_ID, "service", "application-default");

    private record Matrix(String versionId, int versionNumber, ZoneMatrixContent content) {}

    /**
     * Z-USER 192.0.2.0/25, Z-DMZ 192.0.2.128/25, Z-APP 198.51.100.0/24, Z-DB 203.0.113.0/24.
     * Z-USER -> Z-APP lists two applications by name and one port; Z-USER -> Z-DMZ lists ports only (through a set);
     * Z-USER -> Z-DB stays the default DENY_ALL.
     */
    private static Matrix matrix() {
        var store = new ZoneMatrixServiceTest.Memory();
        PolicyCatalog catalog = new PolicyCatalog() {
            @Override public List<Map<String, Object>> catalogViews() { return List.of(); }
            @Override public Optional<PolicySnapshot> find(String policyId) { return Optional.empty(); }
        };
        byte[] key = "synthetic-zone-matrix-step7b-key".getBytes(StandardCharsets.UTF_8);
        var service = new ZoneMatrixService(store,
                new Principals() {
                    @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
                    @Override public boolean isRoot(String actor) { return false; }
                },
                MAPPER, Clock.fixed(NOW, ZoneOffset.UTC), catalog, null,
                new TopologyNamePseudonymizer(key), new SubnetPreservingIpMasker(key));
        String zUser = zone(service, store, "Z-USER", "USER", "192.0.2.0/25");
        String zDmz = zone(service, store, "Z-DMZ", "DMZ", "192.0.2.128/25");
        String zApp = zone(service, store, "Z-APP", "APPLICATION", "198.51.100.0/24");
        zone(service, store, "Z-DB", "DATABASE", "203.0.113.0/24");
        service.saveServiceSet("security-1", store.draft().orElseThrow().revision(), "web-ports", List.of("tcp/443", "tcp/80"));
        service.setCell("security-1", store.draft().orElseThrow().revision(), new CellInput(zUser, zApp, "ALLOW_LISTED",
                List.of("app:pan:web-browsing", "app:pan:ssl", "tcp/8443"), "HIGH", "synthetic web tier", ""));
        service.setCell("security-1", store.draft().orElseThrow().revision(), new CellInput(zUser, zDmz, "ALLOW_LISTED",
                List.of("set:web-ports"), "MEDIUM", "synthetic dmz", ""));
        var draft = store.draft().orElseThrow();
        var pub = service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        return new Matrix((String) pub.get("version_id"), ((Number) pub.get("number")).intValue(),
                ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER));
    }

    private static String zone(ZoneMatrixService service, ZoneMatrixServiceTest.Memory store, String name, String kind, String block) {
        Long rev = store.draft().map(d -> d.revision()).orElse(null);
        return (String) service.createZone("security-1", rev, new ZoneInput(name, "Synthetic " + name, kind, List.of(block))).get("zone_id");
    }

    private static String app(String name) { return PolicySnapshot.ref(PAN_ID, "application", name); }

    /** Content-provided (predefined App-ID) applications as the mapper stores them, plus groups, a filter and an alias. */
    private static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> o = new LinkedHashMap<>();
        o.put("obj-any", new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED"));
        o.put("u1", new PolicyObject("u1", "USER-HOST", "address", List.of(), List.of("ip-netmask: 192.0.2.10/32"), "RESOLVED"));
        o.put("m1", new PolicyObject("m1", "DMZ-HOST", "address", List.of(), List.of("ip-netmask: 192.0.2.200/32"), "RESOLVED"));
        o.put("a1", new PolicyObject("a1", "APP-HOST", "address", List.of(), List.of("ip-netmask: 198.51.100.10/32"), "RESOLVED"));
        o.put("d1", new PolicyObject("d1", "DB-HOST", "address", List.of(), List.of("ip-netmask: 203.0.113.10/32"), "RESOLVED"));
        o.put("https", new PolicyObject("https", "HTTPS", "service", List.of(), List.of("protocol/tcp/port: 443"), "RESOLVED"));
        o.put("alt-https", new PolicyObject("alt-https", "ALT-HTTPS", "service", List.of(), List.of("protocol/tcp/port: 8443"), "RESOLVED"));
        o.put(APP_DEFAULT, new PolicyObject(APP_DEFAULT, "application-default", "service", List.of(), List.of(), "RESOLVED"));
        for (String name : List.of("web-browsing", "ssl", "Web-Browsing", "ftp"))
            o.put(app(name), new PolicyObject(app(name), name, "unresolved", List.of(), List.of(), "UNRESOLVED"));
        o.put(app("grp-web"), new PolicyObject(app("grp-web"), "grp-web", "application-group",
                List.of(app("web-browsing"), app("ssl")), List.of(), "RESOLVED"));
        o.put(app("grp-mixed"), new PolicyObject(app("grp-mixed"), "grp-mixed", "application-group",
                List.of(app("web-browsing"), app("ftp")), List.of(), "RESOLVED"));
        o.put(app("grp-loop-a"), new PolicyObject(app("grp-loop-a"), "grp-loop-a", "application-group",
                List.of(app("grp-loop-b")), List.of(), "RESOLVED"));
        o.put(app("grp-loop-b"), new PolicyObject(app("grp-loop-b"), "grp-loop-b", "application-group",
                List.of(app("grp-loop-a")), List.of(), "RESOLVED"));
        o.put(app("grp-empty"), new PolicyObject(app("grp-empty"), "grp-empty", "application-group", List.of(), List.of(), "RESOLVED"));
        o.put(app("flt-web"), new PolicyObject(app("flt-web"), "flt-web", "application-filter", List.of(),
                List.of("category: general-internet"), "DYNAMIC"));
        // A custom application stored under a reference that is not the one minted for its name: never read as that name.
        o.put("app-alias", new PolicyObject("app-alias", "web-browsing", "application", List.of(), List.of(), "RESOLVED"));
        return o;
    }

    private static Rule panRule(String id, int number, String dst, String service, String... apps) {
        return new Rule(id, "uid-" + id, number, "Synthetic-" + id, true, new Cell(List.of("u1"), false),
                new Cell(List.of(dst), false), new Cell(List.of(service), false), new Cell(List.of(apps), false),
                "allow", "Log", "", Map.of());
    }

    private static EvaluationResult evaluatePan(Matrix m, Rule... rules) {
        Section pre = new Section(PolicySnapshot.ref(PAN_ID, "PAN device-group", "pre-rulebase"), "Pre rules",
                "PAN device-group", null, List.of(rules));
        PolicySnapshot snapshot = new PolicySnapshot(meta(PAN_ID, "PAN"), List.of(pre), objects());
        return ZoneMatrixEvaluator.evaluate(snapshot, m.versionId(), m.versionNumber(), m.content(), NOW);
    }

    private static Metadata meta(String id, String vendor) {
        return new Metadata(id, "src-1", "FW-TANGO-04", vendor, "dom-1", "DOM-BRAVO-02", "Synthetic-" + vendor,
                "2026-11-25T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static RuleEvaluation single(Matrix m, Rule rule) {
        EvaluationResult result = evaluatePan(m, rule);
        assertEquals(1, result.rules().size());
        return result.rules().get(0);
    }

    private static void assertNotEvaluable(RuleEvaluation r, String reason) {
        assertEquals(OUTCOME_NOT_EVALUABLE, r.outcome(), r.ruleName());
        assertEquals(reason, r.reason(), r.ruleName());
        assertTrue(r.violatedCells().isEmpty(), r.ruleName());
    }

    @Test
    void app_singleListedApplicationIsCompliant() {
        RuleEvaluation r = single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("web-browsing")));
        assertEquals(OUTCOME_COMPLIANT, r.outcome());
        assertNull(r.reason());
        assertTrue(r.undecidedParts().isEmpty());
    }

    @Test
    void app_everyListedApplicationIsCompliant() {
        assertEquals(OUTCOME_COMPLIANT, single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("web-browsing"), app("ssl"))).outcome());
    }

    @Test
    void app_listedWithDifferentCaseIsNotEvaluable() {
        // The cell lists app:pan:web-browsing; the rule names Web-Browsing. No case folding (identity law).
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("Web-Browsing"))),
                "application ports not collected");
    }

    @Test
    void app_twoApplicationsOneUnlistedIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("web-browsing"), app("ftp"))),
                "application ports not collected");
    }

    @Test
    void app_portsOnlyCellStaysNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "m1", APP_DEFAULT, app("web-browsing"))),
                "application ports not collected");
    }

    @Test
    void app_denyAllCellStaysViolation() {
        RuleEvaluation r = single(matrix(), panRule("r1", 1, "d1", APP_DEFAULT, app("web-browsing")));
        assertEquals(OUTCOME_VIOLATION, r.outcome());
        assertEquals("DENY_ALL", r.violatedCells().get(0).statement());
        assertEquals(List.of("application-default"), r.violatedCells().get(0).notAllowedServices());
    }

    @Test
    void app_groupWithEveryMemberListedIsCompliant() {
        assertEquals(OUTCOME_COMPLIANT, single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("grp-web"))).outcome());
    }

    @Test
    void app_groupWithOneMemberMissingIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("grp-mixed"))),
                "application ports not collected");
    }

    @Test
    void app_groupCycleIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("grp-loop-a"))),
                "group cycle or depth limit");
    }

    @Test
    void app_emptyGroupIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("grp-empty"))),
                "empty or dynamic group");
    }

    @Test
    void app_filterIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, app("flt-web"))), "unresolved object");
        // Listed names next to a filter do not help: the filter's members are unknown.
        assertNotEvaluable(single(matrix(), panRule("r2", 2, "a1", APP_DEFAULT, app("web-browsing"), app("flt-web"))),
                "unresolved object");
    }

    @Test
    void app_unresolvedNameIsNotEvaluable() {
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, "missing-application-reference")),
                "unresolved object");
        assertNotEvaluable(single(matrix(), panRule("r2", 2, "a1", APP_DEFAULT, "app-alias")), "unresolved object");
    }

    @Test
    void app_applicationAnyWithApplicationDefaultStaysNotEvaluable() {
        // Section 4.3 does not decide application any with application-default: unchanged.
        assertNotEvaluable(single(matrix(), panRule("r1", 1, "a1", APP_DEFAULT, "obj-any")), "application ports not collected");
    }

    @Test
    void app_explicitServiceListIsEvaluatedOnPorts() {
        Matrix m = matrix();
        // tcp/443 is not listed in Z-USER -> Z-APP (only tcp/8443 and two applications): violation on ports as before.
        RuleEvaluation listedAppWrongPort = single(m, panRule("r1", 1, "a1", "https", app("web-browsing")));
        assertEquals(OUTCOME_VIOLATION, listedAppWrongPort.outcome());
        assertEquals(List.of("tcp/443"), listedAppWrongPort.violatedCells().get(0).notAllowedServices());
        // tcp/8443 is listed; an unlisted application only narrows: compliant on ports as before.
        assertEquals(OUTCOME_COMPLIANT, single(m, panRule("r2", 2, "a1", "alt-https", app("ftp"))).outcome());
    }

    @Test
    void app_checkPointRuleIgnoresPanApplicationEntries() {
        Matrix m = matrix();
        Map<String, List<String>> layer = Map.of("layer-name", List.of("Network"));
        Rule appDefaultNamedService = new Rule("cp-1", "uid-cp-1", 1, "Synthetic-cp-1", true, new Cell(List.of("u1"), false),
                new Cell(List.of("a1"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", layer);
        Rule listedPort = new Rule("cp-2", "uid-cp-2", 2, "Synthetic-cp-2", true, new Cell(List.of("u1"), false),
                new Cell(List.of("a1"), false), new Cell(List.of("alt-https"), false), ANY_CELL, "Accept", "Log", "", layer);
        PolicySnapshot cp = new PolicySnapshot(meta("pol-cp-apps", "CP"),
                List.of(new Section("s1", "Network", "CP access layer", null, List.of(appDefaultNamedService, listedPort))), objects());
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(cp, m.versionId(), m.versionNumber(), m.content(), NOW);
        assertEquals(OUTCOME_VIOLATION, result.rules().get(0).outcome());
        assertEquals(List.of("tcp/443"), result.rules().get(0).violatedCells().get(0).notAllowedServices());
        assertEquals(OUTCOME_COMPLIANT, result.rules().get(1).outcome());
    }

    @Test
    void app_sameInputTwiceGivesEqualOutput() {
        Matrix m = matrix();
        Rule[] rules = {
                panRule("r1", 1, "a1", APP_DEFAULT, app("grp-web")),
                panRule("r2", 2, "a1", APP_DEFAULT, app("ssl"), app("ftp")),
                panRule("r3", 3, "d1", APP_DEFAULT, app("web-browsing")),
                panRule("r4", 4, "a1", APP_DEFAULT, app("grp-loop-a"))};
        EvaluationResult first = evaluatePan(m, rules);
        EvaluationResult second = evaluatePan(m, rules);
        assertEquals(first.version(), second.version());
        assertEquals(first.rules(), second.rules());
        assertEquals(first.headline(), second.headline());
        assertEquals(List.of(OUTCOME_COMPLIANT, OUTCOME_NOT_EVALUABLE, OUTCOME_VIOLATION, OUTCOME_NOT_EVALUABLE),
                first.rules().stream().map(RuleEvaluation::outcome).toList());
    }

    @Test
    void app_headlineOverUndecidedRulesNeverShowsZero() {
        EvaluationResult result = evaluatePan(matrix(),
                panRule("r1", 1, "a1", APP_DEFAULT, app("web-browsing")),
                panRule("r2", 2, "a1", APP_DEFAULT, app("ftp")));
        assertEquals(List.of(OUTCOME_COMPLIANT, OUTCOME_NOT_EVALUABLE),
                result.rules().stream().map(RuleEvaluation::outcome).toList());
        assertEquals(0, result.headline().distinctViolatingRules());
        assertFalse(result.headline().exact());
        assertEquals(1, result.headline().notEvaluableRules());
        assertFalse(result.headline().text().startsWith("0 "), result.headline().text());
    }

    @Test
    void app_entryIsEvaluableInTheVocabulary() {
        assertNull(ServiceEntry.parse("app:pan:web-browsing").notEvaluableReason());
        assertNotNull(ServiceEntry.parse("icmp/8").notEvaluableReason());
    }
}
