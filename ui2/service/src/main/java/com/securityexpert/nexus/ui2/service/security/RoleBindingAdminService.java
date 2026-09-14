package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SecurityAdminLockoutGuard;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;
import com.securityexpert.nexus.ui2.platform.LocalPrincipalFingerprint;

/**
 * {@code role_bindings} administration: four-eyes / self-grant refusal
 * (C3 §4.3, {@code SR-D5}). The {@code role:security_admin} requirement
 * itself is enforced by {@link GateChain}'s {@code E4} before this class
 * is ever reached (the action {@link ActionRegistry#ROLE_BINDING_CREATE}
 * requires {@code role:security_admin}); this class enforces the
 * <b>additional</b> self-grant check C3 §4.3 requires on top of that.
 */
public final class RoleBindingAdminService {

    public static final String ACTION_CREATE = ActionRegistry.ROLE_BINDING_CREATE;
    public static final String ACTION_REVOKE = ActionRegistry.ROLE_BINDING_REVOKE;

    public sealed interface Outcome {
        record Created(String bindingId) implements Outcome {
        }

        record Revoked(String bindingId) implements Outcome {
        }

        /** {@code 403 SELF_GRANT_REFUSED} (C3 §4.3). */
        record SelfGrantRefused() implements Outcome {
        }

        /**
         * 13G {@code LIA-3.5}: the distinct, non-identity-bearing refusal
         * for revoking the product's last enabled {@code role:security_admin}
         * binding -- the same check {@code LocalIdentityAdministration}'s
         * {@code disable} path reaches, via {@link SecurityAdminLockoutGuard}.
         */
        record LastSecurityAdminRefused() implements Outcome {
        }
    }

    private final RoleBindingRepository roleBindingRepository;
    private final ActorAuthzStateRepository actorAuthzStateRepository;
    private final GroupReferenceCipher groupReferenceCipher;
    private final SecurityAdminLockoutGuard securityAdminLockoutGuard;
    private final RootIdentityRepository rootIdentityRepository;

    public RoleBindingAdminService(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher,
            SecurityAdminLockoutGuard securityAdminLockoutGuard) {
        this.roleBindingRepository = roleBindingRepository;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.groupReferenceCipher = groupReferenceCipher;
        this.securityAdminLockoutGuard = securityAdminLockoutGuard;
        this.rootIdentityRepository = null;
    }

    public RoleBindingAdminService(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher,
            SecurityAdminLockoutGuard securityAdminLockoutGuard, RootIdentityRepository rootIdentityRepository) {
        this.roleBindingRepository = roleBindingRepository;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.groupReferenceCipher = groupReferenceCipher;
        this.securityAdminLockoutGuard = securityAdminLockoutGuard;
        this.rootIdentityRepository = rootIdentityRepository;
    }

    public Outcome create(String actingAdminActorFingerprint, String roleToken, String plaintextGroupReference,
            String groupReferenceKeyId, Instant now) {
        if (!isRoot(actingAdminActorFingerprint) && adminAlreadyInGroup(actingAdminActorFingerprint, plaintextGroupReference, now)) {
            return new Outcome.SelfGrantRefused();
        }
        String bindingId = OpaqueId.random().value();
        byte[] encrypted = groupReferenceCipher.encrypt(plaintextGroupReference);
        roleBindingRepository.create(bindingId, roleToken, encrypted, groupReferenceKeyId,
                actingAdminActorFingerprint, ACTION_CREATE);
        return new Outcome.Created(bindingId);
    }

    public Outcome revoke(String actingAdminActorFingerprint, String bindingId, Instant now) {
        Optional<RoleBindingRecord> binding = roleBindingRepository.find(bindingId);
        if (binding.isPresent() && binding.get().isActive()) {
            // 13G LIA-3.5: checked before the self-grant check, and
            // independent of it -- revoking the last enabled
            // role:security_admin binding is refused even when the acting
            // admin is not the one losing access.
            if (!isRoot(actingAdminActorFingerprint) && RoleToken.SECURITY_ADMIN.token().equals(binding.get().roleToken())
                    && !securityAdminLockoutGuard.anyEnabledSecurityAdminRemainsIfBindingRevoked(bindingId)) {
                return new Outcome.LastSecurityAdminRefused();
            }
            String plaintext = groupReferenceCipher.decrypt(binding.get().groupReferenceEncrypted());
            if (!isRoot(actingAdminActorFingerprint) && adminAlreadyInGroup(actingAdminActorFingerprint, plaintext, now)) {
                return new Outcome.SelfGrantRefused();
            }
        }
        roleBindingRepository.revoke(bindingId, actingAdminActorFingerprint, ACTION_REVOKE);
        return new Outcome.Revoked(bindingId);
    }

    /** C3 §4.3: refuses when the acting admin's own resolved group set already contains the group in play. */
    private boolean adminAlreadyInGroup(String actingAdminActorFingerprint, String plaintextGroupReference,
            Instant now) {
        Set<String> adminGroups = actorAuthzStateRepository.find(actingAdminActorFingerprint)
                .filter(state -> state.isFresh(now))
                .map(state -> state.groupReferences())
                .orElse(Set.of());
        return adminGroups.contains(plaintextGroupReference);
    }

    private boolean isRoot(String actorFingerprint) {
        return rootIdentityRepository != null && rootIdentityRepository.rootLocalIdentityId()
                .map(LocalPrincipalFingerprint::forLocalIdentity).filter(actorFingerprint::equals).isPresent();
    }
}
