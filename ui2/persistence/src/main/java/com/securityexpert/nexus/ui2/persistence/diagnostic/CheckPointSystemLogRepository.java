package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;

/**
 * The Check Point half of the system-log phase B storage (V160, V161; DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections
 * 4.1, 5.2, 7, 8). It shares the tables, the artefact store and the audited, lease-fenced transaction shape of
 * {@link SystemLogCollectionRepository}; what it adds is what a byte-offset source, a listing and a whole-file fetch need:
 *
 * <ul>
 *   <li>a byte-offset cursor (inode, offset, anchor HMAC, family snapshot, device epoch);</li>
 *   <li>one chunk per ranged read, written with the cursor it advances in one transaction (section 4.1: "the cursor
 *       advances only in the same transaction that records the chunk row"), so each read is stored before the next is
 *       sent;</li>
 *   <li>the crash/dump listing state and its alerts (section 7);</li>
 *   <li>whole-file fetch rows (section 8);</li>
 *   <li>the Spike Detective data-directory state (section 8.3, V167): one row per directory a C4 listing printed, the
 *       baseline included, keyed by the name HMAC.</li>
 * </ul>
 *
 * No method takes or stores a clear file name, path or log line: names are HMACs, the family snapshot holds base
 * names only (the family pattern's own names; anything else as a digest token).
 */
public interface CheckPointSystemLogRepository extends SystemLogCollectionRepository {
    Pattern INODE = Pattern.compile("[0-9]{1,20}");
    Pattern HMAC = Pattern.compile("[0-9a-f]{64}");

    /** One member of the stored family snapshot: a base name (or a {@code #}-prefixed digest token), inode, size, mtime. */
    record SnapshotEntry(String name, String inode, long size, long mtime) {
        public SnapshotEntry {
            if (name == null || name.isEmpty() || name.contains("/")) throw new IllegalArgumentException("snapshot name shape");
            if (inode == null || !INODE.matcher(inode).matches()) throw new IllegalArgumentException("snapshot inode shape");
            if (size < 0 || mtime < 0) throw new IllegalArgumentException("snapshot counters");
        }
    }

    /** The byte-offset cursor of one (device, Check Point source). Inodes are digits-only text (identity law). */
    record CpCursor(String inode, long offsetBytes, String anchorHmac, List<SnapshotEntry> familySnapshot,
            Long deviceEpochAtRead) {
        public CpCursor {
            if (inode == null || !INODE.matcher(inode).matches()) throw new IllegalArgumentException("cursor inode shape");
            if (offsetBytes < 0) throw new IllegalArgumentException("cursor offset");
            if (anchorHmac != null && !HMAC.matcher(anchorHmac).matches()) throw new IllegalArgumentException("anchor shape");
            familySnapshot = List.copyOf(familySnapshot);
        }
    }

    Pattern RULE_SET = Pattern.compile("[a-z0-9][a-z0-9._-]{0,31}");

    /**
     * One stored Check Point chunk: the shared chunk fields, the inode of the file it came from and the id of the
     * redaction rule set that produced the stored bytes (V161 {@code redaction_rule_set}).
     */
    record CpChunk(Chunk chunk, String inode, String redactionRuleSet) {
        public CpChunk {
            if (inode == null || !INODE.matcher(inode).matches()) throw new IllegalArgumentException("chunk inode shape");
            if (redactionRuleSet == null || !RULE_SET.matcher(redactionRuleSet).matches())
                throw new IllegalArgumentException("rule set shape");
        }
    }

    /** How a chunk write ended. */
    enum ChunkWrite {
        /** The chunk, its manifest, the cursor and the gaps are written. */
        STORED,
        /** The same content (keyed digest) of the same file at the same offset is already stored (an at-least-once re-read): only the
         *  cursor and the gaps are written; the caller discards its duplicate artefact. */
        DUPLICATE,
        /** A different chunk already holds this (device, source, inode, start offset): nothing is written. */
        IDENTITY_CONFLICT,
        /** The lease fence refused the write (stale worker or job no longer executing): nothing is written. */
        NOT_RECORDED
    }

