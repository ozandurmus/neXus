package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceOnboardingRepository implements DeviceOnboardingRepository {

    private static final String SELECT = "select device_id, source, state, step, step_job_id, reason, skipped, "
            + "started_at, updated_at, completed_at from device_onboarding ";

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqDeviceOnboardingRepository(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    @Override
    public void start(String deviceId, String source, String confirmJobId, String actorFingerprint, String actionId) {
        audited.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> dsl.execute(
                "insert into device_onboarding(device_id, source, state, step, step_job_id) "
                        + "values ({0}, {1}, 'RUNNING', 'identity', {2}) on conflict (device_id) do update set "
                        + "state = 'RUNNING', step = 'identity', step_job_id = excluded.step_job_id, reason = null, "
                        + "skipped = '', updated_at = now(), completed_at = null",
                deviceId, source, confirmJobId));
    }

    @Override
    public Optional<DeviceOnboarding> find(String deviceId) {
        return tx.inTransaction(dsl -> dsl.fetch(SELECT + "where device_id = {0}", deviceId).stream()
                .findFirst().map(JooqDeviceOnboardingRepository::map));
    }

    @Override
    public List<DeviceOnboarding> findRunning() {
        return tx.inTransaction(dsl -> dsl.fetch(SELECT + "where state = 'RUNNING' order by updated_at").stream()
                .map(JooqDeviceOnboardingRepository::map).toList());
    }

    @Override
    public List<DeviceOnboarding> findOpen() {
        return tx.inTransaction(dsl -> dsl.fetch(SELECT + "where state <> 'COMPLETED'").stream()
                .map(JooqDeviceOnboardingRepository::map).toList());
    }

    @Override
    public boolean advance(String deviceId, String expectedStep, Optional<String> expectedJobId, String state,
            String step, Optional<String> stepJobId, Optional<String> reason, String skipped,
            String actorFingerprint, String actionId) {
        Integer updated = audited.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> dsl.execute(
                "update device_onboarding set state = {0}, step = {1}, step_job_id = {2}, reason = {3}, skipped = {4}, "
                        + "updated_at = now(), completed_at = case when {0} = 'COMPLETED' then now() else null end "
                        + "where device_id = {5} and step = {6} and step_job_id is not distinct from {7}",
                state, step, stepJobId.orElse(null), reason.map(r -> r.length() > 600 ? r.substring(0, 600) : r).orElse(null),
                skipped, deviceId, expectedStep, expectedJobId.orElse(null)));
        return updated != null && updated == 1;
    }

    private static DeviceOnboarding map(Record r) {
        return new DeviceOnboarding(r.get("device_id", String.class), r.get("source", String.class),
                r.get("state", String.class), r.get("step", String.class),
                Optional.ofNullable(r.get("step_job_id", String.class)), Optional.ofNullable(r.get("reason", String.class)),
                r.get("skipped", String.class), instant(r.get("started_at", OffsetDateTime.class)),
                instant(r.get("updated_at", OffsetDateTime.class)),
                Optional.ofNullable(r.get("completed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
