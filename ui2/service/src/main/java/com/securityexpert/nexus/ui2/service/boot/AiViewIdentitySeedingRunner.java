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
    }
}
