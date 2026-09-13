package com.securityexpert.nexus.ui2.platform;

import java.time.Instant;
import java.util.List;

/**
 * Local identity administration (13G {@code LIA-1}/{@code LIA-2}: create,
 * list, set/reset password, disable, enable). Declared here so {@code cli}
 * can depend on it without reaching {@code persistence} directly (mirrors
 * {@link LocalIdentityBootstrapPort}'s existing pattern exactly); the HTTP
 * API and the CLI share this exact interface and its one implementation
 * (13G LIA-2: "sharing the same domain port ... so the two cannot drift").
 *
 * <p>Role assignment/revocation is deliberately absent from this port (13G
 * {@code LIA-3.4}): both the screen and the CLI reach that through the
 * existing {@code POST /role-bindings} / {@code POST /role-bindings/revoke}
 * paths instead, whose four-eyes and {@code SELF_GRANT_REFUSED} behaviour
 * (C3 §4.3) this movement does not re-implement.</p>
 */
public interface LocalIdentityAdministrationPort {

    /**
     * 13G section 3: exactly the fields an operator may see. Never a
     * verifier, a salt, a password, a group reference or a session token.
     */
    record LocalIdentityView(
            String localIdentityId,
            String localIdentityName,
            boolean enabled,
            boolean mustChangePassword,
            Instant createdAt,
            Instant passwordSetAt) {
    }

    sealed interface MutationResult {
        record Ok(LocalIdentityView view) implements MutationResult {
        }

        record NotFound() implements MutationResult {
        }

        /**
         * 13G {@code LIA-3.5}: the distinct, non-identity-bearing refusal
         * for the operation that would leave the product with no ENABLED
         * identity holding {@code role:security_admin}.
         */
        record LastSecurityAdminRefused() implements MutationResult {
        }
    }

    /**
     * 13G {@code LIA-3.2}/{@code LIA-3.3}: the caller must consider
     * {@code initialPassword} consumed (zeroed) after this call returns.
     * The created identity's {@code mustChangePassword} is true.
     */
    LocalIdentityView create(String actingAdminActorFingerprint, String localIdentityName, char[] initialPassword);

    List<LocalIdentityView> list();

    /**
     * 13G {@code LIA-3.2}/{@code LIA-3.3}: an administrative reset, distinct
     * from an identity's own self-service password change -- the caller
     * must consider {@code newPassword} consumed (zeroed) after this call
     * returns. Leaves {@code mustChangePassword} true.
     */
    MutationResult setPassword(String actingAdminActorFingerprint, String localIdentityId, char[] newPassword);

    /**
     * 13G {@code LIA-3.5}/{@code LIA-3.6}: refused with
     * {@link MutationResult.LastSecurityAdminRefused} if this identity is
     * the product's last enabled {@code role:security_admin} holder;
     * otherwise disables the identity and ends its active session, if any.
     */
    MutationResult disable(String actingAdminActorFingerprint, String localIdentityId);

    /** Re-enables authentication; never resets the credential (13G AC-4, BOOT-2's spirit). */
    MutationResult enable(String actingAdminActorFingerprint, String localIdentityId);
}
