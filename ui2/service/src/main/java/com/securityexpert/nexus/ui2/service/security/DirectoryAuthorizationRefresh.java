package com.securityexpert.nexus.ui2.service.security;

import java.time.Instant;
import java.util.Optional;
import com.securityexpert.nexus.ui2.platform.*;
import com.securityexpert.nexus.ui2.persistence.identity.*;

/** Existing service-account port refreshes only an independently authenticated session's own proof.
 * No scheduler or corporate activation; disabled state performs zero cache/secret/directory reads.
 */
public final class DirectoryAuthorizationRefresh {
    private final LdapRevalidationPort directory;
    private final RoleBindingRepository bindings;
    private final GroupReferenceCipher cipher;

    public DirectoryAuthorizationRefresh(LdapRevalidationPort directory, RoleBindingRepository bindings, GroupReferenceCipher cipher) {
        this.directory = directory;
        this.bindings = bindings;
        this.cipher = cipher;
    }

    public boolean refresh(String actor, Instant now) {
        if (!directory.directoryPostureEnabled()) return false;
        long started = System.nanoTime();
        try {
            return bindings.directoryMutation(tx -> {
                Optional<ActorAuthzStateRecord> state = tx.actors().find(actor).filter(ActorAuthzStateRecord::hasDirectoryProof);
                var session = tx.sessions().findActiveByActor(actor).filter(s -> s.isActive(now));
                if (state.isEmpty() || session.isEmpty() || new LocalIdentityResolver(tx.locals()).resolve(actor).isPresent()) return false;
                String principal = cipher.decryptDirectory(state.get().principalReferenceEncrypted(), state.get().directoryProfileId(),
                        DirectoryBindingKind.DIRECTORY_PRINCIPAL, state.get().principalReferenceKeyId());
                Result<DirectoryObservation> result = directory.revalidatePrincipal(principal);
                if (result instanceof Result.Err<DirectoryObservation> error) {
                    if ("access_group_lost".equals(error.code())) {
                        tx.sessions().revokeAccessGroupLost(session.get().sessionId(), "session_access_group_lost");
                        tx.actors().delete(actor);
                    }
                    return false;
                }
                Instant at = now.plusNanos(System.nanoTime() - started);
                if (!session.get().isActive(at)) return false;
                DirectoryObservation observation = ((Result.Ok<DirectoryObservation>) result).value();
                if (!state.get().directoryProfileId().equals(observation.profileId()) || !principal.equals(observation.principalReference())
                        || at.isBefore(observation.resolvedAt()) || observation.resolvedAt().isBefore(state.get().resolvedAt()) || !at.isBefore(observation.validUntil())) return false;
                return observation.publication().ifCurrent(() -> tx.actors().upsertDirectory(new ActorAuthzStateRecord(actor,
                        observation.groupReferences(), observation.resolvedAt(), observation.validUntil(), observation.profileId(),
                        cipher.encryptDirectory(principal, observation.profileId(), DirectoryBindingKind.DIRECTORY_PRINCIPAL), cipher.keyId())));
            });
        } catch (RuntimeException e) { return false; }
    }
}
