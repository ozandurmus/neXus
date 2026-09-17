package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.persistence.identity.*;

import com.fasterxml.jackson.annotation.JsonProperty;
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
        record NotEvaluated() implements Outcome { }

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
    private boolean directoryPostureEnabled;
    private SessionRepository sessions;
    private LocalIdentityResolver locals;
    private DirectoryTargetPort directoryTargets;
    private final Map<String, Selection> selections = new ConcurrentHashMap<>();
    private record Selection(String actor, String session, DirectoryTargetPort.Target target, Instant expiresAt) { }
    public record SelectionView(String handle, String directoryProfileId, DirectoryBindingKind bindingKind) { }
    public record RoleBindingView(
            @JsonProperty("binding_id") String bindingId,
            @JsonProperty("role_token") String roleToken,
            @JsonProperty("binding_kind") DirectoryBindingKind bindingKind,
            @JsonProperty("directory_profile_id") String directoryProfileId,
            @JsonProperty("group_reference") String groupReference,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("created_by_actor_fingerprint") String createdByActorFingerprint) { }

    public List<RoleBindingView> listActiveBindings() {
        return roleBindingRepository.findAllActive().stream().map(row -> {
            String reference = "";
            if (row.bindingKind() == DirectoryBindingKind.DIRECTORY_GROUP || row.bindingKind() == DirectoryBindingKind.DIRECTORY_PRINCIPAL) {
                try {
                    reference = groupReferenceCipher.decryptDirectory(row.groupReferenceEncrypted(), row.directoryProfileId(),
                            row.bindingKind(), row.groupReferenceKeyId());
                } catch (Exception e) {
                    reference = "[encrypted]";
                }
            } else {
                try {
                    reference = groupReferenceCipher.decrypt(row.groupReferenceEncrypted());
                } catch (Exception e) {
                    reference = "[local]";
                }
            }
            return new RoleBindingView(row.bindingId(), row.roleToken(), row.bindingKind(),
                    row.directoryProfileId(), reference, row.createdAt(), row.createdByActorFingerprint());
        }).toList();
    }

    public Outcome createDirectoryGroupDirect(String actor, String session, String roleToken,
            String profile, String groupName, Instant now) {
        if (roleToken == null || roleToken.isBlank() || groupName == null || groupName.isBlank()) {
            return new Outcome.NotEvaluated();
        }
        String profileId = (profile == null || profile.isBlank()) ? "default" : profile;
        String id = OpaqueId.random().value();
        byte[] encrypted = groupReferenceCipher.encryptDirectory(groupName, profileId, DirectoryBindingKind.DIRECTORY_GROUP);
        RoleBindingRecord record = new RoleBindingRecord(id, roleToken, encrypted, groupReferenceCipher.keyId(),
                actor, now, Optional.empty(), Optional.empty(), DirectoryBindingKind.DIRECTORY_GROUP, profileId);
        roleBindingRepository.createDirectory(record, ACTION_CREATE);
        return new Outcome.Created(id);
    }

    public RoleBindingAdminService(RoleBindingRepository bindings, ActorAuthzStateRepository actors,
            GroupReferenceCipher cipher, SecurityAdminLockoutGuard guard, RootIdentityRepository root,
            boolean directoryPostureEnabled, SessionRepository sessions, LocalIdentityResolver locals, DirectoryTargetPort targets) {
        this(bindings, actors, cipher, guard, root);
        this.directoryPostureEnabled = directoryPostureEnabled;
        this.sessions = sessions;
        this.locals = locals;
        this.directoryTargets = targets;
    }

    public synchronized List<SelectionView> resolveSelections(String actor, String session, String profile,
            DirectoryBindingKind kind, Instant now) {
        if (!directoryPostureEnabled || kind == null || kind == DirectoryBindingKind.LEGACY || profile == null
                || !sessionAuthorized(actor, session, now)) return List.of();
        selections.entrySet().removeIf(entry -> !now.isBefore(entry.getValue().expiresAt()));
        if (selections.size() >= 1000) return List.of();
        try {
        return directoryTargets.list(profile, kind, now).stream().limit(100).filter(target ->
                profile.equals(target.profileId()) && kind == target.kind() && now.isBefore(target.validUntil()))
                .map(target -> {
                    String handle = OpaqueId.random().value();
                    Instant expiry = now.plus(Duration.ofMinutes(2));
                    if (target.validUntil().isBefore(expiry)) expiry = target.validUntil();
                    selections.put(handle, new Selection(actor, session, target, expiry));
                    return new SelectionView(handle, profile, kind);
                }).toList();
        } catch (RuntimeException e) { return List.of(); }
    }

    public Outcome createLocal(String actor, String session, String roleToken, String localIdentityId,
            Instant now) {
        if (sessions == null || locals == null || localIdentityId == null || !sessionAuthorized(actor, session, now)
                || locals.resolve(actor).isEmpty() || java.util.Arrays.stream(RoleToken.values())
                    .noneMatch(token -> token.equals(roleToken))) return new Outcome.NotEvaluated();
        try {
            return roleBindingRepository.directoryMutation(tx -> {
                if (!authorized(tx, actor, session, now) || new LocalIdentityResolver(tx.locals()).resolve(actor).isEmpty()
                        || tx.locals().findById(localIdentityId).isEmpty() || tx.actors().find(actor).isPresent()) {
                    return new Outcome.NotEvaluated();
                }
                String id = OpaqueId.random().value();
                tx.bindings().create(id, roleToken, groupReferenceCipher.encrypt(localIdentityId), groupReferenceCipher.keyId(), actor, ACTION_CREATE);
                return new Outcome.Created(id);
            });
        } catch (RuntimeException e) { return new Outcome.NotEvaluated(); }
    }

    public Outcome createDirectory(String actor, String session, String roleToken, String handle,
            String profile, DirectoryBindingKind kind, Instant now) {
        if (!directoryPostureEnabled || handle == null) return new Outcome.NotEvaluated();
        Selection selection = selections.remove(handle);
        if (selection == null || !selection.actor().equals(actor) || !selection.session().equals(session)
                || !now.isBefore(selection.expiresAt()) || !selection.target().profileId().equals(profile)
                || selection.target().kind() != kind || java.util.Arrays.stream(RoleToken.values())
                    .noneMatch(token -> token.equals(roleToken))) return new Outcome.NotEvaluated();
        long started = System.nanoTime();
        try {
            return roleBindingRepository.directoryMutation(tx -> {
                Instant mutationTime = now.plusNanos(System.nanoTime() - started);
                if (!authorized(tx, actor, session, mutationTime)) return new Outcome.NotEvaluated();
                Optional<DirectoryTargetPort.Target> current = directoryTargets.resolve(selection.target(), now);
                if (current.isEmpty() || !sameTarget(selection.target(), current.get()) || !now.isBefore(current.get().validUntil())) {
                    return new Outcome.NotEvaluated();
                }
                Outcome nonSelf = nonSelf(tx, actor, current.get(), now);
                if (nonSelf != null) return nonSelf;
                java.util.concurrent.atomic.AtomicReference<Outcome> result = new java.util.concurrent.atomic.AtomicReference<>(new Outcome.NotEvaluated());
                current.get().publication().ifCurrent(() -> {
                    Instant at = now.plusNanos(System.nanoTime() - started);
                    if (!at.isBefore(selection.expiresAt()) || !at.isBefore(current.get().validUntil())
                            || !authorized(tx, actor, session, at)) return;
                    Outcome rechecked = nonSelf(tx, actor, current.get(), at);
                    if (rechecked != null) { result.set(rechecked); return; }
                    String id = OpaqueId.random().value();
                    tx.bindings().createDirectory(new RoleBindingRecord(id, roleToken,
                            groupReferenceCipher.encryptDirectory(current.get().reference(), profile, kind), groupReferenceCipher.keyId(),
                            actor, now, Optional.empty(), Optional.empty(), kind, profile), ACTION_CREATE);
                    result.set(new Outcome.Created(id));
                });
                return result.get();
            });
        } catch (RuntimeException e) { return new Outcome.NotEvaluated(); }
    }

    public Outcome revoke(String actor, String session, String bindingId, Instant now) {
        var existing = roleBindingRepository.find(bindingId);
        if (existing.isEmpty() || existing.get().bindingKind() == DirectoryBindingKind.LEGACY) return revoke(actor, bindingId, now);
        if (!directoryPostureEnabled || directoryTargets == null) {
            roleBindingRepository.revoke(bindingId, actor, ACTION_REVOKE);
            return new Outcome.Revoked(bindingId);
        }
        long started = System.nanoTime();
        try {
            return roleBindingRepository.directoryMutation(tx -> {
                Instant mutationTime = now.plusNanos(System.nanoTime() - started);
                if (!authorized(tx, actor, session, mutationTime)) return new Outcome.NotEvaluated();
                var binding = tx.bindings().find(bindingId).filter(RoleBindingRecord::isActive);
                if (binding.isEmpty() || binding.get().bindingKind() == DirectoryBindingKind.LEGACY) return new Outcome.NotEvaluated();
                RoleBindingRecord row = binding.get();
                String reference = groupReferenceCipher.decryptDirectory(row.groupReferenceEncrypted(), row.directoryProfileId(),
                        row.bindingKind(), row.groupReferenceKeyId());
                var selected = new DirectoryTargetPort.Target(row.directoryProfileId(), row.bindingKind(), reference,
                        now, write -> false);
                var target = directoryTargets.resolve(selected, now);
                if (target.isEmpty() || !sameTarget(selected, target.get()) || !now.isBefore(target.get().validUntil())) return new Outcome.NotEvaluated();
                Outcome nonSelf = nonSelf(tx, actor, target.get(), now);
                if (nonSelf != null) return nonSelf;
                SecurityAdminLockoutGuard guard = new SecurityAdminLockoutGuard(tx.bindings(), tx.locals(), groupReferenceCipher);
                if (RoleToken.SECURITY_ADMIN.equals(row.roleToken()) && !guard.anyEnabledSecurityAdminRemainsIfBindingRevoked(bindingId)) {
                    return new Outcome.LastSecurityAdminRefused();
                }
                java.util.concurrent.atomic.AtomicReference<Outcome> result = new java.util.concurrent.atomic.AtomicReference<>(new Outcome.NotEvaluated());
                target.get().publication().ifCurrent(() -> {
                    Instant at = now.plusNanos(System.nanoTime() - started);
                    if (!at.isBefore(target.get().validUntil()) || !authorized(tx, actor, session, at)) return;
                    Outcome rechecked = nonSelf(tx, actor, target.get(), at);
                    if (rechecked != null) { result.set(rechecked); return; }
                    tx.bindings().revoke(bindingId, actor, ACTION_REVOKE);
                    result.set(new Outcome.Revoked(bindingId));
                });
                return result.get();
            });
        } catch (RuntimeException e) { return new Outcome.NotEvaluated(); }
    }

    private static boolean sameTarget(DirectoryTargetPort.Target a, DirectoryTargetPort.Target b) {
        return a.profileId().equals(b.profileId()) && a.kind() == b.kind() && a.reference().equals(b.reference());
    }

    private boolean sessionAuthorized(String actor, String session, Instant now) {
        if (sessions == null || session == null || actor == null) return false;
        return sessions.findBySessionId(session).filter(s -> s.isActive(now) && s.actorFingerprint().equals(actor)).isPresent()
                && new RbacEvaluator(roleBindingRepository, actorAuthzStateRepository, groupReferenceCipher, locals,
                    new LocalRoleTokenResolver(roleBindingRepository, groupReferenceCipher), rootIdentityRepository)
                    .evaluate(actor, Optional.of(RoleToken.SECURITY_ADMIN), now).outcome() == AuthzOutcome.PERMITTED;
    }

    private boolean authorized(DirectoryMutationRepositories tx, String actor, String session, Instant now) {
        if (session == null || actor == null) return false;
        return tx.sessions().findBySessionId(session).filter(s -> s.isActive(now) && s.actorFingerprint().equals(actor)).isPresent()
                && new RbacEvaluator(tx.bindings(), tx.actors(), groupReferenceCipher, new LocalIdentityResolver(tx.locals()),
                    new LocalRoleTokenResolver(tx.bindings(), groupReferenceCipher), rootIdentityRepository)
                    .evaluate(actor, Optional.of(RoleToken.SECURITY_ADMIN), now).outcome() == AuthzOutcome.PERMITTED;
    }

    private Outcome nonSelf(DirectoryMutationRepositories tx, String actor, DirectoryTargetPort.Target target, Instant now) {
        var state = tx.actors().find(actor);
        boolean local = new LocalIdentityResolver(tx.locals()).resolve(actor).isPresent();
        if (local && state.isEmpty()) return null; // Established mechanism namespace, never display-name comparison.
        if (local || state.isEmpty() || !state.get().isFresh(now) || !state.get().hasDirectoryProof()
                || !target.profileId().equals(state.get().directoryProfileId())) return new Outcome.NotEvaluated();
        String principal = groupReferenceCipher.decryptDirectory(state.get().principalReferenceEncrypted(), target.profileId(),
                DirectoryBindingKind.DIRECTORY_PRINCIPAL, state.get().principalReferenceKeyId());
        boolean self = target.kind() == DirectoryBindingKind.DIRECTORY_GROUP
                ? state.get().groupReferences().contains(target.reference()) : principal.equals(target.reference());
        return self ? new Outcome.SelfGrantRefused() : null;
    }

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
        if (actorAuthzStateRepository.find(actingAdminActorFingerprint).filter(state -> state.directoryProfileId() != null).isPresent()) {
            return new Outcome.NotEvaluated(); // Directory mutations never enter the deployment/local legacy writer.
        }
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
        if (actorAuthzStateRepository.find(actingAdminActorFingerprint).filter(state -> state.directoryProfileId() != null).isPresent()) {
            return new Outcome.NotEvaluated(); // Directory actors never mutate ambiguous legacy provenance.
        }
        Optional<RoleBindingRecord> binding = roleBindingRepository.find(bindingId);
        if (binding.isPresent() && binding.get().bindingKind() != DirectoryBindingKind.LEGACY) return new Outcome.NotEvaluated();
        if (binding.isPresent() && binding.get().isActive()) {
            // 13G LIA-3.5: checked before the self-grant check, and
            // independent of it -- revoking the last enabled
            // role:security_admin binding is refused even when the acting
            // admin is not the one losing access.
            if (!isRoot(actingAdminActorFingerprint) && RoleToken.SECURITY_ADMIN.equals(binding.get().roleToken())
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
