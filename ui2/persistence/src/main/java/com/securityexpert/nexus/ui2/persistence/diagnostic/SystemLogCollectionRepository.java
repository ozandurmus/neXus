package com.securityexpert.nexus.ui2.persistence.diagnostic;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRecord;

/**
 * System-log programme phase B storage (V160; DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 5.2): cursors, chunk
 * index rows, gap rows and per-run outcome rows. No row holds log text. The raw bytes live in the encrypted artefact
 * store; the manifest row of that artefact ({@code artefact_class = system_log}) is written in the same transaction as
 * the chunk row and the cursor (section 4.1: "the cursor advances only in the same transaction that records the chunk").
 */
public interface SystemLogCollectionRepository {
    String SOURCE_PAN_SYSTEM = "pan_system";
    String SOURCE_PAN_CONFIG = "pan_config";
    Pattern RECEIVE_TIME = Pattern.compile("[0-9]{4}/[0-9]{2}/[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}");
    Pattern SEQNO = Pattern.compile("[0-9]{1,20}");

    /** Palo Alto resume point: the newest receive_time seen (device-local text, verbatim) and the seqnos stored at exactly that second. */
    record PanCursor(String lastReceiveTime, List<String> boundarySeqnos) {
        public PanCursor {
            if (lastReceiveTime == null || !RECEIVE_TIME.matcher(lastReceiveTime).matches())
                throw new IllegalArgumentException("pan cursor time shape");
            boundarySeqnos = List.copyOf(boundarySeqnos);
            for (String seqno : boundarySeqnos)
                if (!SEQNO.matcher(seqno).matches()) throw new IllegalArgumentException("pan cursor seqno shape");
        }
    }

    /** The typed settings row (section 5.3): raw retention and the management-server switch. */
    record Settings(int rawRetentionDays, boolean includeManagementServers) {
        public static final Settings DEFAULTS = new Settings(30, false);
    }

    /**
     * One stored chunk. {@code manifest} is the backup_artefact row of the encrypted chunk; the chunk row reuses its
     * artefact id, wrapped data key, plaintext digest and ciphertext size. Times are device-local wall clock
     * ({@code yyyy-MM-dd HH:mm:ss}), never converted, or null.
     */
    record Chunk(String chunkId, java.time.LocalDate logDay, String fileNameHmac, long startOffset, long endOffset,
            long bytes, long lines, String firstLineTime, String lastLineTime, int redactionCount, List<String> flags,
            BackupArtefactManifestRecord manifest) {
    }

    record Gap(String kind, long knownLostBytes, long pendingBytes, boolean extentUnknown, Long windowStartEpoch,
            Long windowEndEpoch, String detailJson) {
    }

    record Run(String outcome, long bytesRead, long bytesStored, int readsIssued, long durationMs, String detailJson) {
    }

    /** What one source's run leaves behind, written atomically. */
    record SourceWrite(Run run, Optional<Chunk> chunk, Optional<PanCursor> cursor, List<Gap> gaps) {
        public SourceWrite {
            gaps = List.copyOf(gaps);
        }
    }

    Optional<PanCursor> findPanCursor(String deviceId, String sourceKey);

    Settings settings();

    /**
     * Writes the run row, then the chunk (with its artefact manifest), the cursor and the gap rows, in one audited
     * transaction fenced to the job's current lease. A stale worker, or a second write for the same (job, source),
     * writes nothing and returns {@code false}.
     */
    boolean record(String jobId, long leaseEpoch, String deviceId, String sourceKey, SourceWrite write);
}
