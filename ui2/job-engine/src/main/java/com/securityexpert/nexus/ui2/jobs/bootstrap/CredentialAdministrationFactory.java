package com.securityexpert.nexus.ui2.jobs.bootstrap;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;

/**
 * Composition helper for the credential store's CLI parity (2026-09-14 PO
 * decision record CS-1), mirroring {@link LocalIdentityAdministrationFactory}
 * exactly: {@code cli} depends on {@code job-engine} (which already depends
 * on {@code persistence}) rather than on {@code persistence} directly, so
 * {@code cli}'s own compile classpath needs no jOOQ dependency for this call.
 */
public final class CredentialAdministrationFactory {

    private CredentialAdministrationFactory() {
    }

    public static CredentialStorePort create(String jdbcUrl, String user, String password,
            String credentialStoreKeyBase64, String credentialStoreKeyId) {
        return CredentialStoreComposition.administrationPort(jdbcUrl, user, password, credentialStoreKeyBase64,
                credentialStoreKeyId);
    }
}
