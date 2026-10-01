package com.securityexpert.nexus.ui2.persistence.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;

/**
 * 2026-09-14 PO decision record CS-1..CS-4. {@code
 * deleteOfAReferencedCredentialIsRefused} is AC-2's decisive test: a
 * credential a device's {@code credential_reference_id} points at cannot be
 * deleted. {@code noRecordAnywhereInThisRoundTripContainsThePlaintextFixtureSecret}
 * is AC-1's domain-layer half.
 */
class CredentialAdministrationTest {

    private static final String FIXTURE_SECRET = "correct-horse-battery-staple-CS-1-fixture";

    private static CredentialStoreCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return CredentialStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    /** A minimal in-memory {@link CredentialRepository}, mirroring the local-identity fakes' own shape. */
    private static final class FakeCredentialRepository implements CredentialRepository {
        final Map<String, CredentialRecord> byId = new HashMap<>();
        final Map<String, String> referenceIdByCredentialId = new HashMap<>();
        final Set<String> deviceUsedReferenceIds = new HashSet<>();
        int replaceSecretCalls;

        @Override
        public String create(String credentialId, String credentialReferenceId, String displayName,
                CredentialKind kind, String username, byte[] encryptedSecret, byte[] encryptedPassphrase,
                String envelopeKeyId, boolean allowsCheckPoint, boolean allowsPaloAlto,
                String createdByActorFingerprint, SnmpSettings snmp) {
            Instant now = Instant.now();
            byId.put(credentialId, new CredentialRecord(credentialId, displayName, kind, username, encryptedSecret,
                    encryptedPassphrase, envelopeKeyId, allowsCheckPoint, allowsPaloAlto, createdByActorFingerprint,
                    now, now, snmp));
            referenceIdByCredentialId.put(credentialId, credentialReferenceId);
            return credentialId;
        }

        @Override
        public Optional<CredentialRecord> findById(String credentialId) {
            return Optional.ofNullable(byId.get(credentialId));
        }

        @Override
        public List<CredentialRecord> findAll() {
            return List.copyOf(byId.values());
        }

        @Override
        public Optional<String> findCredentialReferenceId(String credentialId) {
            return Optional.ofNullable(referenceIdByCredentialId.get(credentialId));
        }

        @Override
        public void replaceSecret(String credentialId, byte[] encryptedSecret, byte[] encryptedPassphrase,
                String envelopeKeyId, String actingAdminActorFingerprint, String username,
                SnmpSettings snmp) {
            replaceSecretCalls++;
            CredentialRecord existing = byId.get(credentialId);
            byId.put(credentialId, new CredentialRecord(existing.credentialId(), existing.displayName(),
                    existing.kind(), username == null ? existing.username() : username, encryptedSecret, encryptedPassphrase, envelopeKeyId,
                    existing.allowsCheckPoint(), existing.allowsPaloAlto(), existing.createdByActorFingerprint(),
                    existing.createdAt(), Instant.now(), snmp));
        }

        @Override
        public boolean isCredentialReferenceInUse(String credentialReferenceId) {
            return deviceUsedReferenceIds.contains(credentialReferenceId);
        }

        @Override
        public void delete(String credentialId, String credentialReferenceId, String actingAdminActorFingerprint) {
            byId.remove(credentialId);
            referenceIdByCredentialId.remove(credentialId);
        }
    }

    private record Fixture(CredentialAdministration administration, FakeCredentialRepository repository) {
    }

    private static Fixture fixture() {
        FakeCredentialRepository repository = new FakeCredentialRepository();
        CredentialAdministration administration = new CredentialAdministration(repository, cipher(), "key-v1");
        return new Fixture(administration, repository);
    }

    @Test
    void noRecordAnywhereInThisRoundTripContainsThePlaintextFixtureSecret() {
        Fixture fx = fixture();

        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PASSWORD, "svc-account", true, false, FIXTURE_SECRET.toCharArray(),
                Optional.empty());
        List<CredentialStorePort.CredentialView> listed = fx.administration.list();
        CredentialStorePort.ReplaceSecretResult replaced =
                fx.administration.replaceSecret("admin1", created.credentialId(), "a-different-secret".toCharArray(),
                        Optional.empty());

        assertFalse(created.toString().contains(FIXTURE_SECRET), created.toString());
        assertFalse(listed.toString().contains(FIXTURE_SECRET), listed.toString());
        assertFalse(replaced.toString().contains(FIXTURE_SECRET), replaced.toString());

