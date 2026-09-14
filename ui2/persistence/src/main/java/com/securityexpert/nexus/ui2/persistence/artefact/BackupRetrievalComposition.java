package com.securityexpert.nexus.ui2.persistence.artefact;

import java.nio.file.Path;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;

/**
 * Composes the {@code persistence}-side dependencies for {@link
 * BackupArtefactRetrievalPort} (14I OR-1..OR-5, the CLI path) from raw JDBC
 * parameters, mirroring {@code credential.CredentialStoreComposition}
 * exactly -- {@code cli} needs no jOOQ on its own compile classpath.
 */
public final class BackupRetrievalComposition {

    private BackupRetrievalComposition() {
    }

    public static BackupArtefactRetrievalPort retrievalPort(String jdbcUrl, String user, String password,
            String artefactStoreKeyBase64, String artefactStoreRoot, String groupReferenceKeyBase64) {
        TransactionBoundary transactionBoundary = transactionBoundary(jdbcUrl, user, password);
        ArtefactStore artefactStore =
                new FileArtefactStore(Path.of(artefactStoreRoot), ArtefactStoreCipher.fromBase64Key(artefactStoreKeyBase64));
        BackupArtefactManifestRepository manifestRepository = new JooqBackupArtefactManifestRepository(transactionBoundary);
        BackupArtefactRetrievalRepository retrievalRepository = new JooqBackupArtefactRetrievalRepository(transactionBoundary);
        return new BackupArtefactRetrieval(artefactStore, manifestRepository, retrievalRepository,
                new JooqRoleBindingRepository(transactionBoundary), new JooqActorAuthzStateRepository(transactionBoundary),
                GroupReferenceCipher.fromBase64Key(groupReferenceKeyBase64));
    }

    private static TransactionBoundary transactionBoundary(String jdbcUrl, String user, String password) {
        DSLContext dsl = DSL.using(jdbcUrl, user, password);
        return new JooqTransactionBoundary(dsl);
    }
}
