package com.securityexpert.nexus.ui2.worker.backup.asa;

/** Where the ASA backup hands the derived running-vs-startup relation of a stored backup (never any text). */
@FunctionalInterface
public interface AsaConfigRelationSink {

    AsaConfigRelationSink NONE = (deviceId, artefactId, relation) -> { };

    void record(String deviceId, String artefactId, AsaConfigRelation relation);
}
