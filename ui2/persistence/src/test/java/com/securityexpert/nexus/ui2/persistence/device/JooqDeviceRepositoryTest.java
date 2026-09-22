package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
                        "management_ip", "ip_addresses", "backup_target" },
                new String[] { "device-1", "gateway", "check_point", "DRAFT", "member-1", "Quantum",
                        "R81.20", null, "cluster-1", null, null, null, null, "192.0.2.10", null, "false" });
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
    void summaryNeverSubstitutesManagementAddressForMissingHostname() {
        AtomicReference<String> sql = new AtomicReference<>();
        DSLContext create = DSL.using(SQLDialect.POSTGRES);
        Result<Record> rows = create.fetchFromStringData(
                new String[] { "device_id", "role", "vendor_hint", "enrollment_state", "observed_hostname",
                        "observed_model", "observed_software_version", "observed_ha_role", "cluster_member_ref",
                        "virtual_systems", "latest_job_state", "latest_job_type", "latest_job_terminal_reason",
                        "management_ip", "ip_addresses", "backup_target" },
                new String[] { "device-2", "gateway", "check_point", "DRAFT", null, null, null, null, null,
                        null, null, null, null, "192.0.2.11", null, "false" });
        var repository = new JooqDeviceRepository(new JooqTransactionBoundary(DSL.using(
                new MockConnection(context -> {
                    sql.set(context.sql());
                    return new MockResult[] { new MockResult(rows.size(), rows) };
                }), SQLDialect.POSTGRES)));

        DeviceSummaryRecord result = repository.listAll().get(0);

        assertTrue(result.observedHostname().isEmpty());
        assertEquals("192.0.2.11", result.managementIp().orElseThrow());
        assertFalse(sql.get().contains("coalesce(d.observed_hostname, dc.display_name, ep.address_ref)"));
    }
}
