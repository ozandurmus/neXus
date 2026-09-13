package com.securityexpert.nexus.ui2.service.boot;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.JooqAuthzDecisionRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalIdentityAdministration;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SecurityAdminLockoutGuard;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.LocalIdentityAdministrationPort;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChain;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.security.RoleBindingAdminService;
import com.securityexpert.nexus.ui2.service.security.SecurityWebMvcConfig;

/**
 * Composition root for the {@code E1}-{@code E6} gate chain (C3 §6.1) and
 * the {@code role_bindings}/13G local-identity-administration domain
 * services it protects. Completes the wiring
 * {@link Ui2Application}'s own javadoc names as deferred ("their own
 * collaborators ... are untouched by this movement and remain out of
 * scope") -- this movement is the one that needs
 * {@code POST /role-bindings}/{@code POST /role-bindings/revoke} and its
 * own new {@code /local-identities} resource reachable for real (13G
 * {@code LIA-5}: "through the existing gate chain -- reuse it").
 *
 * <p>{@code SessionAdminController} and {@code DeviceRegistrationController}
 * stay excluded from component scanning (unaffected, out of this
 * movement's scope): their own remaining collaborators (a session-admin
 * service, device registration) are still unwired.</p>
 */
@Configuration
public class RbacConfiguration {

    @Bean
    public ActorAuthzStateRepository actorAuthzStateRepository(TransactionBoundary transactionBoundary) {
        return new JooqActorAuthzStateRepository(transactionBoundary);
    }

    @Bean
    public ActionRegistry actionRegistry() {
        return new ActionRegistry();
    }

    @Bean
    public AuthzDecisionRepository authzDecisionRepository(TransactionBoundary transactionBoundary) {
        return new JooqAuthzDecisionRepository(transactionBoundary);
    }

    @Bean
    public RbacEvaluator rbacEvaluator(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher) {
        return new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, groupReferenceCipher);
    }

    @Bean
    public GateChain gateChain(SessionRepository sessionRepository, ActionRegistry actionRegistry,
            RbacEvaluator rbacEvaluator, AuthzDecisionRepository authzDecisionRepository) {
        return new GateChain(sessionRepository, actionRegistry, rbacEvaluator, authzDecisionRepository);
    }

    @Bean
    public SecurityWebMvcConfig securityWebMvcConfig(GateChain gateChain) {
        return new SecurityWebMvcConfig(gateChain);
    }

    /** 13G LIA-3.5: shared by {@link RoleBindingAdminService#revoke} and {@link LocalIdentityAdministration#disable}. */
    @Bean
    public SecurityAdminLockoutGuard securityAdminLockoutGuard(RoleBindingRepository roleBindingRepository,
            LocalCredentialsRepository localCredentialsRepository, GroupReferenceCipher groupReferenceCipher) {
        return new SecurityAdminLockoutGuard(roleBindingRepository, localCredentialsRepository, groupReferenceCipher);
    }

    @Bean
    public RoleBindingAdminService roleBindingAdminService(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher,
            SecurityAdminLockoutGuard securityAdminLockoutGuard) {
        return new RoleBindingAdminService(roleBindingRepository, actorAuthzStateRepository, groupReferenceCipher,
                securityAdminLockoutGuard);
    }

    /** 13G LIA-2: the exact same class the CLI's own composition (job-engine) constructs. */
    @Bean
    public LocalIdentityAdministrationPort localIdentityAdministrationPort(
            LocalCredentialsRepository localCredentialsRepository, SessionRepository sessionRepository,
            SecurityAdminLockoutGuard securityAdminLockoutGuard) {
        return new LocalIdentityAdministration(localCredentialsRepository, sessionRepository,
                securityAdminLockoutGuard);
    }
}
