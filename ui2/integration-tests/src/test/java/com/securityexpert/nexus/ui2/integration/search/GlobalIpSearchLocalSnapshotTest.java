package com.securityexpert.nexus.ui2.integration.search;

import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.policy.PanoramaPolicyMapper;
import com.securityexpert.nexus.ui2.service.boot.DeviceCompositionConfiguration.ArtefactStoreAccess;
import com.securityexpert.nexus.ui2.service.policy.LocalFirewallPolicyService;
import com.securityexpert.nexus.ui2.service.policy.PolicyQueryService;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

/** Real PostgreSQL search_policy_address and scope SQL combined with local ASA, FortiGate and PAN firewall snapshots. */
class GlobalIpSearchLocalSnapshotTest {
    private static final String ASA_CONFIG = """
            ASA Version 9.18(4)22
            interface GigabitEthernet0/0
             nameif outside
             security-level 0
            interface GigabitEthernet0/1
             nameif inside
             security-level 100
            object network ASA-SRV-TANGO-04
             host 192.0.2.44
            object network ASA-NET-TANGO-04
             subnet 198.51.100.0 255.255.255.0
            object network ASA-PEER-TANGO-04
             host 198.51.100.20
            access-list ACL-OUT extended permit tcp any object ASA-SRV-TANGO-04 eq 443
            access-list ACL-OUT extended permit tcp object ASA-SRV-TANGO-04 object ASA-PEER-TANGO-04 eq 8443
            access-list ACL-OUT extended permit tcp object ASA-NET-TANGO-04 object ASA-PEER-TANGO-04 eq 22
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
                edit "FGT-SRV-TANGO-04"
                    set subnet 192.0.2.44 255.255.255.255
                next
            end
            config firewall addrgrp
                edit "FGT-GRP-TANGO-04"
                    set member "FGT-SRV-TANGO-04"
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
                    set dstaddr "FGT-GRP-TANGO-04"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
                edit 2
                    set srcintf "port1"
                    set dstintf "any"
                    set srcaddr "FGT-CLIENT-TANGO-04"
                    set dstaddr "FGT-SRV-TANGO-04"
                    set action accept
                    set schedule "always"
                    set service "HTTPS"
                next
            end
            """;

    private static final String PAN_LOCAL_XML = "<config><devices><entry name='localhost.localdomain'><vsys><entry name='vsys1'>"
            + "<address><entry name='PAN-SRV-TANGO-04'><ip-netmask>192.0.2.44/32</ip-netmask></entry></address>"
            + "<rulebase><security><rules><entry name='ALLOW-PAN'>"
            + "<source><member>any</member></source>"
            + "<destination><member>PAN-SRV-TANGO-04</member></destination>"
            + "<service><member>application-default</member></service>"
            + "<application><member>any</member></application>"
            + "<action>allow</action>"
            + "</entry></rules></security></rulebase>"
            + "</entry></vsys></entry></devices></config>";

