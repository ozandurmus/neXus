package com.securityexpert.nexus.ui2.service.security;

import com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism;

/** The {@code ldap} mechanism ({@link LdapMechanism}) as an {@code AuthenticationProvider}. */
public final class LdapAuthenticationProvider extends MechanismAuthenticationProvider {

    public LdapAuthenticationProvider(LdapMechanism ldap) {
        super(ldap);
    }
}
