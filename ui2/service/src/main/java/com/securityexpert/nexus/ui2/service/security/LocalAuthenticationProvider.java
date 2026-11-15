package com.securityexpert.nexus.ui2.service.security;

/** The {@code local} mechanism ({@link LocalMechanism}) as an {@code AuthenticationProvider}. */
public final class LocalAuthenticationProvider extends MechanismAuthenticationProvider {

    public LocalAuthenticationProvider(LocalMechanism local) {
        super(local);
    }
}
