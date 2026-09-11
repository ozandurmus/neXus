package com.securityexpert.nexus.ui2.platform;

import java.util.Set;

/**
 * The operator-bind port (C3 §2.1). A port shared across modules lives in
 * {@code platform-core} (adjudication F10) — {@code service} consumes this
 * interface per the allowed {@code service}→{@code ldap-adapter} edge;
 * {@code ldap-adapter}'s {@code UnboundIdOperatorBindAdapter} is the only
 * implementation.
 *
 * <p>Every implementation must, before any network call: refuse an empty
 * password (the RFC 4513 unauthenticated-bind trap, {@code AG-2}); hold the
 * password in a mutable {@code char[]}, never a {@code String}; zero it
 * immediately after the one bind call, success or failure; never retain it
 * for a re-bind.</p>
 */
public interface LdapOperatorBindPort {

    /**
     * @param username the operator's directory username (never logged as a
     *                  literal DN — only as {@link PrincipalFingerprint})
     * @param password the operator's password, in a mutable buffer the
     *                  caller must consider consumed (zeroed) after this
     *                  call returns, regardless of outcome
     */
    Result<OperatorBindOutcome> bind(String username, char[] password);

    /**
     * @param actorFingerprint    {@link PrincipalFingerprint#of(String)} of the bind DN
     * @param groupReferences     the directory's own opaque group identifiers the
     *                             actor is a member of, resolved at bind time —
     *                             never a corporate group name, never persisted
     *                             in plaintext outside this in-flight value
     */
    record OperatorBindOutcome(String actorFingerprint, Set<String> groupReferences) {
    }

    /** Closed failure-code vocabulary for {@link Result#err}, C3 §2.2/§2.4. */
    final class FailureCodes {
        public static final String INVALID_CREDENTIALS = "invalid_credentials";
        public static final String DIRECTORY_UNAVAILABLE = "directory_unavailable";
        public static final String EMPTY_PASSWORD = "invalid_credentials"; // same generic surface, §2.2

        private FailureCodes() {
        }
    }
}
