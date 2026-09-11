package com.securityexpert.nexus.ui2.persistence.identity;

/**
 * {@code sessions.state}'s closed vocabulary (C3 §3.1). {@code SUPERSEDED},
 * {@code EXPIRED} and {@code REVOKED} are terminal — no code path moves a
 * row out of any of them (C3 §3.3).
 */
public enum SessionState {
    ACTIVE,
    SUPERSEDED,
    EXPIRED,
    REVOKED
}
