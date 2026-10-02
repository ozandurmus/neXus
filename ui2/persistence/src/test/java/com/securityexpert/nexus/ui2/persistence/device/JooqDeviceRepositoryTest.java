package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

class JooqDeviceRepositoryTest {
    @Test
    void diagnosticTargetsUseOneReadWithEnrollmentDisabledAndTransportFilters() {
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(query -> {
                    count.incrementAndGet();
                    assertTrue(query.sql().contains("where not d.disabled and d.enrollment_state in ('ENROLLED','DEGRADED')"));
                    assertTrue(query.sql().contains("order by e.created_at limit 1)=?"));
                    assertEquals("ssh_exec",query.bindings()[0]);
                    return new MockResult[]{new MockResult(0,DSL.using(SQLDialect.POSTGRES)
                        .newResult(DSL.field("device_id",String.class)))};
                }),SQLDialect.POSTGRES)));
        assertTrue(repository.listReadCollectionTargets("ssh_exec").isEmpty());
        assertEquals(1,count.get());
    }


    private static final class PairState {
        String claim;
        String ref;
        String unit;
    }

    @Test
    void haPairRequiresOneOtherEnrolledClaimAndDropsStalePair() {
        Map<String, PairState> state = new LinkedHashMap<>();
        for (String id : List.of("x", "y", "z", "w")) state.put(id, new PairState());
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    String sql = context.sql();
                    Object[] b = context.bindings();
                    if (sql.startsWith("select pg_advisory_xact_lock")) {
                        Result<Record> rows = create.fetchFromStringData(new String[] { "lock" }, new String[] { "" });
                        return new MockResult[] { new MockResult(1, rows) };
                    }
                    if (sql.contains("from devices where device_id =") && sql.endsWith("for update")) {
                        PairState s = state.get(b[0]);
                        Result<Record> rows = create.fetchFromStringData(
                                new String[] { "vendor_hint", "ha_pair_claim", "cluster_member_ref", "enrollment_state" },
                                new String[] { "cisco_asa", s.claim, s.ref, "ENROLLED" });
                        return new MockResult[] { new MockResult(1, rows) };
                    }
                    if (sql.startsWith("select device_id from devices")) {
                        List<String> peers = state.entrySet().stream()
                                .filter(e -> !e.getKey().equals(b[0]) && b[2].equals(e.getValue().claim)
                                        && !b[3].equals(e.getValue().unit))
                                .map(Map.Entry::getKey).limit(2).toList();
                        List<String[]> data = new java.util.ArrayList<>();
                        data.add(new String[] { "device_id" });
                        peers.forEach(id -> data.add(new String[] { id }));
                        Result<Record> rows = create.fetchFromStringData(data.toArray(String[][]::new));
                        return new MockResult[] { new MockResult(peers.size(), rows) };
                    }
                    if (sql.startsWith("update devices set ha_pair_claim")) state.get(b[1]).claim = (String) b[0];
                    if (sql.startsWith("update devices set ha_unit_token")) state.get(b[1]).unit = (String) b[0];
                    if (sql.startsWith("update devices set cluster_member_ref = null where device_id <>")) {
                        state.forEach((id, s) -> { if (!id.equals(b[0]) && b[2].equals(s.claim) && b[2].equals(s.ref)) s.ref = null; });
                    } else if (sql.startsWith("update devices set cluster_member_ref = null")) {
                        PairState s = state.get(b[0]);
                        if (b[1].equals(s.ref)) s.ref = null;
                    } else if (sql.startsWith("update devices set cluster_member_ref =")) {
                        state.get(b[1]).ref = (String) b[0];
                        state.get(b[2]).ref = (String) b[0];
                    }
                    return new MockResult[] { new MockResult(1, null) };
                }), SQLDialect.POSTGRES)));

        String a = "asa-failover|aaaaaaaaaaaaaaaa";
        repository.recordHaPairClaim("x", a + "|1111111111111111", "synthetic-actor", "claim");
        assertEquals(null, state.get("x").ref);
        // A second record of the SAME unit (same unit token) never corroborates.
        repository.recordHaPairClaim("w", a + "|1111111111111111", "synthetic-actor", "claim");
        assertEquals(null, state.get("x").ref);
        assertEquals(null, state.get("w").ref);
        state.remove("w");
        repository.recordHaPairClaim("y", a + "|2222222222222222", "synthetic-actor", "claim");
        assertEquals(a, state.get("x").ref);
        assertEquals(a, state.get("y").ref);
        repository.recordHaPairClaim("z", a + "|3333333333333333", "synthetic-actor", "claim");
        assertEquals(null, state.get("z").ref);
        assertEquals(a, state.get("x").ref);
        assertEquals(a, state.get("y").ref);
        repository.recordHaPairClaim("x", "asa-failover|bbbbbbbbbbbbbbbb|1111111111111111", "synthetic-actor", "claim");
        assertEquals(null, state.get("x").ref);
        assertEquals(null, state.get("y").ref);
    }

    @Test
    void clusterReferenceUpdateWritesOnlyWhenDifferent() {
        List<String> sql = new ArrayList<>();
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    if (context.sql().startsWith("update devices set cluster_member_ref")) {
                        sql.add(context.sql());
                        return new MockResult[] { new MockResult(sql.size() == 1 ? 1 : 0, null) };
                    }
                    return new MockResult[] { new MockResult(0, null) };
                }), SQLDialect.POSTGRES)));

        assertTrue(repository.setClusterMemberRef("device-1", Optional.of("asa-failover|abc123"),
                "synthetic-actor", "asa_failover_pairing"));
        assertFalse(repository.setClusterMemberRef("device-1", Optional.of("asa-failover|abc123"),
                "synthetic-actor", "asa_failover_pairing"));
        assertTrue(sql.stream().allMatch(statement -> statement.contains("cluster_member_ref is distinct from")));
    }

    @Test
    void observedRoleRefreshWritesPresentRoleAndKeepsItWhenAbsent() {
        AtomicReference<String> storedRole = new AtomicReference<>();
        List<String> updates = new ArrayList<>();
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    if (context.sql().startsWith("update devices set observed_hostname")) {
                        updates.add(context.sql());
                        Object incomingRole = context.bindings()[3]; // bindings follow the SQL text order: {1},{2},{3},{4} in the SET clause first
                        if (incomingRole != null) {
                            storedRole.set(incomingRole.toString());
                        }
                    }
                    return new MockResult[] { new MockResult(1, null) };
                }), SQLDialect.POSTGRES)));

        assertTrue(repository.refreshObservedFacts("device-1", Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("primary"), "synthetic-actor", "refresh"));
        assertEquals("primary", storedRole.get());
        assertTrue(repository.refreshObservedFacts("device-1", Optional.of("FW-TANGO-04"), Optional.empty(), Optional.empty(),
                Optional.empty(), "synthetic-actor", "refresh"));
        assertEquals("primary", storedRole.get());
        assertTrue(updates.stream().allMatch(sql -> sql.contains("observed_ha_role = coalesce(")));
        assertEquals(2, updates.size());
    }

    @Test
    void missingBackupDispositionReportsCountWithoutDeletingAnything() {
        List<String> sql = new ArrayList<>();
        JooqDeviceRepository repository = deletionRepository(2, sql);

        DeviceRepository.DeleteResult result = repository.deleteDevice(
                "device-1", null, "actor", "device.delete");

        assertFalse(result.deleted());
        assertTrue(result.dispositionRequired());
        assertEquals(2, result.backupArtefactCount());
        assertFalse(sql.stream().anyMatch(statement -> statement.startsWith("delete ")));
    }

    @Test
    void keepNeverDeletesBackupHistory() {
        List<String> sql = new ArrayList<>();
        JooqDeviceRepository repository = deletionRepository(1, sql);

        DeviceRepository.DeleteResult result = repository.deleteDevice(
                "device-1", DeviceRepository.BackupDisposition.KEEP, "actor", "device.delete");

        assertTrue(result.deleted());
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("delete from backup_artefact")));
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("delete from artefact_retention_ledger")));
    }

    @Test
    void noArtefactsNeedsNoDisposition() {
        List<String> sql = new ArrayList<>();
        JooqDeviceRepository repository = deletionRepository(0, sql);

        DeviceRepository.DeleteResult result = repository.deleteDevice(
                "device-1", null, "actor", "device.delete");

        assertTrue(result.deleted());
        assertFalse(result.dispositionRequired());
        assertTrue(sql.stream().anyMatch(statement -> statement.startsWith("delete from devices ")));
    }

    @Test
    void removeDeletesOnlyTheArtefactAndNotItsRecordedHistory() {
        List<String> sql = new ArrayList<>();
        JooqDeviceRepository repository = deletionRepository(1, sql);

        DeviceRepository.DeleteResult result = repository.deleteDevice(
                "device-1", DeviceRepository.BackupDisposition.REMOVE, "actor", "device.delete");

        assertTrue(result.deleted());
        assertEquals(1, sql.stream().filter(statement -> statement.startsWith("delete from backup_artefact ")).count());
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("delete from artefact_retention_ledger")));
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("delete from backup_artefact_retrieval")));
        assertFalse(sql.stream().anyMatch(statement -> statement.contains("delete from backup_deviation_record")));
    }

    private static JooqDeviceRepository deletionRepository(int artefactCount, List<String> sql) {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Field<Integer> count = DSL.field("count", Integer.class);
        Result<Record1<Integer>> countResult = create.newResult(count);
        countResult.add(create.newRecord(count).values(artefactCount));
        return new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    sql.add(context.sql());
                    if (context.sql().startsWith("select count(*) from backup_artefact")) {
                        return new MockResult[] { new MockResult(1, countResult) };
                    }
                    return new MockResult[] { new MockResult(1, null) };
                }), SQLDialect.POSTGRES)));
    }

    @Test
    void summaryPrefersParentlessCandidateButStillAllowsClusterMembers() {
        AtomicReference<String> sql = new AtomicReference<>();
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> rows = create.fetchFromStringData(
                new String[] { "device_id", "role", "vendor_hint", "enrollment_state", "observed_hostname",
                        "observed_model", "observed_software_version", "observed_ha_role", "cluster_member_ref",
                        "virtual_systems", "latest_job_state", "latest_job_type", "latest_job_terminal_reason",
                        "management_ip", "ip_addresses", "backup_target", "ha_peer_unconfirmed" },
                new String[] { "device-1", "gateway", "check_point", "DRAFT", "member-1", "Quantum",
                        "R81.20", null, "cluster-1", null, null, null, null, "192.0.2.10", null, "false", "false" });
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    sql.set(context.sql());
                    return new MockResult[] { new MockResult(rows.size(), rows) };
                }), SQLDialect.POSTGRES)));

        List<DeviceSummaryRecord> result = repository.listAll();

        assertTrue(sql.get().contains("order by (c.parent_candidate_id is null) desc"));
        assertFalse(sql.get().contains("and c.parent_candidate_id is null"));
        assertEquals("member-1", result.get(0).observedHostname().orElseThrow());
        assertEquals("Quantum", result.get(0).observedModel().orElseThrow());
        assertEquals("cluster-1", result.get(0).clusterMemberRef().orElseThrow());
    }

    @Test
    void findSummaryUsesBoundDeviceLookupAndReturnsEmptyWhenAbsent() {
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> rows = create.fetchFromStringData(
                new String[] { "device_id", "role", "vendor_hint", "enrollment_state", "observed_hostname",
                        "observed_model", "observed_software_version", "observed_ha_role", "cluster_member_ref",
                        "virtual_systems", "latest_job_state", "latest_job_type", "latest_job_terminal_reason",
                        "management_ip", "ip_addresses", "backup_target", "ha_peer_unconfirmed" },
                new String[] { "device-1", "gateway", "check_point", "DRAFT", null, "Quantum",
                        null, null, null, null, null, null, null, null, null, "true", "false" });
        List<Object> lookups = new ArrayList<>();
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    assertTrue(context.sql().endsWith("where d.device_id = ?"));
                    assertTrue(context.sql().contains("coalesce(d.observed_model, dc.model, c_parent.model)"));
                    assertEquals(1, context.bindings().length);
                    lookups.add(context.bindings()[0]);
                    return new MockResult[] { new MockResult(rows.size(), rows) };
                }), SQLDialect.POSTGRES)));

        DeviceSummaryRecord result = repository.findSummary("device-1").orElseThrow();
        assertEquals("device-1", result.deviceId());
        assertEquals("Quantum", result.observedModel().orElseThrow());
        assertTrue(result.backupTarget());

        rows.clear();
        assertTrue(repository.findSummary("device-missing").isEmpty());
        assertEquals(List.of("device-1", "device-missing"), lookups);
    }

    @Test
    void summaryNeverSubstitutesManagementAddressForMissingHostname() {
        AtomicReference<String> sql = new AtomicReference<>();
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> rows = create.fetchFromStringData(
                new String[] { "device_id", "role", "vendor_hint", "enrollment_state", "observed_hostname",
                        "observed_model", "observed_software_version", "observed_ha_role", "cluster_member_ref",
                        "virtual_systems", "latest_job_state", "latest_job_type", "latest_job_terminal_reason",
                        "management_ip", "ip_addresses", "backup_target", "ha_peer_unconfirmed" },
                new String[] { "device-2", "gateway", "check_point", "DRAFT", null, null, null, null, null,
                        null, null, null, null, "192.0.2.11", null, "false", "true" });
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    sql.set(context.sql());
                    return new MockResult[] { new MockResult(rows.size(), rows) };
                }), SQLDialect.POSTGRES)));

        DeviceSummaryRecord result = repository.listAll().get(0);

        assertTrue(result.observedHostname().isEmpty());
        assertTrue(result.haPeerUnconfirmed());
        assertEquals("192.0.2.11", result.managementIp().orElseThrow());
        assertFalse(sql.get().contains("coalesce(d.observed_hostname, dc.display_name, ep.address_ref)"));
    }
}
