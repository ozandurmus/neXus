package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/**
 * G5: a group member the rulebase dictionary did not carry is stored as an UNRESOLVED placeholder (its name is the
 * vendor uid). The viewer resolves it against the same domain's stored inventory and labels the provenance.
 */
class PolicyObjectInventoryResolutionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);

    private PolicySnapshot snapshot() {
        var meta = new Metadata("policy-1", "source-1", "Synthetic manager", "CP", "domain-1", "Synthetic domain", "Synthetic package",
            "2026-10-05T00:00:00Z", "", List.of());
        String group = ref(meta.id(), "object", "group-uid"), member = ref(meta.id(), "object", "inner-uid"),
            missing = ref(meta.id(), "object", "missing-uid");
        var objects = new LinkedHashMap<String, PolicyObject>();
        objects.put(group, new PolicyObject(group, "Synthetic group", "group", List.of(member, missing), List.of(), "RESOLVED"));
        // Placeholders exactly as CheckPointPolicyMapper writes them for dictionary misses.
        objects.put(member, new PolicyObject(member, "inner-uid", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        objects.put(missing, new PolicyObject(missing, "missing-uid", "unresolved", List.of(), List.of(), "UNRESOLVED"));
        var empty = new Cell(List.of(), false);
        var rule = new Rule("rule-1", "rule-uid", 1, "Synthetic rule", true, new Cell(List.of(group), false), empty, empty, empty, "Accept", "Log", "", Map.of());
        return new PolicySnapshot(meta, List.of(new Section("layer-1", "Synthetic layer", "CP access layer", null, List.of(rule))), objects);
    }
    private PolicyDomainIndex index(PolicySnapshot snapshot) {
        var inner = PolicyDomainIndexTest.item("inner-uid", "Synthetic nested group", "group", List.of("host-uid", "ghost-uid"));
        var host = PolicyDomainIndexTest.item("host-uid", "Synthetic host", "host", List.of(), "ipv4-address: 192.0.2.8");
        return new PolicyDomainIndex(List.of(PolicyDomainIndexTest.inventory("groups", "RESOLVED", inner),
            PolicyDomainIndexTest.inventory("hosts", "RESOLVED", host)), List.of(snapshot));
    }
    private PolicyQueryService service() {
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> new MockResult[]{new MockResult(0, null)}), SQLDialect.POSTGRES));
        return new PolicyQueryService(tx, mapper, names);
    }

    @Test void dictionaryMissesResolveFromTheDomainInventoryAndTrulyMissingMembersStayUnresolved() {
        var snapshot = snapshot();
        var body = mapper.valueToTree(service().object(snapshot, ref("policy-1", "object", "group-uid"), index(snapshot)).body()).path("object");
        assertEquals("RESOLVED", body.path("status").asText());
        var inner = body.path("children").get(0);
        assertEquals("DOMAIN_INVENTORY", inner.path("resolution").asText());
        assertEquals("Synthetic nested group", inner.path("name").asText()); assertEquals("RESOLVED", inner.path("status").asText());
        assertEquals("Synthetic host", inner.path("children").get(0).path("name").asText());
        assertEquals("ipv4-address: 192.0.2.8", inner.path("children").get(0).path("values").get(0).asText());
        assertEquals("UNRESOLVED", inner.path("children").get(1).path("status").asText(), "a member absent from the inventory stays UNRESOLVED");
        var missing = body.path("children").get(1);
        assertEquals("UNRESOLVED", missing.path("status").asText());
        assertFalse(missing.has("resolution"));
    }

    @Test void withoutAnInventoryTheSnapshotOnlyExpansionIsUnchanged() {
        var snapshot = snapshot();
        var body = mapper.valueToTree(service().object(snapshot, ref("policy-1", "object", "group-uid"), null).body()).path("object");
        assertEquals("UNRESOLVED", body.path("children").get(0).path("status").asText());
        assertEquals("UNRESOLVED", body.path("children").get(1).path("status").asText());
        // The public entry point falls back the same way when the stored inventory cannot be read.
        var fallback = mapper.valueToTree(service().object(snapshot, ref("policy-1", "object", "group-uid")).body()).path("object");
        assertEquals(2, fallback.path("children").size());
    }

    @Test void cyclesAndBudgetsHoldAcrossInventoryExpansion() {
        var snapshot = snapshot();
        var a = PolicyDomainIndexTest.item("inner-uid", "Synthetic cycle a", "group", List.of("cycle-uid"));
        var b = PolicyDomainIndexTest.item("cycle-uid", "Synthetic cycle b", "group", List.of("inner-uid"));
        var index = new PolicyDomainIndex(List.of(PolicyDomainIndexTest.inventory("groups", "RESOLVED", a, b)), List.of(snapshot));
        var body = mapper.valueToTree(service().object(snapshot, ref("policy-1", "object", "group-uid"), index).body()).path("object");
        assertEquals("CYCLE", body.path("children").get(0).path("children").get(0).path("children").get(0).path("status").asText());
    }

    @Test void aiviewProjectionKeepsProvenanceAndVendorClassButMasksNamesAndAddresses() {
        var row = Map.of("id", "object-1", "name", "Synthetic host", "type", "gateway", "status", "RESOLVED", "resolution", "DOMAIN_INVENTORY",
            "values", List.of("ipv4-address: 192.0.2.8", "native-type: CpmiSyntheticFuture", "native-type: bad value;"));
        var masked = mapper.valueToTree(PolicyPrivacy.mask(Map.of("object", row), "", names, ips)).path("object");
        assertEquals("DOMAIN_INVENTORY", masked.path("resolution").asText());
        assertEquals("gateway", masked.path("type").asText());
        assertNotEquals("Synthetic host", masked.path("name").asText());
        assertEquals("ipv4-address: " + ips.mask("192.0.2.8"), masked.path("values").get(0).asText());
        assertEquals("native-type: CpmiSyntheticFuture", masked.path("values").get(1).asText());
        assertFalse(masked.toString().contains("bad value"));
        var forged = mapper.valueToTree(PolicyPrivacy.mask(Map.of("resolution", "Synthetic private text"), "", names, ips));
        assertEquals("UNKNOWN", forged.path("resolution").asText());
        for (String type : List.of("application", "action", "track", "updatable-object", "access-role", "security-zone"))
            assertEquals(type, mapper.valueToTree(PolicyPrivacy.mask(Map.of("type", type), "", names, ips)).path("type").asText());
    }
}
