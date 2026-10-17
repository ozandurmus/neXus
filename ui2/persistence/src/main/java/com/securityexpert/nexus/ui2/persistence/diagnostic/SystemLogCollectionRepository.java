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

    /**
     * The typed settings row (sections 5.3, 2.3, 8.3): retention days, per-device caps, the management-server switch and
     * the spike-data switch. The two-value constructor keeps the other columns at their V160 defaults.
     */
    record Settings(int rawRetentionDays, boolean includeManagementServers, long deviceCapBytes, int hcpRetentionDays,
            int spikeDataRetentionDays, long spikeDataCapBytes, int ledgerRetentionDays, boolean spikeDataEnabled) {
        public static final long DEFAULT_DEVICE_CAP_BYTES = 2L * 1024 * 1024 * 1024;
        public static final long DEFAULT_SPIKE_DATA_CAP_BYTES = 512L * 1024 * 1024;
        public static final Settings DEFAULTS = new Settings(30, false);

        public Settings(int rawRetentionDays, boolean includeManagementServers) {
            this(rawRetentionDays, includeManagementServers, DEFAULT_DEVICE_CAP_BYTES, 7, 30, DEFAULT_SPIKE_DATA_CAP_BYTES,
                    180, false);
        }
    }

    /**
     * One stored chunk. {@code manifest} is the backup_artefact row of the encrypted chunk; the chunk row reuses its
     * artefact id, wrapped data key and ciphertext size. {@code plaintextHmac} is the chunk's keyed content identity
     * (V161: HMAC-SHA256 of the stored, redacted plaintext under the keyed-fingerprint secret, domain
     * {@value #CHUNK_CONTENT_DOMAIN}; never a bare hash, contract section 12). Times are device-local wall clock
     * ({@code yyyy-MM-dd HH:mm:ss}), never converted, or null.
     */
    record Chunk(String chunkId, java.time.LocalDate logDay, String fileNameHmac, long startOffset, long endOffset,
            long bytes, long lines, String firstLineTime, String lastLineTime, int redactionCount, List<String> flags,
            String plaintextHmac, BackupArtefactManifestRecord manifest) {
        public Chunk {
            if (plaintextHmac == null || !plaintextHmac.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("chunk content hmac shape");
        }
    }

    /** The HMAC domain of a chunk's content identity (V161 {@code system_log_chunk.plaintext_hmac}). */
    String CHUNK_CONTENT_DOMAIN = "system-log-chunk-content";
    /** The HMAC domain of a whole file's content identity (V161 {@code system_log_file.content_hmac}). */
    String FILE_CONTENT_DOMAIN = "system-log-file-content";

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
