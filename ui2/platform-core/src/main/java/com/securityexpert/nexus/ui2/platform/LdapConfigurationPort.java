package com.securityexpert.nexus.ui2.platform;

import java.util.Optional;

public interface LdapConfigurationPort {

    record LdapConfigurationView(
            String serverUrl,
            String bindDn,
            String baseDn,
            String searchFilter,
            String caCertificate
    ) {}

    Optional<LdapConfigurationView> getConfiguration();

    LdapConfigurationView updateConfiguration(
            String actorFingerprint,
            String serverUrl,
            String bindDn,
            char[] password,
            String baseDn,
            String searchFilter,
            String caCertificate
    );
}