    private static LocalFirewallPolicyService localService() {
        var create = DSL.using(SQLDialect.POSTGRES);
        var collected = Timestamp.from(Instant.parse("2026-11-25T10:00:00Z"));
        var localTx = new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            String sql = context.sql();
            if (sql.contains("d.vendor_hint = 'cisco_asa'")) {
                var rows = create.newResult(new org.jooq.Field<?>[]{
                        DSL.field("device_id", String.class),
                        DSL.field("collected_at", Timestamp.class),
                        DSL.field("artefact_ref", String.class),
                        DSL.field("wrapped_data_key", byte[].class),
                        DSL.field("compression", String.class)});
                var row = create.newRecord(rows.fields());
                row.set(DSL.field("device_id", String.class), "asa-tango-04");
                row.set(DSL.field("collected_at", Timestamp.class), collected);
                row.set(DSL.field("artefact_ref", String.class), "artefact-asa-tango-04");
                row.set(DSL.field("wrapped_data_key", byte[].class), new byte[32]);
                row.set(DSL.field("compression", String.class), "none");
                rows.add(row);
                return new MockResult[]{new MockResult(1, rows)};
            }
            if (sql.contains("d.vendor_hint = 'fortinet'")) {
                var rows = create.newResult(new org.jooq.Field<?>[]{
                        DSL.field("device_id", String.class),
                        DSL.field("observed_software_version", String.class),
                        DSL.field("collected_at", Timestamp.class),
                        DSL.field("artefact_ref", String.class),
                        DSL.field("wrapped_data_key", byte[].class),
                        DSL.field("compression", String.class)});
                var row = create.newRecord(rows.fields());
                row.set(DSL.field("device_id", String.class), "fgt-tango-04");
                row.set(DSL.field("observed_software_version", String.class), "v7.4.8 build2795");
                row.set(DSL.field("collected_at", Timestamp.class), collected);
                row.set(DSL.field("artefact_ref", String.class), "artefact-fgt-tango-04");
                row.set(DSL.field("wrapped_data_key", byte[].class), new byte[32]);
                row.set(DSL.field("compression", String.class), "none");
                rows.add(row);
                return new MockResult[]{new MockResult(1, rows)};
            }
            if (sql.contains("d.vendor_hint = 'palo_alto'")) {
                var rows = create.newResult(new org.jooq.Field<?>[]{
                        DSL.field("device_id", String.class),
                        DSL.field("collected_at", Timestamp.class),
                        DSL.field("artefact_ref", String.class),
                        DSL.field("wrapped_data_key", byte[].class),
                        DSL.field("compression", String.class)});
                var row = create.newRecord(rows.fields());
                row.set(DSL.field("device_id", String.class), "pan-tango-04");
                row.set(DSL.field("collected_at", Timestamp.class), collected);
                row.set(DSL.field("artefact_ref", String.class), "artefact-pan-tango-04");
                row.set(DSL.field("wrapped_data_key", byte[].class), new byte[32]);
                row.set(DSL.field("compression", String.class), "none");
                rows.add(row);
                return new MockResult[]{new MockResult(1, rows)};
            }
            return new MockResult[]{new MockResult(0, create.newResult())};
        }), SQLDialect.POSTGRES));

        Map<String, String> artefacts = Map.of(
                "artefact-asa-tango-04", ASA_CONFIG,
                "artefact-fgt-tango-04", FGT_CONFIG,
                "artefact-pan-tango-04", PAN_LOCAL_XML);
        ArtefactStore store = new ArtefactStore() {
            @Override
            public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) {
                throw new UnsupportedOperationException();
            }

            @Override
            public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException {
                String content = artefacts.get(ref.value());
                if (content == null) throw new IOException("missing synthetic artefact");
                return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
        };
        return new LocalFirewallPolicyService(localTx, new ArtefactStoreAccess(store));
    }

    @Test
    void localAsaAndFortiGateSnapshotsJoinAddressSearchMaskNamesDeduplicateSqlScopesAndAnswerDuplicates() throws Exception {
        try (var fixture = Ui2PostgresFixture.createAndMigrate("global_ip_local_policy");
                var connection = fixture.appConnection()) {
            var db = DSL.using(connection, SQLDialect.POSTGRES);
            for (String table : List.of("policy_snapshot", "cp_policy_object_inventory", "cp_policy_json_chunk")) {
                db.execute("create temp table " + table + " (like public." + table + " including all)");
            }
            var mapper = new ObjectMapper();
            byte[] key = new byte[32];

            // Store the same PAN scope ("pan-tango-04", ref("pan-tango-04", "vsys1")) in SQL too: must appear once.
            var panStored = new PanoramaPolicyMapper()
                    .local("pan-tango-04", "2026-11-25T10:00:00Z",
                            ref("configuration-artefact", "artefact-pan-tango-04"), PAN_LOCAL_XML)
                    .get(0);
            db.execute("insert into policy_snapshot values ({0}, now(), {1}::jsonb, {2}::jsonb)",
                    panStored.metadata().id(),
                    mapper.writeValueAsString(panStored.metadata()),
                    mapper.writeValueAsString(panStored));

            var local = localService();
            var service = new PolicyQueryService(
                    new JooqTransactionBoundary(db),
                    mapper,
                    new TopologyNamePseudonymizer(key),
                    local,
                    new SubnetPreservingIpMasker(key));

            var result = service.searchAddresses("192.0.2.44", 20, 0, false);
            assertEquals(4, result.get("total"));
            assertEquals(false, result.get("partial"));
            assertEquals(0, result.get("unavailableScopes"));
            @SuppressWarnings("unchecked")
            var items = (List<Map<String, Object>>) result.get("items");
            Map<String, Long> byName = new LinkedHashMap<>();
            for (var hit : items) {
                assertNull(byName.put((String) hit.get("name"), (Long) hit.get("rule_count")),
                        "duplicate scope hit for " + hit.get("name"));
                assertTrue(((String) hit.get("href")).contains("tab=objects"));
            }
            assertEquals(Map.of(
                    "ASA-SRV-TANGO-04", 2L,
                    "FGT-GRP-TANGO-04", 1L,
                    "FGT-SRV-TANGO-04", 2L,
                    "PAN-SRV-TANGO-04", 1L), byName);
            // Sorted scope order: asa-tango-04 before fgt-tango-04 before pan-tango-04.
            assertTrue(((String) items.get(0).get("href")).contains("source_id=asa-tango-04"));
            assertTrue(((String) items.get(items.size() - 1).get("href")).contains("source_id=pan-tango-04"));

            var subnetHits = service.searchAddresses("198.51.100.42", 20, 0, false);
            assertEquals(1, subnetHits.get("total"));
            @SuppressWarnings("unchecked")
            var subnetItems = (List<Map<String, Object>>) subnetHits.get("items");
            assertEquals("ASA-NET-TANGO-04", subnetItems.get(0).get("name"));
            assertEquals(1L, subnetItems.get(0).get("rule_count"));

            var maskedResult = service.searchAddresses("192.0.2.44", 20, 0, true);
            assertEquals(4, maskedResult.get("total"));
            String maskedBody = mapper.writeValueAsString(maskedResult);
            for (String rawName : List.of(
                    "ASA-SRV-TANGO-04", "ASA-NET-TANGO-04", "ASA-PEER-TANGO-04",
                    "FGT-SRV-TANGO-04", "FGT-GRP-TANGO-04", "FGT-CLIENT-TANGO-04",
                    "PAN-SRV-TANGO-04", "192.0.2.44")) {
                assertFalse(maskedBody.contains(rawName), "masked search leaked raw token: " + rawName);
            }

            var asaDuplicates = service.objectDuplicates("asa-tango-04", ref("asa-tango-04", "single"), 0, "", false);
            assertTrue(asaDuplicates.isPresent());
            assertInstanceOf(List.class, asaDuplicates.orElseThrow().body().get("duplicates"));
            var asaBody = asaDuplicates.orElseThrow().body();
            assertEquals(((List<?>) asaBody.get("duplicates")).size(), ((Number) asaBody.get("total")).intValue());
            for (var group : (List<?>) asaBody.get("duplicates")) {
                assertFalse(((List<?>) ((Map<?, ?>) group).get("objects")).isEmpty());
            }

            var fgtDuplicates = service.objectDuplicates("fgt-tango-04", ref("fgt-tango-04", "root"), 0, "", true);
            assertTrue(fgtDuplicates.isPresent());
            assertInstanceOf(List.class, fgtDuplicates.orElseThrow().body().get("duplicates"));
            var fgtBody = fgtDuplicates.orElseThrow().body();
            assertEquals(((List<?>) fgtBody.get("duplicates")).size(), ((Number) fgtBody.get("total")).intValue());
            for (var group : (List<?>) fgtBody.get("duplicates")) {
                assertFalse(((List<?>) ((Map<?, ?>) group).get("objects")).isEmpty());
            }

            assertTrue(service.objectDuplicates("missing-device", "missing-domain", 0, "", false).isEmpty());
        }
    }
}
