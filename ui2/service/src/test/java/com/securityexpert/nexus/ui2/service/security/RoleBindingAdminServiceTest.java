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

import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;

/**
 * Contract §8 test 10 ({@code SelfGrantRefused}) -- the decision-logic half
 * that is provable without a directory: given an already-resolved
 * {@code actor_authz_state} group set, an admin binding a token to a group
 * they already belong to is refused and no row is created.
 */
class RoleBindingAdminServiceTest {

    private static GroupReferenceCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return GroupReferenceCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static final class FakeRoleBindingRepository implements RoleBindingRepository {
        final List<RoleBindingRecord> bindings = new ArrayList<>();
        int createCalls = 0;

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
            createCalls++;
            bindings.add(new RoleBindingRecord(bindingId, roleToken, groupReferenceEncrypted, groupReferenceKeyId,
                    createdByActorFingerprint, Instant.now(), Optional.empty(), Optional.empty()));
            return bindingId;
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            bindings.removeIf(b -> b.bindingId().equals(bindingId));
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        private final Map<String, ActorAuthzStateRecord> rows = new HashMap<>();

        void put(String actorFingerprint, Set<String> groupReferences, Instant validUntil) {
            rows.put(actorFingerprint,
                    new ActorAuthzStateRecord(actorFingerprint, groupReferences, Instant.now(), validUntil));
        }

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            return Optional.ofNullable(rows.get(actorFingerprint));
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt,
                Instant validUntil) {
            put(actorFingerprint, groupReferences, validUntil);
        }

        @Override
        public void delete(String actorFingerprint) {
            rows.remove(actorFingerprint);
        }
    }

    @Test
    void selfGrantIsRefusedAndNoRowIsCreated() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        String adminActor = "admin1";
        String group = "cn=security-admins,dc=example,dc=com";
        authzState.put(adminActor, Set.of(group), now.plus(15, ChronoUnit.MINUTES));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher());

        RoleBindingAdminService.Outcome outcome = service.create(adminActor, RoleToken.SECURITY_ADMIN.token(),
                group, "key-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.SelfGrantRefused);
        assertEquals(0, bindings.createCalls, "no role_bindings row must be created on self-grant refusal");
    }

    @Test
    void bindingADifferentGroupTheAdminIsNotAMemberOfSucceeds() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        Instant now = Instant.now();
        String adminActor = "admin1";
        authzState.put(adminActor, Set.of("cn=security-admins,dc=example,dc=com"), now.plus(15, ChronoUnit.MINUTES));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher());

        RoleBindingAdminService.Outcome outcome = service.create(adminActor, RoleToken.BACKUP_ADMIN.token(),
                "cn=backup-admins,dc=example,dc=com", "key-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.Created);
        assertEquals(1, bindings.createCalls);
    }
}
