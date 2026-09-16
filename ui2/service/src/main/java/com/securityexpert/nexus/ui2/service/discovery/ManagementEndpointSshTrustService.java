package com.securityexpert.nexus.ui2.service.discovery;

import java.time.Instant;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;

/** C10: operator attests observed-key verification using an independent out-of-band source. */
public final class ManagementEndpointSshTrustService {
    private static final Set<String> ALGORITHMS = Set.of("ssh-ed25519", "ssh-rsa",
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521");
    public enum Outcome { MATCH, MISMATCH, NOT_EVALUABLE }
    private final ManagementEndpointSshTrustRepository repository;

    public ManagementEndpointSshTrustService(ManagementEndpointSshTrustRepository repository) {
        this.repository = repository;
    }

    public Outcome enroll(String actor, String address, int port, String algorithm, String fingerprint,
            Instant observedAt, boolean independentlyVerified, boolean reEnroll) {
        if (actor == null || actor.isBlank() || address == null || address.isBlank() || address.length() > 253
                || address.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || port < 1 || port > 65535 || algorithm == null || !ALGORITHMS.contains(algorithm)
                || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || observedAt == null || observedAt.isAfter(Instant.now()) || !independentlyVerified) {
            return Outcome.NOT_EVALUABLE;
        }
        try {
            return repository.enroll(address, port, algorithm, fingerprint, actor, observedAt, reEnroll)
                    ? Outcome.MATCH : Outcome.MISMATCH;
        } catch (RuntimeException e) {
            // A uniqueness/transaction failure is a refusal; no database exception is disclosed.
            return Outcome.NOT_EVALUABLE;
        }
    }
}
