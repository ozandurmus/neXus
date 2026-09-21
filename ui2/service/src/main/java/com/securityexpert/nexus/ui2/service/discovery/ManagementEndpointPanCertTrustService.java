package com.securityexpert.nexus.ui2.service.discovery;

import java.time.Instant;

import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointPanCertTrustRepository;

/** Records an operator's explicit authorization of one observed endpoint certificate. */
public final class ManagementEndpointPanCertTrustService {
    public enum Outcome { MATCH, MISMATCH, NOT_EVALUABLE }
    private final ManagementEndpointPanCertTrustRepository repository;

    public ManagementEndpointPanCertTrustService(ManagementEndpointPanCertTrustRepository repository) {
        this.repository = repository;
    }

    public Outcome enroll(String actor, String address, int port, String fingerprint,
            Instant observedAt, boolean explicitlyConfirmed, boolean reEnroll) {
        if (actor == null || actor.isBlank() || address == null || address.isBlank() || address.length() > 253
                || address.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                || port < 1 || port > 65535 || fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")
                || observedAt == null || observedAt.isAfter(Instant.now()) || !explicitlyConfirmed) {
            return Outcome.NOT_EVALUABLE;
        }
        try {
            return repository.enroll(address, port, fingerprint, actor, observedAt, reEnroll)
                    ? Outcome.MATCH : Outcome.MISMATCH;
        } catch (RuntimeException e) {
            return Outcome.NOT_EVALUABLE;
        }
    }
}
