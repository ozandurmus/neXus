package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.securityexpert.nexus.ui2.identity.ldap.*;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqDirectoryProfileRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

@Configuration
public class DirectoryAuthenticationConfiguration {
    @Bean
    public DirectoryProfile directoryProfile(DirectoryProfileRepository repo) {
        return repo.findActiveProfile().map(record -> {
            try {
                Path trustPath = Files.createTempFile("ldap-trust", ".pem");
                if (record.trustMaterialPem() != null) {
                    Files.writeString(trustPath, record.trustMaterialPem());
                }
                return new DirectoryProfile(record.id().toString(), record.host(), record.port(),
                        DirectoryProfile.Transport.valueOf(record.transport()),
                        trustPath, DirectoryProfile.Format.valueOf(record.trustFormat()),
                        null, record.bindDnTemplate(), record.groupSearchBaseDn(), record.accessGroupReference());
            } catch (Exception e) {
                throw new LdapStartupException("directory_profile_invalid");
            }
        }).orElse(null);
    }
    
    @Bean public DirectoryTrustPolicy directoryTrustPolicy(DirectoryProfile profile, ActorAuthzStateRepository actors) {
        if (profile == null) return null;
        return new DirectoryTrustPolicy(profile, actors::expireDirectory);
    }
    
    @Bean public LdapMechanism ldapMechanism(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        if (profile == null || trust == null) return null;
        return new LdapMechanism(UnboundIdOperatorBindAdapter.create(profile, trust));
    }
    
    @Bean public UnboundIdRevalidationAdapter ldapRevalidationAdapter(DirectoryProfile profile, DirectoryTrustPolicy trust) {
        if (profile == null || trust == null) return null;
        return new UnboundIdRevalidationAdapter(false, profile, trust, null, null);
    }
}
