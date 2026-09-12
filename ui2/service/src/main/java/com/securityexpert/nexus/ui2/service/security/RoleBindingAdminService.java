package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.OpaqueId;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;

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
    }

    private final RoleBindingRepository roleBindingRepository;
    private final ActorAuthzStateRepository actorAuthzStateRepository;
    private final GroupReferenceCipher groupReferenceCipher;

    public RoleBindingAdminService(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher) {
        this.roleBindingRepository = roleBindingRepository;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.groupReferenceCipher = groupReferenceCipher;
    }

    public Outcome create(String actingAdminActorFingerprint, String roleToken, String plaintextGroupReference,
            String groupReferenceKeyId, Instant now) {
        if (adminAlreadyInGroup(actingAdminActorFingerprint, plaintextGroupReference, now)) {
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
            String plaintext = groupReferenceCipher.decrypt(binding.get().groupReferenceEncrypted());
            if (adminAlreadyInGroup(actingAdminActorFingerprint, plaintext, now)) {
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
}
