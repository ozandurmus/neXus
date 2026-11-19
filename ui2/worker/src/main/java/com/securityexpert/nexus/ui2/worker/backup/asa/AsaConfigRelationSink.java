package com.securityexpert.nexus.ui2.worker.backup.asa;

/** Where the ASA backup hands the derived running-vs-startup relation of a stored backup (never any text). */
@FunctionalInterface
public interface AsaConfigRelationSink {

    AsaConfigRelationSink NONE = (deviceId, storageRef, relation) -> { };

    /** {@code storageRef}: the storage reference of the stored bundle (the manifest's recovery_volume_path), not yet the artefact id. */
    void record(String deviceId, String storageRef, AsaConfigRelation relation);
}
