package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;

/**
 * Refreshes a signed-in directory actor's group set through the service account (design
 * LDAP_SERVICE_BIND_DESIGN_2026_11_11 §6). Called by {@link RbacEvaluator} when the set is stale.
 *
 * <p>The directory round trip runs outside the table-locking directory mutation; the mutation then re-checks
 * that the actor's proof and session are unchanged before it publishes or revokes. One refresh per actor at a
 * time; concurrent requests of the same actor wait for it instead of querying the directory again.</p>
 */
public final class DirectoryAuthorizationRefresh {
    private final LdapRevalidationPort directory;
    private final RoleBindingRepository bindings;
    private final GroupReferenceCipher cipher;
    private final ActorAuthzStateRepository actors;
    private final SessionRepository sessions;
    private final ConcurrentHashMap<String, Object> inFlight = new ConcurrentHashMap<>();
    /** A failed refresh is not retried for this long, so an unreachable directory is not asked once per request. */
    static final java.time.Duration RETRY_AFTER_FAILURE = java.time.Duration.ofSeconds(15);
    private final ConcurrentHashMap<String, Instant> failedAt = new ConcurrentHashMap<>();

    public DirectoryAuthorizationRefresh(LdapRevalidationPort directory, RoleBindingRepository bindings,
            GroupReferenceCipher cipher, ActorAuthzStateRepository actors, SessionRepository sessions) {
        this.directory = directory;
        this.bindings = bindings;
        this.cipher = cipher;
        this.actors = actors;
        this.sessions = sessions;
    }

    /** True when a fresh group set was published for {@code actor}. Always asks the directory. */
    public boolean refresh(String actor, Instant now) {
        return refresh(actor, now, false);
    }

    /** The evaluator's entry: a set another request refreshed meanwhile is used as is. */
    public boolean refreshStale(String actor, Instant now) {
        return refresh(actor, now, true);
    }

    private boolean refresh(String actor, Instant now, boolean onlyIfStale) {
        Object lock = inFlight.computeIfAbsent(actor, key -> new Object());
        try {
            synchronized (lock) {
                Instant failed = failedAt.get(actor);
                if (failed != null && Instant.now().isBefore(failed.plus(RETRY_AFTER_FAILURE))) return false;
                Outcome outcome = refreshLocked(actor, now, onlyIfStale);
                if (outcome == Outcome.DIRECTORY_FAILED) failedAt.put(actor, Instant.now()); else failedAt.remove(actor);
                if (failedAt.size() > 10_000) failedAt.clear();
                return outcome == Outcome.REFRESHED;
            }
        } finally {
            inFlight.remove(actor, lock);
        }
    }

    private enum Outcome { REFRESHED, NOT_APPLICABLE, DIRECTORY_FAILED }

    private Outcome refreshLocked(String actor, Instant now, boolean onlyIfStale) {
        long started = System.nanoTime();
        try {
            if (!directory.directoryPostureEnabled()) return Outcome.NOT_APPLICABLE;
            Optional<ActorAuthzStateRecord> state = actors.find(actor).filter(ActorAuthzStateRecord::hasDirectoryProof);
            if (state.isEmpty()) return Outcome.NOT_APPLICABLE;
            // Another request of this actor refreshed while this one waited.
            if (onlyIfStale && state.get().isFresh(now)) return Outcome.REFRESHED;
            var session = sessions.findActiveByActor(actor, now);
            if (session.isEmpty()) return Outcome.NOT_APPLICABLE;
            String principal = cipher.decryptDirectory(state.get().principalReferenceEncrypted(),
                    state.get().directoryProfileId(), DirectoryBindingKind.DIRECTORY_PRINCIPAL,
                    state.get().principalReferenceKeyId());
            Result<DirectoryObservation> result = directory.revalidatePrincipal(principal);
            Boolean published = bindings.directoryMutation(tx -> {
                Optional<ActorAuthzStateRecord> current = tx.actors().find(actor).filter(ActorAuthzStateRecord::hasDirectoryProof);
                Instant at = now.plusNanos(System.nanoTime() - started);
                // The adapter stamps the observation with the wall clock after the directory round trip, while the
                // caller's now was taken when its request began (gate chain, session and binding reads, a wait for
                // another refresh of this actor). Measured from now alone, a refresh that started late found its own
                // observation "from the future" and published nothing, so a signed-in administrator was refused as
                // actor_group_set_stale once the five-minute set expired (ldap admin roles 2026-11-13). An observation
                // is from the future only when it is later than both clocks.
                Instant wall = Instant.now();
                Instant latest = wall.isAfter(at) ? wall : at;
                var active = tx.sessions().findActiveByActor(actor).filter(s -> s.isActive(at));
                if (current.isEmpty() || active.isEmpty() || !active.get().sessionId().equals(session.get().sessionId())
                        || !current.get().directoryProfileId().equals(state.get().directoryProfileId())
                        || !Arrays.equals(current.get().principalReferenceEncrypted(), state.get().principalReferenceEncrypted())
                        || new LocalIdentityResolver(tx.locals()).resolve(actor).isPresent()) {
                    return false;
                }
                if (result instanceof Result.Err<DirectoryObservation> error) {
                    if ("access_group_lost".equals(error.code())) {
                        tx.sessions().revokeAccessGroupLost(active.get().sessionId(), "session_access_group_lost");
                        tx.actors().delete(actor);
                    }
                    return false;
                }
                DirectoryObservation observation = ((Result.Ok<DirectoryObservation>) result).value();
                if (!current.get().directoryProfileId().equals(observation.profileId())
                        || !principal.equals(observation.principalReference())
                        || latest.isBefore(observation.resolvedAt()) || observation.resolvedAt().isBefore(current.get().resolvedAt())
                        || !at.isBefore(observation.validUntil())) {
                    return false;
                }
                return observation.publication().ifCurrent(() -> tx.actors().upsertDirectory(new ActorAuthzStateRecord(actor,
                        observation.groupReferences(), observation.resolvedAt(), observation.validUntil(), observation.profileId(),
                        cipher.encryptDirectory(principal, observation.profileId(), DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                        cipher.keyId(), current.get().directoryDisplayName())));
            });
            if (Boolean.TRUE.equals(published)) return Outcome.REFRESHED;
            return result instanceof Result.Err<DirectoryObservation> err && "directory_unavailable".equals(err.code())
                    ? Outcome.DIRECTORY_FAILED : Outcome.NOT_APPLICABLE;
        } catch (RuntimeException e) {
            return Outcome.DIRECTORY_FAILED;
        }
    }
}
