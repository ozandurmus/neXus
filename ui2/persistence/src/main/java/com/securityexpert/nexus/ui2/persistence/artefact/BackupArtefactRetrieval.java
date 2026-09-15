package com.securityexpert.nexus.ui2.persistence.artefact;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * The one {@link BackupArtefactRetrievalPort} implementation (14I OR-1..
 * OR-5): checks {@code role:backup_admin} and the reason length, decrypts
 * through {@link ArtefactStore#retrieve} to the operator's named path and
 * nowhere else, and writes the typed audit row.
 *
 * <p>The role check re-implements {@code service.security.RbacEvaluator}'s
 * exact E4 decision (unbound token / stale-or-missing authz state / group
 * intersection) rather than depending on it -- {@code service} is not on
 * {@code persistence}'s dependency graph (DIR rules; {@code service}
 * depends inward on {@code persistence}, never the reverse), so the CLI
 * path this class serves needs its own copy of the same algorithm. Keep
 * the two in sync if the E4 decision ever changes.</p>
 *
 * <p>{@code gzip} is hardcoded {@code false}: every artefact this movement
 * writes (backup archives, {@code check_point} configuration reads) is
 * opened through {@code ArtefactStore.open(..., false)} -- {@code
 * backup_artefact} carries no {@code compression} column of its own to
 * recover this from generically (unlike {@code configuration_artefact}),
 * so a future artefact class that gzips must extend this record and this
 * check together.</p>
 */
public final class BackupArtefactRetrieval implements BackupArtefactRetrievalPort {

    private static final int MIN_REASON_LENGTH = 8;

    private final ArtefactStore artefactStore;
    private final BackupArtefactManifestRepository manifestRepository;
    private final BackupArtefactRetrievalRepository retrievalRepository;
    private final RoleBindingRepository roleBindingRepository;
    private final ActorAuthzStateRepository actorAuthzStateRepository;
    private final GroupReferenceCipher groupReferenceCipher;

    public BackupArtefactRetrieval(ArtefactStore artefactStore, BackupArtefactManifestRepository manifestRepository,
            BackupArtefactRetrievalRepository retrievalRepository, RoleBindingRepository roleBindingRepository,
            ActorAuthzStateRepository actorAuthzStateRepository, GroupReferenceCipher groupReferenceCipher) {
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.manifestRepository = Objects.requireNonNull(manifestRepository, "manifestRepository");
        this.retrievalRepository = Objects.requireNonNull(retrievalRepository, "retrievalRepository");
        this.roleBindingRepository = Objects.requireNonNull(roleBindingRepository, "roleBindingRepository");
        this.actorAuthzStateRepository =
                Objects.requireNonNull(actorAuthzStateRepository, "actorAuthzStateRepository");
        this.groupReferenceCipher = Objects.requireNonNull(groupReferenceCipher, "groupReferenceCipher");
    }

    @Override
    public RetrieveResult retrieve(String actorFingerprint, String artefactId, String destinationPath,
            String reason) {
        if (reason == null || reason.strip().length() < MIN_REASON_LENGTH) {
            return new RetrieveResult.ReasonTooShort();
        }
        if (!hasActiveBackupAdminBinding(actorFingerprint)) {
            return new RetrieveResult.RoleRefused();
        }
        Optional<BackupArtefactManifestRepository.RetrievalManifest> manifest =
                manifestRepository.findForRetrieval(artefactId);
        if (manifest.isEmpty()) {
            return new RetrieveResult.ArtefactNotFound();
        }

        try {
            retrievalRepository.record(UUID.randomUUID().toString(), artefactId, reason, destinationPath,
                    actorFingerprint, "backup_artefact_retrieved");
        } catch (RuntimeException e) {
            return new RetrieveResult.AuditRefused();
        }

        Path destination = Path.of(destinationPath);
        try (InputStream decrypted = artefactStore.retrieve(new ArtefactRef(artefactId), manifest.get().wrappedDataKey(),
                false);
                OutputStream out = Files.newOutputStream(destination)) {
            decrypted.transferTo(out);
        } catch (IOException e) {
            return new RetrieveResult.IoFailure(String.valueOf(e.getMessage()));
        }

        return new RetrieveResult.Ok(destinationPath);
    }

    private boolean hasActiveBackupAdminBinding(String actorFingerprint) {
        List<RoleBindingRecord> activeBindings = roleBindingRepository.findActiveByToken(RoleToken.BACKUP_ADMIN.token());
        if (activeBindings.isEmpty()) {
            return false;
        }
        Optional<ActorAuthzStateRecord> authzState = actorAuthzStateRepository.find(actorFingerprint);
        if (authzState.isEmpty() || !authzState.get().isFresh(Instant.now())) {
            return false;
        }
        var actorGroupReferences = authzState.get().groupReferences();
        for (RoleBindingRecord binding : activeBindings) {
            String plaintextGroupReference = groupReferenceCipher.decrypt(binding.groupReferenceEncrypted());
            if (actorGroupReferences.contains(plaintextGroupReference)) {
                return true;
            }
        }
        return false;
    }
}
