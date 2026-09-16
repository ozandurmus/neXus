package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RootIdentityRepository;

/**
 * {@code E4}'s four-outcome evaluation (C3 §5.1, §6.1). An exact-match
 * set-membership check against {@code actor_authz_state}'s resolved group
 * references — no nested-group expansion ({@code M14 U-1}, still open, C3
 * §9 item 6, unaffected by this class).
 */
public final class RbacEvaluator {

    /** Closed {@code reason_code} vocabulary (C3 §5.2). */
    public static final String REASON_ACTOR_NOT_IN_REQUIRED_GROUP = "actor_not_in_required_group";
    public static final String REASON_ROLE_TOKEN_UNBOUND = "role_token_unbound";
    public static final String REASON_ACTOR_GROUP_SET_STALE = "actor_group_set_stale";
    public static final String REASON_DIRECTORY_BINDING_NOT_EVALUABLE = "directory_binding_not_evaluable";
    public static final String REASON_ACTOR_NOT_IN_REQUIRED_BINDING = "actor_not_in_required_binding";
    public static final String AUTHORITY = "ui2_ldap";
    public static final String LOCAL_AUTHORITY = "ui2_local";
    public static final String ROOT_AUTHORITY = "ui2_root";

    private final RoleBindingRepository roleBindingRepository;
    private final ActorAuthzStateRepository actorAuthzStateRepository;
    private final GroupReferenceCipher groupReferenceCipher;
    private final LocalIdentityResolver localIdentityResolver;
    private final LocalRoleTokenResolver localRoleTokenResolver;
    private final RootIdentityRepository rootIdentityRepository;

    public RbacEvaluator(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher) {
        this.roleBindingRepository = roleBindingRepository;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.groupReferenceCipher = groupReferenceCipher;
        this.localIdentityResolver = null;
        this.localRoleTokenResolver = null;
        this.rootIdentityRepository = null;
    }

    public RbacEvaluator(RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher,
            LocalIdentityResolver localIdentityResolver, LocalRoleTokenResolver localRoleTokenResolver,
            RootIdentityRepository rootIdentityRepository) {
        this.roleBindingRepository = roleBindingRepository;
        this.actorAuthzStateRepository = actorAuthzStateRepository;
        this.groupReferenceCipher = groupReferenceCipher;
        this.localIdentityResolver = localIdentityResolver;
        this.localRoleTokenResolver = localRoleTokenResolver;
        this.rootIdentityRepository = rootIdentityRepository;
    }

    public record Decision(AuthzOutcome outcome, Optional<String> authority, Optional<String> reasonCode,
            Optional<String> bindingId) {

        static Decision permitted(String bindingId) {
            return new Decision(AuthzOutcome.PERMITTED, Optional.of(AUTHORITY), Optional.empty(),
                    Optional.of(bindingId));
        }

        static Decision permittedLocal(String bindingId) {
            return new Decision(AuthzOutcome.PERMITTED, Optional.of(LOCAL_AUTHORITY), Optional.empty(), Optional.of(bindingId));
        }

        static Decision permittedRoot() {
            return new Decision(AuthzOutcome.PERMITTED, Optional.of(ROOT_AUTHORITY), Optional.empty(), Optional.empty());
        }

        static Decision noApplicableAuthority() {
            return new Decision(AuthzOutcome.NO_APPLICABLE_AUTHORITY, Optional.empty(), Optional.empty(),
                    Optional.empty());
        }

        static Decision deniedNotInGroup() {
            return new Decision(AuthzOutcome.DENIED, Optional.of(AUTHORITY),
                    Optional.of(REASON_ACTOR_NOT_IN_REQUIRED_GROUP), Optional.empty());
        }

        static Decision notEvaluatedUnbound() {
            return new Decision(AuthzOutcome.AUTHZ_NOT_EVALUATED, Optional.of(AUTHORITY),
                    Optional.of(REASON_ROLE_TOKEN_UNBOUND), Optional.empty());
        }

        static Decision notEvaluatedStale() {
            return new Decision(AuthzOutcome.AUTHZ_NOT_EVALUATED, Optional.of(AUTHORITY),
                    Optional.of(REASON_ACTOR_GROUP_SET_STALE), Optional.empty());
        }

        static Decision deniedLocalUnbound() {
            return new Decision(AuthzOutcome.DENIED, Optional.of(LOCAL_AUTHORITY),
                    Optional.of(REASON_ACTOR_NOT_IN_REQUIRED_GROUP), Optional.empty());
        }
    }