        CredentialRecord stored = fx.repository.findById(created.credentialId()).orElseThrow();
        assertFalse(new String(stored.encryptedSecret()).contains(FIXTURE_SECRET),
                "the stored bytes must be ciphertext, never the plaintext fixture");
    }

    @Test
    void createEncryptsTheSecretAndZeroesTheCallerSuppliedArray() {
        Fixture fx = fixture();
        char[] secret = FIXTURE_SECRET.toCharArray();

        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PASSWORD, "svc-account", true, false, secret, Optional.empty());

        assertTrue(new String(secret).chars().allMatch(c -> c == '\0'), "secret array must be zeroed after use");
        CredentialRecord stored = fx.repository.findById(created.credentialId()).orElseThrow();
        assertFalse(new String(stored.encryptedSecret()).contains(FIXTURE_SECRET));
        assertEquals("edge-fw-1", created.displayName());
        assertEquals(CredentialKind.SSH_PASSWORD, created.kind());
    }

    /** AC-2 THE DECISIVE TEST: a credential a device's credential_reference_id points at cannot be deleted. */
    @Test
    void deleteOfAReferencedCredentialIsRefused() {
        Fixture fx = fixture();
        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PASSWORD, "svc-account", true, false, FIXTURE_SECRET.toCharArray(),
                Optional.empty());
        fx.repository.deviceUsedReferenceIds.add(created.credentialReferenceId());

        CredentialStorePort.DeleteResult result = fx.administration.delete("admin1", created.credentialId());

        assertTrue(result instanceof CredentialStorePort.DeleteResult.CredentialInUse,
                "expected CredentialInUse, got " + result);
        assertTrue(fx.repository.findById(created.credentialId()).isPresent(),
                "a referenced credential must not be deleted");
    }

    @Test
    void deleteOfAnUnreferencedCredentialSucceeds() {
        Fixture fx = fixture();
        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PASSWORD, "svc-account", true, false, FIXTURE_SECRET.toCharArray(),
                Optional.empty());

        CredentialStorePort.DeleteResult result = fx.administration.delete("admin1", created.credentialId());

        assertTrue(result instanceof CredentialStorePort.DeleteResult.Ok, "expected Ok, got " + result);
        assertTrue(fx.repository.findById(created.credentialId()).isEmpty());
    }

    @Test
    void deleteOfAnUnknownCredentialIsNotFound() {
        Fixture fx = fixture();

        CredentialStorePort.DeleteResult result = fx.administration.delete("admin1", "no-such-id");

        assertTrue(result instanceof CredentialStorePort.DeleteResult.NotFound);
    }

    @Test
    void replaceSecretOverwritesTheEncryptedMaterialAndUpdatesSecretSetAt() throws InterruptedException {
        Fixture fx = fixture();
        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PASSWORD, "svc-account", true, false, "original-secret".toCharArray(),
                Optional.empty());
        Thread.sleep(5);

        CredentialStorePort.ReplaceSecretResult result =
                fx.administration.replaceSecret("admin1", created.credentialId(), FIXTURE_SECRET.toCharArray(),
                        Optional.empty());

        assertTrue(result instanceof CredentialStorePort.ReplaceSecretResult.Ok);
        CredentialRecord stored = fx.repository.findById(created.credentialId()).orElseThrow();
        assertFalse(new String(stored.encryptedSecret()).contains(FIXTURE_SECRET));
        assertTrue(stored.secretSetAt().isAfter(created.secretSetAt()));
    }

    @Test
    void replaceSecretRejectsPassphraseForNonPrivateKeyCredentialsBeforeWrite() {
        for (CredentialKind kind : List.of(CredentialKind.SSH_PASSWORD, CredentialKind.API_PASSWORD)) {
            Fixture fx = fixture();
            CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1", kind,
                    "svc-account", true, false, "original-secret".toCharArray(), Optional.empty());
            CredentialRecord before = fx.repository.findById(created.credentialId()).orElseThrow();
            char[] secret = "replacement-secret".toCharArray();
            char[] passphrase = "not-allowed".toCharArray();

            CredentialStorePort.ReplaceSecretResult result = fx.administration.replaceSecret("admin1",
                    created.credentialId(), secret, Optional.of(passphrase));

            assertTrue(result instanceof CredentialStorePort.ReplaceSecretResult.PassphraseNotAllowed);
            assertEquals(0, fx.repository.replaceSecretCalls);
            assertEquals(before, fx.repository.findById(created.credentialId()).orElseThrow());
            assertTrue(new String(secret).chars().allMatch(c -> c == '\0'));
            assertTrue(new String(passphrase).chars().allMatch(c -> c == '\0'));
        }
    }

    @Test
    void privateKeyRoundTripEncryptsBothSecretAndPassphraseAndZeroesBothArrays() {
        Fixture fx = fixture();
        char[] pem = "-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----".toCharArray();
        char[] passphrase = "fixture-passphrase".toCharArray();

        CredentialStorePort.CredentialView created = fx.administration.create("admin1", "edge-fw-1",
                CredentialKind.SSH_PRIVATE_KEY, "svc-account", true, true, pem, Optional.of(passphrase));

        assertTrue(new String(pem).chars().allMatch(c -> c == '\0'));
        assertTrue(new String(passphrase).chars().allMatch(c -> c == '\0'));
        CredentialRecord stored = fx.repository.findById(created.credentialId()).orElseThrow();
        assertFalse(new String(stored.encryptedPassphrase()).contains("fixture-passphrase"));
        assertTrue(created.allowsCheckPoint());
        assertTrue(created.allowsPaloAlto());

        char[] replacement = "replacement-key".toCharArray();
        char[] replacementPassphrase = "replacement-passphrase".toCharArray();
        CredentialStorePort.ReplaceSecretResult result = fx.administration.replaceSecret("admin1",
                created.credentialId(), replacement, Optional.of(replacementPassphrase));

        assertTrue(result instanceof CredentialStorePort.ReplaceSecretResult.Ok);
        assertEquals(1, fx.repository.replaceSecretCalls);
        assertTrue(new String(replacement).chars().allMatch(c -> c == '\0'));
        assertTrue(new String(replacementPassphrase).chars().allMatch(c -> c == '\0'));
        assertTrue(fx.repository.findById(created.credentialId()).orElseThrow().encryptedPassphrase() != null);
    }

    @Test
    void snmpLevelsEnforceSecretPresenceEncryptAndClearInputs() {
        for (String level : List.of("noAuthNoPriv", "authNoPriv", "authPriv")) {
            boolean auth = !level.equals("noAuthNoPriv");
            boolean privacy = level.equals("authPriv");
            var settings = new CredentialStorePort.SnmpSettings(level, auth ? "SHA-256" : null,
                    privacy ? "AES-128" : null);
            for (boolean authPresent : List.of(false, true)) {
                for (boolean privacyPresent : List.of(false, true)) {
                    var repository = new FakeCredentialRepository();
                    var encryption = cipher();
                    var admin = new CredentialAdministration(repository, encryption, "fixture-key");
                    char[] primary = authPresent ? "synthetic-auth".toCharArray() : new char[0];
                    char[] secondary = privacyPresent ? "synthetic-privacy".toCharArray() : new char[0];
                    if (auth == authPresent && privacy == privacyPresent) {
                        var view = admin.create("fixture-actor", "Synthetic SNMP", CredentialKind.SNMP_V3,
                                "synthetic-user", false, false, primary, Optional.of(secondary), settings);
                        var stored = repository.findById(view.credentialId()).orElseThrow();
                        assertEquals(settings, view.snmp());
                        if (auth) assertEquals("synthetic-auth", encryption.decrypt(stored.encryptedSecret()));
                        if (privacy) assertEquals("synthetic-privacy", encryption.decrypt(stored.encryptedPassphrase()));
                        assertFalse(view.toString().contains("synthetic-auth"));
                        assertFalse(view.toString().contains("synthetic-privacy"));
                        var changed = admin.replaceSecret("fixture-actor", view.credentialId(), new char[0],
                                Optional.empty(), "synthetic-next-user",
                                new CredentialStorePort.SnmpSettings("noAuthNoPriv", null, null));
                        assertTrue(changed instanceof CredentialStorePort.ReplaceSecretResult.Ok);
                        var updated = repository.findById(view.credentialId()).orElseThrow();
                        org.junit.jupiter.api.Assertions.assertNull(updated.encryptedSecret());
                        org.junit.jupiter.api.Assertions.assertNull(updated.encryptedPassphrase());
                        assertEquals("synthetic-next-user", updated.username());
                    } else {
                        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                                () -> admin.create("fixture-actor", "Synthetic SNMP", CredentialKind.SNMP_V3,
                                        "synthetic-user", false, false, primary, Optional.of(secondary), settings));
                        assertTrue(repository.byId.isEmpty());
                    }
                    assertTrue(new String(primary).chars().allMatch(c -> c == 0));
                    assertTrue(new String(secondary).chars().allMatch(c -> c == 0));
                }
            }
        }
    }

    @Test
    void communityRoundTripAndInvalidSnmpMetadata() {
        var repository = new FakeCredentialRepository();
        var encryption = cipher();
        var admin = new CredentialAdministration(repository, encryption, "fixture-key");
        var view = admin.create("fixture-actor", "Synthetic community", CredentialKind.SNMP_V1_V2C,
                "", false, false, "synthetic-community".toCharArray(), Optional.empty());
        assertEquals("synthetic-community", encryption.decrypt(repository.findById(view.credentialId())
                .orElseThrow().encryptedSecret()));
        assertFalse(view.toString().contains("synthetic-community"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> admin.create("fixture-actor", "Synthetic community", CredentialKind.SNMP_V1_V2C,
                        "", false, false, new char[0], Optional.empty()));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> admin.create("fixture-actor", "Synthetic v3", CredentialKind.SNMP_V3,
                        "", false, false, new char[0], Optional.empty(),
                        new CredentialStorePort.SnmpSettings("noAuthNoPriv", null, null)));
        for (String[] invalid : new String[][] {{null, null, null}, {"unknown", null, null},
                {"noAuthNoPriv", "MD5", null}, {"authNoPriv", null, null},
                {"authNoPriv", "unknown", null}, {"authNoPriv", "SHA1", "DES"},
                {"authPriv", "SHA1", null}, {"authPriv", "SHA1", "unknown"}}) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> new CredentialStorePort.SnmpSettings(invalid[0], invalid[1], invalid[2]));
        }
        for (String auth : List.of("SHA-256", "SHA-384", "SHA-512", "SHA-224", "SHA1", "MD5")) {
            for (String privacy : List.of("AES-128", "AES-192", "AES-256", "DES")) {
                new CredentialStorePort.SnmpSettings("authPriv", auth, privacy);
            }
        }
    }

}
