package com.securityexpert.nexus.ui2.integration.search;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.service.search.IpInventorySearch;
import com.securityexpert.nexus.ui2.service.search.IpQuery;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.*;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;

/** Real PostgreSQL operators, migrated indexes and bounded search projection; no network devices. */
class GlobalIpSearchTest {
    private static final byte[] KEY = "synthetic-search-test-key-32-bytes".getBytes(StandardCharsets.UTF_8);

    @Test void routeContainmentOrderingFallbackIpv6OverlapAndTenThousandRows() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("global_ip_routes"); var connection = fixture.appConnection()) {
            var db = DSL.using(connection, SQLDialect.POSTGRES);
            // Isolated stored evidence, no audit/job/device side effects. LIKE copies the migrated expression indexes.
            for (String table : List.of("device_inventory_run", "device_route", "device_interface", "device_interface_address"))
                db.execute("create temp table " + table + " (like public." + table + " including all)");
            db.execute("insert into device_inventory_run(run_id, device_id, job_id, collected_at, context_count) values "
                + "('old', 'device-1', 'job-old', '2026-10-04', 1), ('new', 'device-1', 'job-new', '2026-10-05', 2), "
                + "('other', 'device-2', 'job-other', '2026-10-05', 1)");
            db.execute("insert into device_route(route_id, run_id, context, destination, next_hop, interface, protocol, route_table) values "
                + "('stale', 'old', 'ctx-1', '192.0.2.8/32', null, 'eth0', 'host', 'vrf-old'), "
                + "('wide', 'new', 'ctx-1', '192.0.2.0/24', '192.0.2.1', 'eth0', 'static', 'vrf-1'), "
                + "('narrow', 'new', 'ctx-2', '192.0.2.0/28', null, 'eth1', 'connected', 'vrf-2'), "
                + "('default-1', 'new', 'ctx-1', '0.0.0.0/0', '192.0.2.1', 'eth0', 'default', 'vrf-1'), "
                + "('default-2', 'other', 'ctx-1', '0.0.0.0/0', null, 'eth0', 'default', 'vrf-1'), "
                + "('ipv6', 'new', 'ctx-2', '2001:db8::/64', '2001:db8::1', 'eth1', 'static', 'vrf-2'), "
                + "('unknown', 'new', 'ctx-2', 'unsupported', null, null, 'unknown', null)");
            db.execute("insert into device_interface(interface_id, run_id, context, name, kind, state) values "
                + "('iface-1', 'new', 'ctx-1', 'eth0', 'physical', 'up')");
            db.execute("insert into device_interface_address(address_id, interface_id, address, family, role) values "
                + "('addr-1', 'iface-1', '192.0.2.10/24', 'ipv4', 'member')");
            db.execute("insert into device_route(route_id, run_id, context, destination, protocol) "
                + "select 'bulk-' || n, 'new', 'ctx-1', '198.51.100.0/24', 'static' from generate_series(1, 10001) n");
            var tx = new JooqTransactionBoundary(db);
            var names = new TopologyNamePseudonymizer(KEY);
            var masking = new PrivacyMaskingResponseBodyAdvice(new SubnetPreservingIpMasker(KEY), names);
            var identities = Map.of("device-1", Map.<String, Object>of("hostname", names.maskDeviceName("Synthetic gateway", null)),
                "device-2", Map.<String, Object>of("hostname", names.maskDeviceName("Synthetic fallback", null)));
            var address = IpInventorySearch.search(tx, IpQuery.parse("192.0.2.8"), true, 20, 0, identities, masking, false);
            assertEquals(List.of("192.0.2.0/28", "192.0.2.0/24", "0.0.0.0/0"), address.items().stream().map(h -> h.get("destination")).toList());
            assertEquals("ctx-2", address.items().get(0).get("context"));
            assertEquals(true, address.items().get(2).get("default_fallback"));
            assertEquals("device-2", address.items().get(2).get("device_id"));
            assertEquals(false, address.items().get(0).get("default_fallback"));
            var overlap = IpInventorySearch.search(tx, IpQuery.parse("192.0.2.0/25"), true, 20, 0, identities, masking, false);
            assertEquals(4, overlap.total());
            assertEquals(1, IpInventorySearch.search(tx, IpQuery.parse("2001:db8::8"), true, 20, 0, identities, masking, false).total());
            assertEquals(1, IpInventorySearch.search(tx, IpQuery.parse("192.0.2.200"), false, 20, 0, identities, masking, false).total());
            var masked = IpInventorySearch.search(tx, IpQuery.parse("192.0.2.8"), true, 20, 0, identities, masking, true);
            String body = new ObjectMapper().writeValueAsString(masked.items());
            assertFalse(body.contains("192.0.2.")); assertFalse(body.contains("ctx-")); assertFalse(body.contains("vrf-"));
            assertFalse(body.contains("eth0")); assertFalse(body.contains("Synthetic gateway"));
            assertTrue(((String) masked.items().get(0).get("destination")).matches("2(?:4[0-9]|5[0-5])\\..*"));
            var ipv6 = IpInventorySearch.search(tx, IpQuery.parse("2001:db8::8"), true, 20, 0, identities, masking, true);
            assertFalse(new ObjectMapper().writeValueAsString(ipv6.items()).contains("2001:db8"));
            var bulk = IpInventorySearch.search(tx, IpQuery.parse("198.51.100.8"), true, 20, 0, identities, masking, false);
            assertEquals(10002, bulk.total()); assertEquals(20, bulk.items().size());
            assertEquals(20, IpInventorySearch.search(tx, IpQuery.parse("198.51.100.8"), true, 20, 20, identities, masking, false).items().size());
            assertTrue(IpInventorySearch.ROUTES.contains("limit {2} offset {3}"));
            db.execute("set enable_seqscan = off");
            String plan = db.fetch("explain select route_id from device_route where network(search_inet(destination)) && '192.0.2.8'::inet").toString();
            assertTrue(plan.contains("Index"), "The migrated GiST expression must support containment lookup");
        }
    }

    @Test void policyHostsNetworksRangesNestedGroupsUsageAndMaskedSearch() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("global_ip_policy"); var connection = fixture.appConnection()) {
            var db = DSL.using(connection, SQLDialect.POSTGRES);
            for (String table : List.of("policy_snapshot", "cp_policy_object_inventory", "cp_policy_json_chunk"))
                db.execute("create temp table " + table + " (like public." + table + " including all)");
            var mapper = new ObjectMapper();
            var cpHost = new CpObjectInventory.Item("inventory-host", "001", "Synthetic host", "host", List.of(),
                List.of("ipv4-address: 192.0.2.8"), null, List.of());
            var cpGroup = new CpObjectInventory.Item("inventory-group", "002", "Synthetic group", "group", List.of("001"), List.of(), null, List.of());
            var inventory = new CpObjectInventory("source-cp", "domain-cp", "hosts", "2026-10-05T00:00:00Z", "RESOLVED", "", List.of(cpHost, cpGroup), 1, 1);
            db.execute("insert into cp_policy_object_inventory(source_id, domain_ref, object_type, collected_at, snapshot) values ({0}, {1}, 'hosts', '2026-10-05T00:00:00Z', {2}::jsonb)", "source-cp", "domain-cp", mapper.writeValueAsString(inventory));
            var empty = new Cell(List.of(), false);
            for (String vendor : List.of("CP", "PAN")) {
                String policy = "policy-" + vendor;
                String hostId = vendor.equals("CP") ? ref(policy, "object", "001") : "pan-host";
                String groupId = vendor.equals("CP") ? ref(policy, "object", "002") : "pan-group";
                var host = new PolicyObject(hostId, "Synthetic address", vendor.equals("CP") ? "host" : "address", List.of(),
                    List.of(vendor.equals("CP") ? "ipv4-address: 192.0.2.8" : "ip-netmask: 192.0.2.8"), "RESOLVED");
                var group = new PolicyObject(groupId, "Synthetic group", vendor.equals("CP") ? "group" : "address-group", List.of(hostId), List.of(), "RESOLVED");
                var outer = new PolicyObject("outer-" + vendor, "Synthetic outer", "address-group", List.of(groupId), List.of(), "RESOLVED");
                var rule = new Rule("rule-" + vendor, "uuid-" + vendor, 1, "Synthetic rule", true,
                    new Cell(List.of(outer.id(), hostId), false), empty, empty, empty, "Allow", "Log", "", Map.of());
                var meta = new Metadata(policy, vendor.equals("CP") ? "source-cp" : "source-pan", "Synthetic manager", vendor,
                    vendor.equals("CP") ? "domain-cp" : "domain-pan", "Synthetic domain", "Synthetic policy", "2026-10-05T00:00:00Z", "artifact-1", List.of());
                var snapshot = new PolicySnapshot(meta, List.of(new Section("section-1", "Synthetic section", "Pre rules", null, List.of(rule))),
                    Map.of(hostId, host, groupId, group, outer.id(), outer));
                db.execute("insert into policy_snapshot values ({0}, now(), {1}::jsonb, {2}::jsonb)", policy,
                    mapper.writeValueAsString(meta), mapper.writeValueAsString(snapshot));
            }
            var service = new PolicyQueryService(new JooqTransactionBoundary(db), mapper, new TopologyNamePseudonymizer(KEY), null, new SubnetPreservingIpMasker(KEY));
            var result = service.searchAddresses("192.0.2.8", 20, 0, false);
            assertEquals(6, result.get("total"));
            for (Object item : (List<?>) result.get("items")) {
                var hit = (Map<?, ?>) item;
                assertEquals(1L, hit.get("rule_count")); assertTrue(((String) hit.get("href")).contains("tab=objects"));
            }
            assertEquals(6, service.searchAddresses("192.0.2.0/24", 20, 0, false).get("total"));
            assertEquals(2, ((List<?>) service.searchAddresses("192.0.2.8", 2, 2, false).get("items")).size());
            String masked = mapper.writeValueAsString(service.searchAddresses("192.0.2.8", 20, 0, true));
            assertFalse(masked.contains("Synthetic")); assertFalse(masked.contains("192.0.2.8"));
            assertTrue(service.domainObjects("source-pan", "domain-pan", 0, "", "", "", true).isPresent());
            // Publish gzip chunks with a metadata-only manifest; cross both parser and matching batch boundaries.
            var chunkItems = new java.util.ArrayList<CpObjectInventory.Item>(inventory.objects());
            for (int n = 0; n < 450; n++) chunkItems.add(new CpObjectInventory.Item("filler-" + n, "filler-" + n,
                "Synthetic filler " + "x".repeat(400), "host", List.of(), List.of("ipv4-address: 198.51.100.8"), null, List.of()));
            chunkItems.add(new CpObjectInventory.Item("chunk-only", "opaque-0003", "Synthetic chunk address", "host",
                List.of(), List.of("ipv4-address: 203.0.113.8"), null, List.of()));
            var chunkInventory = new CpObjectInventory(inventory.sourceId(), inventory.containerId(), inventory.type(),
                "2026-10-06T00:00:00Z", "RESOLVED", "", chunkItems, 3, 1);
            new com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository(new JooqTransactionBoundary(db))
                .saveInventory("source-cp", "domain-cp", "hosts", chunkInventory.collectedAt(),
                    mapper.writeValueAsString(chunkInventory), "synthetic-actor");
            assertEquals(true, db.fetchValue("select chunk_count > 1 and jsonb_array_length(snapshot->'objects') = 0 "
                + "from cp_policy_object_inventory where source_id = 'source-cp'"));
            assertEquals(6, service.searchAddresses("192.0.2.8", 20, 0, false).get("total"));
            var chunkResult = service.searchAddresses("203.0.113.8", 20, 0, false);
            assertEquals(1, chunkResult.get("total"));
            assertEquals("chunk-only", ((Map<?, ?>) ((List<?>) chunkResult.get("items")).get(0)).get("id"));
            assertEquals(1, service.searchAddresses("203.0.113.0/24", 20, 0, false).get("total"));
            assertFalse(mapper.writeValueAsString(service.searchAddresses("203.0.113.8", 20, 0, true)).contains("Synthetic"));
            for (String json : List.of(
                "{\"type\":\"network\",\"values\":[\"subnet4: 192.0.2.0\",\"subnet-mask: 255.255.255.0\"]}",
                "{\"type\":\"address-range\",\"values\":[\"ipv4-address-first: 192.0.2.1\",\"ipv4-address-last: 192.0.2.9\"]}",
                "{\"type\":\"address\",\"values\":[\"ip-range: 192.0.2.1-192.0.2.9\"]}"))
                assertEquals(true, db.fetchValue("select search_policy_address({0}::jsonb, '192.0.2.8'::inet)", json));
            assertEquals(true, db.fetchValue("select search_policy_address({0}::jsonb, '2001:db8::8'::inet)",
                "{\"type\":\"network\",\"values\":[\"subnet6: 2001:db8::\",\"mask-length6: 64\"]}"));
            assertEquals(false, db.fetchValue("select search_policy_address({0}::jsonb, '192.0.2.8'::inet)",
                "{\"type\":\"network\",\"values\":[\"subnet4: 192.0.2.0\",\"subnet-mask: 255.0.255.0\"]}"));
        }
    }
}
