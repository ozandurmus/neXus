package com.securityexpert.nexus.ui2.service.failover;

import java.util.Locale;
import java.util.Objects;

/**
 * CF-P0.14: every four-eyes gate in the Failover Engine compared requester/approver
 * or operator/second-approver principals with {@code equalsIgnoreCase}, which a
 * trailing space (or any non-canonical variant) defeats -- {@code "alice"} and
 * {@code "alice "} pass as distinct principals. Every dual-control comparison in the
 * engine must go through {@link #requireDistinctPrincipals} instead of comparing raw
 * strings directly.
 */
final class PrincipalCanonicalization {

    private PrincipalCanonicalization() {
    }

    /** Canonical form used only for the equality check -- never persisted in place of the raw principal. */
    static String canonicalize(String principal, String fieldName) {
        Objects.requireNonNull(principal, fieldName + " must not be null");
        if (principal.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        for (int i = 0; i < principal.length(); i++) {
            char c = principal.charAt(i);
            if (Character.isISOControl(c)) {
                throw new IllegalArgumentException(fieldName + " must not contain control characters or tabs");
            }
        }
        if (!principal.equals(principal.stripTrailing())) {
            throw new IllegalArgumentException(fieldName + " must not contain trailing whitespace");
        }
        return principal.trim().toLowerCase(Locale.ROOT);
    }

    /** Rejects a self-approval attempt after canonicalizing both sides of the dual-control gate. */
    static void requireDistinctPrincipals(String requesterId, String approverId, String gateDescription) {
        String canonicalRequester = canonicalize(requesterId, "requesterId");
        String canonicalApprover = canonicalize(approverId, "approverId");
        if (canonicalRequester.equals(canonicalApprover)) {
            throw new IllegalArgumentException(
                gateDescription + " requires 4-eyes dual control (requester != approver)");
        }
    }
}
