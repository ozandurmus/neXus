package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactRetrievalRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;

/**
 * PO decision record 2026-09-22: the browser download path keeps 14I OR-3's
 * order -- the audit row lands before the first byte is read, and an audit
 * failure sends nothing.
 */
class BackupDownloadServiceTest {

    private static final String ARTEFACT_ID = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    private static ArtefactStore store(Path root) {
        return new FileArtefactStore(root, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32])));
    }

    private static ArtefactStore.ArtefactMetadata write(ArtefactStore store, byte[] plaintext) throws IOException {
        try (ArtefactStore.ArtefactHandle handle = store.open("dev-1", "job-1", "check_point", false)) {
            handle.sink().write(plaintext);
            return handle.finish();
        }
    }

    private static final class Manifests implements BackupArtefactManifestRepository {
        Optional<RetrievalManifest> retrieval = Optional.empty();
        Optional<BackupArtefactSummary> summary = Optional.empty();

        @Override
        public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
        }

        @Override
        public Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass) {
            return Optional.empty();
        }

        @Override
        public List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
            return List.of();
        }

        @Override
        public List<BackupArtefactSummary> findAll(String artefactClass) {
            return List.of();
        }

        @Override
        public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            return retrieval.filter(m -> m.artefactId().equals(artefactId));
        }

        @Override
        public Optional<BackupArtefactSummary> findSummary(String artefactId) {
            return summary;
        }
    }

    private static final class Audits implements BackupArtefactRetrievalRepository {
        final List<String> rows = new ArrayList<>();
        boolean fail;

        @Override
        public void record(String retrievalId, String artefactId, String reason, String destinationPath,
                String actorFingerprint, String actionId) {
            if (fail) {
                throw new IllegalStateException("audit_log insert refused");
            }
            rows.add(artefactId + "|" + reason + "|" + destinationPath + "|" + actorFingerprint + "|" + actionId);
        }
    }

    @Test
    void refusesAShortReasonBeforeTouchingAnything(@TempDir Path root) {
        Manifests manifests = new Manifests();
        Audits audits = new Audits();
        BackupDownloadService service = new BackupDownloadService(store(root), manifests, audits);

        assertInstanceOf(BackupDownloadService.Outcome.ReasonTooShort.class, service.prepare("actor", ARTEFACT_ID, "short"));
        assertTrue(audits.rows.isEmpty());
    }

    @Test
    void auditRowIsWrittenBeforeTheBytesAndCarriesTheReason(@TempDir Path root) throws IOException {
        ArtefactStore store = store(root);
        byte[] plaintext = "gaia-archive-bytes".getBytes(StandardCharsets.UTF_8);
        ArtefactStore.ArtefactMetadata stored = write(store, plaintext);
        Manifests manifests = new Manifests();
        manifests.retrieval = Optional.of(new BackupArtefactManifestRepository.RetrievalManifest(ARTEFACT_ID,
                stored.ref().value(), stored.wrappedDataKey()));
        manifests.summary = Optional.of(new BackupArtefactManifestRepository.BackupArtefactSummary(ARTEFACT_ID, "dev-1",
                Instant.parse("2026-09-22T11:16:12Z"), plaintext.length, stored.plaintextSha256(), "V1",
                Optional.empty(), "check_point", "backup"));
        Audits audits = new Audits();
        BackupDownloadService service = new BackupDownloadService(store, manifests, audits);

        BackupDownloadService.Outcome outcome = service.prepare("actor-fp", ARTEFACT_ID, "DR drill ticket SEC-4091");

        BackupDownloadService.Outcome.Ready ready = assertInstanceOf(BackupDownloadService.Outcome.Ready.class, outcome);
        try (InputStream in = ready.stream()) {
            assertArrayEquals(plaintext, in.readAllBytes());
        }
        assertEquals("check_point", ready.vendor());
        assertEquals(plaintext.length, ready.plaintextBytes());
        assertEquals(1, audits.rows.size());
        assertEquals(ARTEFACT_ID + "|DR drill ticket SEC-4091|browser|actor-fp|backup_artefact_downloaded",
                audits.rows.get(0));
    }

    @Test
    void anAuditFailureRefusesWithNoStreamOpened(@TempDir Path root) throws IOException {
        ArtefactStore store = store(root);
        ArtefactStore.ArtefactMetadata stored = write(store, "x".getBytes(StandardCharsets.UTF_8));
        Manifests manifests = new Manifests();
        manifests.retrieval = Optional.of(new BackupArtefactManifestRepository.RetrievalManifest(ARTEFACT_ID,
                stored.ref().value(), stored.wrappedDataKey()));
        Audits audits = new Audits();
        audits.fail = true;
        BackupDownloadService service = new BackupDownloadService(store, manifests, audits);

        assertInstanceOf(BackupDownloadService.Outcome.AuditRefused.class,
                service.prepare("actor", ARTEFACT_ID, "DR drill ticket SEC-4091"));
    }

    @Test
    void unknownArtefactIsNotFoundAndNotAudited(@TempDir Path root) {
        Audits audits = new Audits();
        BackupDownloadService service = new BackupDownloadService(store(root), new Manifests(), audits);

        assertInstanceOf(BackupDownloadService.Outcome.ArtefactNotFound.class,
                service.prepare("actor", ARTEFACT_ID, "DR drill ticket SEC-4091"));
        assertTrue(audits.rows.isEmpty());
    }

    @Test
    void aServiceWithoutAStoreReportsThatRatherThanFailing() {
        BackupDownloadService service = new BackupDownloadService(null, new Manifests(), new Audits());

        assertInstanceOf(BackupDownloadService.Outcome.StoreUnavailable.class,
                service.prepare("actor", ARTEFACT_ID, "DR drill ticket SEC-4091"));
    }
}
