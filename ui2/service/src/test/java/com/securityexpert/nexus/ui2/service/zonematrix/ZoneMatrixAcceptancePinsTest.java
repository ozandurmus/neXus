package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.api.ZoneMatrixController;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Finding;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixFindingLifecycle.Transition;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Acceptance pins for ZM-25 (hygiene cover) and ZM-37 (audit and no-delete surface) of
 * {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN, revision 2, section 10.2). Synthetic principals, zone names,
 * gateway ids and RFC 5737 / RFC 3849 documentation ranges only.
 */
class ZoneMatrixAcceptancePinsTest {
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY = new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");
    private static final String POLICY_ID = "pol-zm25";
    private static final String GW_A = "gw-A";
    private static final String GW_B = "gw-B";

    private final ZoneMatrixServiceTest.Memory store = new ZoneMatrixServiceTest.Memory();
    private final ZoneMatrixService service = new ZoneMatrixService(store, new Principals() {
        @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
        @Override public boolean isRoot(String actor) { return "root-local".equals(actor); }
    }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));

    // ------------------------------------------------------------------------------------------ fixtures

    private long revision() { return store.draft().orElseThrow().revision(); }
    private Long revisionOrNull() { return store.draft().map(v -> v.revision()).orElse(null); }

    private String zone(String actor, String name, String... blocks) {
        return (String) service.createZone(actor, revisionOrNull(), new ZoneInput(name, "", null, List.of(blocks))).get("zone_id");
    }

    private record Matrix(ZoneMatrixContent content, String versionId, int number, String user, String db) {}

    /** Z-USER, Z-APP, Z-DB; the user-to-db pair is left at its default DENY_ALL, published as version 1. */
    private Matrix publishedMatrix() {
        String user = zone("security-1", "Z-USER", "192.0.2.0/25");
        zone("security-1", "Z-APP", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "203.0.113.0/24");
        var draft = store.draft().orElseThrow();
        var published = service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        return new Matrix(ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER),
                (String) published.get("version_id"), ((Number) published.get("number")).intValue(), user, db);
    }

    private static Metadata cpMeta() {
        return new Metadata(POLICY_ID, "src-1", "FW-TANGO-04", "CP", "dom-1", "DOM-BRAVO-02", "CP-ZM25",
                "2026-11-25T08:00:00Z", "art-1",
                List.of(new Target(GW_A, "FW-TANGO-04", "", "IN_SYNC"), new Target(GW_B, "FW-JULIET-06", "", "IN_SYNC")));
    }

    private static PolicyObject addr(String id, String name, String cidr) {
        return new PolicyObject(id, name, "address", List.of(), List.of("ip-netmask: " + cidr), "RESOLVED");
    }

    private static PolicyObject tcp(String id, String name, String port) {
        return new PolicyObject(id, name, "service", List.of(), List.of("protocol/tcp/port: " + port), "RESOLVED");
    }

    private static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("user", addr("user", "USER-NET", "192.0.2.0/26"));
        objects.put("db", addr("db", "DB-NET", "203.0.113.10/32"));
        objects.put("https", tcp("https", "HTTPS", "443"));
        objects.put(GW_A, new PolicyObject(GW_A, "FW-TANGO-04", "simple-gateway", List.of(), List.of(), "RESOLVED"));
        objects.put(GW_B, new PolicyObject(GW_B, "FW-JULIET-06", "simple-gateway", List.of(), List.of(), "RESOLVED"));
        return objects;
    }

    /** A Check Point access-layer rule from user to db on https, installed on the given gateway ids. */
    private static Rule cpRule(String id, int number, String action, String... installOn) {
        return new Rule(id, "uid-" + id, number, id, true, new Cell(List.of("user"), false), new Cell(List.of("db"), false),
                new Cell(List.of("https"), false), ANY_CELL, action, "Log", "",
                Map.of("from", List.of("any"), "to", List.of("any"), "time", List.of("obj-any"),
                        "layer-name", List.of("Network"), "install-on", List.of(installOn)));
    }

    private static PolicySnapshot layer(Metadata meta, Rule... rules) {
        return new PolicySnapshot(meta, List.of(new Section("s1", "Network", "CP access layer", null, List.of(rules))), objects());
    }

    private static RuleEvaluation byId(EvaluationResult result, String ruleId) {
        return result.rules().stream().filter(r -> ruleId.equals(r.ruleId())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ ZM-25

    @Test
    @DisplayName("ZM-25: an earlier deny on the same install targets covers and names its rule id")
    void zm25_earlierDenyOnTheSameInstallTargetsCoversAndNamesTheCoveringRule() {
        Matrix v1 = publishedMatrix();
        PolicySnapshot snap = layer(cpMeta(),
                cpRule("r-deny", 1, "Drop", GW_A),
                cpRule("r-allow", 2, "Accept", GW_A));
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), v1.content(), NOW);
        RuleEvaluation allow = byId(result, "r-allow");
        assertEquals(OUTCOME_VIOLATION, allow.outcome(), "the allow rule violates the DENY_ALL user-to-db cell");
        assertTrue(allow.flags().contains("COVERED_BY_EARLIER_DENY"), "a proven same-target conflict is flagged");
        assertEquals("r-deny", allow.coveringRuleId(), "the flag names the covering deny rule");
    }

    @Test
    @DisplayName("ZM-25: an earlier deny installed on other targets never covers")
    void zm25_earlierDenyOnDifferentInstallTargetsNeverCovers() {
        Matrix v1 = publishedMatrix();
        PolicySnapshot snap = layer(cpMeta(),
                cpRule("r-deny", 1, "Drop", GW_B),
                cpRule("r-allow", 2, "Accept", GW_A));
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), v1.content(), NOW);
        RuleEvaluation allow = byId(result, "r-allow");
        assertEquals(OUTCOME_VIOLATION, allow.outcome());
        assertFalse(allow.flags().contains("COVERED_BY_EARLIER_DENY"), "the deny does not run on the allow's target");
        assertNull(allow.coveringRuleId());
    }

    @Test
    @DisplayName("ZM-25: a partial overlap of install targets or an unresolved install-on never covers")
    void zm25_partialOverlapOrUnresolvedInstallTargetsNeverCover() {
        Matrix v1 = publishedMatrix();
        PolicySnapshot partial = layer(cpMeta(),
                cpRule("r-deny", 1, "Drop", GW_A),
                cpRule("r-allow", 2, "Accept", GW_A, GW_B));
        RuleEvaluation allowPartial = byId(
                ZoneMatrixEvaluator.evaluate(partial, v1.versionId(), v1.number(), v1.content(), NOW), "r-allow");
        assertFalse(allowPartial.flags().contains("COVERED_BY_EARLIER_DENY"), "deny {A} does not cover allow {A, B}");
        assertNull(allowPartial.coveringRuleId());

        PolicySnapshot unresolved = layer(cpMeta(),
                cpRule("r-deny", 1, "Drop", "gw-not-enrolled"),
                cpRule("r-allow", 2, "Accept", GW_A));
        RuleEvaluation allowUnresolved = byId(
                ZoneMatrixEvaluator.evaluate(unresolved, v1.versionId(), v1.number(), v1.content(), NOW), "r-allow");
        assertFalse(allowUnresolved.flags().contains("COVERED_BY_EARLIER_DENY"), "an unresolved deny target fails closed");
        assertNull(allowUnresolved.coveringRuleId());
    }

    // ------------------------------------------------------------------------------------------ ZM-37

    @Test
    @DisplayName("ZM-37: no DELETE route and no store method removes a revision, a run or a finding")
    void zm37_noDeleteSurfaceForRevisionsRunsOrFindings() {
        for (Method method : ZoneMatrixController.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(DeleteMapping.class), method.getName() + " is no @DeleteMapping route");
            RequestMapping mapping = method.getAnnotation(RequestMapping.class);
            if (mapping != null) {
                assertFalse(Arrays.asList(mapping.method()).contains(RequestMethod.DELETE), method.getName() + " is no DELETE");
            }
        }
        for (Class<?> repository : List.of(ZoneMatrixRepository.class, ZoneMatrixRunRepository.class)) {
            for (Method method : repository.getDeclaredMethods()) {
                assertFalse(method.getName().matches("(?i)(delete|remove|purge).*"),
                        repository.getSimpleName() + "." + method.getName() + " deletes nothing");
            }
        }
    }

    // ------------------------------------------------------------------------------------------ ZM-25 lifecycle

    private static FindingIdentity id(String uid, String... pairs) {
        return new FindingIdentity("POL-ZM25", uid, List.of(), List.of(pairs));
    }

    private static RuleEvaluation violation(FindingIdentity identity, Set<String> flags) {
        return new RuleEvaluation(identity, "rule-" + identity.ruleUid(), identity.ruleUid(), 1, "RULE-ONE", "sec-1",
                "SECTION-1", identity.inlineParentPath(), List.of("FW-TANGO-04"), List.of(), List.of(), OUTCOME_VIOLATION,
                "reason-VIOLATION", "HIGH", flags, null, List.of(), List.of(), List.of(), "ORIGINAL");
    }

    private static EvaluationResult completeRun(String version, RuleEvaluation... rules) {
        return new EvaluationResult(ZoneMatrixEvaluator.ENGINE_VERSION, new VersionCitation(version, 1, "sha-" + version),
                "POL-ZM25", "POLICY-NAME", "src-1", "SOURCE-1", "cont-1", "CONTAINER-1", "CP", NOW.toString(), List.of(), true,
                null, "COMPLETE", List.of(), 0, Map.of(), new NotApplicableSummary(0, 0, 0, 0, 0, List.of()), null,
                List.of(rules), List.of(), false, 0L, 0L);
    }

    @Test
    @DisplayName("ZM-25: toggling the cover flag on and off keeps the first-observed instant")
    void zm25_togglingCoverKeepsFirstObserved() {
        FindingIdentity identity = id("00000000-0000-4000-8000-00000000000a", "ZONE-1->ZONE-2");
        Instant t0 = Instant.parse("2026-11-01T08:00:00Z");
        Instant t1 = Instant.parse("2026-11-10T08:00:00Z");
        Instant t2 = Instant.parse("2026-11-20T08:00:00Z");

        Finding first = onlyFinding(ZoneMatrixFindingLifecycle.next(List.of(),
                completeRun("ver-1", violation(identity, Set.of())), t0), identity);
        assertEquals(Transition.NEW, first.transition());
        assertEquals(t0, first.firstObservedAt());

        Finding flagged = onlyFinding(ZoneMatrixFindingLifecycle.next(List.of(first.toKnown()),
                completeRun("ver-1", violation(identity, Set.of("COVERED_BY_EARLIER_DENY"))), t1), identity);
        assertEquals(Transition.REPRODUCED, flagged.transition());
        assertTrue(flagged.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals(t0, flagged.firstObservedAt(), "turning the flag on keeps first-observed");

        Finding unflagged = onlyFinding(ZoneMatrixFindingLifecycle.next(List.of(flagged.toKnown()),
                completeRun("ver-1", violation(identity, Set.of())), t2), identity);
        assertEquals(Transition.REPRODUCED, unflagged.transition());
        assertFalse(unflagged.flags().contains("COVERED_BY_EARLIER_DENY"));
        assertEquals(t0, unflagged.firstObservedAt(), "turning the flag off keeps first-observed");
        assertEquals(t0, unflagged.firstObservedAnyVersion());
        assertEquals(t2, unflagged.lastObservedAt());
    }

    private static Finding onlyFinding(ZoneMatrixFindingLifecycle.Result result, FindingIdentity identity) {
        List<Finding> hits = result.findings().stream().filter(f -> f.identity().equals(identity)).toList();
        assertEquals(1, hits.size(), "exactly one finding for the identity");
        return hits.get(0);
    }
}
