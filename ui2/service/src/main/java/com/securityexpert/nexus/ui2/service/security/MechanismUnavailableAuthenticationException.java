package com.securityexpert.nexus.ui2.service.security;

import org.springframework.security.authentication.AuthenticationServiceException;

/** The mechanism could not decide (directory unreachable, profile invalid, admission off); mapped to 503, never to a refusal. */
public final class MechanismUnavailableAuthenticationException extends AuthenticationServiceException {

    private final String reasonCode;

    public MechanismUnavailableAuthenticationException(String reasonCode) {
        super(reasonCode);
        this.reasonCode = reasonCode;
    }

    /** Internal only; never put in a response. */
    public String reasonCode() {
        return reasonCode;
    }
}
