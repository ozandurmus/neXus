package com.securityexpert.nexus.ui2.service.security;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import com.securityexpert.nexus.ui2.platform.AttemptOutcome;

/**
 * A login attempt on its way through the {@code AuthenticationManager}: before authentication it carries the typed name,
 * the typed password (the caller's own array, zeroed by the caller) and the mechanism id the operator selected; after, it
 * carries the mechanism's {@link AttemptOutcome.Success} and no credentials.
 */
public final class MechanismAuthenticationToken extends AbstractAuthenticationToken {

    private final String mechanismId;
    private final String username;
    private final char[] password;
    private final AttemptOutcome.Success success;

    /** An attempt to be authenticated. */
    public MechanismAuthenticationToken(String mechanismId, String username, char[] password) {
        super(List.of());
        this.mechanismId = mechanismId;
        this.username = username;
        this.password = password;
        this.success = null;
        setAuthenticated(false);
    }

    private MechanismAuthenticationToken(String mechanismId, AttemptOutcome.Success success) {
        super(List.of());
        this.mechanismId = mechanismId;
        this.username = null;
        this.password = null;
        this.success = success;
        setAuthenticated(true);
    }

    static MechanismAuthenticationToken authenticated(String mechanismId, AttemptOutcome.Success success) {
        return new MechanismAuthenticationToken(mechanismId, success);
    }

    public String mechanismId() {
        return mechanismId;
    }

    String username() {
        return username;
    }

    char[] password() {
        return password;
    }

    /** The mechanism's own success outcome; only present on an authenticated token. */
    public AttemptOutcome.Success success() {
        return success;
    }

    @Override
    public Object getPrincipal() {
        return success != null ? success.resolvedActorFingerprint() : username;
    }

    @Override
    public Object getCredentials() {
        return null; // the password is never exposed through the Authentication API
    }

    @Override
    public String toString() {
        return "MechanismAuthenticationToken[mechanism=" + mechanismId + ", authenticated=" + isAuthenticated() + "]";
    }
}
