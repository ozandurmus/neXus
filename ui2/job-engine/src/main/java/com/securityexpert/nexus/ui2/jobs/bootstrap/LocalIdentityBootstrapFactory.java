package com.securityexpert.nexus.ui2.jobs.bootstrap;

import com.securityexpert.nexus.ui2.persistence.identity.LocalIdentityBootstrapComposition;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;

/**
 * Composition helper for the local-identity bootstrap (C3A contract §6,
 * §11 U-2), mirroring {@link SecurityAdminBootstrapFactory} exactly: {@code cli}
 * depends on {@code job-engine} (which already depends on {@code persistence})
 * rather than on {@code persistence} directly, so {@code cli}'s own compile
 * classpath needs no jOOQ dependency for this call.
 */
public final class LocalIdentityBootstrapFactory {

    private LocalIdentityBootstrapFactory() {
    }

    public static LocalIdentityBootstrapPort create(String jdbcUrl, String user, String password) {
        return LocalIdentityBootstrapComposition.create(jdbcUrl, user, password);
    }
}
