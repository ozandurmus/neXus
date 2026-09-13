package com.securityexpert.nexus.ui2.platform;

/**
 * The bootstrap-account creation port (C3A contract §6, §11 U-2), reached
 * only from the {@code cli} module's {@code bootstrap-local-identity}
 * action, for an operator-chosen identity/password pair, deployment
 * -controlled and never the browser (BOOT-5b: this CLI path is unaffected
 * by first-boot seeding and stays the way an identity is created or
 * changed outside first boot).
 *
 * <p>The running service's own first-boot seeding routine
 * ({@code UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md}, BOOT-1..BOOT-5a)
 * does <b>not</b> reach this port: it needs its identity row and role
 * -binding row(s) to commit in one transaction (BOOT-5a), which this port's
 * one-identity-at-a-time shape cannot express, so it goes through
 * {@code persistence.identity.FirstBootIdentityRoleBindingSeeder} instead.
 * See {@link SecurityAdminBootstrapPort}, whose existing pattern this port
 * already mirrored before BOOT-1 existed.</p>
 */
public interface LocalIdentityBootstrapPort {

    /**
     * @param localIdentityName the display/lookup name (e.g. {@code nexusadmin}, {@code claudeadmin})
     * @param initialPassword   the initial credential; the caller must
     *                           consider it consumed (zeroed) after this
     *                           call returns
     * @return the created row's opaque {@code local_identity_id} -- printed
     *          by the CLI so the operator can bind a role to it via the
     *          existing {@link SecurityAdminBootstrapPort} path (§7.1: for a
     *          local identity, that path's group reference is an encrypted
     *          reference to this very id)
     */
    String bootstrapLocalIdentity(String localIdentityName, char[] initialPassword);
}
