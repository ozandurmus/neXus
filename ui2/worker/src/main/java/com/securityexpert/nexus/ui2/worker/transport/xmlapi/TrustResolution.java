package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

/**
 * TLS trust law (WORKER.md "TLS trust"): a trust rule resolves to exactly a
 * PEM CA bundle path or a pinned server-certificate SHA-256 -- never to
 * "accept any certificate". {@link Unresolved} is the fail-closed case: no
 * code path in this package ever treats it as "verification not required".
 */
public sealed interface TrustResolution {

    record CaBundlePath(String path) implements TrustResolution {
    }

    record PinnedFingerprint(String sha256Hex) implements TrustResolution {
    }

    /** No trust rule is registered for the given ref -- a definite refusal, never trust-on-first-use. */
    record Unresolved() implements TrustResolution {
    }
}
