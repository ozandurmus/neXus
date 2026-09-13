package com.securityexpert.nexus.ui2.jobs.bootstrap;

import com.securityexpert.nexus.ui2.persistence.identity.LocalIdentityAdministrationComposition;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;

/**
 * Composition helper for 13G local identity administration, mirroring
 * {@link LocalIdentityBootstrapFactory} exactly: {@code cli} depends on
 * {@code job-engine} (which already depends on {@code persistence}) rather
 * than on {@code persistence} directly, so {@code cli}'s own compile
 * classpath needs no jOOQ dependency for this call.
 */
public final class LocalIdentityAdministrationFactory {

    private LocalIdentityAdministrationFactory() {
    }

    public static LocalIdentityAdministrationPort create(String jdbcUrl, String user, String password,
            String groupReferenceKeyBase64) {
        return LocalIdentityAdministrationComposition.create(jdbcUrl, user, password, groupReferenceKeyBase64);
    }
}
