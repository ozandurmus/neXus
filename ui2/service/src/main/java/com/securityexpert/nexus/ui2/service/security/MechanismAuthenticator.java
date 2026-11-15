package com.securityexpert.nexus.ui2.service.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.core.AuthenticationException;

import com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.Mechanism;

/**
 * The {@code ProviderManager} behind {@code POST /login} (migration plan Step 2), built from the {@link MechanismRegistry}
 * so it covers exactly the registered mechanisms (and {@code local} is always one of them, the registry's C3A AC-2
 * guarantee). Deliberately not an {@code AuthenticationManager} bean: that would change what Spring Security auto-configures.
 * Switched off with {@code ui2.security.provider-manager-enabled=false}, which sends {@code LoginController} back to direct
 * registry dispatch.
 */
public final class MechanismAuthenticator {

    private final ProviderManager manager;

    public MechanismAuthenticator(MechanismRegistry registry) {
        List<AuthenticationProvider> providers = new ArrayList<>();
        for (Mechanism mechanism : registry.all()) {
            if (mechanism instanceof LocalMechanism local) {
                providers.add(new LocalAuthenticationProvider(local));
            } else if (mechanism instanceof LdapMechanism ldap) {
                providers.add(new LdapAuthenticationProvider(ldap));
            } else {
                providers.add(new MechanismAuthenticationProvider(mechanism));
            }
        }
        this.manager = new ProviderManager(providers);
    }

    /**
     * @return the mechanism's own success outcome
     * @throws org.springframework.security.authentication.BadCredentialsException the attempt was refused
     * @throws MechanismUnavailableAuthenticationException the mechanism could not decide
     */
    public AttemptOutcome.Success authenticate(String mechanismId, String username, char[] password)
            throws AuthenticationException {
        MechanismAuthenticationToken result = (MechanismAuthenticationToken) manager.authenticate(
                new MechanismAuthenticationToken(mechanismId, username, password));
        return result.success();
    }
}
