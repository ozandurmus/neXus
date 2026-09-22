package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** V44: the artefact an operator declared a device's reference point ("baseline"). */
public interface BackupBaselineRepository {

    record Baseline(String deviceId, String artefactId, Instant setAt) {
    }

    Optional<Baseline> find(String deviceId);

    /** device_id -> artefact_id for every device that has one; the fleet table's badge. */
    Map<String, String> findAll();

    /** Audited upsert. */
    void set(String deviceId, String artefactId, String actorFingerprint, String actionId);

    /** Audited delete; false when there was none. */
    boolean clear(String deviceId, String actorFingerprint, String actionId);
}
