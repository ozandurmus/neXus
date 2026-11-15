package com.securityexpert.nexus.ui2.service.security;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Mechanism;

/**
 * Adapts one {@link Mechanism} to Spring Security's {@link AuthenticationProvider}. It answers only an attempt addressed to
 * its own mechanism id (anything else returns {@code null}, so a {@code ProviderManager} moves on) and delegates the whole
 * decision to the mechanism, unchanged: Argon2 timing, lockout, directory bind. {@link AttemptOutcome.Refused} becomes a
 * {@link BadCredentialsException}, {@link AttemptOutcome.MechanismUnavailable} a
 * {@link MechanismUnavailableAuthenticationException}, {@link AttemptOutcome.Success} an authenticated token.
 */
public class MechanismAuthenticationProvider implements AuthenticationProvider {

    private final Mechanism mechanism;

    public MechanismAuthenticationProvider(Mechanism mechanism) {
        this.mechanism = java.util.Objects.requireNonNull(mechanism, "mechanism");
    }

    @Override
    public final Authentication authenticate(Authentication authentication) throws AuthenticationException {
        if (!(authentication instanceof MechanismAuthenticationToken attempt)
                || !mechanism.mechanismId().equals(attempt.mechanismId())) {
            return null;
        }
        AttemptOutcome outcome = mechanism.attempt(attempt.username(), attempt.password());
        if (outcome instanceof AttemptOutcome.Success success) {
            return MechanismAuthenticationToken.authenticated(mechanism.mechanismId(), success);
        }
        if (outcome instanceof AttemptOutcome.MechanismUnavailable unavailable) {
            throw new MechanismUnavailableAuthenticationException(unavailable.reasonCode());
        }
        // Refused: the reason code stays internal; the caller answers every refusal identically.
        throw new BadCredentialsException(((AttemptOutcome.Refused) outcome).reasonCode());
    }

    @Override
    public final boolean supports(Class<?> authentication) {
        return MechanismAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
