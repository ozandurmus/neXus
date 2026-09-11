package com.securityexpert.nexus.ui2.persistence.device;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * jOOQ-backed {@link DeviceRepository}. Every mutation carries the audit
 * context (F3) through {@link AuditedTransactionBoundary}: {@code devices}
 * and {@code endpoints} are both audited on every INSERT/UPDATE/DELETE
 * (V1's {@code trg_audit_devices}/{@code trg_audit_endpoints}).
 */
public final class JooqDeviceRepository implements DeviceRepository {

    private static final String DEVICE_COLUMNS = "device_id, vendor_hint, registration_source, created_at, "
            + "is_test_target, enrollment_state, disabled, credential_reference_id";
    private static final String ENDPOINT_COLUMNS = "endpoint_id, device_id, transport_kind, address_ref, created_at";

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqDeviceRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Optional<DeviceRecord> find(String deviceId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + DEVICE_COLUMNS + " from devices where device_id = {0}",
                    deviceId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toDeviceRecord);
        });
    }

    @Override
    public Optional<EndpointRecord> findEndpoint(String endpointId) {
        return transactionBoundary.inTransaction(dsl -> {
            Result<Record> rows = dsl.fetch("select " + ENDPOINT_COLUMNS + " from endpoints where endpoint_id = {0}",
                    endpointId);
            return rows.stream().findFirst().map(JooqDeviceRepository::toEndpointRecord);
        });
    }

    @Override
    public String registerDraft(DeviceDraft draft, String actorFingerprint, String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> {
            Timestamp now = Timestamp.from(Instant.now());
            dsl.execute("insert into devices(device_id, vendor_hint, registration_source, created_at, "
                    + "is_test_target, enrollment_state, disabled, credential_reference_id) "
                    + "values ({0}, {1}, {2}, {3}, {4}, 'DRAFT', false, {5})",
                    draft.deviceId(), draft.vendorHint(), draft.registrationSource(), now, draft.isTestTarget(),
                    draft.credentialReferenceId());
            dsl.execute("insert into endpoints(endpoint_id, device_id, transport_kind, address_ref, created_at) "
                    + "values ({0}, {1}, {2}, {3}, {4})",
                    draft.endpointId(), draft.deviceId(), draft.transportKind(), draft.addressRef(), now);
            return draft.deviceId();
        });
    }

    @Override
    public boolean transitionEnrollmentState(String deviceId, DeviceEnrollmentState fromState,
            DeviceEnrollmentState toState, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set enrollment_state = {0} where device_id = {1} and enrollment_state = {2}",
                toState.name(), deviceId, fromState.name()));
        return updated == 1;
    }

    @Override
    public boolean withdrawDraft(String deviceId, String actorFingerprint, String actionId) {
        int deleted = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "delete from devices where device_id = {0} and enrollment_state = 'DRAFT'", deviceId));
        return deleted == 1;
    }

    @Override
    public boolean setDisabled(String deviceId, boolean disabled, String actorFingerprint, String actionId) {
        int updated = auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "update devices set disabled = {0} where device_id = {1} and disabled <> {0}", disabled, deviceId));
        return updated == 1;
    }

    private static DeviceRecord toDeviceRecord(Record row) {
        return new DeviceRecord(
                row.get("device_id", String.class),
                row.get("vendor_hint", String.class),
                row.get("registration_source", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("is_test_target", Boolean.class),
                DeviceEnrollmentState.fromColumnValue(row.get("enrollment_state", String.class)),
                row.get("disabled", Boolean.class),
                row.get("credential_reference_id", String.class));
    }

    private static EndpointRecord toEndpointRecord(Record row) {
        return new EndpointRecord(
                row.get("endpoint_id", String.class),
                row.get("device_id", String.class),
                row.get("transport_kind", String.class),
                row.get("address_ref", String.class),
                row.get("created_at", Timestamp.class).toInstant());
    }
}