    Optional<CpCursor> findCpCursor(String deviceId, String sourceKey);

    /**
     * One ranged read's chunk, the cursor it advances to and the gap rows that belong to that advance, in one audited
     * transaction fenced to the job's current lease. No run row is written here: the source's run row is written once
     * by {@link #recordCpSource} when the source ends.
     */
    ChunkWrite recordChunk(String jobId, long leaseEpoch, String deviceId, String sourceKey, CpChunk chunk, CpCursor cursor,
            List<Gap> gaps);

    /**
     * The source's run row, then (optionally) a cursor that moved without a chunk (a verified empty continuation, a
     * restart on an empty file, a refreshed family snapshot) and the remaining gap rows, in one fenced transaction.
     */
    boolean recordCpSource(String jobId, long leaseEpoch, String deviceId, String sourceKey, Run run,
            Optional<CpCursor> cursor, List<Gap> gaps);

    /** The outcome of the device's newest run row for a source, if any. */
    Optional<String> lastOutcome(String deviceId, String sourceKey);

    // ---------------------------------------------------------------- crash and dump listing (section 7)

    /** One live {@code device_crash_entry} row (not removed). */
    record CrashEntry(String entryId, String directory, String nameHmac, String process, long size, long mtime) {
    }

    /** One listed entry to insert (a new file, or the baseline). */
    record NewCrashEntry(String entryId, String directory, String nameHmac, String process, long size, long mtime,
            boolean baseline) {
    }

    /** One alert row: one per (device, run, process). */
    record CrashAlert(String alertId, String process, int fileCount, long newestMtime) {
    }

    /** What one listing changes: new rows, rows seen again (with their current size), rows gone, and the alerts. */
    record CrashListingWrite(List<NewCrashEntry> inserted, List<CrashEntry> seen, List<String> removedEntryIds,
            List<CrashAlert> alerts) {
        public CrashListingWrite {
            inserted = List.copyOf(inserted); seen = List.copyOf(seen); removedEntryIds = List.copyOf(removedEntryIds);
            alerts = List.copyOf(alerts);
        }
    }

    /** True once a listing of this device has been recorded (any outcome but UNKNOWN): later new files alert. */
    boolean crashBaselineTaken(String deviceId);

    List<CrashEntry> liveCrashEntries(String deviceId);

    /** The {@code cp_crash_listing} run row, then the listing changes and the alerts, in one fenced transaction. */
    boolean recordCrashListing(String jobId, long leaseEpoch, String deviceId, Run run, CrashListingWrite write);

    // ---------------------------------------------------------------- whole-file fetches (section 8)

    /**
     * One {@code system_log_file} row; {@code nameTime} is device-local text ({@code yyyy-MM-dd HH:mm:ss}) or null;
     * {@code contentHmac} is the keyed content identity of the file as received (V161; domain
     * {@link SystemLogCollectionRepository#FILE_CONTENT_DOMAIN}).
     */
    record FileRow(String fileId, String kind, String remoteNameHmac, String nameTime, long size, long mtime, String status,
            List<String> flags, String contentHmac, BackupArtefactManifestRecord manifest, java.time.Instant expiresAt) {
        public FileRow {
            if (contentHmac == null || !HMAC.matcher(contentHmac).matches())
                throw new IllegalArgumentException("file content hmac shape");
        }
    }

    /** True when this (kind, name, mtime) is already stored: a whole file is fetched once. */
    boolean fileStored(String deviceId, String kind, String remoteNameHmac, long mtime);

    /** The run row of a whole-file source, then the manifest and file row (when fetched) and the gap rows. */
    boolean recordFile(String jobId, long leaseEpoch, String deviceId, String sourceKey, Run run, Optional<FileRow> file,
            List<Gap> gaps);

    // ---------------------------------------------------------------- Spike Detective data (section 8.3, V167)

