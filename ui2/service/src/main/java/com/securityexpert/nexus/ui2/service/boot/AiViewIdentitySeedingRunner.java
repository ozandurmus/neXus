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

    public AiViewIdentitySeedingRunner(LocalCredentialsRepository repository, FirstBootIdentityRoleBindingSeeder seeder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.seeder = Objects.requireNonNull(seeder, "seeder");
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
        }
    }

    static final String E2E_NAME = "aiview-e2e";
}
