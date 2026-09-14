package com.securityexpert.nexus.ui2.persistence.artefact;

/**
 * {@code backup_artefact} / {@code artefact_retention_ledger} persistence
 * (migration V17, BK-16, BK-18). One artefact-class-agnostic manifest table
 * for every artefact this store holds -- the configuration path records
 * {@code artefact_class = 'configuration'} rows here alongside its own
 * authoritative {@code configuration_artefact} table; a future backup
 * capability records {@code artefact_class = 'backup'} rows the same way.
 */
public interface BackupArtefactManifestRepository {

    /**
     * Writes the manifest row and its retention-ledger {@code created}
     * event in one audited transaction (BK-18: the ledger is written
     * "whenever an artefact is created"). {@code manifest}'s own compact
     * constructor already refused construction for an unresolvable
     * check_point software version (C7 section 3.3), so a call reaching
     * this method has already passed that gate.
     */
    void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId);
}
