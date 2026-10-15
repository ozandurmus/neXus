package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqSystemLogMeasurementRepository implements SystemLogMeasurementRepository {
    static final String ACTOR = "system:worker";
    static final String ACTION = "system_log_measurement_record";
    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqSystemLogMeasurementRepository(TransactionBoundary transactions) {
        this.transactions = transactions;
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public boolean record(String jobId, long leaseEpoch, String deviceId, String vendor, String source, String summaryJson) {
        return audited.inTransaction(ACTOR, ACTION, dsl -> dsl.execute(
                "insert into system_log_measurement(measurement_id, device_id, vendor, source, summary, job_id) "
                        + "select {0}, {1}, {2}, {3}, cast({4} as jsonb), {5} where exists (select 1 from jobs j "
                        + "where j.job_id = {5} and j.lease_epoch = {6} and j.state = 'EXECUTING' "
                        + "and j.target_device_id = {1} and ui2_job_owner_valid(j.job_id, j.lease_epoch)) "
                        + "on conflict (job_id, source) do nothing",
                UUID.randomUUID().toString(), deviceId, vendor, source, summaryJson, jobId, leaseEpoch) == 1);
    }

    @Override
    public List<Measurement> list(Optional<String> deviceId, int limit) {
        int bounded = Math.max(1, Math.min(limit, 1000));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select measurement_id, device_id, vendor, source, measured_at, summary::text as summary, job_id "
                        + "from system_log_measurement where (cast({0} as text) is null or device_id = {0}) "
                        + "order by measured_at desc, measurement_id limit {1}",
                deviceId.orElse(null), bounded).stream()
                .map(row -> new Measurement(row.get("measurement_id", String.class), row.get("device_id", String.class),
                        row.get("vendor", String.class), row.get("source", String.class),
                        row.get("measured_at", OffsetDateTime.class).toInstant(), row.get("summary", String.class),
                        row.get("job_id", String.class)))
                .toList());
    }
}
