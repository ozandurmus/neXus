package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Map;

/** {@code asa_config_relation} (V250, V253): the derived running-vs-startup result of one stored ASA backup (counts, never text). */
public interface AsaConfigRelationRepository {

    /**
     * Records (or replaces) the result of one backup. {@code key} is the storage reference of the stored bundle until
     * {@link #linkManifest} re-keys the row to the manifest's artefact id. {@code result}: MATCH, MISMATCH or
     * NOT_EVALUABLE. {@code differingKeywords}: command keyword to number of differing lines (counts only).
     */
    void record(String key, String deviceId, String result, int linesCompared, int linesDiffering,
            int linesEqualOnlyByMask, Map<String, Integer> differingKeywords);

    /**
     * Once the manifest row exists: re-keys the row recorded under {@code storageRef} to {@code artefactId}, the
     * {@code backup_artefact.artefact_id} the service joins on. Returns whether a row was re-keyed.
     */
    boolean linkManifest(String storageRef, String artefactId);
}
