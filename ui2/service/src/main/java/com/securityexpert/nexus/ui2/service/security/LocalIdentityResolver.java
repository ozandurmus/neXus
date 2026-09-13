package com.securityexpert.nexus.ui2.service.security;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;

/**
 * Resolves a session's opaque {@code actor_fingerprint} back to the local
 * identity behind it (NXS-LOCAL-0152: {@code GET /session/status}'s display
 * name/role tokens, and {@link GateChain}'s must-change-password gate).
 * {@code actor_fingerprint} is a one-way hash of {@code "local:" +
 * local_identity_id} ({@link LocalMechanism#actorFingerprintFor}, C3 §3.2)
 * and is never itself a lookup key on {@code local_credentials}; a future
 * directory-authenticated fingerprint matches no row here.
 */
public final class LocalIdentityResolver {

    private final LocalCredentialsRepository localCredentialsRepository;

    public LocalIdentityResolver(LocalCredentialsRepository localCredentialsRepository) {
        this.localCredentialsRepository = Objects.requireNonNull(localCredentialsRepository, "localCredentialsRepository");
    }

    public Optional<LocalCredentialRecord> resolve(String actorFingerprint) {
        return localCredentialsRepository.findAll().stream()
                .filter(record -> LocalMechanism.actorFingerprintFor(record.localIdentityId()).equals(actorFingerprint))
                .findFirst();
    }
}
