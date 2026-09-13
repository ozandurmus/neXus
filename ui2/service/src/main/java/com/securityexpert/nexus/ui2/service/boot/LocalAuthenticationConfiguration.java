package com.securityexpert.nexus.ui2.service.boot;

import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.JooqLocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqSessionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.Clock;
import com.securityexpert.nexus.ui2.service.security.LocalMechanism;
import com.securityexpert.nexus.ui2.service.security.LoginFlow;
import com.securityexpert.nexus.ui2.service.security.MechanismRegistry;
import com.securityexpert.nexus.ui2.service.security.PasswordChangeService;

/**
 * Composition root for the local-authentication flow this movement adds
 * (C3A contract §2's registry, §5's login/lockout). Narrower in scope than
 * a full security composition: it wires only what {@code /login},
 * {@code /login/resolve}, {@code /session/status} and
 * {@code /local-credentials/change-password} need, none of which run
 * through {@code GateChain}/RBAC (C3, unchanged, not wired by this
 * movement).
 *
 * <p>Session durations (30 minutes idle, 10 hours absolute) are {@code C3}
 * §3.2's own fixed values, not a new configuration surface this movement
 * introduces.</p>
 */
@Configuration
public class LocalAuthenticationConfiguration {

    private static final Duration IDLE_TIMEOUT = Duration.ofMinutes(30);
    private static final Duration ABSOLUTE_LIFETIME = Duration.ofHours(10);

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
}
