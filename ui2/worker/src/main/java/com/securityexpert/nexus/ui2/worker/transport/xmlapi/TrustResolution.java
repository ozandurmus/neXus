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

    /** Chain verification first, then an endpoint pin resolved only if the chain is not trusted. */
    final class PaloAltoDeviceTrust implements TrustResolution {
        private final java.util.function.Supplier<java.util.Optional<String>> pinnedFingerprint;

        public PaloAltoDeviceTrust(java.util.Optional<String> pinnedFingerprint) {
            this(() -> pinnedFingerprint);
        }

        public PaloAltoDeviceTrust(java.util.function.Supplier<java.util.Optional<String>> pinnedFingerprint) {
            this.pinnedFingerprint = java.util.Objects.requireNonNull(pinnedFingerprint, "pinnedFingerprint");
        }

        public java.util.Optional<String> pinnedFingerprint() {
            return pinnedFingerprint.get();
        }
    }

    /** No trust rule is registered for the given ref -- a definite refusal, never trust-on-first-use. */
    record Unresolved() implements TrustResolution {
    }
}
