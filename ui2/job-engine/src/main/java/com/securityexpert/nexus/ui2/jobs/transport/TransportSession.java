package com.securityexpert.nexus.ui2.jobs.transport;

import java.util.Optional;

/**
 * An opaque handle to one authenticated connection (contract §5: "one
 * authenticated connection per connect"). The executor never inspects its
 * internals; only the adapter that issued it knows what it wraps.
 */
public interface TransportSession {

    String sessionId();

    /**
     * The identity presented at connection, when the adapter's transport
     * exposes one at the connection layer itself (e.g. an SSH host-key
     * fingerprint) rather than only in a subsequent read's output
     * ({@code DEVICE_IMPORT_AND_ENROLLMENT_CONTRACT.md} EC-5). A default
     * method so every existing {@link TransportSession} implementation --
     * including test doubles constructed as a lambda -- keeps compiling
     * unchanged; only an adapter that has such an identity overrides it.
     */
    default Optional<String> presentedIdentity() {
        return Optional.empty();
    }
}
