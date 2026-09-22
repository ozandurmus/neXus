package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;

class BackupCompareServiceTest {

    private static BackupArtefactManifestRepository.BackupArtefactSummary summary(String id, String deviceId) {
        return new BackupArtefactManifestRepository.BackupArtefactSummary(id, deviceId, Instant.EPOCH, 10, "d", "V1",
                Optional.empty(), "check_point", "backup");
    }

    private static BackupArtefactEntryRepository.Entry file(String path, String digest, long bytes) {
        return new BackupArtefactEntryRepository.Entry(path, BackupArtefactEntryRepository.EntryType.FILE, bytes,
                Optional.of(digest));
    }

    private static final class Manifests implements BackupArtefactManifestRepository {
        final Map<String, BackupArtefactSummary> summaries = new HashMap<>();

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
            return Optional.empty();
        }

        @Override
        public Optional<BackupArtefactSummary> findSummary(String artefactId) {
            return Optional.ofNullable(summaries.get(artefactId));
        }
    }

    private static final class Entries implements BackupArtefactEntryRepository {
        final Map<String, List<Entry>> entries = new HashMap<>();
        final Map<String, Listing> listings = new HashMap<>();

        void listed(String id, List<Entry> rows) {
            entries.put(id, rows);
            listings.put(id, new Listing(id, true, rows.size(), Optional.empty(), Instant.EPOCH));
        }

        @Override
        public void recordListed(String artefactId, List<Entry> rows) {
            listed(artefactId, rows);
        }

        @Override
        public void recordFailed(String artefactId, String reason) {
            listings.put(artefactId, new Listing(artefactId, false, 0, Optional.of(reason), Instant.EPOCH));
        }

        @Override
        public Optional<Listing> findListing(String artefactId) {
            return Optional.ofNullable(listings.get(artefactId));
        }

        @Override
        public List<Entry> findEntries(String artefactId) {
            return entries.getOrDefault(artefactId, List.of());
        }
    }

    @Test
    void classifiesAddedRemovedChangedAndUnchangedByDigest() {
        Manifests manifests = new Manifests();
        manifests.summaries.put("a", summary("a", "dev-1"));
        manifests.summaries.put("b", summary("b", "dev-1"));
        Entries entries = new Entries();
        entries.listed("a", List.of(file("conf/objects.C", "aaa", 10), file("conf/rulebases.fws", "bbb", 20), file("etc/old", "ccc", 1)));
        entries.listed("b", List.of(file("conf/objects.C", "aaa", 10), file("conf/rulebases.fws", "bbb2", 22), file("etc/new", "ddd", 2)));

        BackupCompareService.Outcome outcome = new BackupCompareService(manifests, entries).compare("a", "b");

        BackupCompareService.Outcome.Compared compared = assertInstanceOf(BackupCompareService.Outcome.Compared.class, outcome);
        assertFalse(compared.identical());
        assertEquals(1, compared.unchanged());
        assertEquals(List.of("etc/new"), compared.added());
        assertEquals(List.of("etc/old"), compared.removed());
        assertEquals(List.of(new BackupCompareService.Changed("conf/rulebases.fws", 20, 22)), compared.changed());
    }

    @Test
    void twoIdenticalListingsAreIdentical() {
        Manifests manifests = new Manifests();
        manifests.summaries.put("a", summary("a", "dev-1"));
        manifests.summaries.put("b", summary("b", "dev-1"));
        Entries entries = new Entries();
        entries.listed("a", List.of(file("x", "1", 1)));
        entries.listed("b", List.of(file("x", "1", 1)));

        BackupCompareService.Outcome.Compared compared = assertInstanceOf(BackupCompareService.Outcome.Compared.class,
                new BackupCompareService(manifests, entries).compare("a", "b"));
        assertTrue(compared.identical());
    }

    @Test
    void refusesDifferentDevicesAndReportsAnUnlistedSideInsteadOfInventingADiff() {
        Manifests manifests = new Manifests();
        manifests.summaries.put("a", summary("a", "dev-1"));
        manifests.summaries.put("b", summary("b", "dev-2"));
        manifests.summaries.put("c", summary("c", "dev-1"));
        Entries entries = new Entries();
        entries.listed("a", List.of(file("x", "1", 1)));
        entries.recordFailed("c", "IOException: not a tar header");
        BackupCompareService service = new BackupCompareService(manifests, entries);

        assertInstanceOf(BackupCompareService.Outcome.DifferentDevices.class, service.compare("a", "b"));
        BackupCompareService.Outcome.NotListed notListed =
                assertInstanceOf(BackupCompareService.Outcome.NotListed.class, service.compare("a", "c"));
        assertEquals("c", notListed.artefactId());
        assertEquals(Optional.of("IOException: not a tar header"), notListed.reason());
        assertInstanceOf(BackupCompareService.Outcome.ArtefactNotFound.class, service.compare("a", "zzz"));
    }
}
