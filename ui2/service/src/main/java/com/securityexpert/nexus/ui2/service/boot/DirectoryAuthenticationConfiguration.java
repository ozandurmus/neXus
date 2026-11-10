package com.securityexpert.nexus.ui2.service.boot;

import java.time.Clock;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.identity.ldap.DirectoryProfile;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryReason;
import com.securityexpert.nexus.ui2.identity.ldap.DirectoryTrustPolicy;
import com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism;
import com.securityexpert.nexus.ui2.identity.ldap.UnboundIdOperatorBindAdapter;
import com.securityexpert.nexus.ui2.identity.ldap.UnboundIdRevalidationAdapter;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLdapSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LdapSettingsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LdapOperatorBindPort;
import com.securityexpert.nexus.ui2.platform.LdapRevalidationPort;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.service.security.DirectoryAuthorizationRefresh;
import com.securityexpert.nexus.ui2.service.security.LdapSettingsService;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Active Directory service-bind sign-in (design LDAP_SERVICE_BIND_DESIGN_2026_11_11): the adapters are rebuilt
 * from {@code ldap_settings} whenever the stored row changes. A refused or failed directory attempt logs one line,
 * {@code ldap_login_refused reason=<code>}, and nothing else: never a user name, DN, password or server address.
 */
@Configuration
public class DirectoryAuthenticationConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger("com.securityexpert.nexus.ui2.ldap");

    @Bean
    public LdapSettingsRepository ldapSettingsRepository(TransactionBoundary transactionBoundary) {
        return new JooqLdapSettingsRepository(transactionBoundary);
    }

    /** The live directory, reloaded from the settings row; disabled or invalid settings mean "unavailable". */
    public static final class DirectoryProvider implements LdapOperatorBindPort, LdapRevalidationPort {
        private record Live(LdapSettingsRecord record, DirectoryTrustPolicy trust, LdapOperatorBindPort bind,
                LdapRevalidationPort revalidation) { }

        private final LdapSettingsRepository settings;
        private final ActorAuthzStateRepository actors;
        private final CredentialStoreCipher passwords;
        private LdapSettingsRecord loaded;
        private boolean loadedOnce;
        private Live live;

        public DirectoryProvider(LdapSettingsRepository settings, ActorAuthzStateRepository actors,
                CredentialStoreCipher passwords) {
            this.settings = settings;
            this.actors = actors;
            this.passwords = passwords;
        }

        synchronized Optional<Live> current() {
            Optional<LdapSettingsRecord> row;
            try {
                row = settings.find();
            } catch (RuntimeException e) {
                LOG.warn("ldap_settings_unreadable");
                return Optional.ofNullable(live);
            }
            LdapSettingsRecord record = row.orElse(null);
            if (loadedOnce && java.util.Objects.equals(record, loaded)) return Optional.ofNullable(live);
            if (live != null) live.trust().withdraw();
            live = null;
            loaded = record;
            loadedOnce = true;
            if (record == null || !record.enabled()) return Optional.empty();
            Optional<DirectoryProfile> profile = LdapSettingsService.profile(record);
            if (profile.isEmpty()) {
                LOG.warn("ldap_settings_rejected reason={}", DirectoryReason.SETTINGS_INVALID.code());
                return Optional.empty();
            }
            try {
                DirectoryTrustPolicy trust = new DirectoryTrustPolicy(profile.get(), actors::expireDirectory);
                byte[] encrypted = record.bindPasswordEncrypted();
                java.util.function.Supplier<char[]> password = () -> passwords.decrypt(encrypted).toCharArray();
                live = new Live(record, trust, new UnboundIdOperatorBindAdapter(profile.get(), trust, password),
                        new UnboundIdRevalidationAdapter(profile.get(), trust, password));
            } catch (RuntimeException e) {
                LOG.warn("ldap_settings_rejected reason={}", DirectoryReason.SETTINGS_INVALID.code());
                live = null;
            }
            return Optional.ofNullable(live);
        }

        /** Called after a settings save: retire the old trust generation now, not at the next sign-in. */
        public void reload() {
            current();
        }

        @Override
        public Result<OperatorBindOutcome> bind(String username, char[] password) {
            Optional<Live> directory = current();
            if (directory.isEmpty()) {
                if (password != null) Arrays.fill(password, '\0');
                LOG.info("ldap_login_refused reason={}", DirectoryReason.DIRECTORY_DISABLED.code());
                return Result.err(FailureCodes.DIRECTORY_UNAVAILABLE, DirectoryReason.DIRECTORY_DISABLED.code());
            }
            Result<OperatorBindOutcome> result = directory.get().bind().bind(username, password);
            if (result instanceof Result.Err<OperatorBindOutcome> err) {
                LOG.info("ldap_login_refused reason={}", DirectoryReason.fromCode(err.message()).code());
            }
            return result;
        }

        @Override
        public boolean directoryPostureEnabled() {
            return current().isPresent();
        }

        @Override
        public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
            Optional<Live> directory = current();
            if (directory.isEmpty()) return Result.err("directory_posture_disabled", DirectoryReason.DIRECTORY_DISABLED.code());
            return directory.get().revalidation().revalidate(actorFingerprint, bindDn);
        }

        @Override
        public Result<DirectoryObservation> revalidatePrincipal(String principalReference) {
            Optional<Live> directory = current();
            if (directory.isEmpty()) return Result.err("directory_posture_disabled", DirectoryReason.DIRECTORY_DISABLED.code());
            Result<DirectoryObservation> result = directory.get().revalidation().revalidatePrincipal(principalReference);
            if (result instanceof Result.Err<DirectoryObservation> err) {
                LOG.info("ldap_revalidation_refused reason={}", DirectoryReason.fromCode(err.message()).code());
            }
            return result;
        }
    }

    @Bean
    public DirectoryProvider directoryProvider(LdapSettingsRepository settings, ActorAuthzStateRepository actors,
            CredentialStoreCipher credentialStoreCipher) {
        return new DirectoryProvider(settings, actors, credentialStoreCipher);
    }

    @Bean
    public LdapSettingsService ldapSettingsService(LdapSettingsRepository settings, RoleBindingRepository bindings,
            CredentialStoreCipher credentialStoreCipher, @Value("${ui2.credential-store.key-id}") String credentialStoreKeyId,
            GroupReferenceCipher groupReferenceCipher, Clock clock, DirectoryProvider provider) {
        return new LdapSettingsService(settings, bindings, credentialStoreCipher, credentialStoreKeyId,
                groupReferenceCipher, clock, provider::reload);
    }

    @Bean
    public LdapMechanism ldapMechanism(DirectoryProvider provider) {
        return new LdapMechanism(provider);
    }

    @Bean
    public LdapRevalidationPort ldapRevalidationAdapter(DirectoryProvider provider) {
        return provider;
    }

    /** Stale directory group sets are refreshed on demand by the evaluator (design §6). */
    @Bean
    public DirectoryAuthorizationRefresh directoryAuthorizationRefresh(DirectoryProvider provider,
            RoleBindingRepository bindings, GroupReferenceCipher groupReferenceCipher, ActorAuthzStateRepository actors,
            SessionRepository sessions, RbacEvaluator rbacEvaluator) {
        DirectoryAuthorizationRefresh refresh = new DirectoryAuthorizationRefresh(provider, bindings,
                groupReferenceCipher, actors, sessions);
        rbacEvaluator.useDirectoryRefresh(refresh::refreshStale);
        return refresh;
    }
}
