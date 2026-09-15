package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;

/**
 * BK-14/OR-1: {@link BackupController#toSummaryBody} must never surface a
 * server-side storage location, whatever shape the manifest repository
 * returns -- exercised against the real {@link BackupArtefactSummary}
 * record (no synthetic opaque-only fixture standing in for it), so a
 * regression that widens the summary or response body to carry a path is
 * caught here rather than only by a route-name grep.
 */
class BackupControllerTest {

    private static final class FakeManifestRepository implements BackupArtefactManifestRepository {
        List<BackupArtefactSummary> rows = List.of();

        @Override
        public void record(BackupArtefactManifestRecord manifest, String actorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<PlaintextDigestSummary> findLatestPlaintextDigest(String deviceId, String artefactClass) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<BackupArtefactSummary> findByDevice(String deviceId, String artefactClass) {
            return rows;
        }

        @Override
        public List<BackupArtefactSummary> findAll(String artefactClass) {
            return rows;
        }

        @Override
        public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    /** A real FileArtefactStore-shaped id (C7 section 3.2: 64 lowercase hex, the ciphertext's own sha256). */
    private static final String OPAQUE_ARTEFACT_ID = "9f2c1a7e".repeat(8);

    @Test
    void fleetBackupsCarriesTheOpaqueIdAndNeverAStorageLocation() {
        FakeManifestRepository repo = new FakeManifestRepository();
        repo.rows = List.of(new BackupArtefactSummary(OPAQUE_ARTEFACT_ID, "device-1",
                Instant.parse("2026-09-15T00:00:00Z"), 123L, "a".repeat(64), "V1", Optional.of("first")));
        // BackupCollectService (BK-1/BK-11/BK-12 admission gates) is reserved for a different
        // movement's scope and is never reached by the GET routes this test exercises.
        BackupController controller = new BackupController(null, repo);

        ResponseEntity<Map<String, Object>> response = controller.fleetBackups();

        List<?> backups = (List<?>) response.getBody().get("backups");
        assertEquals(1, backups.size());
        Map<?, ?> entry = (Map<?, ?>) backups.get(0);
        assertEquals(OPAQUE_ARTEFACT_ID, entry.get("artefact_id"));
        assertFalse(String.valueOf(entry.get("artefact_id")).contains("/"),
                "artefact_id must never be a filesystem path");
        assertFalse(entry.containsKey("recovery_volume_path"),
                "no summary body field ever carries the server-side recovery location");
    }

    @Test
    void deviceBackupsCarriesTheOpaqueIdAndNeverAStorageLocation() {
        FakeManifestRepository repo = new FakeManifestRepository();
        repo.rows = List.of(new BackupArtefactSummary(OPAQUE_ARTEFACT_ID, "device-1",
                Instant.parse("2026-09-15T00:00:00Z"), 123L, "a".repeat(64), "V1", Optional.empty()));
        BackupController controller = new BackupController(null, repo);

        ResponseEntity<Map<String, Object>> response = controller.deviceBackups("device-1");

        List<?> backups = (List<?>) response.getBody().get("backups");
        Map<?, ?> entry = (Map<?, ?>) backups.get(0);
        assertEquals(OPAQUE_ARTEFACT_ID, entry.get("artefact_id"));
        assertFalse(entry.containsKey("recovery_volume_path"));
    }
}
