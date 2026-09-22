package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V41: the content listing of a backup archive -- entry names, types, sizes
 * and per-entry digests, never content. A derived cache the worker fills
 * once per artefact, best-effort; a listing that failed is recorded as
 * such so the screen can say "not listed" honestly instead of "empty".
 */
public interface BackupArtefactEntryRepository {

    enum EntryType {
        FILE("file"), DIR("dir"), SYMLINK("symlink"), OTHER("other");

        private final String wire;

        EntryType(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static EntryType fromWire(String wire) {
            for (EntryType type : values()) {
                if (type.wire.equals(wire)) {
                    return type;
                }
            }
            return OTHER;
        }
    }

    record Entry(String path, EntryType type, long bytes, Optional<String> sha256) {
    }

    record Listing(String artefactId, boolean listed, int entryCount, Optional<String> reason, Instant listedAt) {
    }

    /** Replaces any previous listing for the artefact in one transaction. */
    void recordListed(String artefactId, List<Entry> entries);

    void recordFailed(String artefactId, String reason);

    Optional<Listing> findListing(String artefactId);

    List<Entry> findEntries(String artefactId);
}
