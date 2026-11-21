package com.securityexpert.nexus.ui2.service.boot;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.credential.CredentialRepository;
import com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository;
import com.securityexpert.nexus.ui2.persistence.identity.SessionRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStoreCipher;
import com.securityexpert.nexus.ui2.service.api.ScriptRunnerInternalController;
import com.securityexpert.nexus.ui2.service.script.ScriptCredentialSource;
import com.securityexpert.nexus.ui2.service.script.ScriptRunDispatchService;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.script.JooqScriptRepository;
import com.securityexpert.nexus.ui2.persistence.script.ScriptRepository;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.platform.SecretFile;
import com.securityexpert.nexus.ui2.service.script.ScriptExecutionService;
import com.securityexpert.nexus.ui2.service.script.ScriptScheduler;

/**
 * Script Execution slice 1 & 2 composition (V251, V258, {@code SCRIPT_EXECUTION_CONTRACT.md} sections 3.4, 3.7).
 */
@Configuration
public class ScriptExecutionConfiguration {

    @Bean
    public ScriptRepository scriptRepository(TransactionBoundary transactionBoundary) {
        return new JooqScriptRepository(transactionBoundary);
    }

    @Bean
    public ScriptCredentialSource scriptCredentialSource(
            CredentialReferenceRepository credentialReferenceRepository,
            Optional<CredentialRepository> credentialRepository,
            Optional<CredentialStoreCipher> credentialStoreCipher) {
        if (credentialRepository.isEmpty()) {
            return new ScriptCredentialSource() {
                @Override
                public Optional<String> displayName(String refId) {
                    return Optional.empty();
                }

                @Override
                public Optional<Resolved> resolve(String refId) {
                    return Optional.empty();
                }
            };
        }
        return ScriptCredentialSource.fromStore(credentialReferenceRepository, credentialRepository.get(),
                credentialStoreCipher.orElse(null));
    }

    @Bean
    public ScriptExecutionService scriptExecutionService(
            ScriptRepository scriptRepository,
            CredentialReferenceRepository credentialReferenceRepository,
            DeviceRepository deviceRepository,
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess,
            TransactionBoundary transactionBoundary,
            ScriptCredentialSource scriptCredentialSource,
            @Value("${UI2_SCRIPT_RUNNER_TOKEN_SHA256:}") String runnerTokenDigest) {
        ScriptExecutionService service = new ScriptExecutionService(
                scriptRepository,
                credentialReferenceRepository,
                deviceRepository,
                scriptArtefactStore(artefactStoreAccess, transactionBoundary));
        service.useCredentialSource(scriptCredentialSource);
        service.useRunnerTokenConfigured(() -> ScriptRunnerInternalController.isValidTokenDigest(runnerTokenDigest)
                || ScriptRunnerInternalController.isValidTokenDigest(
                        OverwatchEnv.getOrDefault("UI2_SCRIPT_RUNNER_TOKEN_SHA256", "")));
        return service;
    }

    /**
     * The broker's side of a run (Amendment 2026-11-18 (b) item 4): body release, declared credentials, per-run session,
     * output storage. Uses the same writable script store as the script bodies.
     */
    @Bean
    public ScriptRunDispatchService scriptRunDispatchService(
            ScriptRepository scriptRepository,
            ScriptCredentialSource scriptCredentialSource,
            SessionRepository sessionRepository,
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess,
            TransactionBoundary transactionBoundary) {
        return new ScriptRunDispatchService(scriptRepository, scriptCredentialSource, sessionRepository,
                scriptArtefactStore(artefactStoreAccess, transactionBoundary), Clock.systemUTC());
    }

    @Bean
    public ScriptScheduler scriptScheduler(
            ScriptRepository scriptRepository,
            ScriptExecutionService scriptExecutionService) {
        return new ScriptScheduler(scriptRepository, scriptExecutionService);
    }

    /**
     * Script bodies and outputs live under {@code UI2_SCRIPT_STORE_ROOT} (deploy: a directory on the writable report-store
     * claim): the recovery volume is mounted read-only in the service (BK-17), so it can hold neither. Without that
     * variable the recovery-volume store is used (tests and local runs).
     */
    static ArtefactStore scriptArtefactStore(
            DeviceCompositionConfiguration.ArtefactStoreAccess artefactStoreAccess,
            TransactionBoundary transactionBoundary) {
        String root = OverwatchEnv.getOrDefault("UI2_SCRIPT_STORE_ROOT", "");
        String keyFile = OverwatchEnv.getOrDefault("UI2_ARTEFACT_STORE_KEY_FILE", "");
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
