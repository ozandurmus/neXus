package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Read-only view of {@code asa_config_relation} (V250, V253): for each device the relation of its NEWEST backup, when
 * that backup has one. Counts only (never a configuration line or value). No write path.
 */
public final class AsaConfigRelationReader {

    /** The newest backup's saved-vs-running result of one device. */
    public record Latest(String deviceId, String relation, int linesDiffering, Map<String, Integer> differingKeywords,
            Instant backupCreatedAt, Instant derivedAt) {
    }

    /** Newest backup per device (all devices), then its relation row; devices whose newest backup has none are absent. */
    public static final String LATEST_SQL = """
            select n.device_id, n.created_at, rel.relation, rel.lines_differing,
                   rel.differing_keywords::text as differing_keywords, rel.derived_at
            from (select distinct on (a.device_id) a.device_id, a.artefact_id, a.created_at
                  from backup_artefact a
                  where a.artefact_class = 'backup'
                  order by a.device_id, a.created_at desc, a.artefact_id desc) n
            join asa_config_relation rel on rel.artefact_id = n.artefact_id
            order by n.device_id""";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() { };

    private final TransactionBoundary transactionBoundary;

    public AsaConfigRelationReader(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public List<Latest> latestPerDevice() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(LATEST_SQL).map(r -> new Latest(
                r.get("device_id", String.class),
                r.get("relation", String.class),
                r.get("lines_differing", Integer.class) == null ? 0 : r.get("lines_differing", Integer.class),
                parse(r.get("differing_keywords", String.class)),
                instant(r.get("created_at", Object.class)),
                instant(r.get("derived_at", Object.class)))));
    }

    static Map<String, Integer> parse(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new TreeMap<>(MAPPER.readValue(json, COUNTS));
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toInstant();
        }
        return value instanceof Instant i ? i : null;
    }
}
