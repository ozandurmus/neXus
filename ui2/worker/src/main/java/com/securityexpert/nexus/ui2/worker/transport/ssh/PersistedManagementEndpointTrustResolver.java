package com.securityexpert.nexus.ui2.worker.transport.ssh;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;

/** C10: worker and CLI share this resolver; enrolled-device trust is delegated unchanged. */
public final class PersistedManagementEndpointTrustResolver implements TrustRuleResolver {
    private static final String PREFIX = "cp_discovery_endpoint:";
    private final ManagementEndpointSshTrustRepository repository;
    private final TrustRuleResolver enrolledDevices;
    private final boolean allowTofu;

    public PersistedManagementEndpointTrustResolver(ManagementEndpointSshTrustRepository repository,
            TrustRuleResolver enrolledDevices) {
        this(repository, enrolledDevices, false);
    }

    public PersistedManagementEndpointTrustResolver(ManagementEndpointSshTrustRepository repository,
            TrustRuleResolver enrolledDevices, boolean allowTofu) {
        this.repository = repository;
        this.enrolledDevices = enrolledDevices;
        this.allowTofu = allowTofu;
    }

    public static String scopeRef(String address, int port) {
        return PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(address.getBytes(StandardCharsets.UTF_8)) + ":" + port;
    }

    @Override
    public Optional<String> resolveExpectedFingerprint(String ref) {
        return ref.startsWith(PREFIX) ? Optional.empty() : enrolledDevices.resolveExpectedFingerprint(ref);
    }

    @Override
    public Optional<String> resolveExpectedFingerprint(String ref, String host, int port, String algorithm) {
        if (!ref.startsWith(PREFIX)) {
            return enrolledDevices.resolveExpectedFingerprint(ref, host, port, algorithm);
        }
        try {
            return ref.equals(scopeRef(host, port))
                    ? repository.findActiveFingerprint(host, port, algorithm) : Optional.empty();
        } catch (RuntimeException e) {
            throw new IllegalStateException("CP_DISCOVERY_TRUST_NOT_EVALUABLE");
        }
    }

    @Override
    public Optional<List<String>> authorizedAlgorithms(String ref, String host, int port) {
        if (!ref.startsWith(PREFIX)) {
            return enrolledDevices.authorizedAlgorithms(ref, host, port);
        }
        try {
            List<String> algorithms = ref.equals(scopeRef(host, port))
                    ? repository.findActiveAlgorithms(host, port) : List.of();
            if (!algorithms.isEmpty()) {
                return Optional.of(algorithms);
            }
            if (allowTofu) {
                return Optional.empty();
            }
            return Optional.of(List.of());
        } catch (RuntimeException e) {
            throw new IllegalStateException("CP_DISCOVERY_TRUST_NOT_EVALUABLE");
        }
    }

    @Override
    public boolean allowTrustOnFirstUse(String ref, String host, int port) {
        return allowTofu;
    }

    @Override
    public void recordTrustOnFirstUse(String host, int port, String algorithm, String fingerprint) {
        if (allowTofu) {
            try {
                repository.enroll(host, port, algorithm, fingerprint, "system_auto_enroll", java.time.Instant.now(), false);
            } catch (RuntimeException ignored) {
                // If already enrolled concurrently or transaction rolled back, ignore
            }
        }
    }
}
