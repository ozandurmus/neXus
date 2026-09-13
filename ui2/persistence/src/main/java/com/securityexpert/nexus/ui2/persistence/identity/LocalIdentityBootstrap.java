package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.SecurityAdminBootstrapPort;

/**
 * The {@link LocalIdentityBootstrapPort} implementation (C3A contract §6,
 * §11 U-2): hashes the supplied initial password under this movement's
 * current default Argon2id parameters and writes the row directly,
 * attributed to the same reserved {@link SecurityAdminBootstrapPort#BOOTSTRAP_ACTOR}
 * marker the existing role-binding bootstrap uses, itself audited by
 * {@code trg_audit_local_credentials}.
 */
public final class LocalIdentityBootstrap implements LocalIdentityBootstrapPort {

    private final LocalCredentialsRepository repository;

    public LocalIdentityBootstrap(LocalCredentialsRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public String bootstrapLocalIdentity(String localIdentityName, char[] initialPassword) {
        String localIdentityId = OpaqueId.random().value();
        Argon2PasswordHasher.Verifier verifier =
                Argon2PasswordHasher.hash(initialPassword, Argon2PasswordHasher.DEFAULT_PARAMETERS);
        return repository.create(localIdentityId, localIdentityName, verifier, SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR);
    }
}
