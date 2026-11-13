package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRecord;
import com.securityexpert.nexus.ui2.persistence.identity.SessionState;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.DirectoryBindingKind;
import com.securityexpert.nexus.ui2.platform.DirectoryObservation;
import com.securityexpert.nexus.ui2.platform.LdapRevalidationPort;
import com.securityexpert.nexus.ui2.platform.Result;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * The on-demand refresh of a stale directory group set (design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §6) on the wall
 * clock the directory observation is stamped with. The caller's {@code now} is taken when the request starts (gate
 * chain, session read, binding reads); the observation is stamped after the directory round trip. A refresh must
 * publish its own fresh observation however long the request ran before the refresh began.
 */
class DirectoryAuthorizationRefreshTest {
    static final String PROFILE = DirectoryBindingsTest.PROFILE, PRINCIPAL = DirectoryBindingsTest.PRINCIPAL;
    static final String ACTOR = DirectoryBindingsTest.ACTOR, GROUP = "opaque-admin-group";

    /** A fixture whose session and stale group set sit on the wall clock, with a directory that still lists the actor. */
    static final class Live {
        final DirectoryBindingsTest.Fixture f = new DirectoryBindingsTest.Fixture();
        int directoryCalls;
        /** When set, the directory stamps its observation this far ahead of the wall clock. */
        Duration stampAhead;

        Live() {
            Instant wall = Instant.now();
            f.sessionRows.clear();
            f.sessionRows.put("acting-session", new SessionRecord("acting-session", ACTOR, "synthetic-csrf", SessionState.ACTIVE,
                    wall.minusSeconds(900), wall, wall.plusSeconds(900), wall.plusSeconds(3600),
                    Optional.empty(), Optional.empty(), Optional.empty()));
            // Signed in ten minutes ago: the five-minute group set is stale.
            f.states.put(ACTOR, new ActorAuthzStateRecord(ACTOR, Set.of(GROUP), wall.minusSeconds(600), wall.minusSeconds(300),
                    PROFILE, f.cipher.encryptDirectory(PRINCIPAL, PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL), f.cipher.keyId()));
            f.rows.clear();
            f.directory("admin-group-binding", RoleToken.SECURITY_ADMIN, DirectoryBindingKind.DIRECTORY_GROUP, GROUP, PROFILE);
        }

        LdapRevalidationPort directory() {
            return new LdapRevalidationPort() {
                @Override public boolean directoryPostureEnabled() { return true; }
                @Override public Result<Set<String>> revalidate(String actorFingerprint, String bindDn) {
                    return Result.err("directory_identity_not_proven", "typed directory observation required");
                }
                @Override public Result<DirectoryObservation> revalidatePrincipal(String principalReference) {
                    directoryCalls++;
                    if (stampAhead != null) {
                        Instant ahead = Instant.now().plus(stampAhead);
                        return Result.ok(new DirectoryObservation(PROFILE, principalReference, Set.of(GROUP), ahead,
                                ahead.plusSeconds(300), f::publish));
                    }
                    // Stamped with the wall clock after the round trip, exactly like UnboundIdRevalidationAdapter.
                    return Result.ok(new DirectoryObservation(PROFILE, principalReference, Set.of(GROUP), f::publish));
                }
            };
        }

        DirectoryAuthorizationRefresh refresh() {
            return new DirectoryAuthorizationRefresh(directory(), f.bindings, f.cipher, f.actors, f.sessions);
        }
    }

    @Test
    void aRequestThatStartedBeforeTheRefreshStillPublishesTheFreshGroupSet() {
        Live live = new Live();
        // The gate chain took its clock reading, then read the session, the action and the bindings, and waited for
        // another request's refresh of the same actor: the refresh starts well after the request's now.
        Instant requestStarted = Instant.now().minus(Duration.ofMillis(250));

        assertTrue(live.refresh().refreshStale(ACTOR, requestStarted), "the directory still lists the actor in the group");
        ActorAuthzStateRecord state = live.f.states.get(ACTOR);
        assertTrue(state.isFresh(Instant.now()), "the published group set is fresh");
        assertEquals(Set.of(GROUP), state.groupReferences());
        assertEquals(1, live.directoryCalls);
    }

    @Test
    void theEvaluatorPermitsAStaleAdministratorAfterTheRefresh() {
        Live live = new Live();
        RbacEvaluator evaluator = live.f.evaluator();
        evaluator.useDirectoryRefresh(live.refresh()::refreshStale);
        Instant requestStarted = Instant.now().minus(Duration.ofMillis(250));

        RbacEvaluator.Decision decision = evaluator.evaluate(ACTOR, Optional.of(RoleToken.SECURITY_ADMIN), requestStarted);

        assertEquals(AuthzOutcome.PERMITTED, decision.outcome(),
                "a member of the administrators group is not refused as actor_group_set_stale");
        assertEquals(Optional.of("admin-group-binding"), decision.bindingId());
    }

    @Test
    void anObservationOlderThanThePublishedSetIsStillRefused() {
        Live live = new Live();
        // A set published after this refresh began (another request) is newer than an observation stamped in the past.
        Instant wall = Instant.now();
        live.f.states.put(ACTOR, new ActorAuthzStateRecord(ACTOR, Set.of(GROUP), wall.plusSeconds(30), wall.minusSeconds(1),
                PROFILE, live.f.cipher.encryptDirectory(PRINCIPAL, PROFILE, DirectoryBindingKind.DIRECTORY_PRINCIPAL),
                live.f.cipher.keyId()));

        assertFalse(live.refresh().refreshStale(ACTOR, wall), "a newer published set is never replaced by an older observation");
        assertEquals(wall.plusSeconds(30), live.f.states.get(ACTOR).resolvedAt());
    }

    @Test
    void anObservationStampedAfterBothClocksIsStillRefused() {
        Live live = new Live();
        live.stampAhead = Duration.ofSeconds(60);

        assertFalse(live.refresh().refreshStale(ACTOR, Instant.now()), "a future-stamped observation never extends freshness");
        assertFalse(live.f.states.get(ACTOR).isFresh(Instant.now()));
    }

    @Test
    void aRetiredTrustGenerationPublishesNothing() {
        Live live = new Live();
        live.f.currentTrust.set(false);

        assertFalse(live.refresh().refreshStale(ACTOR, Instant.now()));
        assertFalse(live.f.states.get(ACTOR).isFresh(Instant.now()), "nothing proven under retired settings is published");
    }
}
