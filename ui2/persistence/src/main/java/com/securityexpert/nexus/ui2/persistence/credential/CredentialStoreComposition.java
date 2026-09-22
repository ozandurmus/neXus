package com.securityexpert.nexus.ui2.persistence.credential;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqCredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;

/**
 * Composes the {@code persistence}-side dependencies for
 * {@link CredentialStorePort} (the CLI path, mirroring
 * {@code LocalIdentityAdministrationComposition} exactly) and, separately,
 * the read-only components the worker's store-backed resolvers need (SB-16)
 * -- from raw JDBC parameters, so neither {@code cli} nor {@code worker}
 * needs jOOQ on its own compile classpath: both call into this class's
 * compiled code inside {@code persistence} instead.
 */
public final class CredentialStoreComposition {

    private CredentialStoreComposition() {
    }

    public static CredentialStorePort administrationPort(String jdbcUrl, String user, String password,
            String credentialStoreKeyBase64, String credentialStoreKeyId) {
        TransactionBoundary transactionBoundary = transactionBoundary(jdbcUrl, user, password);
        CredentialRepository credentialRepository = new JooqCredentialRepository(transactionBoundary);
        CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(credentialStoreKeyBase64);
        return new CredentialAdministration(credentialRepository, cipher, credentialStoreKeyId);
    }

    /** SB-16: the read-only components {@code StoreBackedSshCredentialResolver}/{@code StoreBackedPanCredentialResolver} need. */
    public static ResolverComponents resolverComponents(String jdbcUrl, String user, String password,
            String credentialStoreKeyBase64) {
        TransactionBoundary transactionBoundary = transactionBoundary(jdbcUrl, user, password);
        CredentialReferenceRepository credentialReferenceRepository =
                new JooqCredentialReferenceRepository(transactionBoundary);
        CredentialRepository credentialRepository = new JooqCredentialRepository(transactionBoundary);
        CredentialStoreCipher cipher = CredentialStoreCipher.fromBase64Key(credentialStoreKeyBase64);
        return new ResolverComponents(credentialReferenceRepository, credentialRepository, cipher);
    }

    /** DataSource-backed, one connection per transaction. {@code DSL.using(url, user, password)} opens a
     * single shared Connection, and every worker thread resolving a credential concurrently raced on
     * it -- measured live (2026-09-22): two Check Point backups claimed in the same second both died
     * with "Cannot commit when autoCommit is enabled" inside JooqCredentialReferenceRepository.find. */
    private static TransactionBoundary transactionBoundary(String jdbcUrl, String user, String password) {
        return com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory.fromJdbc(jdbcUrl, user, password);
    }

    public record ResolverComponents(CredentialReferenceRepository credentialReferenceRepository,
            CredentialRepository credentialRepository, CredentialStoreCipher cipher) {
    }
}
