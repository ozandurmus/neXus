package com.securityexpert.nexus.ui2.worker.discovery.cp;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.ssh.SshCredentialResolver;

/**
 * Resolves a {@code credentialRef} (a {@code credential_references} id)
 * through the credential store (2026-09-14 PO decision record CS-1..CS-5,
 * SB-16), replacing {@code EnvironmentCredentialAndTrustResolvers}' own
 * credential half. Refuses before any connect attempt when the reference
 * cannot be resolved (SB-16): {@link #resolve} throws rather than returning
 * a partial or default credential.
 *
 * <p>The decrypted secret lives only for the duration of this call and is
 * zeroed before returning. Never logs a username or a secret (2026-09-14:
 * "the worker never logs a username or a secret"). {@code
 * encrypted_passphrase} is stored by the credential store for a {@link
 * CredentialKind#SSH_PRIVATE_KEY} credential but is not resolved here:
 * {@link SshCredentialMaterial} has no passphrase field to carry it to
 * (out of this movement's "files you must not touch" scope for the
 * transport layer).</p>
 */
public final class StoreBackedSshCredentialResolver implements SshCredentialResolver {

    private final CredentialReferenceRepository credentialReferenceRepository;
    private final CredentialRepository credentialRepository;
    private final CredentialStoreCipher cipher;

    public StoreBackedSshCredentialResolver(CredentialReferenceRepository credentialReferenceRepository,
            CredentialRepository credentialRepository, CredentialStoreCipher cipher) {
        this.credentialReferenceRepository = Objects.requireNonNull(credentialReferenceRepository,
                "credentialReferenceRepository");
        this.credentialRepository = Objects.requireNonNull(credentialRepository, "credentialRepository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public SshCredentialMaterial resolve(String credentialRef) {
        CredentialReferenceRecord reference = credentialReferenceRepository.find(credentialRef)
                .orElseThrow(() -> unresolvable(credentialRef));
        CredentialRecord credential = credentialRepository.findById(reference.backendPointer())
                .orElseThrow(() -> unresolvable(credentialRef));

        char[] secret = cipher.decrypt(credential.encryptedSecret()).toCharArray();
        if (credential.kind() == CredentialKind.SSH_PRIVATE_KEY) {
            // The PEM travels onward as its own byte[] copy -- the char[] that
            // produced it is no longer needed and is zeroed immediately.
            byte[] privateKeyPem = new String(secret).getBytes(StandardCharsets.UTF_8);
            Arrays.fill(secret, '\0');
            return new SshCredentialMaterial(credential.username(), null, privateKeyPem);
        }
        // The password travels onward as this exact array -- the caller (the
        // ssh_exec transport) owns zeroing it once the connection is made,
        // the same convention SshCredentialMaterial's other producers use.
        return new SshCredentialMaterial(credential.username(), secret, null);
    }

    private static IllegalStateException unresolvable(String credentialRef) {
        // SB-16: refuse before contact -- never a username or a secret in this message.
        return new IllegalStateException("ssh credential reference not resolvable: " + credentialRef);
    }
}
