package com.securityexpert.nexus.ui2.service.boot;

import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.identity.FirstBootIdentityRoleBindingSeeder;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Ensures the AIView identity (and its role:viewer + role:replay_viewer bindings)
 * exists idempotently across both fresh installs and existing live deployments.
 */
@Component
@Order(2)
public class AiViewIdentitySeedingRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(AiViewIdentitySeedingRunner.class);

    static final List<String> AIVIEW_ROLE_TOKENS = List.of(RoleToken.VIEWER, RoleToken.REPLAY_VIEWER);

    private final LocalCredentialsRepository repository;
    private final FirstBootIdentityRoleBindingSeeder seeder;
    private final com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver roles;

    public AiViewIdentitySeedingRunner(LocalCredentialsRepository repository, FirstBootIdentityRoleBindingSeeder seeder) {
        this(repository, seeder, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiViewIdentitySeedingRunner(LocalCredentialsRepository repository, FirstBootIdentityRoleBindingSeeder seeder,
            com.securityexpert.nexus.ui2.service.security.LocalRoleTokenResolver roles) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.seeder = Objects.requireNonNull(seeder, "seeder");
        this.roles = roles;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.findByName(BootstrapCredentialDefaults.AIVIEW_NAME).isEmpty()) {
            seeder.seed(List.of(
                    new FirstBootIdentityRoleBindingSeeder.IdentitySpec(
                            BootstrapCredentialDefaults.AIVIEW_NAME,
                            BootstrapCredentialDefaults.aiviewInitialPassword(),
                            AIVIEW_ROLE_TOKENS)));
            LOG.info("ui2 aiview identity and replay-viewer role bindings seeded successfully");
        } else {
            LOG.debug("ui2 aiview identity already exists, skipping seeding");
        }
        // PO 2026-09-27: a second masked identity for the end-to-end screen tests, so they never supersede the PO's
        // own aiview session. Its initial password is random and never shown: a security_admin sets it under
        // Administration > Local identities ("Set password").
        if (repository.findByName(E2E_NAME).isEmpty()) {
            char[] password = new char[40];
            java.security.SecureRandom random = new java.security.SecureRandom();
            String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
            for (int i = 0; i < password.length; i++) password[i] = alphabet.charAt(random.nextInt(alphabet.length()));
            seeder.seed(List.of(new FirstBootIdentityRoleBindingSeeder.IdentitySpec(E2E_NAME, password, AIVIEW_ROLE_TOKENS)));
            LOG.info("ui2 aiview-e2e identity and replay-viewer role bindings seeded (password to be set by an administrator)");
        } else if (roles != null) {
            // Created by hand before this runner existed (2026-09-27): replay_viewer cannot be assigned in the UI, so
            // complete the missing bindings here. Extra roles are left alone; the machine login refuses them.
            String id = repository.findByName(E2E_NAME).get().localIdentityId();
            java.util.Set<String> held = java.util.Set.copyOf(roles.resolve(id));
            List<String> missing = AIVIEW_ROLE_TOKENS.stream().filter(t -> !held.contains(t)).toList();
            if (!missing.isEmpty()) {
                seeder.bindRoles(id, missing);
                LOG.info("ui2 aiview-e2e: bound missing role tokens {}", missing);
            }
        }
    }

    static final String E2E_NAME = "aiview-e2e";
}
