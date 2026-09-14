package com.securityexpert.nexus.ui2.worker.discovery.cp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;

/** 2026-09-14 PO decision record CS-1..CS-5, SB-16: the store-backed ssh_exec resolver. */
class StoreBackedSshCredentialResolverTest {

    private static final String FIXTURE_SECRET = "correct-horse-battery-staple-SB-16-fixture";

    private static CredentialStoreCipher cipher() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return CredentialStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(key));
    }

    private static final class FakeCredentialReferenceRepository implements CredentialReferenceRepository {
        final java.util.Map<String, CredentialReferenceRecord> byId = new java.util.HashMap<>();

        @Override
        public boolean exists(String credentialReferenceId) {
            return byId.containsKey(credentialReferenceId);
        }

        @Override
        public Optional<CredentialReferenceRecord> find(String credentialReferenceId) {
            return Optional.ofNullable(byId.get(credentialReferenceId));
        }
    }

    private static final class FakeCredentialRepository implements CredentialRepository {
        final java.util.Map<String, CredentialRecord> byId = new java.util.HashMap<>();

        @Override
        public String create(String credentialId, String credentialReferenceId, String displayName,
                CredentialKind kind, String username, byte[] encryptedSecret, byte[] encryptedPassphrase,
                String envelopeKeyId, boolean allowsCheckPoint, boolean allowsPaloAlto,
                String createdByActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<CredentialRecord> findById(String credentialId) {
            return Optional.ofNullable(byId.get(credentialId));
        }

        @Override
        public List<CredentialRecord> findAll() {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<String> findCredentialReferenceId(String credentialId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void replaceSecret(String credentialId, byte[] encryptedSecret, byte[] encryptedPassphrase,
                String envelopeKeyId, String actingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean isCredentialReferenceInUse(String credentialReferenceId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void delete(String credentialId, String credentialReferenceId, String actingAdminActorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    @Test
    void resolvesAnSshPasswordCredentialToTheMaterialTheTransportExpects() {
        FakeCredentialReferenceRepository references = new FakeCredentialReferenceRepository();
        FakeCredentialRepository credentials = new FakeCredentialRepository();
        CredentialStoreCipher cipher = cipher();
        references.byId.put("ref-1", new CredentialReferenceRecord("ref-1", "ssh_password", "cred-1", Instant.now()));
        credentials.byId.put("cred-1", new CredentialRecord("cred-1", "edge-fw-1", CredentialKind.SSH_PASSWORD,
                "svc-account", cipher.encrypt(FIXTURE_SECRET), null, "key-v1", true, false, "admin1", Instant.now(),
                Instant.now()));
        StoreBackedSshCredentialResolver resolver =
                new StoreBackedSshCredentialResolver(references, credentials, cipher);

        SshCredentialMaterial material = resolver.resolve("ref-1");

        assertEquals("svc-account", material.username());
        assertArrayEquals(FIXTURE_SECRET.toCharArray(), material.password());
        assertNull(material.privateKeyPem());
    }

    @Test
    void resolvesAnSshPrivateKeyCredentialToPemBytes() {
        FakeCredentialReferenceRepository references = new FakeCredentialReferenceRepository();
        FakeCredentialRepository credentials = new FakeCredentialRepository();
        CredentialStoreCipher cipher = cipher();
        String pem = "-----BEGIN PRIVATE KEY-----\nfixture\n-----END PRIVATE KEY-----";
        references.byId.put("ref-2", new CredentialReferenceRecord("ref-2", "ssh_private_key", "cred-2", Instant.now()));
        credentials.byId.put("cred-2", new CredentialRecord("cred-2", "edge-fw-2", CredentialKind.SSH_PRIVATE_KEY,
                "svc-account", cipher.encrypt(pem), cipher.encrypt("fixture-passphrase"), "key-v1", true, false,
                "admin1", Instant.now(), Instant.now()));
        StoreBackedSshCredentialResolver resolver =
                new StoreBackedSshCredentialResolver(references, credentials, cipher);

        SshCredentialMaterial material = resolver.resolve("ref-2");

        assertEquals("svc-account", material.username());
        assertNull(material.password());
        assertArrayEquals(pem.getBytes(StandardCharsets.UTF_8), material.privateKeyPem());
    }

    /** SB-16: refuse before contact -- never a partial or default credential, and never a secret in the message. */
    @Test
    void anUnresolvableReferenceRefusesBeforeAnySecretIsTouched() {
        FakeCredentialReferenceRepository references = new FakeCredentialReferenceRepository();
        FakeCredentialRepository credentials = new FakeCredentialRepository();
        StoreBackedSshCredentialResolver resolver =
                new StoreBackedSshCredentialResolver(references, credentials, cipher());

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> resolver.resolve("no-such-ref"));

        assertEquals("ssh credential reference not resolvable: no-such-ref", thrown.getMessage());
    }
}
