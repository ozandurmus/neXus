package com.securityexpert.nexus.ui2.service.security;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * Resolves a local identity's own bound role tokens for display (NXS-LOCAL-0152,
 * {@code GET /session/status}; C3 §4.1's closed vocabulary). A local
 * identity's binding is direct and self-referencing -- {@code
 * group_reference_encrypted} holds the identity's own {@code
 * local_identity_id} (C3A §7.1, {@code FirstBootIdentityRoleBindingSeeder}) --
 * so this never runs {@link RbacEvaluator}'s directory-group evaluation
 * (which needs a fresh {@code actor_authz_state} row this movement does not
 * populate for local identities); it is a direct membership check against
 * each token's active bindings.
 */
public final class LocalRoleTokenResolver {

    private final RoleBindingRepository roleBindingRepository;
    private final GroupReferenceCipher groupReferenceCipher;

    public LocalRoleTokenResolver(RoleBindingRepository roleBindingRepository, GroupReferenceCipher groupReferenceCipher) {
        this.roleBindingRepository = Objects.requireNonNull(roleBindingRepository, "roleBindingRepository");
        this.groupReferenceCipher = Objects.requireNonNull(groupReferenceCipher, "groupReferenceCipher");
    }

    public List<RoleToken> resolve(String localIdentityId) {
        List<RoleToken> resolved = new ArrayList<>();
        for (RoleToken token : RoleToken.values()) {
            for (RoleBindingRecord binding : roleBindingRepository.findActiveByToken(token.token())) {
                if (localIdentityId.equals(groupReferenceCipher.decrypt(binding.groupReferenceEncrypted()))) {
                    resolved.add(token);
                    break;
                }
            }
        }
        return resolved;
    }

    /** The matching direct binding, retained for E4's audit row. */
    public Optional<RoleBindingRecord> resolveBinding(String localIdentityId, RoleToken token) {
        return roleBindingRepository.findActiveByToken(token.token()).stream()
                .filter(binding -> localIdentityId.equals(groupReferenceCipher.decrypt(binding.groupReferenceEncrypted())))
                .findFirst();
    }
}
