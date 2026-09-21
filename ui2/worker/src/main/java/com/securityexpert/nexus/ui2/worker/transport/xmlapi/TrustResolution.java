package com.securityexpert.nexus.ui2.worker.transport.xmlapi;

/** Palo Alto TLS handling either accepts any valid certificate or is unresolved. */
public sealed interface TrustResolution {

    record AcceptAnyValidCertificate() implements TrustResolution {
    }

    /** No trust rule is registered for the given ref -- a definite refusal, never trust-on-first-use. */
    record Unresolved() implements TrustResolution {
    }
}
