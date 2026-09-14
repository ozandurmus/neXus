package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRecord;
import com.securityexpert.nexus.ui2.persistence.identity.ActorAuthzStateRepository;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRecord;
import com.securityexpert.nexus.ui2.persistence.identity.RoleBindingRepository;
import com.securityexpert.nexus.ui2.platform.BackupArtefactRetrievalPort.RetrieveResult;
import com.securityexpert.nexus.ui2.platform.GroupReferenceCipher;
import com.securityexpert.nexus.ui2.platform.RoleToken;

/**
 * 14I OR-1..OR-5, AC-4: the CLI retrieval's own business logic -- {@code
 * role:backup_admin} refusal, the reason-length gate, a successful
 * decrypt-to-path with its own audit row, and an unknown artefact id.
 * Exercised directly against {@link BackupArtefactRetrieval} (no real DB,
 * no real artefact store) since {@code ui2/cli}'s own {@code
 * backup-retrieve} command is a thin wrapper over exactly this port.
 */
class BackupArtefactRetrievalTest {

    private static final String ARTEFACT_ID = "artefact-1";
    private static final String ACTOR = "actor-1";
    private static final String VALID_REASON = "operator investigating a device incident";
    private static final byte[] WRAPPED_KEY = new byte[] {1, 2, 3};

    private static final class FakeArtefactStore implements ArtefactStore {
        String decryptedContent = "plaintext-configuration-with-secret-lines";
        boolean throwOnRetrieve;

