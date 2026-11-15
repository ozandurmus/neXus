package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqArtefactStoreQuotaRepository implements ArtefactStoreQuotaRepository {

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary audited;

    public JooqArtefactStoreQuotaRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.audited = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public Map<String, Long> findAll() {
        return transactionBoundary.inTransaction(dsl -> {
            Map<String, Long> out = new LinkedHashMap<>();
            dsl.fetch("select store_name, quota_bytes from artefact_store_quota order by store_name")
                    .forEach(r -> out.put(r.get("store_name", String.class), r.get("quota_bytes", Long.class)));
            return out;
        });
    }

    @Override
    public void set(String storeName, long quotaBytes, String actorFingerprint, String actionId) {
        if (quotaBytes <= 0) {
            throw new IllegalArgumentException("quotaBytes must be > 0");
        }
        audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "insert into artefact_store_quota(store_name, quota_bytes, updated_by) values ({0}, {1}, {2}) "
                        + "on conflict (store_name) do update set quota_bytes = excluded.quota_bytes, "
                        + "updated_at = now(), updated_by = excluded.updated_by",
                storeName, quotaBytes, actorFingerprint));
    }

    @Override
    public boolean clear(String storeName, String actorFingerprint, String actionId) {
        return audited.inTransaction(actorFingerprint, actionId, dsl -> dsl.execute(
                "delete from artefact_store_quota where store_name = {0}", storeName)) == 1;
    }
}
