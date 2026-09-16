package com.securityexpert.nexus.ui2.persistence.discovery;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** C10: authorized entries only; observation is never authorization. */
public interface ManagementEndpointSshTrustRepository {
    Optional<String> findActiveFingerprint(String address, int port, String algorithm);
    List<String> findActiveAlgorithms(String address, int port);
    boolean enroll(String address, int port, String algorithm, String fingerprint,
            String actorFingerprint, Instant observedAt, boolean reEnroll);
}
