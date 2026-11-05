package com.securityexpert.nexus.ui2.service.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.service.privacy.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyHygienePartialPriorTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import java.util.*;

/** X0: the counters-only split of "earlier rule has unsupported constraints" blockers. Synthetic fixtures, masked names only. */
class PolicyHygieneBlockerSplitTest {
    final ObjectMapper mapper = new ObjectMapper();
    final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    final PolicyQueryService query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names, null, ips);

    static PolicySnapshot plus(PolicySnapshot base, PolicyObject... extra) {
        var objects = new HashMap<>(base.objects());
        for (var o : extra) objects.put(o.id(), o);
        return new PolicySnapshot(base.metadata(), base.sections(), objects);
    }
    JsonNode split(PolicySnapshot snapshot) {
        return mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body()).path("blockers").path("split");
    }
    static void assertRow(JsonNode row, String cls, String cell, String status, String type, String nativeType, Object partial) {
        assertEquals(cls, row.path("class").asText()); assertEquals(cell, row.path("cell").asText());
        assertEquals(status, row.path("status").asText()); assertEquals(type, row.path("type").asText());
        assertEquals(nativeType, row.path("nativeType").asText());
        if (partial instanceof Boolean b) assertEquals(b, row.path("partialPrior").asBoolean()); else assertEquals(partial, row.path("partialPrior").asText());
        assertEquals(1, row.path("blockers").asInt()); assertEquals(1, row.path("blockedRules").asInt());
    }

    @Test void panUnresolvedObjectIsAPartialPriorBlockerOnTheSourceCell() {
        var snapshot = plus(pan(pan("r1", "unres", "net", "port", "allow"), pan("r2", "any", "net", "port", "allow")),
            new PolicyObject("unres", "OBJ-UNRES-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        var rows = split(snapshot);
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "unresolved object", "source", "UNRESOLVED", "unresolved", "NONE", true);
    }
    @Test void panUnsupportedExtraConstraintHasNoPartialPrior() {
        var r1 = with(pan("r1", "any", "net", "port", "allow"), Map.of("unexpected-field", List.of("yes")));
        var rows = split(pan(r1, pan("r2", "any", "net", "port", "allow")));
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "unsupported rule constraint", "extra:unexpected-field", "NONE", "NONE", "NONE", false);
    }
    @Test void checkPointDynamicObjectTypeCarriesItsNativeType() {
        var snapshot = plus(cp(cp("r1", 1, "Accept", "upd", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port")),
            new PolicyObject("upd", "OBJ-UPD-01", "updatable-object", List.of(), List.of("native-type: CpmiUpdatableObject"), "RESOLVED"));
        var rows = split(snapshot);
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "dynamic or unsupported object type", "source", "RESOLVED", "updatable-object", "CpmiUpdatableObject", true);
    }
    @Test void checkPointNonTerminalActionIsAPartialPriorWithResolvedCells() {
        var rows = split(cp(cp("r1", 1, "Inform", "any", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port")));
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "non-terminal or unknown action", "action", "NONE", "NONE", "NONE", true);
    }
    @Test void rowsAreSortedByBlockedRulesAndCountDistinctBlockers() {
        // Two blockers of one bucket block three rules between them; a third bucket blocks one.
        var snapshot = plus(pan(pan("a1", "unres", "net", "port", "allow"), pan("a2", "unres", "other-net", "port", "allow"),
                pan("b1", "any", "host", "port", "allow"), pan("z1", "any", "net", "port", "allow"), pan("z2", "any", "other-net", "port", "allow")),
            new PolicyObject("unres", "OBJ-UNRES-01", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        var rows = split(snapshot);
        long total = 0; long previous = Long.MAX_VALUE;
        for (var row : rows) { assertTrue(row.path("blockedRules").asLong() <= previous); previous = row.path("blockedRules").asLong(); total += row.path("blockers").asLong(); }
        assertEquals(mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body()).path("blockers").path("distinct").asLong(), total);
    }
    @Test void theSplitChangesNoVerdictFindingOrEvidence() {
        var snapshot = plus(cp(cp("r1", 1, "Accept", "upd", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port"), cp("r3", 3, "Inform", "any", "host", "port")),
            new PolicyObject("upd", "OBJ-UPD-01", "updatable-object", List.of(), List.of("native-type: CpmiUpdatableObject"), "RESOLVED"));
        var before = PolicyHygiene.analyze(snapshot, 90, java.time.Instant.parse("2026-10-09T12:00:00Z"), 250000);
        var page = mapper.valueToTree(query.hygiene(snapshot, 0, "unknown", "", 90).body());
        var after = PolicyHygiene.analyze(snapshot, 90, java.time.Instant.parse("2026-10-09T12:00:00Z"), 250000);
        assertEquals(before.rules(), after.rules());
        assertTrue(page.path("blockers").has("split")); assertTrue(page.path("blockers").has("top")); assertTrue(page.path("blockers").has("distinct"));
    }
    @Test void aHostileTypeStringDoesNotPassThrough() {
        String hostile = "FW-TANGO-04 admin@192.0.2.9";
        var snapshot = plus(cp(cp("r1", 1, "Accept", "odd", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port")),
            new PolicyObject("odd", "OBJ-ODD-01", hostile, List.of(), List.of("native-type: " + hostile), "RESOLVED"));
        var body = query.hygiene(snapshot, 0, "unknown", "", 90).body();
        var rows = mapper.valueToTree(body).path("blockers").path("split");
        assertEquals(1, rows.size());
        assertRow(rows.get(0), "dynamic or unsupported object type", "source", "RESOLVED", "UNKNOWN", "UNKNOWN", true);
        assertFalse(rows.toString().contains("TANGO")); assertFalse(rows.toString().contains("192.0.2"));
        // The AIView projection re-checks every token: a hostile row built by hand is reduced to UNKNOWN.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("class", hostile); row.put("cell", hostile); row.put("status", hostile); row.put("type", "FW-TANGO-04"); row.put("nativeType", hostile);
        row.put("partialPrior", hostile); row.put("blockers", 2); row.put("blockedRules", 5);
        var masked = mapper.valueToTree(PolicyPrivacy.mask(Map.of("blockers", Map.of("split", List.of(row))), "", names, ips)).path("blockers").path("split").get(0);
        for (String field : List.of("class", "cell", "status", "type", "nativeType", "partialPrior")) assertEquals("UNKNOWN", masked.path(field).asText(), field);
        assertEquals(2, masked.path("blockers").asInt()); assertEquals(5, masked.path("blockedRules").asInt());
        // A genuine row passes unchanged through the projection.
        var real = mapper.valueToTree(PolicyPrivacy.mask(body, "", names, ips)).path("blockers").path("split").get(0);
        assertEquals("UNKNOWN", real.path("type").asText());
        assertEquals("dynamic or unsupported object type", real.path("class").asText());
    }
    @Test void aGenuineRowPassesTheAiviewProjectionUnchanged() {
        var snapshot = plus(cp(cp("r1", 1, "Accept", "upd", "net", "port"), cp("r2", 2, "Accept", "any", "net", "port")),
            new PolicyObject("upd", "OBJ-UPD-01", "updatable-object", List.of(), List.of("native-type: CpmiUpdatableObject"), "RESOLVED"));
        var body = query.hygiene(snapshot, 0, "unknown", "", 90).body();
        var plain = mapper.valueToTree(body).path("blockers").path("split");
        var masked = mapper.valueToTree(PolicyPrivacy.mask(body, "", names, ips)).path("blockers").path("split");
        // "updatable-object" is in the closed type vocabulary, so the row is identical.
        assertEquals(plain, masked);
    }
    @Test void theAiviewSplitShowsLiveSchemaTypesButNoHostOrPseudonymShapedToken() {
        for (String token : List.of("unresolved", "schedule", "external-list", "application-filter", "access-role", "CpmiHostCkp")) {
            Map<String, Object> row = new LinkedHashMap<>(Map.of("class", "UNKNOWN", "cell", "source", "status", "UNRESOLVED",
                "type", token, "nativeType", token, "partialPrior", true, "blockers", 1, "blockedRules", 1));
            var masked = mapper.valueToTree(PolicyPrivacy.mask(Map.of("blockers", Map.of("split", List.of(row))), "", names, ips)).path("blockers").path("split").get(0);
            assertEquals(token, masked.path("type").asText(), token); assertEquals(token, masked.path("nativeType").asText(), token);
        }
        for (String token : List.of("FW-TANGO-04", "fw-tango-04", "fwtango04", "host.example", "Core-Switch", "a b")) {
            Map<String, Object> row = new LinkedHashMap<>(Map.of("class", "UNKNOWN", "cell", "source", "status", "UNRESOLVED",
                "type", token, "nativeType", token, "partialPrior", true, "blockers", 1, "blockedRules", 1));
            var masked = mapper.valueToTree(PolicyPrivacy.mask(Map.of("blockers", Map.of("split", List.of(row))), "", names, ips)).path("blockers").path("split").get(0);
            assertEquals("UNKNOWN", masked.path("type").asText(), token); assertEquals("UNKNOWN", masked.path("nativeType").asText(), token);
        }
    }
}
