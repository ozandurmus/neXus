package com.securityexpert.nexus.ui2.service.security;

import java.util.Map;

/**
 * The {@code E1}-{@code E6} chain's own outcome (contract §7): either the
 * request proceeds with a resolved actor, or exactly one gate refused it —
 * the chain never lets a later gate's check run once an earlier one has
 * refused (AC-4).
 */
public sealed interface GateOutcome {

    record Proceed(String sessionId, String actorFingerprint) implements GateOutcome {
    }

    record Refused(String gate, int httpStatus, Map<String, Object> body) implements GateOutcome {
    }
}
