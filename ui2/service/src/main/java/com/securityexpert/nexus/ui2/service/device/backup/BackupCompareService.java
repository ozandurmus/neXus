package com.securityexpert.nexus.ui2.service.device.backup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;

/**
 * Backbox-standard "Compare" on V41's listings: two artefacts of the same
 * device, entry sets joined on path, each pair classified by digest. Only
 * names, sizes and digests are ever compared -- never content -- so the
 * verdict is structural ("these files changed"), which is exactly what the
 * listing can honestly support.
 */
public final class BackupCompareService {

    public sealed interface Outcome {
        record Compared(BackupArtefactSummary left, BackupArtefactSummary right, List<String> added,
                List<String> removed, List<Changed> changed, int unchanged, boolean identical) implements Outcome {
        }

        record ArtefactNotFound(String artefactId) implements Outcome {
        }

        record DifferentDevices() implements Outcome {
        }

        /** One side has no LISTED content listing: state and reason are reported, no diff is invented. */
        record NotListed(String artefactId, Optional<String> reason) implements Outcome {
        }
    }

    public record Changed(String path, long leftBytes, long rightBytes) {
    }

    private final BackupArtefactManifestRepository manifestRepository;
    private final BackupArtefactEntryRepository entryRepository;

    public BackupCompareService(BackupArtefactManifestRepository manifestRepository,
            BackupArtefactEntryRepository entryRepository) {
        this.manifestRepository = Objects.requireNonNull(manifestRepository, "manifestRepository");
        this.entryRepository = Objects.requireNonNull(entryRepository, "entryRepository");
    }

    public Outcome compare(String leftId, String rightId) {
        Optional<BackupArtefactSummary> left = manifestRepository.findSummary(leftId);
        if (left.isEmpty()) {
            return new Outcome.ArtefactNotFound(leftId);
        }
        Optional<BackupArtefactSummary> right = manifestRepository.findSummary(rightId);
        if (right.isEmpty()) {
            return new Outcome.ArtefactNotFound(rightId);
        }
        if (!left.get().deviceId().equals(right.get().deviceId())) {
            return new Outcome.DifferentDevices();
        }
        for (String id : List.of(leftId, rightId)) {
            Optional<BackupArtefactEntryRepository.Listing> listing = entryRepository.findListing(id);
            if (listing.isEmpty() || !listing.get().listed()) {
                return new Outcome.NotListed(id, listing.flatMap(BackupArtefactEntryRepository.Listing::reason));
            }
        }
        return diff(left.get(), right.get(), entryRepository.findEntries(leftId), entryRepository.findEntries(rightId));
    }

    static Outcome.Compared diff(BackupArtefactSummary left, BackupArtefactSummary right, List<Entry> leftEntries,
            List<Entry> rightEntries) {
        Map<String, Entry> byPathLeft = new LinkedHashMap<>();
        for (Entry entry : leftEntries) {
            byPathLeft.put(entry.path(), entry);
        }
        Map<String, Entry> byPathRight = new LinkedHashMap<>();
        for (Entry entry : rightEntries) {
            byPathRight.put(entry.path(), entry);
        }
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Changed> changed = new ArrayList<>();
        int unchanged = 0;
        for (Entry l : byPathLeft.values()) {
            Entry r = byPathRight.get(l.path());
            if (r == null) {
                removed.add(l.path());
            } else if (sameContent(l, r)) {
                unchanged++;
            } else {
                changed.add(new Changed(l.path(), l.bytes(), r.bytes()));
            }
        }
        for (Entry r : byPathRight.values()) {
            if (!byPathLeft.containsKey(r.path())) {
                added.add(r.path());
            }
        }
        boolean identical = added.isEmpty() && removed.isEmpty() && changed.isEmpty();
        return new Outcome.Compared(left, right, added, removed, changed, unchanged, identical);
    }

    private static boolean sameContent(Entry l, Entry r) {
        if (l.type() != r.type()) {
            return false;
        }
        if (l.sha256().isPresent() && r.sha256().isPresent()) {
            return l.sha256().get().equalsIgnoreCase(r.sha256().get());
        }
        return l.bytes() == r.bytes(); // directories/symlinks: no digest, same type is enough
    }
}
