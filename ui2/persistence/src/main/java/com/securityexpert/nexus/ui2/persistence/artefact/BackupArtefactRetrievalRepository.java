package com.securityexpert.nexus.ui2.persistence.artefact;

/**
 * {@code backup_artefact_retrieval} persistence (migration V18, 14I OR-3):
 * every operator retrieval through the CLI is audited as its own typed
 * action -- the actor, the artefact id, the reason, and the destination
 * path. A retrieval is not a read of a view; it is an act, and it is
 * recorded as one.
 */
public interface BackupArtefactRetrievalRepository {

    void record(String retrievalId, String artefactId, String reason, String destinationPath,
            String actorFingerprint, String actionId);
}
