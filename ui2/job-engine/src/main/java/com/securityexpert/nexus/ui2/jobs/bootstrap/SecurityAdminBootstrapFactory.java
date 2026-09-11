package com.securityexpert.nexus.ui2.jobs.bootstrap;

import com.securityexpert.nexus.ui2.persistence.identity.SecurityAdminBootstrapComposition;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * Composition helper for the first-{@code security_admin}-binding bootstrap
 * (C3 §4.3, adjudication F8). {@code cli}'s allowed dependencies are
 * {@code platform-core} and {@code job-engine} only — this class is the
 * reason that is sufficient: {@code job-engine} already depends on
 * {@code persistence} (contract §2 row), so it is the module that hands
 * {@code cli} back only the {@code platform-core} port type. This class
 * names no {@code org.jooq} type itself — {@code
 * SecurityAdminBootstrapComposition}'s public signature is the only
 * surface it touches — so {@code job-engine}'s own compile classpath needs
 * no jOOQ dependency declared for this class to compile.
 */
public final class SecurityAdminBootstrapFactory {

    private SecurityAdminBootstrapFactory() {
    }

    /**
     * @param jdbcUrl the {@code ui2_migrate}-equivalent bootstrap connection
     *                 (C1 §2.4's {@code ui2_migrate} posture: deployment
     *                 -controlled, never the running service's own DSN)
     */
    public static SecurityAdminBootstrapPort create(String jdbcUrl, String user, String password) {
        return SecurityAdminBootstrapComposition.create(jdbcUrl, user, password);
    }
}
