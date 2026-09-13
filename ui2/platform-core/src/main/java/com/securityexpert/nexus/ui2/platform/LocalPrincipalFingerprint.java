package com.securityexpert.nexus.ui2.platform;

/**
 * The {@code local:} actor-fingerprint namespace for a local identity
 * (C3A contract §2.1). Shared by {@code LocalMechanism} (service module,
 * login path) and {@code LocalIdentityAdministration} (persistence module,
 * 13G local identity administration) so both compute the exact same
 * fingerprint for the same {@code local_identity_id} from a single source,
 * never two independently-typed prefixes that could drift apart.
 */
public final class LocalPrincipalFingerprint {

    public static final String LOCAL_PRINCIPAL_PREFIX = "local:";

    private LocalPrincipalFingerprint() {
    }

    public static String forLocalIdentity(String localIdentityId) {
        return PrincipalFingerprint.of(LOCAL_PRINCIPAL_PREFIX + localIdentityId);
    }
}
