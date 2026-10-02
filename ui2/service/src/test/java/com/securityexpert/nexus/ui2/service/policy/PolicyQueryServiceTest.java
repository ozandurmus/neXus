package com.securityexpert.nexus.ui2.service.policy;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.util.*;
import java.util.stream.IntStream;

class PolicyQueryServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final PolicyQueryService query = new PolicyQueryService(mock(TransactionBoundary.class), mapper, names);
    private PolicySnapshot snapshot() {
        var meta = new Metadata("policy-1", "source-1", "Synthetic manager", "CP", "domain-1", "Synthetic domain",
                "Synthetic policy", "2026-10-01T12:00:00Z", "artifact-1", List.of());
        var empty = new Cell(List.of(), false);
        var rules = IntStream.range(0, 5000).mapToObj(i -> new Rule("rule-" + i, "uuid-" + i, i + 1, "Synthetic rule " + i,
                true, empty, empty, empty, empty, "Accept", "Log", "Sensitive synthetic comment", Map.<String, List<String>>of())).toList();
        return new PolicySnapshot(meta, List.of(new Section("section-1", "Synthetic section", "CP access layer", null, rules)),
                Map.of("a", new PolicyObject("a", "Synthetic group", "group", List.of("b", "c"), List.of(), "RESOLVED"),
                        "b", new PolicyObject("b", "Synthetic nested", "group", List.of("a", "c"), List.of(), "RESOLVED"),
                        "c", new PolicyObject("c", "Synthetic leaf", "address", List.of(), List.of("192.0.2.8"), "RESOLVED")));
    }
    @Test void immutableSnapshotRoundTripsAndPagesFiveThousandRules() throws Exception {
        var snapshot = mapper.readValue(mapper.writeValueAsString(snapshot()), PolicySnapshot.class);
        var body = mapper.valueToTree(query.page(snapshot, 24, "", false).body());
        assertEquals(5000, body.path("total").asInt());
        assertEquals(200, body.path("sections").get(0).path("rules").size());
        assertEquals("rule-4800", body.path("sections").get(0).path("rules").get(0).path("id").asText());
        assertEquals(0, mapper.valueToTree(query.page(snapshot, Integer.MAX_VALUE, "", false).body()).path("sections").get(0).path("rules").size());
    }
    @Test void maskedSearchCannotBeUsedAsAnOracleForRawNamesOrComments() {
        var snapshot = snapshot();
        assertEquals(0, query.page(snapshot, 0, "Sensitive synthetic", true).body().get("total"));
        assertEquals(0, query.page(snapshot, 0, "Synthetic rule", true).body().get("total"));
        assertEquals(5000, query.page(snapshot, 0, "Sensitive synthetic", false).body().get("total"));
        assertEquals(1, query.page(snapshot, 0, names.maskPolicyName("name", "Synthetic rule 42"), true).body().get("total"));
    }
    @Test void expandsSharedDagNodesButStopsCyclesAndMissingObjects() {
        var body = mapper.valueToTree(query.object(snapshot(), "a").body()).path("object");
        assertEquals("CYCLE", body.path("children").get(0).path("children").get(0).path("status").asText());
        assertEquals("RESOLVED", body.path("children").get(0).path("children").get(1).path("status").asText());
        assertEquals("RESOLVED", body.path("children").get(1).path("status").asText());
        assertEquals("UNRESOLVED", mapper.valueToTree(query.object(snapshot(), "missing").body()).path("object").path("status").asText());
    }    @Test void localPolicyBadgeSurvivesAIViewProjection() {
        var base = snapshot();
        var local = new PolicySnapshot(base.metadata(), List.of(new Section("local-1", "Local rules", "vsys1", null, List.of())), Map.of());
        var page = query.page(local, 0, "", true).body();
        assertEquals("LOCAL_FIREWALL", page.get("policyKind"));
        var masked = (Map<?, ?>) PolicyPrivacy.mask(page, "", names, new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32]));
        assertEquals("LOCAL_FIREWALL", masked.get("policyKind"));
    }

    @Test void partialSnapshotFailureSurvivesPagingAndAIView() {
        var base = snapshot();
        var partial = new PolicySnapshot(base.metadata(), base.sections(), base.objects(),
            List.of(new CollectionFailure("layer-1", "access target=layer-1: TIMEOUT", "Synthetic layer", 4000)));
        var body = query.page(partial, 0, "", true).body();
        var masked = mapper.valueToTree(PolicyPrivacy.mask(body, "", names,
            new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32])));
        assertEquals("layer-1", masked.path("failures").get(0).path("layerRef").asText());
        assertEquals(4000, masked.path("failures").get(0).path("offset").asInt());
        assertEquals(names.maskPolicyName("layerName", "Synthetic layer"), masked.path("failures").get(0).path("layerName").asText());
        assertEquals("access target=layer-1: TIMEOUT", masked.path("failures").get(0).path("reason").asText());
    }

    @Test void numericPolicyLabelsAreMaskedAsWholeValuesOnly() {
        names.maskVirtualSystem("2", null);
        var body = Map.of("name", "2", "collectedAt", "2026-10-02T03:50:21Z",
                "id", "build-20261002-123", "total", 122);
        var masked = (Map<?, ?>) PolicyPrivacy.mask(body, "", names,
                new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32]));
        assertEquals(names.maskPolicyName("name", "2"), masked.get("name"));
        for (String field : List.of("collectedAt", "id", "total")) assertEquals(body.get(field), masked.get(field));
    }

    @Test void incrementalStatusAndPendingReasonPassThroughAIViewWithoutLayerNames() {
        var masked = mapper.valueToTree(PolicyPrivacy.mask(Map.of("layer", 2, "layers", 5, "rulesFetched", 4000,
            "reason", "COLLECTION_PENDING", "layerName", "Synthetic layer"), "", names,
            new com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker(new byte[32])));
        assertEquals(2, masked.path("layer").asInt());
        assertEquals(5, masked.path("layers").asInt());
        assertEquals(4000, masked.path("rulesFetched").asInt());
        assertEquals("COLLECTION_PENDING", masked.path("reason").asText());
        assertNotEquals("Synthetic layer", masked.path("layerName").asText());
    }

}
