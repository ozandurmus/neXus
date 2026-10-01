package com.securityexpert.nexus.ui2.worker.credential;

import java.util.Arrays;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.credential.CredentialStoreComposition.ResolverComponents;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;

/** Worker-only decryption. This class performs no network operations. */
public final class StoreBackedSnmpCredentialResolver {
    private final ResolverComponents components;

    public StoreBackedSnmpCredentialResolver(ResolverComponents components) {
        this.components = Objects.requireNonNull(components);
    }

    public Material resolve(String referenceId) {
        var reference = components.credentialReferenceRepository().find(referenceId).orElseThrow(
                StoreBackedSnmpCredentialResolver::unresolvable);
        var credential = components.credentialRepository().findById(reference.backendPointer()).orElseThrow(
                StoreBackedSnmpCredentialResolver::unresolvable);
        if (credential.kind() != CredentialKind.SNMP_V1_V2C && credential.kind() != CredentialKind.SNMP_V3) {
            throw unresolvable();
        }
        char[] primary = decrypt(credential.encryptedSecret());
        try {
            return new Material(credential.kind(), credential.username(), credential.snmp(), primary,
                    decrypt(credential.encryptedPassphrase()));
        } catch (RuntimeException e) {
            Arrays.fill(primary, '\0');
            throw unresolvable();
        }
    }

    private char[] decrypt(byte[] value) {
        return value == null ? new char[0] : components.cipher().decrypt(value).toCharArray();
    }

    private static IllegalStateException unresolvable() {
        return new IllegalStateException("SNMP credential reference not resolvable");
    }

    /** The caller owns this short-lived material and must close it after use. */
    public record Material(CredentialKind kind, String username, SnmpSettings settings,
            char[] communityOrAuthSecret, char[] privacySecret) implements AutoCloseable {
        @Override
        public void close() {
            Arrays.fill(communityOrAuthSecret, '\0');
            Arrays.fill(privacySecret, '\0');
        }

        @Override
        public String toString() {
            return "SnmpCredentialMaterial[REDACTED]";
        }
    }
}
