package com.securityexpert.nexus.ui2.persistence.discovery;

import java.time.Instant;
import java.util.Optional;

/** Explicitly authorized Palo Alto certificate fingerprints, keyed by endpoint. */
public interface ManagementEndpointPanCertTrustRepository {
    Optional<String> findActiveFingerprint(String address, int port);
    boolean enroll(String address, int port, String fingerprint, String actorFingerprint,
            Instant observedAt, boolean reEnroll);
}
