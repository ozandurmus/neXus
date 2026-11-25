package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.asa.AsaConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.asa.AsaPolicyMapper;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGateConfigPolicyParser;
import com.securityexpert.nexus.ui2.policy.fortigate.FortiGatePolicyMapper;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.util.*;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/** Snapshot-only local firewall domains (ASA, FortiGate, PAN local) in objectDuplicates and searchAddresses scopes. */
class PolicyQueryLocalSnapshotDomainTest {
    private static final String ASA_CONFIG = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            object network ASA-HOST-TANGO-04
             host 192.0.2.44
            object network ASA-PEER-TANGO-04
             host 198.51.100.20
            access-list ACL-OUT extended permit tcp any object ASA-HOST-TANGO-04 eq 443
            access-list ACL-OUT extended permit tcp object ASA-HOST-TANGO-04 object ASA-PEER-TANGO-04 eq 8443
            access-group ACL-OUT in interface outside
            """;

    private static final String FGT_CONFIG = """
            #config-version=FGVM64-7.4.8-FW-build2795:opmode=0:vdom=0
            config system interface
                edit "port1"
                    set vdom "root"
                    set ip 198.51.100.1 255.255.255.0
                next
            end
            config firewall address
                edit "FGT-CLIENT-TANGO-04"
                    set subnet 198.51.100.50 255.255.255.255
                next
                edit "FGT-HOST-TANGO-04"
                    set subnet 192.0.2.44 255.255.255.255
                next
            end
            config firewall service custom
                edit "HTTPS"
                    set tcp-portrange 443
                next
            end
            config firewall policy
                edit 1
                    set srcintf "port1"
                    set dstintf "any"
                    set srcaddr "FGT-CLIENT-TANGO-04"
                    set dstaddr "FGT-HOST-TANGO-04"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
            end
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final TopologyNamePseudonymizer names = new TopologyNamePseudonymizer(new byte[32]);
    private final SubnetPreservingIpMasker ips = new SubnetPreservingIpMasker(new byte[32]);

    private static PolicySnapshot asaSnapshot(String deviceId, String displayName) {
        return AsaPolicyMapper.map(deviceId, displayName, "2026-11-25T10:00:00Z",
                ref("configuration-artefact", "artefact-" + deviceId),
                AsaConfigPolicyParser.parse(ASA_CONFIG));
    }

    private static PolicySnapshot fgtSnapshot(String deviceId, String displayName) {
        return FortiGatePolicyMapper.map(deviceId, displayName, "2026-11-25T10:00:00Z",
                ref("configuration-artefact", "artefact-" + deviceId),
                FortiGateConfigPolicyParser.parse(FGT_CONFIG, "v7.4.8 build2795")).get(0);
    }

    private static PolicySnapshot panLocalSnapshotWithGroupDuplicate(String deviceId) {
        var meta = new Metadata(ref(deviceId, "local-firewall-policy", "vsys1"), deviceId, "FW-TANGO-04", "PAN",
                ref(deviceId, "vsys1"), "vsys1", "Local firewall policy", "2026-11-25T10:00:00Z",
                ref("configuration-artefact", "artefact-" + deviceId),
                List.of(new Target(deviceId, "FW-TANGO-04", "vsys1", "UNKNOWN")));
        var leaf = new PolicyObject(ref(deviceId, "addr-1"), "PAN-LEAF-TANGO-04", "address",
                List.of(), List.of("ip-netmask: 192.0.2.44/32"), "RESOLVED");
        var grpA = new PolicyObject(ref(deviceId, "grp-a"), "PAN-GRP-ALPHA", "group",
                List.of(leaf.id()), List.of(), "RESOLVED");
        var grpB = new PolicyObject(ref(deviceId, "grp-b"), "PAN-GRP-BRAVO", "group",
                List.of(leaf.id()), List.of(), "RESOLVED");
        var empty = new Cell(List.of(), false);
        var rule = new Rule(ref(deviceId, "r-1"), null, 1, "ALLOW-LOCAL", true,
                empty, new Cell(List.of(grpA.id()), false), empty, empty, "allow", "Log", "", Map.of());
        return new PolicySnapshot(meta,
                List.of(new Section(ref(deviceId, "s-1"), "Local rules", "vsys1", null, List.of(rule))),
                Map.of(leaf.id(), leaf, grpA.id(), grpA, grpB.id(), grpB));
    }

