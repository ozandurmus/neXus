package com.securityexpert.nexus.ui2.service.boot;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.script.JooqScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService;

/**
 * Script Execution slice 1 composition (V251, {@code SCRIPT_EXECUTION_CONTRACT.md} section 3.7).
 */
@Configuration
public class ScriptExecutionConfiguration {

    @Bean
    public ScriptRepository scriptRepository(TransactionBoundary transactionBoundary) {
        return new JooqScriptRepository(transactionBoundary);
    }

    @Bean
    public ScriptExecutionService scriptExecutionService(
            ScriptRepository scriptRepository,
            CredentialReferenceRepository credentialReferenceRepository,
            DeviceRepository deviceRepository,
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess,
            TransactionBoundary transactionBoundary) {
        return new ScriptExecutionService(
                scriptRepository,
                credentialReferenceRepository,
                deviceRepository,
                scriptArtefactStore(artefactStoreAccess, transactionBoundary));
    }

    static ArtefactStore scriptArtefactStore(
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess,
            TransactionBoundary transactionBoundary) {
        String root = System.getenv().getOrDefault("UI2_SCRIPT_STORE_ROOT", "");
        String keyFile = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_KEY_FILE", "");
        if (root.isBlank() || keyFile.isBlank()) {
            var fallback = artefactStoreAccess == null ? null : artefactStoreAccess.storeOrNull();
            return fallback instanceof FileArtefactStore file
                    ? ReportConfiguration.withQuotaAudit(file, transactionBoundary)
                    : fallback;
        }
        String keyBase64 = SecretFile.readRequired(Path.of(keyFile), "artefact_store_key");
        return ReportConfiguration.withQuotaAudit(
                new FileArtefactStore(Path.of(root), ArtefactStoreCipher.fromBase64Key(keyBase64)),
                transactionBoundary);
    }
}
