package com.securityexpert.nexus.ui2.persistence.identity;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * 13G {@code LIA-3.5}: "any operation that would leave the product with no
 * ENABLED identity holding {@code role:security_admin} is refused ... this
 * covers disabling the last one and revoking the last binding, so the check
 * lives where both paths reach it, not only in the disable handler."
 *
 * <p>Lives in {@code persistence} (not in either caller's own module) so
 * both {@code LocalIdentityAdministration}'s {@code disable} path and
 * {@code RoleBindingAdminService}'s {@code revoke} path call the exact same
 * check, one implementation, never two independently-written copies of the
 * same safety rule.</p>
 *
 * <p>A {@code role:security_admin} binding whose decrypted group reference
 * does not resolve to a known local identity is treated as an
 * always-available admin: this movement (and this build) has no directory
 * mechanism wired to disable such a principal, so it cannot be the thing a
 * local-identity-administration or role-binding-revoke operation here takes
 * away. Fail-closed applies to the opposite case instead: an operation is
 * refused unless at least one OTHER binding is provably a known, enabled
 * local identity -- an unresolvable binding is never counted as the proof
 * that keeps the operation safe.</p>
 */
public final class SecurityAdminLockoutGuard {

    private static final String SECURITY_ADMIN_TOKEN = RoleToken.SECURITY_ADMIN.token();

    private final RoleBindingRepository roleBindingRepository;
    private final LocalCredentialsRepository localCredentialsRepository;
    private final GroupReferenceCipher groupReferenceCipher;

    public SecurityAdminLockoutGuard(RoleBindingRepository roleBindingRepository,
            LocalCredentialsRepository localCredentialsRepository, GroupReferenceCipher groupReferenceCipher) {
        this.roleBindingRepository = Objects.requireNonNull(roleBindingRepository, "roleBindingRepository");
        this.localCredentialsRepository = Objects.requireNonNull(localCredentialsRepository, "localCredentialsRepository");
        this.groupReferenceCipher = Objects.requireNonNull(groupReferenceCipher, "groupReferenceCipher");
    }

    /** True if disabling {@code candidateLocalIdentityId} would still leave an enabled {@code role:security_admin}. */
    public boolean anyEnabledSecurityAdminRemainsIfLocalIdentityDisabled(String candidateLocalIdentityId) {
        return anyEnabledSecurityAdminRemains(Optional.empty(), Optional.of(candidateLocalIdentityId));
    }

    /** True if revoking {@code candidateBindingId} would still leave an enabled {@code role:security_admin}. */
    public boolean anyEnabledSecurityAdminRemainsIfBindingRevoked(String candidateBindingId) {
        return anyEnabledSecurityAdminRemains(Optional.of(candidateBindingId), Optional.empty());
    }

    private boolean anyEnabledSecurityAdminRemains(Optional<String> excludedBindingId,
            Optional<String> excludedLocalIdentityId) {
        for (RoleBindingRecord binding : roleBindingRepository.findActiveByToken(SECURITY_ADMIN_TOKEN)) {
            if (excludedBindingId.isPresent() && excludedBindingId.get().equals(binding.bindingId())) {
                continue;
            }
            String reference = groupReferenceCipher.decrypt(binding.groupReferenceEncrypted());
            Optional<LocalCredentialRecord> localIdentity = localCredentialsRepository.findById(reference);
            if (localIdentity.isEmpty()) {
                return true;
            }
            boolean excludedByThisOperation = excludedLocalIdentityId.isPresent()
                    && excludedLocalIdentityId.get().equals(reference);
            if (localIdentity.get().enabled() && !excludedByThisOperation) {
                return true;
            }
        }
        return false;
    }
}