    private PolicyQueryService emptyDbService(LocalFirewallPolicyService local) {
        var create = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String sql = context.sql();
            if (sql.contains("union") && sql.contains("domain_ref")) {
                return new MockResult[]{new MockResult(0, create.fetchFromStringData(new String[]{"source_id", "domain_ref"}))};
            }
            if (sql.contains("as revision")) {
                return new MockResult[]{new MockResult(0, create.fetchFromStringData(new String[]{"revision"}))};
            }
            return new MockResult[]{new MockResult(0,
                    create.fetchFromStringData(new String[]{"snapshot", "object_type", "chunk_generation", "chunk_count"}))};
        }), SQLDialect.POSTGRES));
        return new PolicyQueryService(tx, mapper, names, local, ips);
    }

    @Test
    void snapshotOnlyDomainsAnswerObjectDuplicatesAndUnknownDomainsStayEmpty() {
        var asa = asaSnapshot("asa-tango-04", "FW-TANGO-04");
        var fgt = fgtSnapshot("fgt-tango-04", "FW-TANGO-04");
        var pan = panLocalSnapshotWithGroupDuplicate("pan-tango-04");
        var local = mock(LocalFirewallPolicyService.class);
        when(local.snapshots()).thenReturn(List.of(asa, fgt, pan));
        var service = emptyDbService(local);

        for (boolean masked : List.of(false, true)) {
            for (var snap : List.of(asa, fgt)) {
                var response = service.objectDuplicates(snap.metadata().sourceId(), snap.metadata().containerId(), 0, "", masked);
                assertTrue(response.isPresent(), "snapshot-only domain must answer objectDuplicates: " + snap.metadata().vendor());
                var body = response.orElseThrow().body();
                assertInstanceOf(List.class, body.get("duplicates"));
                int total = ((Number) body.get("total")).intValue();
                assertTrue(total >= 0);
                assertEquals(((List<?>) body.get("duplicates")).size(), total);
                assertEquals(0, body.get("page"));
                assertEquals(200, body.get("pageSize"));
            }
        }

        var panDuplicates = service.objectDuplicates(pan.metadata().sourceId(), pan.metadata().containerId(), 0, "", false)
                .orElseThrow().body();
        assertEquals(1, panDuplicates.get("total"));
        assertEquals(1, ((List<?>) panDuplicates.get("duplicates")).size());

        assertTrue(service.objectDuplicates("unknown-source", "unknown-domain", 0, "", false).isEmpty());
        assertTrue(service.objectDuplicates("asa-tango-04", "unknown-domain", 0, "", true).isEmpty());
        assertTrue(emptyDbService(null).objectDuplicates("asa-tango-04", asa.metadata().containerId(), 0, "", false).isEmpty());
    }

    @Test
    void addressScopesAreNullSafeSortedAndDeduplicatedAcrossSqlAndLocalSnapshots() {
        var asa = asaSnapshot("asa-tango-04", "FW-TANGO-04");
        var fgt = fgtSnapshot("fgt-tango-04", "FW-TANGO-04");
        var sharedPan = panLocalSnapshotWithGroupDuplicate("pan-shared-04");

        var create = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> new MockResult[]{
                new MockResult(2, create.fetchFromStringData(
                        new String[]{"source_id", "domain_ref"},
                        new String[]{"cp-mgmt-01", "domain-cp"},
                        new String[]{sharedPan.metadata().sourceId(), sharedPan.metadata().containerId()}))
        }), SQLDialect.POSTGRES));

        var withoutLocal = new PolicyQueryService(tx, mapper, names, null, ips);
        assertEquals(List.of(
                new PolicyQueryService.AddressScope("cp-mgmt-01", "domain-cp"),
                new PolicyQueryService.AddressScope(sharedPan.metadata().sourceId(), sharedPan.metadata().containerId())),
                withoutLocal.addressScopes());

        var local = mock(LocalFirewallPolicyService.class);
        // Include fgt twice and sharedPan (already in SQL) to verify full de-duplication and SQL-matching sort order.
        when(local.snapshots()).thenReturn(List.of(fgt, asa, fgt, sharedPan));
        var withLocal = new PolicyQueryService(tx, mapper, names, local, ips);
        assertEquals(List.of(
                new PolicyQueryService.AddressScope(asa.metadata().sourceId(), asa.metadata().containerId()),
                new PolicyQueryService.AddressScope("cp-mgmt-01", "domain-cp"),
                new PolicyQueryService.AddressScope(fgt.metadata().sourceId(), fgt.metadata().containerId()),
                new PolicyQueryService.AddressScope(sharedPan.metadata().sourceId(), sharedPan.metadata().containerId())),
                withLocal.addressScopes());
    }

    @Test
    void brokenLocalDomainIsSkippedFailClosedWithoutBreakingOtherScopes() throws Exception {
        var asa = asaSnapshot("asa-tango-04", "FW-TANGO-04");
        var fgt = fgtSnapshot("fgt-tango-04", "FW-TANGO-04");
        // Sorts before asa-tango-04 and fgt-tango-04 so it executes first in the scope loop.
        var brokenMeta = new Metadata(ref("asa", "asa-broken-01", "single"), "asa-broken-01", "FW-TANGO-04", "ASA",
                ref("asa-broken-01", "single"), "single", "ASA access rules", "2026-11-25T10:00:00Z",
                ref("configuration-artefact", "artefact-broken"), List.of());
        var brokenSnapshot = new PolicySnapshot(brokenMeta, List.of(), Map.of());

        var local = mock(LocalFirewallPolicyService.class);
        when(local.snapshots()).thenReturn(List.of(brokenSnapshot, asa, fgt));

        var create = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String sql = context.sql();
            Object[] bindings = context.bindings();
            if (bindings.length > 0 && "asa-broken-01".equals(bindings[0])) {
                throw new IllegalStateException("SIMULATED_UNREADABLE_DOMAIN");
            }
            if (sql.contains("union") && sql.contains("domain_ref")) {
                return new MockResult[]{new MockResult(0, create.fetchFromStringData(new String[]{"source_id", "domain_ref"}))};
            }
            if (sql.contains("search_policy_address")) {
                String batchJson = String.valueOf(bindings[0]);
                String needle = String.valueOf(bindings[1]);
                var rows = create.newResult(new org.jooq.Field<?>[]{DSL.field("uid", String.class)});
                try {
                    for (var node : mapper.readTree(batchJson)) {
                        for (var v : node.path("values")) {
                            if (v.asText().contains("ip-netmask: " + needle + "/32")) {
                                var row = create.newRecord(rows.fields());
                                row.set(DSL.field("uid", String.class), node.path("uid").asText());
                                rows.add(row);
                            }
                        }
                    }
                } catch (java.io.IOException e) {
                    throw new java.sql.SQLException(e);
                }
                return new MockResult[]{new MockResult(rows.size(), rows)};
            }
            if (sql.contains("as revision")) {
                return new MockResult[]{new MockResult(0, create.fetchFromStringData(new String[]{"revision"}))};
            }
            return new MockResult[]{new MockResult(0,
                    create.fetchFromStringData(new String[]{"snapshot", "object_type", "chunk_generation", "chunk_count"}))};
        }), SQLDialect.POSTGRES));

        var service = new PolicyQueryService(tx, mapper, names, local, ips);
        var unmasked = service.searchAddresses("192.0.2.44", 20, 0, false);
        assertEquals(2, unmasked.get("total"));
        // The caller must learn a scope was skipped: a missing hit is not "not present".
        assertEquals(true, unmasked.get("partial"));
        assertEquals(1, unmasked.get("unavailableScopes"));
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) unmasked.get("items");
        assertEquals(Map.of("ASA-HOST-TANGO-04", 2L, "FGT-HOST-TANGO-04", 1L),
                Map.of((String) items.get(0).get("name"), (Long) items.get(0).get("rule_count"),
                        (String) items.get(1).get("name"), (Long) items.get(1).get("rule_count")));

        String maskedJson = mapper.writeValueAsString(service.searchAddresses("192.0.2.44", 20, 0, true));
        assertFalse(maskedJson.contains("ASA-HOST-TANGO-04"));
        assertFalse(maskedJson.contains("FGT-HOST-TANGO-04"));
    }

    @Test
    void healthySearchIsNotPartial() {
        var asa = asaSnapshot("asa-tango-04", "FW-TANGO-04");
        var local = mock(LocalFirewallPolicyService.class);
        when(local.snapshots()).thenReturn(List.of(asa));
        var result = emptyDbService(local).searchAddresses("192.0.2.44", 20, 0, false);
        assertEquals(false, result.get("partial"));
        assertEquals(0, result.get("unavailableScopes"));
    }

    @Test
    void unreadableLocalSnapshotListIsReportedPartialNotSilentlyDropped() {
        var local = mock(LocalFirewallPolicyService.class);
        when(local.snapshots()).thenThrow(new IllegalStateException("SIMULATED_LOCAL_LIST_FAILURE"));
        var result = emptyDbService(local).searchAddresses("192.0.2.44", 20, 0, false);
        assertEquals(true, result.get("partial"));
        assertEquals(1, result.get("unavailableScopes"));
    }

    @Test
    void unreadableStoredScopeFailsTheWholeSearch() {
        var create = DSL.using(SQLDialect.POSTGRES);
        var tx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String sql = context.sql();
            if (sql.contains("union") && sql.contains("domain_ref")) {
                return new MockResult[]{new MockResult(1, create.fetchFromStringData(
                        new String[]{"source_id", "domain_ref"}, new String[]{"cp-mgmt-01", "domain-cp"}))};
            }
            throw new IllegalStateException("SIMULATED_UNREADABLE_STORED_DOMAIN");
        }), SQLDialect.POSTGRES));
        var service = new PolicyQueryService(tx, mapper, names, null, ips);
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> service.searchAddresses("192.0.2.44", 20, 0, false));
    }
}
