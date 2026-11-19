package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

public final class JooqAsaConfigRelationRepository implements AsaConfigRelationRepository {

    private final TransactionBoundary transactionBoundary;

    public JooqAsaConfigRelationRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    @Override
    public void record(String key, String deviceId, String result, int linesCompared, int linesDiffering,
            int linesEqualOnlyByMask, Map<String, Integer> differingKeywords) {
        String json = json(differingKeywords);
        transactionBoundary.inTransaction(dsl -> dsl.execute(
                "insert into asa_config_relation(artefact_id, device_id, relation, lines_compared, lines_differing, "
                        + "lines_equal_by_mask, differing_keywords, derived_at) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}::jsonb, now()) "
                        + "on conflict (artefact_id) do update set relation = excluded.relation, "
                        + "lines_compared = excluded.lines_compared, lines_differing = excluded.lines_differing, "
                        + "lines_equal_by_mask = excluded.lines_equal_by_mask, "
                        + "differing_keywords = excluded.differing_keywords, derived_at = now()",
                key, deviceId, result, linesCompared, linesDiffering, linesEqualOnlyByMask, json));
    }

    @Override
    public boolean linkManifest(String storageRef, String artefactId) {
        return transactionBoundary.inTransaction(dsl -> dsl.execute(
                "update asa_config_relation set artefact_id = {1} where artefact_id = {0} and artefact_id <> {1} "
                        + "and not exists (select 1 from asa_config_relation x where x.artefact_id = {1})",
                storageRef, artefactId)) > 0;
    }

    /** Keyword to count; keys are plain command words (the worker only produces such), still escaped defensively. */
    static String json(Map<String, Integer> counts) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Integer> e : new TreeMap<>(counts == null ? Map.<String, Integer>of() : counts).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey().replace("\\", "\\\\").replace("\"", "\\\"")).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }
}
