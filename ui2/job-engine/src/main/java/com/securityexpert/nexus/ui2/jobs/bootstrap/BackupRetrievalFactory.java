package com.securityexpert.nexus.ui2.jobs.bootstrap;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupRetrievalComposition;
import com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort;

/**
 * Composition helper for 14I OR-1..OR-5's CLI retrieval parity, mirroring
 * {@link CredentialAdministrationFactory} exactly: {@code cli} depends on
 * {@code job-engine} (which already depends on {@code persistence}) rather
 * than on {@code persistence} directly.
 */
public final class BackupRetrievalFactory {

    private BackupRetrievalFactory() {
    }

    public static BackupArtefactRetrievalPort create(String jdbcUrl, String user, String password,
            String artefactStoreKeyBase64, String artefactStoreRoot, String groupReferenceKeyBase64) {
        return BackupRetrievalComposition.retrievalPort(jdbcUrl, user, password, artefactStoreKeyBase64,
                artefactStoreRoot, groupReferenceKeyBase64);
    }
}
