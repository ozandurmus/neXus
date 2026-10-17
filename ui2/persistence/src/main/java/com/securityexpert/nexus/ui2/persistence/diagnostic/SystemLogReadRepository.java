package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The read side of the system-log programme (V160/V161; DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md sections 5.2 and 14): what
 * the Logs screen and the {@code syslog-*} commands show. Every method returns index rows and counters only. No method
 * returns a clear file name, an inode, an anchor or file-name HMAC, an artefact location or a wrapped key: names exist
 * only as HMACs in these tables (section 12), and the log text itself is reached through {@link #findChunk} plus the
 * artefact store, never from a table.
 */
public interface SystemLogReadRepository {

    /**
     * One source of one device: its cursor (absent for the whole-file and listing sources, and for a source that has
     * only ever failed), the outcome of its newest run, and what is stored for it now. Times are instants; line times
     * elsewhere are device-local text.
     */
    record SourceRow(String sourceKey, boolean hasCursor, Long offsetBytes, Long deviceEpochAtRead,
            String panLastReceiveTime, boolean fastRotating, Long retentionHorizonSeconds, Instant cursorUpdatedAt,
            String lastOutcome, Instant lastRunAt, String lastJobId, Long lastBytesRead, Long lastBytesStored,
            long pendingBytes, long liveChunks, long liveBytes, long liveLines, Instant newestChunkAt) {
    }

    /** The newest collection job of a device: when it ran, how its sources ended, and the line-time window of its chunks. */
    record LastJob(String jobId, Instant at, Map<String, Integer> outcomes, String windowFrom, String windowTo) {
        public LastJob {
            outcomes = Map.copyOf(outcomes);
        }
    }

    /** One chunk index row. {@code outcome} is the run outcome of the same (job, source); times are device-local text. */
    record ChunkRow(String chunkId, String sourceKey, String jobId, Instant collectedAt, String logDay, long startOffset,
            long endOffset, long bytes, long lines, String firstLineTime, String lastLineTime, int redactionCount,
            String redactionRuleSet, List<String> flags, long ciphertextBytes, Instant prunedAt, String pruneReason,
            String outcome) {
        public ChunkRow {
            flags = List.copyOf(flags);
        }
    }

    /** What the text route needs to open one chunk: identity, size and where the encrypted bytes are. */
    record ChunkRef(String chunkId, String deviceId, String sourceKey, long bytes, long lines, Instant prunedAt,
            String artefactId, String artefactRef, byte[] wrappedDataKey) {
    }

    record GapRow(String gapId, String deviceId, String sourceKey, String jobId, String kind, long knownLostBytes,
            long pendingBytes, boolean extentUnknown, Long windowStartEpoch, Long windowEndEpoch, Instant createdAt) {
    }

    /** One listed crash or dump file: its directory class, parsed process, size and device mtime. No name. */
    record CrashRow(String entryId, String deviceId, String directory, String process, long size, long mtime,
            boolean baseline, Instant firstSeenAt, Instant removedAt) {
    }

    record CrashAlertRow(String alertId, String deviceId, String process, int fileCount, long newestMtime, String jobId,
            Instant createdAt, Instant notifiedAt) {
    }

    /** One whole-file HCP fetch: metadata only. */
    record FileRow(String fileId, String kind, String nameTime, long size, long mtime, String status, List<String> flags,
            Instant collectedAt, Instant expiresAt, Instant prunedAt, long ciphertextBytes) {
        public FileRow {
            flags = List.copyOf(flags);
        }
    }

    List<SourceRow> sources(String deviceId);

    Optional<LastJob> lastJob(String deviceId);

    /**
     * Chunks of one device, newest first, at most {@code limit} (bounded to 1..1000). {@code sourceKey} and {@code day}
     * ({@code yyyy-MM-dd}, the Europe/Istanbul log day) narrow the list when present.
     */
    List<ChunkRow> chunks(String deviceId, Optional<String> sourceKey, Optional<String> day, int limit);

    Optional<ChunkRef> findChunk(String chunkId);

    /** Gap rows, newest first, at most {@code limit} (bounded to 1..1000); one device or, when absent, the fleet. */
    List<GapRow> gaps(Optional<String> deviceId, int limit);

    /** Crash and dump entries, newest device mtime first, at most {@code limit} (bounded to 1..1000). */
    List<CrashRow> crashEntries(Optional<String> deviceId, int limit);

    /** Crash alerts, newest first, at most {@code limit} (bounded to 1..200). */
    List<CrashAlertRow> crashAlerts(Optional<String> deviceId, int limit);

    /** HCP archives of one device, newest first, at most {@code limit} (bounded to 1..200). */
    List<FileRow> hcpFiles(String deviceId, int limit);
}
