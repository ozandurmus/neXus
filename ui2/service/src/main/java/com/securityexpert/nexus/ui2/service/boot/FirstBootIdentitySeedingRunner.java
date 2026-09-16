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
 * First-boot bootstrap identity and role-binding seeding (C3B contract §2,
 * BOOT-1..BOOT-5b, corrected 2026-09-13).
 *
 * <p>BOOT-1: when {@code local_credentials} holds no rows, this runner
 * creates exactly {@code nexusadmin} and {@code readonlyadmin} with their
 * documented initial passwords ({@link BootstrapCredentialDefaults}).</p>
 *
 * <p>BOOT-2, the decisive rule: the gate is {@link LocalCredentialsRepository#anyExist()}
 * -- the table holding <em>any</em> row, not merely both bootstrap rows,
 * is treated as "already seeded" and this runner does nothing. This is the
 * explicit decision for the partially-seeded case (one bootstrap row
 * present, the other missing): it is left exactly as found, never topped up
 * and never rewritten, so a restart can never silently reset a password an
 * operator has changed, or re-create/alter a role binding (BOOT-2 extended
 * to bindings).</p>
 *
 * <p>BOOT-3/BOOT-3a: the verifier is computed here, at boot, by
 * {@link FirstBootIdentityRoleBindingSeeder} (the same Argon2 hasher and
 * fresh per-credential salt the {@code cli} bootstrap path uses) -- never a
 * literal. Only the plaintext initial password is a constant, and it lives
 * in exactly one place, {@link BootstrapCredentialDefaults}.</p>
 *
 * <p>BOOT-4: {@link FirstBootIdentityRoleBindingSeeder} attributes every
 * created row -- identity and binding alike -- to
 * {@code SecurityAdminBootstrapPort.BOOTSTRAP_ACTOR}, captured by
 * {@code trg_audit_local_credentials}/{@code trg_audit_role_bindings}
 * (C1 §3.5) with no credential material.</p>
 *
 * <p>BOOT-5/BOOT-5a: {@code nexusadmin} is bound to every role token in
 * C3 §4.1's closed vocabulary ({@link #NEXUSADMIN_ROLE_TOKENS}) -- "full
 * administrative capability" (ROLE-1), since no token implies another and
 * ROLE-2 places this account outside the separation-of-duties rule that
 * would otherwise forbid holding {@code role:security_admin} alongside an
 * execution role. {@code readonlyadmin} is bound only to {@code role:viewer}
 * (ROLE-3). Both bindings commit in the same transaction as their
 * identity's own row (BOOT-5a/AC-3), so a crash between the two can never
 * leave an identity seeded with no binding for a restart to silently miss
 * (BOOT-2's rule would otherwise treat the table as already seeded).</p>
 *
 * <p>BOOT-5b: the CLI's {@code bootstrap-security-admin}/
 * {@code bootstrap-local-identity} paths are untouched by this class and
 * remain the way a binding is created or changed outside first boot.</p>
 *
 * <p>Ordered after {@link MigrationStartupRunner}: {@code local_credentials}
 * must exist before this runner can query it.</p>
 */
@Component
@Order(1)
public class FirstBootIdentitySeedingRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(FirstBootIdentitySeedingRunner.class);

    /** ROLE-1: every existing role token, together, is "full administrative capability." */
    static final List<RoleToken> NEXUSADMIN_ROLE_TOKENS = List.of(RoleToken.values());

    /** ROLE-3: read-only, and nothing else. */
    static final List<RoleToken> READONLYADMIN_ROLE_TOKENS = List.of(RoleToken.VIEWER);

    private final LocalCredentialsRepository repository;
    private final FirstBootIdentityRoleBindingSeeder seeder;

    public FirstBootIdentitySeedingRunner(LocalCredentialsRepository repository, FirstBootIdentityRoleBindingSeeder seeder) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.seeder = Objects.requireNonNull(seeder, "seeder");
    }

    @Override
    public void run(ApplicationArguments args) {
        if (repository.anyExist()) {
            // BOOT-2: any row at all -- fully seeded, partially seeded, or
            // operator-created -- means this runner does nothing.
            LOG.info("ui2 first-boot identity seeding skipped: local_credentials already holds at least one row");
            return;
        }
        seeder.seed(List.of(
                new FirstBootIdentityRoleBindingSeeder.IdentitySpec(BootstrapCredentialDefaults.NEXUSADMIN_NAME,
                        BootstrapCredentialDefaults.nexusadminInitialPassword(), NEXUSADMIN_ROLE_TOKENS, true),
                new FirstBootIdentityRoleBindingSeeder.IdentitySpec(BootstrapCredentialDefaults.READONLYADMIN_NAME,
                        BootstrapCredentialDefaults.readonlyadminInitialPassword(), READONLYADMIN_ROLE_TOKENS)));
        // Never the password or its hash (C3A §3.1/§8): counts and names only.
        LOG.info("ui2 first-boot identities and role bindings seeded: identities=2 role_bindings={}",
                NEXUSADMIN_ROLE_TOKENS.size() + READONLYADMIN_ROLE_TOKENS.size());
    }
}
