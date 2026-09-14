package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialAdministration;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.credential.JooqCredentialRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort;
import com.securityexpert.nexus.ui2.platform.SecretFile;

/**
 * Composition root for the credential store (2026-09-14 PO decision record
 * section 3, CS-1..CS-5), mirroring {@link LocalAuthenticationConfiguration}'s
 * own cipher-wiring pattern exactly: {@code ui2.credential-store.key-file}
 * (C1 §6.2's {@code _FILE} convention) is a base64-encoded 256-bit key, read
 * once at startup through {@link SecretFile}, failing closed when the file
 * is missing or empty -- and it is a second, separately named key from
 * {@code ui2.role-binding.group-reference-key-file} (C1 §6.1: secrets are
 * scoped per purpose).
 */
@Configuration
public class CredentialStoreConfiguration {

    private final Path credentialStoreKeyFile;
    private final String credentialStoreKeyId;

    public CredentialStoreConfiguration(
            @Value("${ui2.credential-store.key-file}") String credentialStoreKeyFile,
            @Value("${ui2.credential-store.key-id}") String credentialStoreKeyId) {
        this.credentialStoreKeyFile = Path.of(credentialStoreKeyFile);
        this.credentialStoreKeyId = credentialStoreKeyId;
    }

    @Bean
    public CredentialStoreCipher credentialStoreCipher() {
        String base64Key = SecretFile.readRequired(credentialStoreKeyFile, "credential_store_key");
        return CredentialStoreCipher.fromBase64Key(base64Key);
    }

    @Bean
    public CredentialRepository credentialRepository(TransactionBoundary transactionBoundary) {
        return new JooqCredentialRepository(transactionBoundary);
    }

    @Bean
    public CredentialStorePort credentialStorePort(CredentialRepository credentialRepository,
            CredentialStoreCipher credentialStoreCipher) {
        return new CredentialAdministration(credentialRepository, credentialStoreCipher, credentialStoreKeyId);
    }
}
