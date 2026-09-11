package com.securityexpert.nexus.ui2.persistence.identity;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * {@code actor_authz_state} persistence port. Never wired to
 * {@code fn_audit_capture()} (C3 §3.5) -- no audited transaction is used
 * here, by design; this cache carries no decision of its own.
 */
public interface ActorAuthzStateRepository {

    Optional<ActorAuthzStateRecord> find(String actorFingerprint);

    void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil);

    /** Called when an actor's last session ends (C3 §4.4: "deleted when its owning actor has no active session"). */
    void delete(String actorFingerprint);
}
