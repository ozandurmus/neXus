package com.securityexpert.nexus.ui2.platform;

/**
 * The bootstrap-account creation port (C3A contract §6, §11 U-2: this
 * movement's own choice of seeding mechanism -- a CLI-only, deployment
 * -controlled action, mirroring {@link SecurityAdminBootstrapPort}'s
 * existing pattern for the first {@code role:security_admin} binding
 * exactly, per §7.3's "this contract adds no second bootstrap mechanism for
 * role binding; it only supplies the local identity that path binds a role
 * to"). Reachable only from the {@code cli} module, never a running
 * service, never the browser.
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
