package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.List;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceArchiveLedger implements DeviceArchiveLedger {

    private final TransactionBoundary transactionBoundary;

    public JooqDeviceArchiveLedger(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void record(String deviceId, String archiveName) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into cp_device_archive(device_id, archive_name) values ({0}, {1}) on conflict (device_id, archive_name) do nothing",
                deviceId, archiveName));
    }

    @Override
    public void markDeleted(String deviceId, String archiveName) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "update cp_device_archive set deleted_at = now() where device_id = {0} and archive_name = {1} and deleted_at is null",
                deviceId, archiveName));
    }

    @Override
    public List<String> pendingFor(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                "select archive_name from cp_device_archive where device_id = {0} and deleted_at is null order by created_at",
                deviceId).map(row -> row.get("archive_name", String.class)));
    }
}
