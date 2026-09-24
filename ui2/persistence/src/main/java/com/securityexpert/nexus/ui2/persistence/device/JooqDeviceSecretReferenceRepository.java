package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqDeviceSecretReferenceRepository implements DeviceSecretReferenceRepository {

    private final TransactionBoundary tx;
    private final AuditedTransactionBoundary audited;

    public JooqDeviceSecretReferenceRepository(TransactionBoundary tx) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.audited = new AuditedTransactionBoundary(tx);
    }

    @Override
    public Optional<String> find(String deviceId, String purpose) {
        return tx.inTransaction(dsl -> dsl.fetch("select credential_reference_id from device_secret_reference "
                + "where device_id = {0} and purpose = {1}", deviceId, purpose).stream().findFirst()
                .map(r -> r.get("credential_reference_id", String.class)));
    }

    @Override
    public void set(String deviceId, String purpose, String credentialReferenceId, String actorFingerprint, String actionId) {
        audited.inTransaction(actorFingerprint, actionId, (DSLContext dsl) -> dsl.execute(
                "insert into device_secret_reference(device_id, purpose, credential_reference_id, set_by_actor_fingerprint) "
                        + "values ({0}, {1}, {2}, {3}) on conflict (device_id, purpose) do update set "
                        + "credential_reference_id = excluded.credential_reference_id, "
                        + "set_by_actor_fingerprint = excluded.set_by_actor_fingerprint, set_at = now()",
                deviceId, purpose, credentialReferenceId, actorFingerprint));
    }
}
