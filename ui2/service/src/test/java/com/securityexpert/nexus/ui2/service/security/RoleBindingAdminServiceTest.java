package com.securityexpert.nexus.ui2.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Duration;
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

import com.securityexpert.nexus.ui2.platform.Argon2PasswordHasher;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialRecord;
import com.securityexpert.nexus.ui2.persistence.identity.LocalCredentialsRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SecurityAdminLockoutGuard;

/**
 * Contract §8 test 10 ({@code SelfGrantRefused}) -- the decision-logic half
 * that is provable without a directory: given an already-resolved
 * {@code actor_authz_state} group set, an admin binding a token to a group
 * they already belong to is refused and no row is created.
 *
 * <p>Also 13G {@code LIA-3.5}'s decisive test over the revoke path (the
 * disable-path half lives in {@code LocalIdentityAdministrationTest}):
 * revoking the product's last enabled {@code role:security_admin} binding
 * is refused, observed failing before {@link SecurityAdminLockoutGuard}
 * existed and passing once {@link RoleBindingAdminService#revoke} consulted
 * it.</p>
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

    /** Minimal fake: {@link SecurityAdminLockoutGuard} only ever calls {@link #findById}. */
    private static final class FakeLocalCredentialsRepository implements LocalCredentialsRepository {
        private final Map<String, LocalCredentialRecord> byId = new HashMap<>();

        void put(String localIdentityId, boolean enabled) {
            byId.put(localIdentityId, new LocalCredentialRecord(localIdentityId, localIdentityId, new byte[0],
                    new byte[0], "argon2id", 1, 1, 1, 0, Optional.empty(), Instant.now(), Instant.now(), enabled,
                    "admin0", Instant.now(), false));
        }

        /** NXS-LOCAL-0152's V9 seeding hook: not exercised by this test. */
        @Override
        public void markMustChangePassword(String localIdentityId, String actorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<LocalCredentialRecord> findByName(String localIdentityName) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<LocalCredentialRecord> findById(String localIdentityId) {
            return Optional.ofNullable(byId.get(localIdentityId));
        }

        @Override
        public List<LocalCredentialRecord> findAll() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean anyExist() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String create(String localIdentityId, String localIdentityName, Argon2PasswordHasher.Verifier verifier,
                String createdByActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void recordFailedAttempt(String localIdentityId, Instant now, int lockoutThreshold,
                Duration lockoutDuration) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void recordSuccessfulLogin(String localIdentityId, Instant now, String actorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void changePassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String changedByActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void adminSetPassword(String localIdentityId, Argon2PasswordHasher.Verifier newVerifier,
                String settingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void setEnabled(String localIdentityId, boolean enabled, String actingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    void selfGrantIsRefusedAndNoRowIsCreated() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        GroupReferenceCipher cipher = cipher();
        Instant now = Instant.now();
        String adminActor = "admin1";
        String group = "cn=security-admins,dc=example,dc=com";
        authzState.put(adminActor, Set.of(group), now.plus(15, ChronoUnit.MINUTES));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher,
                new SecurityAdminLockoutGuard(bindings, new FakeLocalCredentialsRepository(), cipher));

        RoleBindingAdminService.Outcome outcome = service.create(adminActor, RoleToken.SECURITY_ADMIN.token(),
                group, "key-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.SelfGrantRefused);
        assertEquals(0, bindings.createCalls, "no role_bindings row must be created on self-grant refusal");
    }

    @Test
    void bindingADifferentGroupTheAdminIsNotAMemberOfSucceeds() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        GroupReferenceCipher cipher = cipher();
        Instant now = Instant.now();
        String adminActor = "admin1";
        authzState.put(adminActor, Set.of("cn=security-admins,dc=example,dc=com"), now.plus(15, ChronoUnit.MINUTES));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher,
                new SecurityAdminLockoutGuard(bindings, new FakeLocalCredentialsRepository(), cipher));

        RoleBindingAdminService.Outcome outcome = service.create(adminActor, RoleToken.BACKUP_ADMIN.token(),
                "cn=backup-admins,dc=example,dc=com", "key-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.Created);
        assertEquals(1, bindings.createCalls);
    }

    /**
     * 13G {@code LIA-3.5} decisive test, revoke half: a single active
     * {@code role:security_admin} binding, referencing an enabled local
     * identity, is the product's only admin -- revoking it must be refused,
     * not merely discouraged, and the binding must remain active.
     */
    @Test
    void revokingTheLastEnabledSecurityAdminBindingIsRefused() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        FakeLocalCredentialsRepository localCredentials = new FakeLocalCredentialsRepository();
        GroupReferenceCipher cipher = cipher();
        Instant now = Instant.now();

        String onlyAdminIdentityId = "identity-only-admin";
        localCredentials.put(onlyAdminIdentityId, true);
        byte[] encrypted = cipher.encrypt(onlyAdminIdentityId);
        bindings.bindings.add(new RoleBindingRecord("binding-1", RoleToken.SECURITY_ADMIN.token(), encrypted, "key-1",
                "system:bootstrap", now, Optional.empty(), Optional.empty()));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher,
                new SecurityAdminLockoutGuard(bindings, localCredentials, cipher));

        RoleBindingAdminService.Outcome outcome = service.revoke("someone-else", "binding-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.LastSecurityAdminRefused,
                "expected LastSecurityAdminRefused, got " + outcome);
        assertTrue(bindings.find("binding-1").isPresent(), "the last security_admin binding must not be revoked");
    }

    /** The same shape, but a second enabled admin exists -- the revoke must succeed. */
    @Test
    void revokingASecurityAdminBindingSucceedsWhenAnotherEnabledAdminRemains() {
        FakeRoleBindingRepository bindings = new FakeRoleBindingRepository();
        FakeActorAuthzStateRepository authzState = new FakeActorAuthzStateRepository();
        FakeLocalCredentialsRepository localCredentials = new FakeLocalCredentialsRepository();
        GroupReferenceCipher cipher = cipher();
        Instant now = Instant.now();

        String firstAdminId = "identity-admin-1";
        String secondAdminId = "identity-admin-2";
        localCredentials.put(firstAdminId, true);
        localCredentials.put(secondAdminId, true);
        bindings.bindings.add(new RoleBindingRecord("binding-1", RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt(firstAdminId), "key-1", "system:bootstrap", now, Optional.empty(), Optional.empty()));
        bindings.bindings.add(new RoleBindingRecord("binding-2", RoleToken.SECURITY_ADMIN.token(),
                cipher.encrypt(secondAdminId), "key-1", "admin1", now, Optional.empty(), Optional.empty()));

        RoleBindingAdminService service = new RoleBindingAdminService(bindings, authzState, cipher,
                new SecurityAdminLockoutGuard(bindings, localCredentials, cipher));

        RoleBindingAdminService.Outcome outcome = service.revoke("someone-else", "binding-1", now);

        assertTrue(outcome instanceof RoleBindingAdminService.Outcome.Revoked, "expected Revoked, got " + outcome);
        assertTrue(bindings.find("binding-1").isEmpty());
    }
}
