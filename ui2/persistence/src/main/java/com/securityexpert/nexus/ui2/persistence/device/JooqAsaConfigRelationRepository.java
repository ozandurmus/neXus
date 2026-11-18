package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqAsaConfigRelationRepository implements AsaConfigRelationRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqAsaConfigRelationRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void record(String artefactId, String deviceId, String result, int linesCompared, int linesDiffering,
            int linesEqualOnlyByMask) {
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into asa_config_relation(artefact_id, device_id, relation, lines_compared, lines_differing, "
                        + "lines_equal_by_mask, derived_at) values ({0}, {1}, {2}, {3}, {4}, {5}, now()) "
                        + "on conflict (artefact_id) do update set relation = excluded.relation, "
                        + "lines_compared = excluded.lines_compared, lines_differing = excluded.lines_differing, "
                        + "lines_equal_by_mask = excluded.lines_equal_by_mask, derived_at = now()",
                artefactId, deviceId, result, linesCompared, linesDiffering, linesEqualOnlyByMask));
    }
}
