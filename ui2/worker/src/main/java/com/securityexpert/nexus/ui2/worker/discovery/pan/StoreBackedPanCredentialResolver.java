package com.securityexpert.nexus.ui2.worker.discovery.pan;

import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialRecord;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRecord;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialMaterial;
import com.securityexpert.nexus.ui2.worker.transport.xmlapi.PanCredentialResolver;

/**
 * Resolves a {@code credentialRef} (a {@code credential_references} id)
 * through the credential store (2026-09-14 PO decision record CS-1..CS-5,
 * SB-14/SB-16), replacing {@code EnvironmentPanCredentialAndTrustResolvers}'
 * own credential half. Mirrors {@code StoreBackedSshCredentialResolver}
 * exactly. SB-14: the same credential store row can serve both vendors, so
 * this resolver accepts any password-shaped kind ({@code ssh_password} or
 * {@code api_password}); a {@code ssh_private_key} credential has no
 * password to hand Panorama's XML API and is refused, not silently
 * misresolved.
 */
final class StoreBackedPanCredentialResolver implements PanCredentialResolver {

    private final CredentialReferenceRepository credentialReferenceRepository;
    private final CredentialRepository credentialRepository;
    private final CredentialStoreCipher cipher;

    StoreBackedPanCredentialResolver(CredentialReferenceRepository credentialReferenceRepository,
            CredentialRepository credentialRepository, CredentialStoreCipher cipher) {
        this.credentialReferenceRepository = Objects.requireNonNull(credentialReferenceRepository,
                "credentialReferenceRepository");
        this.credentialRepository = Objects.requireNonNull(credentialRepository, "credentialRepository");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
    }

    @Override
    public PanCredentialMaterial resolve(String credentialRef) {
        CredentialReferenceRecord reference = credentialReferenceRepository.find(credentialRef)
                .orElseThrow(() -> unresolvable(credentialRef));
        CredentialRecord credential = credentialRepository.findById(reference.backendPointer())
                .orElseThrow(() -> unresolvable(credentialRef));
        if (credential.kind() == CredentialKind.SSH_PRIVATE_KEY) {
            throw unresolvable(credentialRef);
        }

        char[] password = cipher.decrypt(credential.encryptedSecret()).toCharArray();
        return new PanCredentialMaterial(credential.username(), password);
    }

    private static IllegalStateException unresolvable(String credentialRef) {
        // SB-16: refuse before contact -- never a username or a secret in this message.
        return new IllegalStateException("pan credential reference not resolvable: " + credentialRef);
    }
}
