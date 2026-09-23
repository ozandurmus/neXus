package com.securityexpert.nexus.ui2.service.compliance;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** {@link ComplianceEvaluationStore} over {@code compliance_evaluation} (V59). */
public final class JooqComplianceEvaluationStore implements ComplianceEvaluationStore {

    private final TransactionBoundary tx;

    public JooqComplianceEvaluationStore(TransactionBoundary tx) {
        this.tx = tx;
    }

    @Override
    public List<Stored> loadAll() {
        return tx.inTransaction(dsl -> dsl.fetch("select device_id, canonical_hash, config_hash, rules_fingerprint, evaluated_at, "
                + "result::text as result from compliance_evaluation")).stream()
                .map(r -> new Stored(r.get("device_id", String.class), r.get("canonical_hash", String.class),
                        r.get("config_hash", String.class), r.get("rules_fingerprint", String.class),
                        r.get("evaluated_at", OffsetDateTime.class).toInstant(), r.get("result", String.class)))
                .toList();
    }

    @Override
    public void save(Stored s) {
        tx.inTransaction(dsl -> dsl.execute("insert into compliance_evaluation(device_id, canonical_hash, config_hash, "
                + "rules_fingerprint, evaluated_at, result) values ({0}, {1}, {2}, {3}, {4}, {5}::jsonb) "
                + "on conflict (device_id) do update set canonical_hash = excluded.canonical_hash, config_hash = excluded.config_hash, "
                + "rules_fingerprint = excluded.rules_fingerprint, evaluated_at = excluded.evaluated_at, result = excluded.result",
                s.deviceId(), s.canonicalHash(), s.configHash(), s.rulesFingerprint(),
                OffsetDateTime.ofInstant(s.evaluatedAt(), ZoneOffset.UTC), s.resultJson()));
    }
}
