package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRootIdentityRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqRoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.service.security.LocalIdentityResolver;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;
import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;
import com.securityexpert.nexus.ui2.service.security.SessionSelfLogoutService;

/**
 * Composition root for the local-authentication flow (C3A contract §2's
 * registry, §5's login/lockout) and, since C3B, for
 * {@link FirstBootIdentitySeedingRunner}'s BOOT-5a role-binding seeding.
 * Narrower in scope than a full security composition: beyond what
 * {@code /login}, {@code /login/resolve}, {@code /session/status} and
 * {@code /local-credentials/change-password} need, it wires only the
 * {@code role_bindings} persistence and {@link GroupReferenceCipher} the
 * first-boot seeder requires -- {@code GateChain}/RBAC's HTTP enforcement
 * (C3) stays unwired by this movement.
 *
 * <p>Session durations (30 minutes idle, 10 hours absolute) are {@code C3}
 * §3.2's own fixed values, not a new configuration surface this movement
 * introduces.</p>
 */
@Configuration
public class LocalAuthenticationConfiguration {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(10);

    private final Path groupReferenceKeyFile;
    private final String groupReferenceKeyId;

    public LocalAuthenticationConfiguration(
            @Value("${ui2.role-binding.group-reference-key-file}") String groupReferenceKeyFile,
            @Value("${ui2.role-binding.group-reference-key-id}") String groupReferenceKeyId) {
        this.groupReferenceKeyFile = Path.of(groupReferenceKeyFile);
        this.groupReferenceKeyId = groupReferenceKeyId;
    }

    @Bean
    public DSLContext dslContext(DataSource dataSource) {
        return DSL.using(dataSource, SQLDialect.POSTGRES);
    }

    @Bean
    public TransactionBoundary transactionBoundary(DSLContext dslContext) {
        return new JooqTransactionBoundary(dslContext);
    }

    @Bean
    public Clock clock() {
        return Clock.system();
    }

    @Bean
    public SessionRepository sessionRepository(TransactionBoundary transactionBoundary) {
        return new JooqSessionRepository(transactionBoundary);
    }

    @Bean
    public LocalCredentialsRepository localCredentialsRepository(TransactionBoundary transactionBoundary) {
        return new JooqLocalCredentialsRepository(transactionBoundary);
    }

    /** {@code role_bindings} persistence (C3 §4.2 placement row) -- needed here only for {@link #firstBootIdentityRoleBindingSeeder}; the HTTP administration path stays excluded (BOOT-5b unaffected). */
    @Bean
    public RoleBindingRepository roleBindingRepository(TransactionBoundary transactionBoundary) {
        return new JooqRoleBindingRepository(transactionBoundary);
    }

    @Bean
    public RootIdentityRepository rootIdentityRepository(TransactionBoundary transactionBoundary) {
        return new JooqRootIdentityRepository(transactionBoundary);
    }

    /**
     * The encryption envelope C3 §4.2 requires for {@code role_bindings.group_reference_encrypted}
     * (C1 §6.2's {@code <VAR>_FILE} secret convention: a base64-encoded
     * 256-bit key, read once at startup, failing closed per {@link SecretFile}).
     */
    @Bean
    public GroupReferenceCipher groupReferenceCipher() {
        String base64Key = SecretFile.readRequired(groupReferenceKeyFile, "role_binding_group_ref_key");
        return GroupReferenceCipher.fromBase64Key(base64Key);
    }

    /** C3B contract §2 (BOOT-1..BOOT-5a): the seeder {@link FirstBootIdentitySeedingRunner} runs through. */
    @Bean
    public FirstBootIdentityRoleBindingSeeder firstBootIdentityRoleBindingSeeder(TransactionBoundary transactionBoundary,
            LocalCredentialsRepository localCredentialsRepository, RoleBindingRepository roleBindingRepository,
            GroupReferenceCipher groupReferenceCipher, RootIdentityRepository rootIdentityRepository) {
        return new FirstBootIdentityRoleBindingSeeder(transactionBoundary, localCredentialsRepository,
                roleBindingRepository, groupReferenceCipher, groupReferenceKeyId, rootIdentityRepository);
    }

    @Bean
    public LocalMechanism localMechanism(LocalCredentialsRepository localCredentialsRepository, Clock clock) {
        return new LocalMechanism(localCredentialsRepository, clock);
    }

    /**
     * {@code local} only (contract §2.1) -- no {@code ldap} member is
     * registered here: no directory host/CA/bind-DN configuration exists
     * for this build, and building it is {@code C3}'s scope (§1.2), not
     * this movement's. {@link com.securityexpert.nexus.ui2.identity.ldap.LdapMechanism}
     * exists and compiles against this exact registry shape, ready to be
     * added to the {@code List} below without any other change, once that
     * configuration exists.
     */
    @Bean
    public MechanismRegistry mechanismRegistry(LocalMechanism localMechanism) {
        return new MechanismRegistry(localMechanism, List.of());
    }

    @Bean
    public LoginFlow loginFlow(SessionRepository sessionRepository) {
        return new LoginFlow(sessionRepository, IDLE_TIMEOUT, ABSOLUTE_LIFETIME);
    }

    @Bean
    public PasswordChangeService passwordChangeService(LocalCredentialsRepository localCredentialsRepository) {
        return new PasswordChangeService(localCredentialsRepository);
    }

    /** NXS-LOCAL-0152 AC-4: self sign-out, distinct from the admin-only {@code /sessions/revoke} path. */
    @Bean
    public SessionSelfLogoutService sessionSelfLogoutService(SessionRepository sessionRepository) {
        return new SessionSelfLogoutService(sessionRepository);
    }

    /** NXS-LOCAL-0152: resolves {@code GET /session/status}'s display name and must-change-password flag. */
    @Bean
    public LocalIdentityResolver localIdentityResolver(LocalCredentialsRepository localCredentialsRepository) {
        return new LocalIdentityResolver(localCredentialsRepository);
    }

    /** NXS-LOCAL-0152: resolves {@code GET /session/status}'s displayed role tokens (C3 §4.1). */
    @Bean
    public LocalRoleTokenResolver localRoleTokenResolver(RoleBindingRepository roleBindingRepository,
            GroupReferenceCipher groupReferenceCipher) {
        return new LocalRoleTokenResolver(roleBindingRepository, groupReferenceCipher);
    }
}
