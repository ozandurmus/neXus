package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import com.securityexpert.nexus.ui2.identity.ldap.*;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;

/** Optional server-owned LDAP profile. No profile means no LDAP loader/calls.
 * Corporate DIRECTORY-POSTURE/admission remain disabled pending recorded joint approval.
 */
@Configuration
@ConditionalOnProperty(name = "ui2.ldap.profile-id")
public class DirectoryAuthenticationConfiguration {
    @Bean public DirectoryProfile directoryProfile(Environment env) {
        try {
            String pin = env.getProperty("ui2.ldap.store-pin-file");
            return new DirectoryProfile(env.getRequiredProperty("ui2.ldap.profile-id"),
                    env.getRequiredProperty("ui2.ldap.host"), Integer.parseInt(env.getRequiredProperty("ui2.ldap.port")),
                    DirectoryProfile.Transport.valueOf(env.getRequiredProperty("ui2.ldap.transport")),
                    Path.of(env.getRequiredProperty("ui2.ldap.trust-material-file")),
                    DirectoryProfile.Format.valueOf(env.getRequiredProperty("ui2.ldap.trust-format")),
                    pin == null ? null : Path.of(pin), env.getRequiredProperty("ui2.ldap.bind-dn-template"),
                    env.getRequiredProperty("ui2.ldap.group-search-base-dn"), env.getRequiredProperty("ui2.ldap.access-group-reference"));
        } catch (RuntimeException e) { throw new LdapStartupException("directory_profile_invalid"); }
    }
    @Bean public DirectoryTrustPolicy directoryTrustPolicy(DirectoryProfile profile, ActorAuthzStateRepository actors) {
        return new DirectoryTrustPolicy(profile, actors::expireDirectory);
    }
    @Bean public LdapMechanism ldapMechanism(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        return new LdapMechanism(UnboundIdOperatorBindAdapter.create(profile, trust), false);
    }
    @Bean public UnboundIdRevalidationAdapter ldapRevalidationAdapter(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        // No service-account path is resolved/read while corporate posture is disabled.
        return new UnboundIdRevalidationAdapter(false, profile, trust, null, null);
    }
}
