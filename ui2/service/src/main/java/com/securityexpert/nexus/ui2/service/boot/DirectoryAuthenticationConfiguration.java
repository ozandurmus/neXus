package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import com.securityexpert.nexus.ui2.identity.ldap.*;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRecord;
import com.securityexpert.nexus.ui2.persistence.identity.DirectoryProfileRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqDirectoryProfileRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.AttemptOutcome;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.LdapRevalidationPort;
import com.securityexpert.nexus.ui2.platform.Result;

@Configuration
public class DirectoryAuthenticationConfiguration {
    @Bean
    public DirectoryProfileRepository directoryProfileRepository(TransactionBoundary transactionBoundary) {
        return new JooqDirectoryProfileRepository(transactionBoundary);
    }

    private static class DynamicDirectoryProvider implements LdapOperatorBindPort, LdapRevalidationPort {
        private final DirectoryProfileRepository repo;
        private final ActorAuthzStateRepository actors;

        private DirectoryProfileRecord cachedRecord;
        private DirectoryTrustPolicy cachedTrust;
        private LdapOperatorBindPort bindPort;
        private LdapRevalidationPort revalidationPort;

        public DynamicDirectoryProvider(DirectoryProfileRepository repo, ActorAuthzStateRepository actors) {
            this.repo = repo;
            this.actors = actors;
        }

        private synchronized boolean loadActiveAdapters() {
            var recordOpt = repo.findActiveProfile();
            if (recordOpt.isEmpty()) {
                if (cachedTrust != null) {
                    cachedTrust.withdraw();
                }
                cachedRecord = null;
                cachedTrust = null;
                bindPort = null;
                revalidationPort = null;
                return false;
            }

            DirectoryProfileRecord record = recordOpt.get();
            if (record.equals(cachedRecord)) {
                return true;
            }

            if (cachedTrust != null) {
                cachedTrust.withdraw();
            }

            try {
                Path trustPath = Files.createTempFile("ldap-trust", ".pem");
                if (record.trustMaterialPem() != null) {
                    Files.writeString(trustPath, record.trustMaterialPem());
                }
                DirectoryProfile profile = new DirectoryProfile(record.id().toString(), record.host(), record.port(),
                        DirectoryProfile.Transport.valueOf(record.transport()),
                        trustPath, DirectoryProfile.Format.valueOf(record.trustFormat()),
                        null, record.bindDnTemplate(), record.groupSearchBaseDn(), record.accessGroupReference());

                DirectoryTrustPolicy trust = new DirectoryTrustPolicy(profile, actors::expireDirectory);

                this.bindPort = UnboundIdOperatorBindAdapter.create(profile, trust);
                this.revalidationPort = new UnboundIdRevalidationAdapter(false, profile, trust, null, null);

                this.cachedRecord = record;
                this.cachedTrust = trust;
                return true;
            } catch (Exception e) {
                this.cachedRecord = null;
                this.cachedTrust = null;
                this.bindPort = null;
                this.revalidationPort = null;
                return false;
            }
        }

        @Override
        public Result<OperatorBindOutcome> bind(String username, char[] password) {
            if (!loadActiveAdapters() || bindPort == null) {
                if (password != null) java.util.Arrays.fill(password, '\0');
                return Result.err("directory_profile_invalid", "directory_profile_invalid");
            }
            return bindPort.bind(username, password);
        }

        @Override
        public boolean directoryPostureEnabled() {
            if (!loadActiveAdapters() || revalidationPort == null) return false;
            return revalidationPort.directoryPostureEnabled();
        }

        @Override
        public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
            if (!loadActiveAdapters() || revalidationPort == null) {
                return Result.err("directory_profile_invalid", "directory_profile_invalid");
            }
            return revalidationPort.revalidate(actorFingerprint, bindDn);
        }

        @Override
        public Result<DirectoryObservation> revalidatePrincipal(String principalReference) {
            if (!loadActiveAdapters() || revalidationPort == null) {
                return Result.err("directory_profile_invalid", "directory_profile_invalid");
            }
            return revalidationPort.revalidatePrincipal(principalReference);
        }
    }

    @Bean
    public DynamicDirectoryProvider dynamicDirectoryProvider(DirectoryProfileRepository repo, ActorAuthzStateRepository actors) {
        return new DynamicDirectoryProvider(repo, actors);
    }

    @Bean
    public LdapMechanism ldapMechanism(DynamicDirectoryProvider provider) {
        return new LdapMechanism(provider);
    }

    @Bean
    public LdapRevalidationPort ldapRevalidationAdapter(DynamicDirectoryProvider provider) {
        return provider;
    }
}
