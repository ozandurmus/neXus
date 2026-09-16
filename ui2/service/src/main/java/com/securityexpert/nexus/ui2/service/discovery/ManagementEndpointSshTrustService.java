package com.securityexpert.nexus.ui2.service.discovery;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

import com.securityexpert.nexus.ui2.persistence.discovery.ManagementEndpointSshTrustRepository;

/** C10 first-contact key observation and explicit security-admin cache authorization. */
public final class ManagementEndpointSshTrustService {
    private static final Set<String> ALGORITHMS = Set.of("ssh-ed25519", "ssh-rsa",
            "ecdsa-sha2-nistp256", "ecdsa-sha2-nistp384", "ecdsa-sha2-nistp521");
    public enum Outcome { MATCH, MISMATCH, NOT_EVALUABLE }
    public record Observation(String observationId, String keyAlgorithm, String fingerprint) {
        @Override public String toString() { return "Observation[redacted]"; }
    }
    record ObservedHostKey(String keyAlgorithm, String fingerprint, Instant observedAt) { }
    @FunctionalInterface interface HostKeyObserver {
        Optional<ObservedHostKey> observe(String address, int port);
    }
    private record PendingObservation(String actor, String sessionId, String address, int port, ObservedHostKey key, Instant expiresAt) { }

    private final ManagementEndpointSshTrustRepository repository;
    private final HostKeyObserver observer;
    private final java.util.function.Supplier<Instant> now;
    private final ConcurrentHashMap<String, PendingObservation> pending = new ConcurrentHashMap<>();

    public ManagementEndpointSshTrustService(ManagementEndpointSshTrustRepository repository) {
        this(repository, ManagementEndpointSshTrustService::observeHostKey);
    }

    ManagementEndpointSshTrustService(ManagementEndpointSshTrustRepository repository, HostKeyObserver observer) {
        this(repository, observer, Instant::now);
    }

    ManagementEndpointSshTrustService(ManagementEndpointSshTrustRepository repository, HostKeyObserver observer,
            java.util.function.Supplier<Instant> now) {
        this.repository = repository;
        this.observer = observer;
        this.now = now;
    }

    public Optional<Observation> observe(String actor, String sessionId, String address, int port) {
        if (actor == null || actor.isBlank() || sessionId == null || sessionId.isBlank()
                || !validEndpoint(address, port)) return Optional.empty();
        synchronized (pending) {
            pending.entrySet().removeIf(entry -> !now.get().isBefore(entry.getValue().expiresAt()));
            if (pending.size() >= 256) return Optional.empty();
        }
        Optional<ObservedHostKey> observed = observer.observe(address, port);
        if (observed.isEmpty() || !ALGORITHMS.contains(observed.get().keyAlgorithm())
                || !observed.get().fingerprint().matches("[0-9a-f]{64}")) return Optional.empty();
        String id = UUID.randomUUID().toString();
        synchronized (pending) {
            if (pending.size() >= 256) return Optional.empty();
            pending.put(id, new PendingObservation(actor, sessionId, address, port, observed.get(), now.get().plusSeconds(300)));
        }
        return Optional.of(new Observation(id, observed.get().keyAlgorithm(), observed.get().fingerprint()));
    }

    public Outcome authorizeObserved(String actor, String sessionId, String observationId, boolean confirmed, boolean reEnroll) {
        PendingObservation pendingObservation = observationId == null ? null : pending.get(observationId);
        if (!confirmed || pendingObservation == null || !pendingObservation.actor().equals(actor)
                || !pendingObservation.sessionId().equals(sessionId)
                || !now.get().isBefore(pendingObservation.expiresAt())
                || !pending.remove(observationId, pendingObservation)) return Outcome.NOT_EVALUABLE;
        Optional<String> active = repository.findActiveFingerprint(pendingObservation.address(), pendingObservation.port(),
                pendingObservation.key().keyAlgorithm());
        if (active.isPresent() && active.get().equals(pendingObservation.key().fingerprint())) return Outcome.MATCH;
        if (active.isPresent() && !reEnroll) return Outcome.MISMATCH;
        try {
            return repository.enroll(pendingObservation.address(), pendingObservation.port(), pendingObservation.key().keyAlgorithm(),
                    pendingObservation.key().fingerprint(), actor, pendingObservation.key().observedAt(), reEnroll)
                    ? Outcome.MATCH : Outcome.MISMATCH;
        } catch (RuntimeException e) {
            return Outcome.NOT_EVALUABLE;
        }
    }

    private static boolean validEndpoint(String address, int port) {
        return address != null && !address.isBlank() && address.length() <= 253
                && address.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))
                && port >= 1 && port <= 65535;
    }

    private static Optional<ObservedHostKey> observeHostKey(String address, int port) {
        ObservedHostKey[] captured = {null};
        Session session = null;
        try {
            session = new JSch().getSession("nexus-host-key-observer", address, port);
            session.setHostKeyRepository(new HostKeyRepository() {
                @Override public int check(String ignored, byte[] key) {
                    try {
                        HostKey hostKey = new HostKey(address, key);
                        captured[0] = new ObservedHostKey(hostKey.getType(), sha256(key), Instant.now());
                    } catch (JSchException ignoredFailure) {
                        captured[0] = null;
                    }
                    return NOT_INCLUDED;
                }
                @Override public void add(HostKey hostKey, UserInfo userInfo) { }
                @Override public void remove(String host, String type) { }
                @Override public void remove(String host, String type, byte[] key) { }
                @Override public String getKnownHostsRepositoryID() { return "nexus-observation-only"; }
                @Override public HostKey[] getHostKey() { return new HostKey[0]; }
                @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
            });
            session.setConfig("StrictHostKeyChecking", "yes");
            try {
                session.connect(5000);
            } catch (JSchException expectedAfterKeyExchange) {
                // Returning NOT_INCLUDED ends the session before user authentication.
            }
        } catch (JSchException ignored) {
            captured[0] = null;
        } finally {
            if (session != null) session.disconnect();
        }
        return Optional.ofNullable(captured[0]);
    }

    private static String sha256(byte[] key) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(key);
            StringBuilder value = new StringBuilder();
            for (byte b : digest) value.append(String.format("%02x", b));
            return value.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