    /** The V167 {@code system_log_spike_directory.state} vocabulary. */
    enum SpikeDirectoryState {
        /** Listed by the device's first C4 listing: "present before monitoring", never fetched. */
        BASELINE,
        /** C5 listed it and every member on the fetch list was fetched and stored (or none was on it). */
        COLLECTED,
        /** C5 listed it but no member is on the fetch list: nothing fetched, the member classes are recorded. */
        NOTHING_TO_FETCH,
        /** The fetch-list members exceed the 16 MiB per-directory bound (listed, or grown while fetched): not stored. */
        TOO_LARGE,
        /** The name is outside the section 8.3 safe form: no C5 is sent for it. */
        NAME_REJECTED,
        /** Gone between C4 and C5. */
        ABSENT
    }

    Pattern NAME_CLASS = Pattern.compile("[A-Za-z0-9._:+?-]{1,160}");

    /**
     * One data directory. {@code nameHmac} is the keyed digest of its name (the same value as the
     * {@code system_log_file.remote_name_hmac} of its stored archive); {@code nameClass} its name class (closed
     * vocabulary, never the name); {@code membersJson} the per-class member summary (class, count, bytes, fetched
     * count; never a member name); {@code fileId} the {@code system_log_file} row of a stored archive.
     */
    record SpikeDirectory(String directoryId, String nameHmac, String nameClass, long mtime, SpikeDirectoryState state,
            int memberCount, int fetchedFiles, long memberBytes, long fetchedBytes, String membersJson, String fileId) {
        public SpikeDirectory {
            if (nameHmac == null || !HMAC.matcher(nameHmac).matches()) throw new IllegalArgumentException("directory hmac shape");
            if (nameClass == null || !NAME_CLASS.matcher(nameClass).matches()) throw new IllegalArgumentException("name class shape");
            if (state == null || mtime < 0 || memberCount < 0 || fetchedFiles < 0 || memberBytes < 0 || fetchedBytes < 0)
                throw new IllegalArgumentException("directory counters");
            if (membersJson == null || !membersJson.startsWith("{")) throw new IllegalArgumentException("members shape");
        }

        /** A listed directory that is decided without a C5 (the baseline, a rejected name). */
        public static SpikeDirectory listed(String directoryId, String nameHmac, String nameClass, long mtime,
                SpikeDirectoryState state) {
            return new SpikeDirectory(directoryId, nameHmac, nameClass, mtime, state, 0, 0, 0, 0, "{}", null);
        }
    }

    /** How a directory write ended. */
    enum SpikeWrite {
        /** The directory row (and its archive's manifest and file row, when present) is written. */
        STORED,
        /** The directory is already recorded for this device: nothing is written; the caller discards its artefact. */
        DUPLICATE,
        /** The lease fence refused the write: nothing is written. */
        NOT_RECORDED
    }

    /**
     * True once the device has a recorded Spike Detective listing of a present parent directory (a run row of
     * {@code cp_spike_data} with an outcome other than UNKNOWN, ABSENT or UNSUPPORTED): later directories are new.
     */
    boolean spikeBaselineTaken(String deviceId);

    /** The name HMACs of every directory recorded for the device (baseline included). */
    java.util.Set<String> spikeDirectoryHmacs(String deviceId);

    /**
     * One processed directory in its own fenced, audited transaction, before the next directory's C5 is sent: the
     * archive's manifest and {@code system_log_file} row (when {@code file} is present) and the directory row.
     */
    SpikeWrite recordSpikeDirectory(String jobId, long leaseEpoch, String deviceId, SpikeDirectory directory,
            Optional<FileRow> file);

    /**
     * The {@code cp_spike_data} run row, then the directories decided without a C5 (the baseline, rejected names; an
     * already recorded name is left as it is) and the gap rows, in one fenced transaction.
     */
    boolean recordSpikeListing(String jobId, long leaseEpoch, String deviceId, Run run, List<SpikeDirectory> listed,
            List<Gap> gaps);
}
