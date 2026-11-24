package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.api.ZoneMatrixController;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.*;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.*;
import java.io.IOException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

/**
 * Acceptance-criteria pins for delivery steps 1-3 of {@code docs/design/ZONE_MATRIX_CONTRACT.md} (FROZEN 2026-11-13,
 * section 10.2): the criteria whose sentence no other test proved on its own. Which test proves which criterion is the
 * table in {@code docs/history/backlog/zone_matrix_delivery.md}. Method names start with the criterion id.
 *
 * <p>Synthetic principals, zone names and RFC 5737 / RFC 3849 documentation ranges only.</p>
 */
class ZoneMatrixAcceptanceCriteriaTest {
    private static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY = new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");

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

    private Map<String, Object> publish(String actor, String rationale) {
        var draft = store.draft().orElseThrow();
        return service.publish(actor, false, new PublishRequest(draft.revision(), draft.contentSha256(), rationale));
    }

    private ZoneMatrixContent stored(String versionId) {
        return ZoneMatrixContent.parse(store.rows.get(versionId).content(), MAPPER);
    }

    /** Z-USER, Z-APP, Z-DB with one explicit cell (user to app, tcp/443), published as version 1. */
    private record Matrix(ZoneMatrixContent content, String versionId, int number, String user, String app, String db) {}