    private static Decision directoryNotEvaluable() {
        return new Decision(AuthzOutcome.AUTHZ_NOT_EVALUATED, Optional.of(AUTHORITY),
                Optional.of(REASON_DIRECTORY_BINDING_NOT_EVALUABLE), Optional.empty());
    }

    public Decision evaluate(String actorFingerprint, Optional<RoleToken> requiredToken, Instant now) {
        Optional<String> localIdentityId = localIdentityResolver == null ? Optional.empty()
                : localIdentityResolver.resolve(actorFingerprint).map(record -> record.localIdentityId());
        Optional<ActorAuthzStateRecord> authzState = actorAuthzStateRepository.find(actorFingerprint);
        if (localIdentityId.isPresent() && authzState.filter(state -> state.directoryProfileId() != null).isPresent()) {
            return directoryNotEvaluable();
        }
        if (localIdentityId.isPresent() && rootIdentityRepository != null
                && rootIdentityRepository.rootLocalIdentityId().filter(localIdentityId.get()::equals).isPresent()) {
            return Decision.permittedRoot();
        }
        if (requiredToken.isEmpty()) {
            // NO_APPLICABLE_AUTHORITY: open to any authenticated session.
            return Decision.noApplicableAuthority();
        }
        if (localIdentityId.isPresent() && localRoleTokenResolver != null) {
            return localRoleTokenResolver.resolveBinding(localIdentityId.get(), requiredToken.get())
                    .map(binding -> Decision.permittedLocal(binding.bindingId()))
                    .orElseGet(Decision::deniedLocalUnbound);
        }
        String token = requiredToken.get().token();

        List<RoleBindingRecord> activeBindings = roleBindingRepository.findActiveByToken(token);
        if (activeBindings.isEmpty()) {
            // role_token_unbound: zero active bindings for this token at
            // all -- AUTHZ_NOT_EVALUATED, never silently downgraded to
            // NO_APPLICABLE_AUTHORITY (LD-2/AG-4).
            return Decision.notEvaluatedUnbound();
        }

        if (authzState.isEmpty() || !authzState.get().isFresh(now)) {
            // actor_group_set_stale: no fresh resolved group set to
            // evaluate against -- AUTHZ_NOT_EVALUATED, never DENIED, never
            // a stale PERMITTED (AG-11).
            return Decision.notEvaluatedStale();
        }

        ActorAuthzStateRecord state = authzState.get();
        if (!state.hasDirectoryProof()) return directoryNotEvaluable();
        String principal;
        try {
            principal = groupReferenceCipher.decryptDirectory(state.principalReferenceEncrypted(), state.directoryProfileId(),
                    DirectoryBindingKind.DIRECTORY_PRINCIPAL, state.principalReferenceKeyId());
            if (principal.isBlank()) return directoryNotEvaluable();
        } catch (RuntimeException e) { return directoryNotEvaluable(); }
        boolean unevaluable = false;
        String match = null;
        for (RoleBindingRecord binding : activeBindings) {
            if (!binding.isActive()) continue;
            if (binding.bindingKind() == null || binding.bindingKind() == DirectoryBindingKind.LEGACY
                    || !state.directoryProfileId().equals(binding.directoryProfileId())) {
                unevaluable = true;
                continue;
            }
            try {
                String reference = groupReferenceCipher.decryptDirectory(binding.groupReferenceEncrypted(), binding.directoryProfileId(),
                        binding.bindingKind(), binding.groupReferenceKeyId());
                boolean matches = binding.bindingKind() == DirectoryBindingKind.DIRECTORY_GROUP
                        ? state.groupReferences().contains(reference) : principal.equals(reference);
                if (reference.isBlank()) { unevaluable = true; continue; }
                if (matches && (match == null || binding.bindingId().compareTo(match) < 0)) match = binding.bindingId();
            } catch (RuntimeException e) { unevaluable = true; }
        }
        if (match != null) return Decision.permitted(match);
        if (unevaluable) return directoryNotEvaluable();
        return new Decision(AuthzOutcome.DENIED, Optional.of(AUTHORITY),
                Optional.of(REASON_ACTOR_NOT_IN_REQUIRED_BINDING), Optional.empty());
    }
}
