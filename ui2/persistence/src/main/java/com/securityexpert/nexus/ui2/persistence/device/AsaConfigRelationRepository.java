package com.securityexpert.nexus.ui2.persistence.device;

/** V250 {@code asa_config_relation}: the derived running-vs-startup result of one stored ASA backup (counts, never text). */
public interface AsaConfigRelationRepository {

    /** Records (or replaces) the result of one backup artefact. {@code result}: MATCH, MISMATCH or NOT_EVALUABLE. */
    void record(String artefactId, String deviceId, String result, int linesCompared, int linesDiffering,
            int linesEqualOnlyByMask);
}
