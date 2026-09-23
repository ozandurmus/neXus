package com.securityexpert.nexus.ui2.service.compliance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Where compliance evaluations persist between restarts (V59); an in-memory store serves tests. */
public interface ComplianceEvaluationStore {

    record Stored(String deviceId, String canonicalHash, String configHash, String rulesFingerprint, Instant evaluatedAt,
            String resultJson) {
    }

    List<Stored> loadAll();

    void save(Stored stored);

    static ComplianceEvaluationStore inMemory() {
        Map<String, Stored> rows = new ConcurrentHashMap<>();
        return new ComplianceEvaluationStore() {
            @Override
            public List<Stored> loadAll() {
                return List.copyOf(rows.values());
            }

            @Override
            public void save(Stored stored) {
                rows.put(stored.deviceId(), stored);
            }
        };
    }
}