        @Override
        public ArtefactHandle open(String deviceId, String jobId, String vendor, boolean gzip) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public InputStream retrieve(ArtefactRef ref, byte[] wrappedDataKey, boolean gzip) throws IOException {
            if (throwOnRetrieve) {
                throw new IOException("simulated store failure");
            }
            return new ByteArrayInputStream(decryptedContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private static final class FakeManifestRepository implements BackupArtefactManifestRepository {
        Optional<RetrievalManifest> manifest = Optional.of(new RetrievalManifest(ARTEFACT_ID, WRAPPED_KEY));

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
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public List<BackupArtefactSummary> findAll(String artefactClass) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public Optional<RetrievalManifest> findForRetrieval(String artefactId) {
            return artefactId.equals(ARTEFACT_ID) ? manifest : Optional.empty();
        }
    }

    private record RecordedRetrieval(String retrievalId, String artefactId, String reason, String destinationPath,
            String actorFingerprint, String actionId) {
    }

    private static final class FakeRetrievalRepository implements BackupArtefactRetrievalRepository {
        final List<RecordedRetrieval> recorded = new ArrayList<>();

        @Override
        public void record(String retrievalId, String artefactId, String reason, String destinationPath,
                String actorFingerprint, String actionId) {
            recorded.add(new RecordedRetrieval(retrievalId, artefactId, reason, destinationPath, actorFingerprint,
                    actionId));
        }
    }

    private static final class FakeRoleBindingRepository implements RoleBindingRepository {
        List<RoleBindingRecord> activeBackupAdminBindings = List.of();

        @Override
        public List<RoleBindingRecord> findActiveByToken(String roleToken) {
            return RoleToken.BACKUP_ADMIN.token().equals(roleToken) ? activeBackupAdminBindings : List.of();
        }

        @Override
        public Optional<RoleBindingRecord> find(String bindingId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public boolean hasAnyActiveBinding(String roleToken) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public String create(String bindingId, String roleToken, byte[] groupReferenceEncrypted,
                String groupReferenceKeyId, String createdByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void revoke(String bindingId, String revokedByActorFingerprint, String actionId) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class FakeActorAuthzStateRepository implements ActorAuthzStateRepository {
        Optional<ActorAuthzStateRecord> state = Optional.empty();

        @Override
        public Optional<ActorAuthzStateRecord> find(String actorFingerprint) {
            return actorFingerprint.equals(ACTOR) ? state : Optional.empty();
        }

        @Override
        public void upsert(String actorFingerprint, Set<String> groupReferences, Instant resolvedAt,
                Instant validUntil) {
            throw new UnsupportedOperationException("not used by this test");
        }

        @Override
        public void delete(String actorFingerprint) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static GroupReferenceCipher testCipher() {
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 9);
        return GroupReferenceCipher.fromBase64Key(java.util.Base64.getEncoder().encodeToString(key));
    }

    private static final class Harness {
        final FakeArtefactStore artefactStore = new FakeArtefactStore();
        final FakeManifestRepository manifestRepository = new FakeManifestRepository();
        final FakeRetrievalRepository retrievalRepository = new FakeRetrievalRepository();
        final FakeRoleBindingRepository roleBindingRepository = new FakeRoleBindingRepository();
        final FakeActorAuthzStateRepository actorAuthzStateRepository = new FakeActorAuthzStateRepository();
        final GroupReferenceCipher cipher = testCipher();
        final BackupArtefactRetrieval retrieval = new BackupArtefactRetrieval(artefactStore, manifestRepository,
                retrievalRepository, roleBindingRepository, actorAuthzStateRepository, cipher);

        /** Grants role:backup_admin to {@link #ACTOR} by making its resolved group set contain the one bound (decrypted) group reference. */
        void grantBackupAdmin() {
            byte[] encrypted = cipher.encrypt("cn=backup-admins,dc=example,dc=com");
            roleBindingRepository.activeBackupAdminBindings = List.of(new RoleBindingRecord("binding-1",
                    RoleToken.BACKUP_ADMIN.token(), encrypted, "key-1", "security-admin-1", Instant.now(),
                    Optional.empty(), Optional.empty()));
            actorAuthzStateRepository.state = Optional.of(new ActorAuthzStateRecord(ACTOR,
                    Set.of("cn=backup-admins,dc=example,dc=com"), Instant.now(), Instant.now().plusSeconds(3600)));
        }
    }

    @Test
    void refusesWithoutAnActiveBackupAdminBinding() {
        Harness harness = new Harness(); // no grant

        RetrieveResult result = harness.retrieval.retrieve(ACTOR, ARTEFACT_ID, "/tmp/out.txt", VALID_REASON);

        assertTrue(result instanceof RetrieveResult.RoleRefused, "expected RoleRefused, got " + result);
        assertTrue(harness.retrievalRepository.recorded.isEmpty(), "a refused retrieval is never audited as a success");
    }

    @Test
    void refusesAReasonShorterThanEightCharacters() {
        Harness harness = new Harness();
        harness.grantBackupAdmin();

        RetrieveResult result = harness.retrieval.retrieve(ACTOR, ARTEFACT_ID, "/tmp/out.txt", "short");

        assertTrue(result instanceof RetrieveResult.ReasonTooShort, "expected ReasonTooShort, got " + result);
    }

    @Test
    void refusesAnUnknownArtefactId() {
        Harness harness = new Harness();
        harness.grantBackupAdmin();

        RetrieveResult result = harness.retrieval.retrieve(ACTOR, "unknown-artefact", "/tmp/out.txt", VALID_REASON);

        assertTrue(result instanceof RetrieveResult.ArtefactNotFound, "expected ArtefactNotFound, got " + result);
    }

    @Test
    void decryptsToTheNamedPathAndWritesItsOwnAuditRow(@TempDir Path tempDir) throws IOException {
        Harness harness = new Harness();
        harness.grantBackupAdmin();
        Path destination = tempDir.resolve("retrieved-configuration.txt");

        RetrieveResult result = harness.retrieval.retrieve(ACTOR, ARTEFACT_ID, destination.toString(), VALID_REASON);

        assertTrue(result instanceof RetrieveResult.Ok, "expected Ok, got " + result);
        assertEquals(destination.toString(), ((RetrieveResult.Ok) result).destinationPath());
        assertEquals(harness.artefactStore.decryptedContent, Files.readString(destination));

        assertEquals(1, harness.retrievalRepository.recorded.size(), "OR-3: the retrieval is audited as its own typed action");
        RecordedRetrieval audited = harness.retrievalRepository.recorded.get(0);
        assertEquals(ARTEFACT_ID, audited.artefactId());
        assertEquals(VALID_REASON, audited.reason());
        assertEquals(destination.toString(), audited.destinationPath());
        assertEquals(ACTOR, audited.actorFingerprint());
    }
}