    private Matrix publishedMatrix() {
        String user = zone("security-1", "Z-USER", "192.0.2.0/25");
        String app = zone("security-1", "Z-APP", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "203.0.113.0/24");
        service.setCell("security-1", revision(), new CellInput(user, app, "ALLOW_LISTED", List.of("tcp/443"), "HIGH", "web tier", ""));
        var published = publish("compliance-1", null);
        return new Matrix(ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER),
                (String) published.get("version_id"), ((Number) published.get("number")).intValue(), user, app, db);
    }

    private static Metadata meta(String id, String vendor, String name) {
        return new Metadata(id, "src-1", "FW-TANGO-04", vendor, "dom-1", "DOM-BRAVO-02", name,
                "2026-11-23T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static PolicyObject addr(String id, String name, String cidr) {
        return new PolicyObject(id, name, "address", List.of(), List.of("ip-netmask: " + cidr), "RESOLVED");
    }

    private static PolicyObject tcp(String id, String name, String port) {
        return new PolicyObject(id, name, "service", List.of(), List.of("protocol/tcp/port: " + port), "RESOLVED");
    }

    private static Rule rule(String id, int number, String name, Boolean enabled, String source, String destination,
            String service, String action) {
        return new Rule(id, "uid-" + id, number, name, enabled, new Cell(List.of(source), false), new Cell(List.of(destination), false),
                new Cell(List.of(service), false), ANY_CELL, action, "Log", "", Map.of());
    }

    private static Section panSection(String metaId, String name, List<Rule> rules) {
        return new Section(PolicySnapshot.ref(metaId, "PAN device-group", "pre-rulebase"), name, "PAN device-group", null, rules);
    }

    private static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("user", addr("user", "USER-NET", "192.0.2.0/26"));
        objects.put("app", addr("app", "APP-NET", "198.51.100.0/25"));
        objects.put("db", addr("db", "DB-NET", "203.0.113.10/32"));
        objects.put("https", tcp("https", "HTTPS", "443"));
        return objects;
    }

    private static RuleEvaluation byId(EvaluationResult result, String ruleId) {
        return result.rules().stream().filter(r -> ruleId.equals(r.ruleId())).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------------------------------ ZM-1

    @Test
    @DisplayName("ZM-1: every address of both families is in exactly one zone or UNASSIGNED; cross-zone duplicates are refused")
    void zm1_partitionPutsEveryAddressInExactlyOneZoneAcrossBothFamiliesAndRefusesDuplicates() {
        Random random = new Random(20261123);
        for (int round = 0; round < 150; round++) {
            List<ZonePartition.Placed> placed = new ArrayList<>();
            Set<ZoneBlock> used = new HashSet<>();
            for (int i = 0; i < 14; i++) {
                ZoneBlock block = random.nextBoolean() ? randomV4(random) : randomV6(random);
                if (used.add(block)) placed.add(new ZonePartition.Placed("z" + random.nextInt(4), block));
            }
            ZonePartition partition = ZonePartition.of(placed);
            Map<String, Map<ZoneBlock.Family, BigInteger>> sizes = partition.effectiveSizes();
            for (ZoneBlock.Family family : ZoneBlock.Family.values()) {
                List<ZonePartition.Segment> segments = partition.segments(family);
                assertEquals(BigInteger.ZERO, segments.get(0).start(), "round " + round);
                assertEquals(family.max(), segments.get(segments.size() - 1).end(), "round " + round);
                for (int k = 1; k < segments.size(); k++)
                    assertEquals(segments.get(k - 1).end().add(BigInteger.ONE), segments.get(k).start(), "no gap and no overlap");
                BigInteger total = BigInteger.ZERO;
                for (Map<ZoneBlock.Family, BigInteger> perFamily : sizes.values())
                    total = total.add(perFamily.getOrDefault(family, BigInteger.ZERO));
                assertEquals(BigInteger.ONE.shiftLeft(family.bits), total, "every address counted exactly once, round " + round);
            }
            for (int probe = 0; probe < 200 && !placed.isEmpty(); probe++) {
                ZonePartition.Placed anchor = placed.get(random.nextInt(placed.size()));
                BigInteger span = anchor.block().end().subtract(anchor.block().start()).add(BigInteger.ONE);
                BigInteger offset = new BigInteger(span.bitLength() + 8, random).mod(span);
                ZoneBlock address = new ZoneBlock(anchor.block().family(), anchor.block().start().add(offset), anchor.block().family().bits);
                ZonePartition.Placed best = null;
                for (var p : placed) if (p.block().contains(address) && (best == null || p.block().prefix() > best.block().prefix())) best = p;
                assertNotNull(best);
                assertEquals(best.zoneId(), partition.zoneOf(address), "nesting follows the longest prefix, round " + round);
            }
            ZonePartition.Placed victim = placed.get(random.nextInt(placed.size()));
            String other = victim.zoneId().equals("zx") ? "zy" : "zx";
            List<ZonePartition.Placed> clash = new ArrayList<>(placed);
            clash.add(new ZonePartition.Placed(other, victim.block()));
            assertThrows(ZonePartition.DuplicateBlock.class, () -> ZonePartition.of(clash), "the same block in two zones is refused");
        }
    }

    private static ZoneBlock randomV4(Random random) {
        int prefix = 18 + random.nextInt(15);
        long mask = prefix == 32 ? 0xFFFFFFFFL : ~((1L << (32 - prefix)) - 1) & 0xFFFFFFFFL;
        long base = (0xC6336400L & 0xFFFF0000L) | random.nextInt(1 << 16);
        return new ZoneBlock(ZoneBlock.Family.IPV4, BigInteger.valueOf(base & mask), prefix);
    }

    private static ZoneBlock randomV6(Random random) {
        int prefix = 36 + random.nextInt(60);
        BigInteger documentation = new BigInteger("20010db8", 16).shiftLeft(96);
        BigInteger host = new BigInteger(96, random);
        BigInteger mask = BigInteger.ONE.shiftLeft(128 - prefix).subtract(BigInteger.ONE).not();
        return new ZoneBlock(ZoneBlock.Family.IPV6, documentation.or(host).and(mask), prefix);
    }

    // ------------------------------------------------------------------------------------------ ZM-2

    @Test
    @DisplayName("ZM-2: a published version is never edited; edits go to a new draft that leaves it byte-identical")
    void zm2_publishedVersionStaysByteIdenticalWhileEditsGoToTheNextDraft() {
        Matrix v1 = publishedMatrix();
        String json = store.rows.get(v1.versionId()).content(), sha = store.rows.get(v1.versionId()).contentSha256();
        // an edit with a revision when no draft exists is refused: there is no published row to address
        assertEquals("REQUEST_CHANGED", assertThrows(Refused.class, () -> service.createZone("security-1", 2L,
                new ZoneInput("Z-NEW", "", null, List.of("192.0.2.128/26")))).code);
        var edit = service.createZone("security-1", null, new ZoneInput("Z-NEW", "", null, List.of("192.0.2.128/26")));
        String draftId = (String) edit.get("version_id");
        assertNotEquals(v1.versionId(), draftId, "the edit lands in a different row");
        assertEquals("DRAFT", store.rows.get(draftId).state());
        assertEquals(v1.versionId(), store.rows.get(draftId).basedOnVersionId());
        service.updateZone("security-1", revision(), (String) edit.get("zone_id"), new ZoneInput("Z-NEW", "x", null, List.of("192.0.2.128/26")));
        assertEquals(json, store.rows.get(v1.versionId()).content());
        assertEquals(sha, store.rows.get(v1.versionId()).contentSha256());
        assertEquals("ACTIVE", store.rows.get(v1.versionId()).state());
        // publishing the draft retires version 1 without touching its content
        var v2 = publish("compliance-1", "synthetic rationale");
        assertEquals(2, v2.get("number"));
        assertEquals("RETIRED", store.rows.get(v1.versionId()).state());
        assertEquals(json, store.rows.get(v1.versionId()).content());
        assertEquals(sha, store.rows.get(v1.versionId()).contentSha256());
        assertNotEquals(sha, store.rows.get((String) v2.get("version_id")).contentSha256());
        // the store refuses to write a draft update to a published row
        var tamper = assertThrows(RuntimeException.class, () -> store.locked(tx -> {
            tx.updateDraft(v1.versionId(), store.rows.get(v1.versionId()).revision(), 99L, "{}", "0".repeat(64), "security-1", NOW);
            return null;
        }));
        assertNotNull(tamper);
        assertEquals(json, store.rows.get(v1.versionId()).content());
    }

    // ------------------------------------------------------------------------------------------ ZM-3

    @Test
    @DisplayName("ZM-3: every evaluation cites version id, number and hash; the hash recomputes from the stored content")
    void zm3_citationHashRecomputesFromStoredContent() {
        Matrix v1 = publishedMatrix();
        for (var row : store.rows.values()) {
            if (row.number() == null) continue;
            assertEquals(row.contentSha256(), ZoneMatrixContent.parse(row.content(), MAPPER).sha256(), "stored hash is the hash of the stored bytes");
            assertEquals(row.contentSha256(), sha256Hex(row.content()), "and of the canonical text itself");
        }
        Map<String, PolicyObject> objects = objects();
        PolicySnapshot snap = new PolicySnapshot(meta("pol-3", "PAN", "PAN-3"), List.of(panSection("pol-3", "Pre rules",
                List.of(rule("r1", 1, "Web", true, "user", "app", "https", "allow")))), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), stored(v1.versionId()), NOW);
        assertEquals(new VersionCitation(v1.versionId(), v1.number(), store.rows.get(v1.versionId()).contentSha256()), result.version());
        assertEquals(1, result.rules().size());
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------ ZM-4

    @Test
    @DisplayName("ZM-4: an undecided part gives NOT_EVALUABLE or UNKNOWN with a reason from the fixed list, never COMPLIANT")
    void zm4_everyUndecidedRuleCarriesAReasonFromTheFixedList() {
        Matrix v1 = publishedMatrix();
        Map<String, PolicyObject> objects = objects();
        objects.put("v6", new PolicyObject("v6", "V6-NET", "address", List.of(), List.of("ipv6-address: 2001:db8::10"), "RESOLVED"));
        objects.put("dyn", new PolicyObject("dyn", "DYN-GROUP", "group", List.of(), List.of(), "UNSUPPORTED"));
        List<Rule> rules = List.of(
                rule("r-v6", 1, "V6", true, "v6", "app", "https", "allow"),
                rule("r-dyn", 2, "Dyn", true, "user", "dyn", "https", "allow"),
                rule("r-unknown-enabled", 3, "NoState", null, "user", "app", "https", "allow"),
                rule("r-action", 4, "Odd", true, "user", "app", "https", "UNKNOWN"),
                rule("r-ok", 5, "Ok", true, "user", "app", "https", "allow"));
        PolicySnapshot snap = new PolicySnapshot(meta("pol-4", "PAN", "PAN-4"), List.of(panSection("pol-4", "Pre rules", rules)), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), v1.content(), NOW);
        int undecided = 0;
        for (RuleEvaluation r : result.rules()) {
            if (r.outcome().equals(OUTCOME_NOT_EVALUABLE)) {
                undecided++;
                assertTrue(NOT_EVALUABLE_REASONS.contains(r.reason()), r.ruleId() + ": " + r.reason());
            } else if (r.outcome().equals(OUTCOME_UNKNOWN)) {
                undecided++;
                assertTrue(UNKNOWN_REASONS.contains(r.reason()), r.ruleId() + ": " + r.reason());
            }
        }
        assertTrue(undecided >= 3, "the fixture yields several undecided rules");
        assertEquals(OUTCOME_COMPLIANT, byId(result, "r-ok").outcome(), "a decided compliant rule stays COMPLIANT");
        assertNotEquals(OUTCOME_COMPLIANT, byId(result, "r-v6").outcome());
        assertNotEquals(OUTCOME_COMPLIANT, byId(result, "r-dyn").outcome());
    }

    // ------------------------------------------------------------------------------------------ ZM-5

    @Test
    @DisplayName("ZM-5: service any in an ALLOW_LISTED pair is a VIOLATION; any rule in a DENY_ALL pair is a VIOLATION whatever its service")
    void zm5_anyServiceInAllowListedPairAndAnyServiceInDenyAllPairAreViolations() {
        Matrix v1 = publishedMatrix();
        Map<String, PolicyObject> objects = objects();
        objects.put("ssh", tcp("ssh", "SSH", "22"));
        List<Rule> rules = List.of(
                rule("r-any-listed", 1, "AnyInListed", true, "user", "app", "obj-any", "allow"),
                rule("r-listed-ok", 2, "ListedOk", true, "user", "app", "https", "allow"),
                rule("r-listed-extra", 3, "ListedExtra", true, "user", "app", "ssh", "allow"),
                rule("r-deny-all-https", 4, "DenyAllWithAllowedLookingService", true, "user", "db", "https", "allow"),
                rule("r-deny-all-any", 5, "DenyAllAny", true, "user", "db", "obj-any", "allow"));
        PolicySnapshot snap = new PolicySnapshot(meta("pol-5", "PAN", "PAN-5"), List.of(panSection("pol-5", "Pre rules", rules)), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), v1.content(), NOW);
        RuleEvaluation anyListed = byId(result, "r-any-listed");
        assertEquals(OUTCOME_VIOLATION, anyListed.outcome());
        assertEquals("ALLOW_LISTED", anyListed.violatedCells().get(0).statement());
        assertEquals(OUTCOME_COMPLIANT, byId(result, "r-listed-ok").outcome());
        assertEquals(OUTCOME_VIOLATION, byId(result, "r-listed-extra").outcome());
        for (String id : List.of("r-deny-all-https", "r-deny-all-any")) {
            RuleEvaluation e = byId(result, id);
            assertEquals(OUTCOME_VIOLATION, e.outcome(), id);
            assertEquals("DENY_ALL", e.violatedCells().get(0).statement(), id);
        }
        assertEquals(4, result.headline().distinctViolatingRules());
    }

    // ------------------------------------------------------------------------------------------ ZM-17

    @Test
    @DisplayName("ZM-17: an Apply Layer parent is never reported as action semantics not modelled")
    void zm17_applyLayerParentIsNeverActionSemanticsNotModelled() {
        Matrix v1 = publishedMatrix();
        Map<String, PolicyObject> objects = objects();
        Map<String, List<String>> network = Map.of("layer-name", List.of("Network"), "install-on", List.of("obj-any"),
                "inline-layer", List.of("Inline-1"));
        Rule parent = new Rule("p1", "uid-p1", 1, "Parent", true, new Cell(List.of("user"), false), new Cell(List.of("app"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Apply Layer", "Log", "", network);
        Rule child = new Rule("c1", "uid-c1", 1, "Child", true, new Cell(List.of("user"), false), new Cell(List.of("app"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "",
                Map.of("layer-name", List.of("Inline-1"), "install-on", List.of("obj-any")));
        PolicySnapshot snap = new PolicySnapshot(meta("pol-17", "CP", "CP-17"), List.of(
                new Section("s1", "Network", "CP access layer", null, List.of(parent)),
                new Section("s2", "Inline-1", "CP access layer", "p1", List.of(child))), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(snap, v1.versionId(), v1.number(), v1.content(), NOW);
        assertEquals(OUTCOME_COMPLIANT, byId(result, "p1").outcome(), "the parent is read as an Accept upper bound");
        for (RuleEvaluation e : result.rules()) assertNotEquals("action semantics not modelled", e.reason(), e.ruleId());
    }

    // ------------------------------------------------------------------------------------------ ZM-6

    @Test
    @DisplayName("ZM-6: disabled, deny-type and NAT rules never produce a VIOLATION and are counted separately")
    void zm6_disabledDenyAndNatRulesNeverViolateAndAreCountedSeparately() {
        Matrix v1 = publishedMatrix();
        Map<String, PolicyObject> objects = objects();
        // every one of these would be a VIOLATION as an enabled allow rule: user to db is DENY_ALL
        Rule disabled = rule("r-dis", 1, "Disabled", false, "user", "db", "https", "allow");
        Rule denyPan = rule("r-deny", 2, "Deny", true, "user", "db", "https", "deny");
        Rule denyDrop = rule("r-drop", 3, "Drop", true, "user", "db", "https", "drop");
        Rule realViolation = rule("r-real", 4, "Real", true, "user", "db", "https", "allow");
        Rule natAsAllow = rule("nat-1", 1, "NatLooksLikeAllow", true, "user", "db", "https", "allow");
        PolicySnapshot pan = new PolicySnapshot(meta("pol-6", "PAN", "PAN-6"), List.of(
                panSection("pol-6", "Pre rules", List.of(disabled, denyPan, denyDrop, realViolation)),
                new Section("s-nat", PolicySnapshot.PAN_PRE_NAT, "PAN device-group", null, List.of(natAsAllow))), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(pan, v1.versionId(), v1.number(), v1.content(), NOW);
        assertEquals(1, result.notApplicable().disabled());
        assertEquals(2, result.notApplicable().denyType());
        assertEquals(1, result.notApplicable().outsideSecurityRulebase(), "the NAT rule is counted outside the security rulebase");
        assertEquals(List.of("r-real"), result.rules().stream().map(RuleEvaluation::ruleId).toList(),
                "only the enabled allow rule is evaluated; the others have no finding at all");
        assertEquals(OUTCOME_VIOLATION, byId(result, "r-real").outcome());
        assertEquals(1, result.headline().distinctViolatingRules());
        assertEquals(1, result.outcomeCounts().get(OUTCOME_VIOLATION));
        // Check Point: a Drop rule and a rule of the NAT rulebase
        Rule cpDrop = new Rule("cp-drop", "uid-cp-drop", 1, "Drop", true, new Cell(List.of("user"), false), new Cell(List.of("db"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Drop", "Log", "", Map.of("layer-name", List.of("Network")));
        Rule cpNat = new Rule("cp-nat", "uid-cp-nat", 1, "Nat", true, new Cell(List.of("user"), false), new Cell(List.of("db"), false),
                new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", Map.of());
        PolicySnapshot cp = new PolicySnapshot(meta("pol-6cp", "CP", "CP-6"), List.of(
                new Section("s1", "Network", "CP access layer", null, List.of(cpDrop)),
                new Section("s2", "NAT", "CP NAT rulebase", null, List.of(cpNat))), objects);
        EvaluationResult cpResult = ZoneMatrixEvaluator.evaluate(cp, v1.versionId(), v1.number(), v1.content(), NOW);
        assertEquals(1, cpResult.notApplicable().denyType());
        assertEquals(1, cpResult.notApplicable().outsideSecurityRulebase());
        assertEquals(0, cpResult.outcomeCounts().get(OUTCOME_VIOLATION));
        assertTrue(cpResult.rules().isEmpty());
    }

    // ------------------------------------------------------------------------------------------ ZM-7

    @Test
    @DisplayName("ZM-7: a snapshot with collection failures is INCOMPLETE and never reads as no violations")
    void zm7_collectionFailureGivesIncompleteCoverageAndNeverZero() {
        Matrix v1 = publishedMatrix();
        Rule clean = rule("r-clean", 1, "Clean", true, "user", "app", "https", "allow");
        Section local = new Section("s1", PolicySnapshot.PAN_LOCAL_RULES, PolicySnapshot.PAN_LOCAL_RULES, null, List.of(clean));
        Rule intraDeny = new Rule("d1", "uid-d1", 1, "intrazone-default", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL, "deny", "Log", "",
                Map.of("rule-type", List.of("intrazone")));
        Section defaults = new Section("s2", PolicySnapshot.PAN_LOCAL_DEFAULT, PolicySnapshot.PAN_LOCAL_DEFAULT, null, List.of(intraDeny));
        PolicySnapshot complete = new PolicySnapshot(meta("pol-7", "PAN", "PAN-7"), List.of(local, defaults), objects());
        EvaluationResult whole = ZoneMatrixEvaluator.evaluate(complete, v1.versionId(), v1.number(), v1.content(), NOW);
        assertEquals("COMPLETE", whole.coverage(), "the same policy without failures is complete");
        assertEquals("0 rules violate the zone matrix (v1)", whole.headline().text());

        PolicySnapshot failed = new PolicySnapshot(meta("pol-7", "PAN", "PAN-7"), List.of(local, defaults), objects(),
                List.of(new CollectionFailure("layer-1", "LAYER_TIMEOUT", "Network", 0)));
        EvaluationResult partial = ZoneMatrixEvaluator.evaluate(failed, v1.versionId(), v1.number(), v1.content(), NOW);
        assertEquals("INCOMPLETE", partial.coverage());
        assertEquals(1, partial.failureCount());
        assertEquals(0, partial.headline().distinctViolatingRules());
        assertFalse(partial.headline().exact());
        assertEquals("no proven violations in evaluated rules", partial.headline().text());
        assertFalse(partial.headline().text().startsWith("0 "), "never a bare zero while coverage is incomplete");
        assertEquals(1, partial.headline().incompletePolicies());
    }

    // ------------------------------------------------------------------------------------------ ZM-18

    @Test
    @DisplayName("ZM-18: a collected Palo Alto default override with action allow produces a finding with DEFAULT_RULE")
    void zm18_panDefaultOverrideWithActionAllowCarriesDefaultRule() {
        Matrix v1 = publishedMatrix();
        Map<String, PolicyObject> objects = objects();
        Rule allowDefault = new Rule("def-allow", "uid-def-allow", 1, "interzone-default", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL,
                "allow", "Log", "", Map.of("rule-type", List.of("interzone")));
        Rule denyDefault = new Rule("def-deny", "uid-def-deny", 2, "intrazone-default", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL,
                "deny", "Log", "", Map.of("rule-type", List.of("intrazone")));
        // local firewall: the overridden default rules sit in their own section beside the (empty) local rules
        PolicySnapshot local = new PolicySnapshot(meta("pol-18", "PAN", "PAN-18"), List.of(
                new Section("s1", PolicySnapshot.PAN_LOCAL_RULES, PolicySnapshot.PAN_LOCAL_RULES, null, List.of()),
                new Section("s2", PolicySnapshot.PAN_LOCAL_DEFAULT, PolicySnapshot.PAN_LOCAL_DEFAULT, null, List.of(allowDefault, denyDefault))), objects);
        EvaluationResult result = ZoneMatrixEvaluator.evaluate(local, v1.versionId(), v1.number(), v1.content(), NOW);
        RuleEvaluation finding = byId(result, "def-allow");
        assertEquals(OUTCOME_VIOLATION, finding.outcome(), "any to any allow crosses DENY_ALL cells of the matrix");
        assertTrue(finding.flags().contains("DEFAULT_RULE"));
        assertFalse(finding.violatedCells().isEmpty());
        assertEquals(1, result.headline().distinctViolatingRules());
        assertEquals(1, result.notApplicable().denyType(), "the deny override is counted, not a finding");
        assertTrue(result.rules().stream().noneMatch(r -> "def-deny".equals(r.ruleId())));
        // Panorama: the pre and post default rule sections carry the same flag
        PolicySnapshot panorama = new PolicySnapshot(meta("pol-18p", "PAN", "DG-18"), List.of(
                new Section("s-pre", PolicySnapshot.PAN_PRE_DEFAULT, "PAN device-group", null, List.of(allowDefault)),
                new Section("s-post", PolicySnapshot.PAN_POST_DEFAULT, "PAN device-group", null, List.of(
                        new Rule("def-allow-2", "uid-def-allow-2", 1, "interzone-default", true, ANY_CELL, ANY_CELL, ANY_CELL, ANY_CELL,
                                "allow", "Log", "", Map.of("rule-type", List.of("interzone"))))),
                panSection("pol-18p", "Pre rules", List.of())), objects);
        EvaluationResult panoramaResult = ZoneMatrixEvaluator.evaluate(panorama, v1.versionId(), v1.number(), v1.content(), NOW);
        for (String id : List.of("def-allow", "def-allow-2")) {
            RuleEvaluation e = byId(panoramaResult, id);
            assertEquals(OUTCOME_VIOLATION, e.outcome(), id);
            assertTrue(e.flags().contains("DEFAULT_RULE"), id);
        }
    }

    // ------------------------------------------------------------------------------------------ ZM-22

    @Test
    @DisplayName("ZM-22: the four-eyes rule looks at the current draft only, so a person who edited an earlier, published draft may publish")
    void zm22_fourEyesCountsTheEditorsOfTheCurrentDraftOnly() {
        zone("security-1", "Z-APP", "198.51.100.0/24");
        publish("compliance-1", null);
        // security-1 and compliance-1 took part in draft 1; draft 2 is edited by compliance-2 alone
        zone("compliance-2", "Z-DB", "203.0.113.0/25");
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> publish("compliance-2", null)).code);
        assertEquals("SELF_APPROVAL", assertThrows(Refused.class, () -> publish(" Compliance-2 ", null)).code,
                "canonical principal ids");
        assertEquals(2, publish("security-1", null).get("number"), "an editor of the earlier draft is not excluded from this one");
        // an editor without compliance_admin, a publisher without it: refused for the missing role, not for self-approval
        zone("security-2", "Z-MGMT", "192.0.2.0/26");
        assertEquals("COMPLIANCE_ROLE_MISSING", assertThrows(Refused.class, () -> publish("security-3", null)).code);
    }

    // ------------------------------------------------------------------------------------------ ZM-29

    @Test
    @DisplayName("ZM-29: the publish diff lists the addresses that move between zones with their effective counts")
    @SuppressWarnings("unchecked")
    void zm29_publishDiffListsMovedAddressesWithEffectiveCounts() {
        zone("security-1", "Z-APP", "198.51.100.0/24");
        publish("compliance-1", null);
        zone("security-1", "Z-MGMT", "198.51.100.128/28");
        zone("security-1", "Z-NEW", "203.0.113.0/30");
        var diff = (Map<String, Object>) service.draft(View.NAMES, "compliance-1", false, true).get("diff");
        List<Map<String, Object>> moves = (List<Map<String, Object>>) diff.get("moves");
        assertEquals(2, moves.size());
        Map<String, Object> fromApp = moves.stream().filter(m -> "Z-APP".equals(m.get("from"))).findFirst().orElseThrow();
        assertEquals("Z-MGMT", fromApp.get("to"));
        assertEquals("16", fromApp.get("addresses"));
        assertEquals("IPV4", fromApp.get("family"));
        assertEquals(true, fromApp.get("leaves_a_zone"));
        Map<String, Object> fromUnassigned = moves.stream().filter(m -> "UNASSIGNED".equals(m.get("from"))).findFirst().orElseThrow();
        assertEquals("4", fromUnassigned.get("addresses"));
        assertEquals(false, fromUnassigned.get("leaves_a_zone"));
        List<Map<String, Object>> zones = (List<Map<String, Object>>) diff.get("zones");
        Map<String, Object> mgmt = zones.stream().filter(z -> "Z-MGMT".equals(z.get("name_after"))).findFirst().orElseThrow();
        assertEquals("ADDED", mgmt.get("change"));
        assertEquals("16", ((Map<?, ?>) mgmt.get("effective_after")).get("IPV4"), "the new zone's effective count matches the move into it");
    }

    // ------------------------------------------------------------------------------------------ ZM-37

    @Test
    @DisplayName("ZM-37: the audit capture functions write ids, change kind and hashes only (column allowlist)")
    void zm37_auditCaptureFunctionsNameOnlyAllowlistedColumns() throws IOException {
        Set<String> allowedKeys = Set.of("version_id", "revision", "change_kind", "zone_id", "content_sha256", "rationale_given", "kind");
        Set<String> allowedColumns = Set.of("version_id", "revision", "change_kind", "zone_id", "content_sha256", "kind", "access_id",
                "actor", "rationale");
        Pattern call = Pattern.compile("jsonb_build_object\\((.*?)\\)\\);", Pattern.DOTALL);
        int captured = 0;
        for (String file : List.of("V216__zone_matrix_versions.sql", "V220__zone_matrix_cells.sql", "V228__zone_matrix_witness_access_log.sql")) {
            String sql = new String(getClass().getResourceAsStream("/db/migration/" + file).readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = call.matcher(sql);
            while (m.find()) {
                captured++;
                String args = m.group(1);
                Matcher keys = Pattern.compile("'([a-z_0-9]+)'\\s*,").matcher(args);
                while (keys.find()) assertTrue(allowedKeys.contains(keys.group(1)), file + " audit key " + keys.group(1));
                Matcher columns = Pattern.compile("NEW\\.([a-z_0-9]+)").matcher(args);
                while (columns.find()) assertTrue(allowedColumns.contains(columns.group(1)), file + " audit column " + columns.group(1));
                assertFalse(args.contains("NEW.content,") || args.contains("NEW.content)"), file + " never captures the content text");
                assertFalse(args.matches("(?s).*NEW\\.rationale\\s*[,)].*"), file + " never captures the rationale text, only whether one was given");
            }
        }
        assertTrue(captured >= 3, "the revision capture (twice, V216 and V220) and the access capture are all inspected");
    }

    @Test
    @DisplayName("ZM-37: no route and no store method deletes or rewrites a revision, a version or an access row")
    void zm37_noDeleteRouteAndAppendOnlyStoreSurface() {
        for (Method method : ZoneMatrixController.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(DeleteMapping.class), method.getName() + " is no DELETE route");
            for (String path : paths(method)) {
                assertFalse(path.matches(".*(revision|finding|/runs?(/|$)).*"), "no route addresses revisions, runs or findings: " + path);
                if (path.endsWith("/delete")) assertTrue(path.contains("/draft/"), "a delete-style route only edits the draft: " + path);
            }
            if (method.getAnnotation(PutMapping.class) != null)
                assertTrue(paths(method).stream().allMatch(p -> p.contains("/draft/")), "an update route only edits the draft");
        }
        var repository = com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRepository.class;
        List<String> names = new ArrayList<>();
        for (Method m : repository.getDeclaredMethods()) names.add(m.getName());
        for (Class<?> inner : repository.getDeclaredClasses()) for (Method m : inner.getDeclaredMethods()) names.add(m.getName());
        for (String name : names) assertFalse(name.matches("(?i)(delete|remove|purge|truncate|clear).*"), "store method " + name);
        assertTrue(names.contains("appendRevision") && names.contains("recordAccess"), "history grows through append methods");
    }

    private static List<String> paths(Method method) {
        List<String> out = new ArrayList<>();
        var get = method.getAnnotation(GetMapping.class);
        var post = method.getAnnotation(PostMapping.class);
        var put = method.getAnnotation(PutMapping.class);
        if (get != null) out.addAll(List.of(get.value()));
        if (post != null) out.addAll(List.of(post.value()));
        if (put != null) out.addAll(List.of(put.value()));
        return out;
    }

    // ------------------------------------------------------------------------------------------ ZM-40

    @Test
    @DisplayName("ZM-40: the total-block cap, the per-zone cap, the zone cap and the field bounds refuse and never truncate")
    void zm40_capsRefuseAndNeverTruncate() {
        // one zone at the per-zone cap is accepted, one block more is refused and the draft is unchanged
        String first = zone("security-1", "Z-FIRST", ipv6Blocks(0, ZoneMatrixContent.MAX_BLOCKS_PER_ZONE));
        long revisionBefore = revision();
        String shaBefore = store.draft().orElseThrow().contentSha256();
        Refused perZone = assertThrows(Refused.class, () -> service.createZone("security-1", revisionBefore,
                new ZoneInput("Z-BIG", "", null, List.of(ipv6Blocks(100_000, ZoneMatrixContent.MAX_BLOCKS_PER_ZONE + 1)))));
        assertEquals("TOO_MANY_BLOCKS", perZone.code);
        assertUnchanged(revisionBefore, shaBefore, 1);
        // fill the version to the total cap with full zones, then one block more is refused
        int zones = 1;
        for (int from = ZoneMatrixContent.MAX_BLOCKS_PER_ZONE; from < ZoneMatrixContent.MAX_BLOCKS_TOTAL;
                from += ZoneMatrixContent.MAX_BLOCKS_PER_ZONE) {
            zone("security-1", "Z-FILL-" + zones, ipv6Blocks(from, ZoneMatrixContent.MAX_BLOCKS_PER_ZONE));
            zones++;
        }
        final int fresh = ZoneMatrixContent.MAX_BLOCKS_TOTAL, zoneCount = zones;
        assertEquals(ZoneMatrixContent.MAX_BLOCKS_TOTAL, stored(store.draftId()).blockCount(), "the cap itself is accepted");
        long atCap = revision();
        String shaAtCap = store.draft().orElseThrow().contentSha256();
        Refused total = assertThrows(Refused.class, () -> service.createZone("security-1", atCap,
                new ZoneInput("Z-ONE-MORE", "", null, List.of(ipv6Blocks(fresh, 1)))));
        assertEquals("TOO_MANY_BLOCKS", total.code);
        assertEquals(400, total.status);
        assertUnchanged(atCap, shaAtCap, zoneCount);
        assertEquals(ZoneMatrixContent.MAX_BLOCKS_TOTAL, stored(store.draftId()).blockCount(), "nothing was cut to make room");
        assertNotNull(first);
    }

    private void assertUnchanged(long revision, String sha, int zoneCount) {
        var draft = store.draft().orElseThrow();
        assertEquals(revision, draft.revision(), "a refused edit changes nothing");
        assertEquals(sha, draft.contentSha256());
        assertEquals(zoneCount, stored(draft.versionId()).zones().size());
    }

    @Test
    @DisplayName("ZM-40: zone count, name, description, rationale, service and block text bounds are refused, not trimmed")
    void zm40_requestFieldBoundsAreRefusedNotTruncated() {
        var zones = new ArrayList<ZoneMatrixContent.Zone>();
        for (int i = 0; i <= ZoneMatrixContent.MAX_ZONES; i++)
            zones.add(new ZoneMatrixContent.Zone(String.format("00000000-0000-4000-8000-%012x", i + 1), "Z-" + i, "", null, List.of()));
        var tooManyZones = assertThrows(ZoneMatrixContent.Invalid.class, () -> new ZoneMatrixContent(zones).validate());
        assertEquals("TOO_MANY_ZONES", tooManyZones.code);
        assertDoesNotThrow(() -> new ZoneMatrixContent(zones.subList(0, ZoneMatrixContent.MAX_ZONES)).validate());

        String app = zone("security-1", "Z-APP", "198.51.100.0/24");
        String db = zone("security-1", "Z-DB", "203.0.113.0/24");
        long revision = revision();
        String sha = store.draft().orElseThrow().contentSha256();
        assertEquals("INVALID_ZONE_NAME", assertThrows(Refused.class, () -> service.createZone("security-1", revision,
                new ZoneInput("N".repeat(ZoneMatrixContent.MAX_NAME + 1), "", null, List.of()))).code);
        assertEquals("INVALID_DESCRIPTION", assertThrows(Refused.class, () -> service.createZone("security-1", revision,
                new ZoneInput("Z-LONG", "d".repeat(ZoneMatrixContent.MAX_DESCRIPTION + 1), null, List.of()))).code);
        Refused blockText = assertThrows(Refused.class, () -> service.createZone("security-1", revision,
                new ZoneInput("Z-TEXT", "", null, List.of("1".repeat(5_000) + "/32"))));
        assertEquals("INVALID_BLOCKS", blockText.code, "an oversized block text is refused as a block, not read in part");
        assertEquals("TOO_MANY_SERVICES", assertThrows(Refused.class, () -> service.setCell("security-1", revision,
                new CellInput(app, db, "ALLOW_LISTED",
                        Collections.nCopies(ZoneMatrixContent.MAX_SERVICES_PER_CELL + 1, "tcp/1"), "LOW", "", ""))).code);
        assertEquals("INVALID_RATIONALE", assertThrows(Refused.class, () -> {
            var draft = store.draft().orElseThrow();
            service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), "r".repeat(2_001)));
        }).code);
        assertUnchanged(revision, sha, 2);
    }

    private static String[] ipv6Blocks(int from, int count) {
        String[] out = new String[count];
        for (int i = 0; i < count; i++) out[i] = String.format("2001:db8:%x:%x::/64", (from + i) >> 16, (from + i) & 0xFFFF);
        return out;
    }
}
