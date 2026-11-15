package com.securityexpert.nexus.ui2.service.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * The Spring Security view of an active {@code ui2_session}: the principal is the session's actor fingerprint (never a
 * name, never the cookie), the credentials are always absent, and there are no granted authorities. Authorization is
 * still decided by {@link GateChain}; this token only says "this request carries a live session".
 */
public final class Ui2SessionAuthenticationToken extends AbstractAuthenticationToken {

    private final String actorFingerprint;
    private final String sessionId;
    private final boolean machine;

    public Ui2SessionAuthenticationToken(String actorFingerprint, String sessionId, boolean machine) {
        super(List.of());
        this.actorFingerprint = java.util.Objects.requireNonNull(actorFingerprint, "actorFingerprint");
        this.sessionId = java.util.Objects.requireNonNull(sessionId, "sessionId");
        this.machine = machine;
        setAuthenticated(true);
    }

    @Override
    public Object getPrincipal() {
        return actorFingerprint;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    /** The session id: the SHA-256 hex of the cookie value, not the cookie value. */
    public String sessionId() {
        return sessionId;
    }

    public boolean machine() {
        return machine;
    }

    @Override
    public String toString() {
        return "Ui2SessionAuthenticationToken[actor=" + actorFingerprint + ", machine=" + machine + "]";
    }
}
