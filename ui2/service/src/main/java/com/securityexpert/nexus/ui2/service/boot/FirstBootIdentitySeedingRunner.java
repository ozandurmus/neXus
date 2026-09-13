package com.securityexpert.nexus.ui2.service.boot;

import java.util.Arrays;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.platform.LocalIdentityBootstrapPort;

/**
 * First-boot bootstrap identity seeding (C3B contract §2, BOOT-1..BOOT-5).
 *
 * <p>BOOT-1: when {@code local_credentials} holds no rows, this runner
 * creates exactly {@code nexusadmin} and {@code claudeadmin} with their
 * documented initial passwords ({@link BootstrapCredentialDefaults}).</p>
 *
 * <p>BOOT-2, the decisive rule: the gate is {@link LocalCredentialsRepository#anyExist()}
 * -- the table holding <em>any</em> row, not merely both bootstrap rows,
 * is treated as "already seeded" and this runner does nothing. This is the
 * explicit decision for the partially-seeded case (one bootstrap row
 * present, the other missing): it is left exactly as found, never topped up
 * and never rewritten, so a restart can never silently reset a password an
 * operator has changed.</p>
 *
 * <p>BOOT-3/BOOT-3a: the verifier is computed here, at boot, by
 * {@link LocalIdentityBootstrapPort#bootstrapLocalIdentity} (the same
 * Argon2 hasher and fresh per-credential salt the {@code cli} bootstrap
 * path uses) -- never a literal. Only the plaintext initial password is a
 * constant, and it lives in exactly one place,
 * {@link BootstrapCredentialDefaults}.</p>
 *
 * <p>BOOT-4: {@link LocalIdentityBootstrapPort}'s implementation attributes
 * each created row to {@code SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR},
 * captured by {@code trg_audit_local_credentials} (C1 §3.5) with no
 * credential material.</p>
 *
 * <p>BOOT-5: this runner depends on nothing capable of writing
 * {@code role_bindings} -- it creates identities only.</p>
 *
 * <p>Ordered after {@link MigrationStartupRunner}: {@code local_credentials}
 * must exist before this runner can query it.</p>
 */
@Component
@Order(1)
public class FirstBootIdentitySeedingRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FirstBootIdentitySeedingRunner.class);

    private final LocalCredentialsRepository repository;
    private final LocalIdentityBootstrapPort bootstrap;

    public FirstBootIdentitySeedingRunner(LocalCredentialsRepository repository, LocalIdentityBootstrapPort bootstrap) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.anyExist()) {
            // BOOT-2: any row at all -- fully seeded, partially seeded, or
            // operator-created -- means this runner does nothing.
            LOG.info("ui2 first-boot identity seeding skipped: local_credentials already holds at least one row");
            return;
        }
        seedOne(BootstrapCredentialDefaults.NEXUSADMIN_NAME, BootstrapCredentialDefaults.nexusadminInitialPassword());
        seedOne(BootstrapCredentialDefaults.CLAUDEADMIN_NAME, BootstrapCredentialDefaults.claudeadminInitialPassword());
        // Never the password or its hash (C3A §3.1/§8): counts and names only.
        LOG.info("ui2 first-boot identities seeded: count=2");
    }

    private void seedOne(String localIdentityName, char[] initialPassword) {
        try {
            bootstrap.bootstrapLocalIdentity(localIdentityName, initialPassword);
        } finally {
            Arrays.fill(initialPassword, '\0');
        }
    }
}
