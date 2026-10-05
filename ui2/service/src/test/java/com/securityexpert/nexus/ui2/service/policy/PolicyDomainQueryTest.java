package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyDomainIndexTest.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;

class PolicyDomainQueryTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<CpObjectInventory> inventories = new ArrayList<>();
    private final List<PolicySnapshot> snapshots = new ArrayList<>();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);
    private PolicyQueryService query() { return query(false); }
    private PolicyQueryService query(boolean chunked) {
        var create = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String scope = context.sql() + Arrays.toString(context.bindings());
            assertTrue(scope.contains("source-1")); assertTrue(scope.contains("domain-1"));
            if (context.sql().contains("from cp_policy_json_chunk")) {
                var inventory = inventories.stream().filter(i -> i.type().equals(context.bindings()[3])).findFirst().orElseThrow();
                String json = mapper.valueToTree(inventory).toString();
                int start = (Integer) context.bindings()[5] * 512;
                byte[] plain = json.substring(start, Math.min(json.length(), start + 512)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                var rows = create.newResult(new org.jooq.Field<?>[]{DSL.field("payload", byte[].class), DSL.field("plain_bytes", Integer.class)});
                var row = create.newRecord(rows.fields());
                row.set(DSL.field("payload", byte[].class), gzip(plain)); row.set(DSL.field("plain_bytes", Integer.class), plain.length); rows.add(row);
                return new MockResult[]{new MockResult(1, rows)};
            }
            if (context.sql().contains("from devices manager")) {
                var rows = create.fetchFromStringData(new String[]{"stable_identifier", "display_name", "device_id"},
                    new String[]{"gateway-uid", "Synthetic gateway", "device-1"});
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }
            boolean inventoryQuery = context.sql().contains("cp_policy_object_inventory");
            var rows = create.fetchFromStringData(new String[]{"snapshot", "object_type", "chunk_generation", "chunk_count"});
            var values = inventoryQuery ? inventories : snapshots;
            for (var value : values) {
                var row = create.newRecord(rows.fields());
                var json = mapper.valueToTree(value);
                String payload = json.toString();
                if (chunked && inventoryQuery) {
                    String type = ((CpObjectInventory) value).type();
                    var header = ((com.fasterxml.jackson.databind.node.ObjectNode) json).deepCopy(); header.putArray("objects");
                    row.set(DSL.field("snapshot", String.class), header.toString());
                    row.set(DSL.field("object_type", String.class), type);
                    row.set(DSL.field("chunk_generation", String.class), "generation-" + type);
                    row.set(DSL.field("chunk_count", String.class), String.valueOf((payload.length() + 511) / 512));
                } else row.set(DSL.field("snapshot", String.class), payload);
                rows.add(row);
            }
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }), SQLDialect.POSTGRES));
        return new PolicyQueryService(tx, mapper, names, null, ips);
    }
    private static byte[] gzip(byte[] plain) {
        try {
            var out = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(out)) { gzip.write(plain); }
            return out.toByteArray();
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
    }
    @Test void chunkStreamPreservesEveryDomainApiIncludingCountsAndCrossBatchUsageAndDuplicates() {
        var hosts = IntStream.range(0, 401).mapToObj(i -> item("uid-" + i, "Synthetic host " + i, "host", List.of(),
            "ipv4-address: 192.0.2.8")).toArray(CpObjectInventory.Item[]::new);
        inventories.add(inventory("hosts", "RESOLVED", hosts));
        inventories.add(inventory("unused-objects", "RESOLVED", hosts[0], hosts[400]));
        inventories.add(inventory("groups", "RESOLVED", item("group-uid", "Synthetic group", "group", List.of("uid-0", "uid-400"))));
        inventories.add(inventory("gateways-and-servers", "RESOLVED", new CpObjectInventory.Item(
            "gateway-1", "gateway-uid", "Synthetic gateway", "simple-gateway", List.of(), List.of(), null,
            List.of(new CpObjectInventory.Installation("Synthetic package", true)))));
        snapshots.add(snapshot(List.of(new Target("all-reference", "ALL", "", "UNKNOWN"))));
        var old = query(false); var chunks = query(true);
        for (String view : List.of("objects", "unused", "gateways")) for (int page : List.of(0, 1, 2))
            assertEquals(old.domainInventory("source-1", "domain-1", view, page).orElseThrow().body(),
                chunks.domainInventory("source-1", "domain-1", view, page).orElseThrow().body());
        assertEquals(old.domainObjects("source-1", "domain-1", 2, "", "", "", false).orElseThrow().body(),
            chunks.domainObjects("source-1", "domain-1", 2, "", "", "", false).orElseThrow().body());
        assertEquals(old.objectUsage("source-1", "domain-1", "uid-0", 0).orElseThrow().body(),
            chunks.objectUsage("source-1", "domain-1", "uid-0", 0).orElseThrow().body());
        assertEquals(old.objectDuplicates("source-1", "domain-1", 0, "", false).orElseThrow().body(),
            chunks.objectDuplicates("source-1", "domain-1", 0, "", false).orElseThrow().body());
        assertEquals(old.installations("source-1", "domain-1", 0, "", "", false).orElseThrow().body(),
            chunks.installations("source-1", "domain-1", 0, "", "", false).orElseThrow().body());
    }
    @Test void serverPagingSearchHygieneAndUsageAreScopedAndCacheInvalidatesOnAnySnapshotChange() {
        var hosts = IntStream.range(0, 201).mapToObj(i -> item("uid-" + i, "Synthetic host " + i, "host", List.of(), "ipv4-address: 192.0.2.8")).toArray(CpObjectInventory.Item[]::new);
        inventories.add(inventory("hosts", "RESOLVED", hosts));
        inventories.add(inventory("unused-objects", "RESOLVED", hosts[0]));
        var query = query();
        var cached = query.domainIndex("source-1", "domain-1");
        assertSame(cached, query.domainIndex("source-1", "domain-1"));
        var page = mapper.valueToTree(query.domainObjects("source-1", "domain-1", 1, "", "host", "", false).orElseThrow().body());
        assertEquals(201, page.path("total").asInt()); assertEquals(1, page.path("objects").size());
        assertEquals(1, query.domainObjects("source-1", "domain-1", 0, "", "host", "unused", false).orElseThrow().body().get("total"));
        assertEquals(0, query.domainObjects("source-1", "domain-1", 0, "Synthetic host", "", "", true).orElseThrow().body().get("total"));
        String maskedName = names.maskPolicyName("address", hosts[0].name());
        assertEquals(1, query.domainObjects("source-1", "domain-1", 0, maskedName, "", "", true).orElseThrow().body().get("total"));
        String maskedIp = ips.maskText("192.0.2.8");
        assertEquals(201, query.domainObjects("source-1", "domain-1", 0, maskedIp, "", "", true).orElseThrow().body().get("total"));
        assertEquals(1, query.objectDuplicates("source-1", "domain-1", 0, "", true).orElseThrow().body().get("total"));
        assertTrue(query.objectUsage("source-1", "domain-1", "missing", 0).isEmpty());
        inventories.add(inventory("groups", "RESOLVED", item("group-uid", "Synthetic group", "group", List.of("uid-0"))));
        snapshots.add(snapshot(List.of()));
        assertNotSame(cached, query.domainIndex("source-1", "domain-1"));
        var usage = mapper.valueToTree(query.objectUsage("source-1", "domain-1", "uid-0", 0).orElseThrow().body());
        assertEquals(1, usage.path("ruleCount").asInt()); assertEquals(1, usage.path("groupCount").asInt());
        assertEquals(7, usage.path("rules").get(0).path("number").asInt());
        assertEquals(1, query.domainObjects("source-1", "domain-1", 0, "", "group", "single", false).orElseThrow().body().get("total"));
    }
    @Test void allTargetsAndReportedInstallationRemainSeparateAndInventoryLinksUseExactMatches() {
        var gateway = new CpObjectInventory.Item("gateway-1", "gateway-uid", "Synthetic gateway", "simple-gateway", List.of(), List.of(), null,
            List.of(new CpObjectInventory.Installation("Synthetic package", true)));
        var unknown = new CpObjectInventory.Item("gateway-2", "other-uid", "Synthetic unmatched gateway", "simple-cluster", List.of(), List.of(), null,
            List.of(new CpObjectInventory.Installation("Synthetic package", null)));
        var manager = item("manager-uid", "Synthetic manager", "checkpoint-host", List.of());
        inventories.add(inventory("gateways-and-servers", "RESOLVED", gateway, unknown, manager));
        snapshots.add(snapshot(List.of(new Target("all-reference", "ALL", "", "UNKNOWN"))));
        var query = query();
        var result = mapper.valueToTree(query.installations("source-1", "domain-1", 0, "", "", false).orElseThrow().body());
        assertEquals(2, result.path("total").asInt());
        var rows = result.path("installations");
        assertEquals("device-1", rows.get(0).path("deviceId").asText());
        assertTrue(rows.get(0).path("installed").asBoolean()); assertTrue(rows.get(0).path("allTargets").asBoolean());
        assertEquals("", rows.get(1).path("deviceId").asText()); assertTrue(rows.get(1).path("installed").isNull());
        assertTrue(rows.get(1).path("targeted").asBoolean());
        assertEquals(0, query.installations("source-1", "domain-1", 0, "", "wrong-policy", false).orElseThrow().body().get("total"));
        assertEquals(0, query.installations("source-1", "domain-1", 0, "Synthetic", "", true).orElseThrow().body().get("total"));
        var conflicting = new CpObjectInventory.Item(gateway.id(), gateway.uid(), gateway.name(), gateway.type(), List.of(), List.of(), null,
            List.of(new CpObjectInventory.Installation("Synthetic package", true), new CpObjectInventory.Installation("Synthetic package", false)));
        inventories.set(0, inventory("gateways-and-servers", "RESOLVED", conflicting));
        var ambiguous = mapper.valueToTree(query.installations("source-1", "domain-1", 0, "", "", false).orElseThrow().body());
        assertTrue(ambiguous.path("installations").get(0).path("installed").isNull());
        inventories.clear();
        var missing = mapper.valueToTree(query.installations("source-1", "domain-1", 0, "", "", false).orElseThrow().body());
        assertEquals(1, missing.path("total").asInt()); assertTrue(missing.path("installations").get(0).path("installed").isNull());
        assertTrue(missing.path("installations").get(0).path("allTargets").asBoolean());
    }
}
