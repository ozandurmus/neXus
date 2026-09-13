package com.securityexpert.nexus.ui2.platform;

/**
 * The bootstrap-account creation port (C3A contract §6, §11 U-2). Two
 * callers reach it, both deployment-controlled or startup-controlled --
 * never the browser: the {@code cli} module's {@code bootstrap-local-identity}
 * action, for an operator-chosen identity/password pair; and, since
 * {@code UI2_0_C3B_BOOTSTRAP_IDENTITIES_AND_ROLES.md} (BOOT-1), the running
 * service's own first-boot seeding routine, which uses it only once, only
 * when {@code local_credentials} is empty, and only for the two documented
 * bootstrap identities. Role binding stays CLI-only and untouched by BOOT-1
 * (BOOT-5); see {@link SecurityAdminBootstrapPort}, whose existing pattern
 * this port already mirrored before BOOT-1 existed.
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
