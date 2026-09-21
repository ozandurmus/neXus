package com.securityexpert.nexus.ui2.persistence.credential;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.OpaqueId;

/**
 * The {@link CredentialStorePort} implementation (2026-09-14 PO decision
 * record CS-1..CS-4): the one class the HTTP API ({@code service} module)
 * and the CLI ({@code cli} module, via a {@code job-engine} composition
 * helper) both construct, so the two paths share the exact same domain logic
 * and cannot drift -- mirrors {@code LocalIdentityAdministration} exactly.
 */
public final class CredentialAdministration implements CredentialStorePort {

    private final CredentialRepository credentialRepository;
    private final CredentialStoreCipher cipher;
    private final String envelopeKeyId;

    public CredentialAdministration(CredentialRepository credentialRepository, CredentialStoreCipher cipher,
            String envelopeKeyId) {
        this.credentialRepository = Objects.requireNonNull(credentialRepository, "credentialRepository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.envelopeKeyId = Objects.requireNonNull(envelopeKeyId, "envelopeKeyId");
    }

    @Override
    public CredentialView create(String actingAdminActorFingerprint, String displayName, CredentialKind kind,
            String username, boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret,
            Optional<char[]> passphrase) {
        String credentialId = OpaqueId.random().value();
        String credentialReferenceId = OpaqueId.random().value();
        byte[] encryptedSecret = encryptAndZero(secret);
        byte[] encryptedPassphrase = passphrase.map(this::encryptAndZero).orElse(null);
        credentialRepository.create(credentialId, credentialReferenceId, displayName, kind, username,
                encryptedSecret, encryptedPassphrase, envelopeKeyId, allowsCheckPoint, allowsPaloAlto,
                actingAdminActorFingerprint);
        return toView(credentialRepository.findById(credentialId)
                .orElseThrow(() -> new IllegalStateException("credential vanished immediately after creation")));
    }

    @Override
    public List<CredentialView> list() {
        return credentialRepository.findAll().stream().map(this::toView).toList();
    }

    @Override
    public ReplaceSecretResult replaceSecret(String actingAdminActorFingerprint, String credentialId, char[] secret,
            Optional<char[]> passphrase) {
        Optional<CredentialRecord> existing = credentialRepository.findById(credentialId);
        if (existing.isEmpty()) {
            zero(secret);
            passphrase.ifPresent(CredentialAdministration::zero);
            return new ReplaceSecretResult.NotFound();
        }
        if (passphrase.isPresent() && existing.orElseThrow().kind() != CredentialKind.SSH_PRIVATE_KEY) {
            zero(secret);
            passphrase.ifPresent(CredentialAdministration::zero);
            return new ReplaceSecretResult.PassphraseNotAllowed();
        }
        byte[] encryptedSecret = encryptAndZero(secret);
        byte[] encryptedPassphrase = passphrase.map(this::encryptAndZero).orElse(null);
        credentialRepository.replaceSecret(credentialId, encryptedSecret, encryptedPassphrase, envelopeKeyId,
                actingAdminActorFingerprint);
        return new ReplaceSecretResult.Ok(toView(credentialRepository.findById(credentialId).orElseThrow()));
    }

    @Override
    public DeleteResult delete(String actingAdminActorFingerprint, String credentialId) {
        Optional<CredentialRecord> existing = credentialRepository.findById(credentialId);
        if (existing.isEmpty()) {
            return new DeleteResult.NotFound();
        }
        String credentialReferenceId = credentialRepository.findCredentialReferenceId(credentialId)
                .orElseThrow(() -> new IllegalStateException(
                        "credential " + credentialId + " has no credential_references row"));
        // CS-4: refused while a device still uses this credential's reference.
        if (credentialRepository.isCredentialReferenceInUse(credentialReferenceId)) {
            return new DeleteResult.CredentialInUse();
        }
        credentialRepository.delete(credentialId, credentialReferenceId, actingAdminActorFingerprint);
        return new DeleteResult.Ok();
    }

    private CredentialView toView(CredentialRecord record) {
        String credentialReferenceId = credentialRepository.findCredentialReferenceId(record.credentialId())
                .orElseThrow(() -> new IllegalStateException(
                        "credential " + record.credentialId() + " has no credential_references row"));
        return new CredentialView(record.credentialId(), credentialReferenceId, record.displayName(), record.kind(),
                record.username(), record.allowsCheckPoint(), record.allowsPaloAlto(), record.createdAt(),
                record.secretSetAt());
    }

    /** Encrypts, then immediately zeroes the caller-supplied array -- it must never outlive this call. */
    private byte[] encryptAndZero(char[] value) {
        byte[] encrypted = cipher.encrypt(new String(value));
        zero(value);
        return encrypted;
    }

    private static void zero(char[] value) {
        Arrays.fill(value, '\0');
    }
}
