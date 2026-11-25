package com.securityexpert.nexus.ui2.service.zonematrix;

import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_COMPLIANT;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_NOT_EVALUABLE;
import static com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.OUTCOME_VIOLATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Section;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.EvaluationResult;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixEvaluator.RuleEvaluation;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.CellInput;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.Principals;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.PublishRequest;
import com.securityexpert.nexus.ui2.service.zonematrix.ZoneMatrixService.ZoneInput;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Zone matrix delivery step 7a (docs/design/ZONE_MATRIX_CONTRACT.md section 5.7 step 2, section 10.1 item 7 first part,
 * ZM-4, ZM-9, ZM-19): per-family address resolution. A mixed IPv4/IPv6 object keeps its IPv4 verdict; the IPv6 part is
 * reported as not evaluated and never lets a rule read as COMPLIANT.
 *
 * <p>Synthetic data only: RFC 5737 IPv4 blocks, RFC 3849 IPv6 block, masked names.</p>
 */
class ZoneMatrixPerFamilyResolutionTest {
    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IPV6_NOT_EVALUATED = "IPv6 not evaluated";
    private static final Cell ANY_CELL = new Cell(List.of("obj-any"), false);
    private static final PolicyObject OBJ_ANY =
            new PolicyObject("obj-any", "ANY", "any", List.of(), List.of("any"), "RESOLVED");

    /** Z-USER 192.0.2.0/25, Z-DMZ 192.0.2.128/25, Z-DB 203.0.113.0/24; USER->DMZ ALLOW_ANY, USER->DB DENY_ALL. */
    private record Matrix(ZoneMatrixContent content, String zUser, String zDb) {}

    private static Matrix publishedMatrix() {
        var store = new ZoneMatrixServiceTest.Memory();
        var service = new ZoneMatrixService(store, new Principals() {
            @Override public boolean holdsComplianceAdmin(String actor) { return actor.startsWith("compliance"); }
            @Override public boolean isRoot(String actor) { return false; }
        }, MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        String zUser = (String) service.createZone("security-1", null,
                new ZoneInput("Z-USER", "Synthetic user zone", "USER", List.of("192.0.2.0/25"))).get("zone_id");
        long rev = store.draft().orElseThrow().revision();
        String zDmz = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DMZ", "Synthetic DMZ zone", "DMZ", List.of("192.0.2.128/25"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        String zDb = (String) service.createZone("security-1", rev,
                new ZoneInput("Z-DB", "Synthetic database zone", "DATABASE", List.of("203.0.113.0/24"))).get("zone_id");
        rev = store.draft().orElseThrow().revision();
        service.setCell("security-1", rev,
                new CellInput(zUser, zDmz, "ALLOW_ANY", List.of(), "MEDIUM", "dmz access", ""));
        var draft = store.draft().orElseThrow();
        service.publish("compliance-1", false, new PublishRequest(draft.revision(), draft.contentSha256(), null));
        return new Matrix(ZoneMatrixContent.parse(store.active().orElseThrow().content(), MAPPER), zUser, zDb);
    }

    private static Metadata meta(String id, String vendor) {
        return new Metadata(id, "src-1", "FW-TANGO-04", vendor, "dom-1", "DOM-BRAVO-02", "POL-" + id,
                "2026-11-25T08:00:00Z", "art-1", List.of(new Target("gw-1", "FW-JULIET-06", "vsys1", "IN_SYNC")));
    }

    private static PolicyObject object(String id, String type, String... values) {
        return new PolicyObject(id, id.toUpperCase(), type, List.of(), List.of(values), "RESOLVED");
    }

    private static PolicyObject group(String id, String... members) {
        return new PolicyObject(id, id.toUpperCase(), "group", List.of(members), List.of(), "RESOLVED");
    }

    private static Map<String, PolicyObject> baseObjects() {
        Map<String, PolicyObject> objects = new LinkedHashMap<>();
        objects.put("obj-any", OBJ_ANY);
        objects.put("db-host", object("db-host", "address", "ip-netmask: 203.0.113.10/32"));
        objects.put("dmz-host", object("dmz-host", "address", "ip-netmask: 192.0.2.140/32"));
        objects.put("https", object("https", "service", "protocol/tcp/port: 443"));
        return objects;
    }

    private static Rule panRule(String id, String src, String dst) {
        return new Rule(id, "uid-" + id, 1, id, true, new Cell(List.of(src), false), new Cell(List.of(dst), false),
                new Cell(List.of("https"), false), ANY_CELL, "allow", "Log", "", Map.of());
    }

    private static Rule panRuleCells(String id, Cell src, Cell dst) {
        return new Rule(id, "uid-" + id, 1, id, true, src, dst, new Cell(List.of("https"), false), ANY_CELL,
                "allow", "Log", "", Map.of());
    }

    private static EvaluationResult evaluatePan(Matrix matrix, Map<String, PolicyObject> objects, Rule... rules) {
        PolicySnapshot snap = new PolicySnapshot(meta("pol-pan", "PAN"),
                List.of(new Section(PolicySnapshot.ref("pol-pan", "PAN device-group", "pre-rulebase"), "Pre rules",
                        "PAN device-group", null, List.of(rules))), objects);
        return ZoneMatrixEvaluator.evaluate(snap, "v-7a", 1, matrix.content(), NOW);
    }

