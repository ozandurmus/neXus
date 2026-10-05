package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.policy.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;

class CpObjectInventoryQueryTest {
    @Test void inventoryPagesAreScopedAndGapsUnusedAndGatewaysRemainIndependent() throws Exception {
        var mapper = new ObjectMapper();
        var items = IntStream.range(0, 201).mapToObj(i -> new CpObjectInventory.Item("object-" + i, "uid-" + i,
            "OBJ-HOST-" + i, "host", List.of(), List.of("ipv4-address: 192.0.2.8"), null, List.of())).toList();
        var inventories = List.of(
            new CpObjectInventory("source-1", "domain-1", "hosts", "2026-10-05T00:00:00Z", "RESOLVED", "", items, 5, 1.5),
            new CpObjectInventory("source-1", "domain-1", "networks", "2026-10-05T00:00:00Z", "COLLECTION_FAILED", "policy: TIMEOUT", List.of(), 0, 2),
            new CpObjectInventory("source-1", "domain-1", "unused-objects", "2026-10-05T00:00:00Z", "RESOLVED", "", items.subList(0, 1), 1, 1),
            new CpObjectInventory("source-1", "domain-1", "gateways-and-servers", "2026-10-05T00:00:00Z", "UNSUPPORTED", "COLLECTION_SKIPPED", List.of(), 0, 0));
        var create = DSL.using(SQLDialect.POSTGRES);
        var rows = create.fetchFromStringData(new String[]{"snapshot"});
        for (var inventory : inventories) {
            var row = create.newRecord(DSL.field("snapshot", String.class));
            row.setValue(DSL.field("snapshot", String.class), mapper.writeValueAsString(inventory)); rows.add(row);
        }
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String scope = context.sql() + Arrays.toString(context.bindings());
            assertTrue(scope.contains("source-1")); assertTrue(scope.contains("domain-1"));
            return new MockResult[]{new MockResult(rows.size(), rows)};
        }), SQLDialect.POSTGRES));
        var query = new PolicyQueryService(tx, mapper, new TopologyNamePseudonymizer(new byte[32]));
        var page = mapper.valueToTree(query.domainInventory("source-1", "domain-1", "objects", 1).orElseThrow().body());
        assertEquals(201, page.path("total").asInt()); assertEquals(1, page.path("objects").size());
        assertEquals("uid-200", page.path("objects").get(0).path("uid").asText()); assertEquals(2, page.path("types").size());
        assertEquals(1, query.domainInventory("source-1", "domain-1", "unused", 0).orElseThrow().body().get("total"));
        var gateways = mapper.valueToTree(query.domainInventory("source-1", "domain-1", "gateways", 0).orElseThrow().body());
        assertEquals("UNSUPPORTED", gateways.path("types").get(0).path("status").asText());
        assertEquals(0, gateways.path("total").asInt());
    }
}
