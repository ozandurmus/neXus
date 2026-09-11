package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;

/**
 * Container-free, directory-free unit tests for {@code E4}'s four-outcome
 * evaluation (C3 §5.1). Contract §8 tests 6/7/8 in spirit — the parts of
 * those scenarios that are pure decision logic against already-resolved
 * group sets, without a live PostgreSQL instance or a test LDAP directory.
 * The full end-to-end scenarios (real bind, real database) are the
 * container/directory-dependent tests named elsewhere, per this class's
 * own SESSION_CLOSE reporting.
 */
class RbacEvaluatorTest {

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static final class FakeRoleBindingRepository implements RoleBindingRepository {
        private final List<RoleBindingRecord> bindings = new ArrayList<>();

        void addActive(String bindingId, String roleToken, byte[] encryptedGroupReference) {
            bindings.add(new RoleBindingRecord(bindingId, roleToken, encryptedGroupReference, "k1",
                    "creator", Instant.now(), Optional.empty(), Optional.empty()));
        }

        @Override
        public List<RoleBindingRecord> findActiveByToken(String roleToken) {
            return bindings.stream().filter(b -> b.roleToken().equals(roleToken) && b.isActive()).toList();
        }

        @Override
        public Optional<RoleBindingRecord> find(String bindingId) {
            return bindings.stream().filter(b -> b.bindingId().equals(bindingId)).findFirst();
        }

        @Override
        public boolean hasAnyActiveBinding(String roleToken) {
            return !findActiveByToken(roleToken).isEmpty();
        }

        @Override
        public String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
                String groupReferenceKeyId, String createdByActorFingerprint, String actionId) {
            addActive(bindingId, roleToken, groupReferenceEncrypted);
            return bindingId;
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            bindings.removeIf(b -> b.bindingId().equals(bindingId));
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        private final Map<String, ActorAuthzStateRecord> rows = new HashMap<>();

        void put(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
            rows.put(actorFingerprint, new ActorAuthzStateRecord(actorFingerprint, groupReferences, resolvedAt, validUntil));
        }

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            return Optional.ofNullable(rows.get(actorFingerprint));
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt, Instant validUntil) {
            put(actorFingerprint, groupReferences, resolvedAt, validUntil);
        }

        @Override
        public void delete(String actorFingerprint) {
            rows.remove(actorFingerprint);
        }
    }

    @Test
    void noRequiredTokenIsNoApplicableAuthority() {
        RbacEvaluator evaluator = new RbacEvaluator(new FakeRoleBindingRepository(),
                new FakeActorAuthzStateRepository(), cipher());

        var decision = evaluator.evaluate("actor1", Optional.empty(), Instant.now());

        assertEquals(AuthzOutcome.NO_APPLICABLE_AUTHORITY, decision.outcome());
    }

    @Test
    void unboundTokenIsAuthzNotEvaluatedWithRoleTokenUnbound() {
        RbacEvaluator evaluator = new RbacEvaluator(new FakeRoleBindingRepository(),
                new FakeActorAuthzStateRepository(), cipher());

        var decision = evaluator.evaluate("actor1", Optional.of(RoleToken.BACKUP_ADMIN), Instant.now());

        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, decision.outcome());
        assertEquals(RbacEvaluator.REASON_ROLE_TOKEN_UNBOUND, decision.reasonCode().orElseThrow());
    }

    @Test
    void staleActorAuthzStateIsAuthzNotEvaluatedNeverDenied() {
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.BACKUP_ADMIN.token(), cipher.encrypt("cn=backup-admins,dc=example,dc=com"));

        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        // valid_until in the past: stale.
        authzState.put("actor1", Set.of("cn=backup-admins,dc=example,dc=com"), now.minus(20, ChronoUnit.MINUTES),
                now.minus(5, ChronoUnit.MINUTES));

        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        var decision = evaluator.evaluate("actor1", Optional.of(RoleToken.BACKUP_ADMIN), now);

        assertEquals(AuthzOutcome.AUTHZ_NOT_EVALUATED, decision.outcome());
        assertEquals(RbacEvaluator.REASON_ACTOR_GROUP_SET_STALE, decision.reasonCode().orElseThrow());
    }

    @Test
    void boundButNotAMemberIsDeniedWithActorNotInRequiredGroup() {
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.BACKUP_ADMIN.token(), cipher.encrypt("cn=backup-admins,dc=example,dc=com"));

        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        authzState.put("actor1", Set.of("cn=some-other-group,dc=example,dc=com"), now, now.plus(15, ChronoUnit.MINUTES));

        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        var decision = evaluator.evaluate("actor1", Optional.of(RoleToken.BACKUP_ADMIN), now);

        assertEquals(AuthzOutcome.DENIED, decision.outcome());
        assertEquals(RbacEvaluator.REASON_ACTOR_NOT_IN_REQUIRED_GROUP, decision.reasonCode().orElseThrow());
    }

    @Test
    void boundAndAMemberIsPermittedWithTheMatchingBindingId() {
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.BACKUP_ADMIN.token(), cipher.encrypt("cn=backup-admins,dc=example,dc=com"));

        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        authzState.put("actor1", Set.of("cn=backup-admins,dc=example,dc=com"), now, now.plus(15, ChronoUnit.MINUTES));

        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);
        var decision = evaluator.evaluate("actor1", Optional.of(RoleToken.BACKUP_ADMIN), now);

        assertEquals(AuthzOutcome.PERMITTED, decision.outcome());
        assertEquals("b1", decision.bindingId().orElseThrow());
        assertTrue(decision.outcome().proceeds());
    }

    @Test
    void unmappedIdentityNeverReceivesPermittedForAnyRoleGatedAction() {
        // Contract §8 test 7 (directory-dependent variant lives at the
        // adapter/integration layer): here, an identity with a resolved
        // group set that matches no role_bindings row for any token in
        // play must never evaluate PERMITTED for that token.
        GroupReferenceCipher cipher = cipher();
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        bindings.addActive("b1", RoleToken.BACKUP_ADMIN.token(), cipher.encrypt("cn=backup-admins,dc=example,dc=com"));
        bindings.addActive("b2", RoleToken.COMPLIANCE_ADMIN.token(), cipher.encrypt("cn=compliance,dc=example,dc=com"));

        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        authzState.put("unmapped-actor", Set.of("cn=unrelated,dc=example,dc=com"), now, now.plus(15, ChronoUnit.MINUTES));

        RbacEvaluator evaluator = new RbacEvaluator(bindings, authzState, cipher);

        for (RoleToken token : List.of(RoleToken.BACKUP_ADMIN, RoleToken.COMPLIANCE_ADMIN)) {
            var decision = evaluator.evaluate("unmapped-actor", Optional.of(token), now);
            assertEquals(AuthzOutcome.DENIED, decision.outcome(),
                    "an unmapped identity must never evaluate PERMITTED for " + token);
        }
    }
}