    private static void assertIpv4ViolationWithIpv6Undecided(Matrix matrix, EvaluationResult res) {
        RuleEvaluation eval = res.rules().get(0);
        assertEquals(OUTCOME_VIOLATION, eval.outcome());
        assertEquals(1, eval.violatedCells().size(), "the Z-USER -> Z-DB cell is violated");
        assertEquals(matrix.zUser(), eval.violatedCells().get(0).sourceZone());
        assertEquals(matrix.zDb(), eval.violatedCells().get(0).destinationZone());
        assertTrue(eval.undecidedParts().contains(IPV6_NOT_EVALUATED));
        assertEquals(1, res.headline().distinctViolatingRules());
        assertEquals(1, res.headline().violatedZonePairs());
        assertFalse(res.headline().exact(), "an unevaluated IPv6 part keeps the headline non-exact");
    }

    @Test
    void dualStackHostSourceIntoDenyAllCellKeepsItsIpv4Violation() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("dual-host", object("dual-host", "address", "ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::20/128"));

        EvaluationResult res = evaluatePan(matrix, objects, panRule("r-dual", "dual-host", "db-host"));

        assertIpv4ViolationWithIpv6Undecided(matrix, res);
    }

    @Test
    void dualStackHostSourceIntoAllowAnyCellIsNotEvaluableNeverCompliant() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("dual-host", object("dual-host", "address", "ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::20/128"));
        objects.put("v4-host", object("v4-host", "address", "ip-netmask: 192.0.2.10/32"));

        EvaluationResult res = evaluatePan(matrix, objects,
                panRule("r-dual", "dual-host", "dmz-host"));
        EvaluationResult ipv4Only = evaluatePan(matrix, objects, panRule("r-v4", "v4-host", "dmz-host"));

        assertEquals(OUTCOME_COMPLIANT, ipv4Only.rules().get(0).outcome(), "the IPv4 part alone is compliant");
        assertEquals(OUTCOME_NOT_EVALUABLE, res.rules().get(0).outcome(), "ZM-4: an unevaluated part is never COMPLIANT");
        assertEquals(IPV6_NOT_EVALUATED, res.rules().get(0).reason());
        assertEquals(0, res.headline().distinctViolatingRules());
        assertEquals(1, res.headline().notEvaluableRules());
    }

    @Test
    void groupOfIpv4HostAndIpv6OnlyHostKeepsTheIpv4Member() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("v4-host", object("v4-host", "address", "ip-netmask: 192.0.2.10/32"));
        objects.put("v6-host", object("v6-host", "address", "ip-netmask: 2001:db8::30/128"));
        objects.put("mixed-group", group("mixed-group", "v4-host", "v6-host"));

        EvaluationResult res = evaluatePan(matrix, objects, panRule("r-group", "mixed-group", "db-host"));

        assertIpv4ViolationWithIpv6Undecided(matrix, res);
    }

    @Test
    void groupWithDualStackMemberKeepsItsIpv4Part() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("dual-host", object("dual-host", "address", "ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::20/128"));
        objects.put("dual-group", group("dual-group", "dual-host"));

        EvaluationResult res = evaluatePan(matrix, objects, panRule("r-dual-group", "dual-group", "db-host"));

        assertIpv4ViolationWithIpv6Undecided(matrix, res);
    }

    @Test
    void cellWithIpv4ObjectAndIpv6OnlyObjectKeepsIpv4VerdictAndFlagsIpv6() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("v4-host", object("v4-host", "address", "ip-netmask: 192.0.2.10/32"));
        objects.put("v6-host", object("v6-host", "address", "ip-netmask: 2001:db8::30/128"));

        EvaluationResult res = evaluatePan(matrix, objects, panRuleCells("r-two-refs",
                new Cell(List.of("v4-host", "v6-host"), false), new Cell(List.of("db-host"), false)));

        assertIpv4ViolationWithIpv6Undecided(matrix, res);
    }

    @Test
    void ipv6OnlySourceIsNotEvaluableAndEmptyIpv4IsNeverCompliant() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("v6-host", object("v6-host", "address", "ip-netmask: 2001:db8::30/128"));

        EvaluationResult toDenyAll = evaluatePan(matrix, objects, panRule("r-v6-deny", "v6-host", "db-host"));
        EvaluationResult toAllowAny = evaluatePan(matrix, objects, panRule("r-v6-allow", "v6-host", "dmz-host"));

        for (EvaluationResult res : List.of(toDenyAll, toAllowAny)) {
            assertEquals(OUTCOME_NOT_EVALUABLE, res.rules().get(0).outcome());
            assertEquals(IPV6_NOT_EVALUATED, res.rules().get(0).reason());
            assertEquals(0, res.headline().distinctViolatingRules());
        }
    }

    @Test
    void dualStackObjectWithUnparseableIpv4ValueKeepsTheRealFailureReason() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("broken-dual", object("broken-dual", "address", "ip-netmask: 192.0.2.999/32", "ip-netmask: 2001:db8::20/128"));
        objects.put("broken-v4", object("broken-v4", "address", "ip-netmask: 192.0.2.999/32"));

        RuleEvaluation dual = evaluatePan(matrix, objects, panRule("r-broken-dual", "broken-dual", "db-host")).rules().get(0);
        RuleEvaluation v4 = evaluatePan(matrix, objects, panRule("r-broken-v4", "broken-v4", "db-host")).rules().get(0);

        assertEquals(OUTCOME_NOT_EVALUABLE, dual.outcome(), "an unresolvable IPv4 part is never turned into a verdict");
        assertEquals(v4.reason(), dual.reason(), "same reason as the IPv4-only object, not hidden behind the IPv6 flag");
        assertNotEquals(IPV6_NOT_EVALUATED, dual.reason());
        assertTrue(dual.violatedCells().isEmpty());
    }

    @Test
    void sameSnapshotEvaluatedTwiceGivesEqualFindingsAndHeadline() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("dual-host", object("dual-host", "address", "ip-netmask: 192.0.2.10/32", "ip-netmask: 2001:db8::20/128"));
        objects.put("v6-host", object("v6-host", "address", "ip-netmask: 2001:db8::30/128"));
        objects.put("mixed-group", group("mixed-group", "dual-host", "v6-host"));
        Rule[] rules = {
                panRule("r-1", "dual-host", "db-host"),
                panRule("r-2", "mixed-group", "db-host"),
                panRule("r-3", "dual-host", "dmz-host")};

        EvaluationResult first = evaluatePan(matrix, objects, rules);
        EvaluationResult second = evaluatePan(matrix, objects, rules);

        assertEquals(first.rules(), second.rules(), "ZM-9");
        assertEquals(first.headline(), second.headline(), "ZM-9");
        assertEquals(2, first.headline().distinctViolatingRules());
    }

    @Test
    void checkPointIpv4AddressAndSubnetShapesKeepTheirIpv4Violation() {
        Matrix matrix = publishedMatrix();
        Map<String, PolicyObject> objects = baseObjects();
        objects.put("cp-dual-host", object("cp-dual-host", "host", "ipv4-address: 192.0.2.10", "ipv6-address: 2001:db8::20"));
        objects.put("cp-dual-net", object("cp-dual-net", "network",
                "subnet4: 192.0.2.0", "mask-length4: 25", "subnet6: 2001:db8::", "mask-length6: 64"));
        objects.put("cp-v6-net", object("cp-v6-net", "network", "subnet6: 2001:db8:1::", "mask-length6: 64"));
        objects.put("cp-db-host", object("cp-db-host", "host", "ipv4-address: 203.0.113.10"));
        objects.put("cp-dmz-host", object("cp-dmz-host", "host", "ipv4-address: 192.0.2.140"));
        Map<String, List<String>> extras = Map.of("layer-name", List.of("Network"));
        Rule hostRule = new Rule("cp-1", "uid-cp-1", 1, "DualHost", true, new Cell(List.of("cp-dual-host"), false),
                new Cell(List.of("cp-db-host"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", extras);
        Rule netRule = new Rule("cp-2", "uid-cp-2", 2, "DualNet", true, new Cell(List.of("cp-dual-net"), false),
                new Cell(List.of("cp-db-host"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", extras);
        Rule v6Rule = new Rule("cp-3", "uid-cp-3", 3, "V6Net", true, new Cell(List.of("cp-v6-net"), false),
                new Cell(List.of("cp-db-host"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", extras);
        Rule allowedRule = new Rule("cp-4", "uid-cp-4", 4, "DualHostToDmz", true, new Cell(List.of("cp-dual-host"), false),
                new Cell(List.of("cp-dmz-host"), false), new Cell(List.of("https"), false), ANY_CELL, "Accept", "Log", "", extras);
        PolicySnapshot snap = new PolicySnapshot(meta("pol-cp", "CP"),
                List.of(new Section("s1", "Network", "CP access layer", null, List.of(hostRule, netRule, v6Rule, allowedRule))),
                objects);

        EvaluationResult res = ZoneMatrixEvaluator.evaluate(snap, "v-7a", 1, matrix.content(), NOW);

        for (int i = 0; i < 2; i++) {
            RuleEvaluation eval = res.rules().get(i);
            assertEquals(OUTCOME_VIOLATION, eval.outcome(), eval.ruleId());
            assertTrue(eval.undecidedParts().contains(IPV6_NOT_EVALUATED), eval.ruleId());
            assertEquals(matrix.zDb(), eval.violatedCells().get(0).destinationZone(), eval.ruleId());
        }
        assertEquals(OUTCOME_NOT_EVALUABLE, res.rules().get(2).outcome());
        assertEquals(IPV6_NOT_EVALUATED, res.rules().get(2).reason());
        assertEquals(OUTCOME_NOT_EVALUABLE, res.rules().get(3).outcome(), "ZM-4: compliant IPv4 part, IPv6 undecided");
        assertEquals(IPV6_NOT_EVALUATED, res.rules().get(3).reason());
        assertEquals(2, res.headline().distinctViolatingRules());
    }
}
