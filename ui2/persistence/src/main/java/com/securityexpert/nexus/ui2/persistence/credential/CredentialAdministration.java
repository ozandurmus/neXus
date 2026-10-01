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
    public CredentialView create(String actor, String displayName, CredentialKind kind,
            String username, boolean allowsCheckPoint, boolean allowsPaloAlto, char[] secret,
            Optional<char[]> passphrase, SnmpSettings snmp) {
        try {
            validate(kind, username, secret, passphrase, snmp);
            if (kind == CredentialKind.SNMP_V1_V2C) username = "";
            String credentialId = OpaqueId.random().value();
            String referenceId = OpaqueId.random().value();
            credentialRepository.create(credentialId, referenceId, displayName, kind, username,
                    encryptAndZero(secret), passphrase.map(this::encryptAndZero).orElse(null), envelopeKeyId,
                    allowsCheckPoint, allowsPaloAlto, actor, snmp);
            return toView(credentialRepository.findById(credentialId).orElseThrow());
        } finally {
            zero(secret);
            passphrase.ifPresent(CredentialAdministration::zero);
        }
    }

    @Override
    public List<CredentialView> list() {
        return credentialRepository.findAll().stream().map(this::toView).toList();
    }

    @Override
    public ReplaceSecretResult replaceSecret(String actor, String credentialId, char[] secret,
            Optional<char[]> passphrase, String username, SnmpSettings snmp) {
        try {
            Optional<CredentialRecord> found = credentialRepository.findById(credentialId);
            if (found.isEmpty()) return new ReplaceSecretResult.NotFound();
            CredentialRecord existing = found.orElseThrow();
            if (passphrase.isPresent() && existing.kind() != CredentialKind.SSH_PRIVATE_KEY
                    && existing.kind() != CredentialKind.SNMP_V3) {
                return new ReplaceSecretResult.PassphraseNotAllowed();
            }
            String effectiveUsername = username == null ? existing.username() : username;
            SnmpSettings effectiveSnmp = snmp == null ? existing.snmp() : snmp;
            if (existing.kind() != CredentialKind.SNMP_V3 && username != null) {
                throw new IllegalArgumentException("username update is only supported for SNMP v3");
            }
            validate(existing.kind(), effectiveUsername, secret, passphrase, effectiveSnmp);
            credentialRepository.replaceSecret(credentialId, encryptAndZero(secret),
                    passphrase.map(this::encryptAndZero).orElse(null), envelopeKeyId, actor,
                    effectiveUsername, effectiveSnmp);
            return new ReplaceSecretResult.Ok(toView(credentialRepository.findById(credentialId).orElseThrow()));
        } finally {
            zero(secret);
            passphrase.ifPresent(CredentialAdministration::zero);
        }
    }

    private static void validate(CredentialKind kind, String username, char[] secret,
            Optional<char[]> secondary, SnmpSettings snmp) {
        boolean hasSecret = secret != null && secret.length > 0;
        boolean hasSecondary = secondary.isPresent() && secondary.orElseThrow().length > 0;
        if (kind == CredentialKind.SNMP_V3) {
            if (snmp == null || username == null || username.isBlank()
                    || hasSecret != !snmp.securityLevel().equals("noAuthNoPriv")
                    || hasSecondary != snmp.securityLevel().equals("authPriv")) {
                throw new IllegalArgumentException("invalid SNMP fields for security level");
            }
        } else if (snmp != null || !hasSecret
                || (secondary.isPresent() && kind != CredentialKind.SSH_PRIVATE_KEY)
                || (kind == CredentialKind.SNMP_V1_V2C && username != null && !username.isEmpty())) {
            throw new IllegalArgumentException("invalid credential fields");
        }
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
                record.secretSetAt(), record.snmp());
    }

    /** Encrypts, then immediately zeroes the caller-supplied array -- it must never outlive this call. */
    private byte[] encryptAndZero(char[] value) {
        if (value == null || value.length == 0) return null;
        try {
            return cipher.encrypt(new String(value));
        } finally {
            zero(value);
        }
    }

    private static void zero(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }
}
