package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogRetentionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogRetentionRepository.Candidate;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;

/**
 * Retention of the system-log pool (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 5.3), once a day, whether or not
 * collection is enabled (data that exists must age out):
 *
 * <ol>
 *   <li>raw chunks (Check Point and Palo Alto) and sar files older than {@code raw_retention_days} (30);</li>
 *   <li>HCP archives older than {@code hcp_retention_days} (7) and spike data older than its own retention;</li>
 *   <li>the per-device cap ({@code device_cap_bytes}, 2 GiB stored): a device above it loses its oldest chunks and sar
 *       files first until it is under the cap. Each such early deletion is ledgered with reason
 *       {@code RETENTION_SHORTENED_BY_CAP} and recorded as one visible gap row per source and pass. New data is never
 *       refused for the cap (PO Q4);</li>
 *   <li>gap, run and alert rows, and removed crash entries, older than {@code ledger_retention_days} (180).</li>
 * </ol>
 *
 * The encrypted file goes first; only a file that is gone (or was already gone) gets its ledger row, so a failed delete
 * is retried the next day. The worker owns the artefact store (BK-17), so this runs in the worker, as backup pruning
 * does. System-log artefacts are not counted in the backup vault accounting ({@code JooqRetentionQueries}).
 */
public final class SystemLogRetentionPruner {
    private static final System.Logger LOG = System.getLogger(SystemLogRetentionPruner.class.getName());
    /** Rows handled per query; a pass loops until nothing is left or this many passes ran. */
    static final int BATCH = 500;
    static final int MAX_BATCHES = 200;

    public record Summary(int expiredChunks, int expiredFiles, int cappedArtefacts, int ledgerRows, long bytesReclaimed) {
    }

    private final SystemLogRetentionRepository retention;
    private final Supplier<SystemLogCollectionRepository.Settings> settings;
    private final Path storeRoot;
    private final Clock clock;
    private LocalDate lastRunDay;

    public SystemLogRetentionPruner(SystemLogRetentionRepository retention,
            Supplier<SystemLogCollectionRepository.Settings> settings, Path storeRoot, Clock clock) {
        this.retention = Objects.requireNonNull(retention, "retention");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.storeRoot = Objects.requireNonNull(storeRoot, "storeRoot").toAbsolutePath().normalize();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Runs {@link #runOnce} when it has not run yet on this Europe/Istanbul day (the scheduler ticks hourly). */
    public synchronized boolean runIfDue() {
        LocalDate today = clock.instant().atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        if (today.equals(lastRunDay)) return false;
        Summary summary = runOnce();
        lastRunDay = today;
        if (summary.expiredChunks() + summary.expiredFiles() + summary.cappedArtefacts() + summary.ledgerRows() > 0)
            LOG.log(System.Logger.Level.INFO, "[SYSTEM_LOG_RETENTION] chunks={0} files={1} capped={2} ledgerRows={3} bytes={4}",
                    summary.expiredChunks(), summary.expiredFiles(), summary.cappedArtefacts(), summary.ledgerRows(),
                    summary.bytesReclaimed());
        return true;
    }

    public Summary runOnce() {
        var s = settings.get();
        long[] bytes = {0};
        int chunks = drain(() -> retention.expiredChunks(s.rawRetentionDays(), BATCH), "RETENTION", bytes);
        int files = drain(() -> retention.expiredFiles("cp_sar_daily", s.rawRetentionDays(), BATCH), "RETENTION", bytes)
                + drain(() -> retention.expiredFiles("cp_hcp_report", s.hcpRetentionDays(), BATCH), "RETENTION", bytes)
                + drain(() -> retention.expiredFiles("cp_spike_data", s.spikeDataRetentionDays(), BATCH), "RETENTION", bytes);
        int capped = 0;
        String passId = "system-log-retention:" + clock.instant().atZone(JobWindowPolicy.ZONE_ID).toLocalDate();
        for (var usage : retention.devicesOverCap(s.deviceCapBytes())) capped += cap(usage, s.deviceCapBytes(), passId, bytes);
        int ledger = retention.pruneLedgerRows(s.ledgerRetentionDays());
        return new Summary(chunks, files, capped, ledger, bytes[0]);
    }

    private int drain(Supplier<List<Candidate>> batch, String reason, long[] bytes) {
        int pruned = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            List<Candidate> candidates = batch.get();
            int before = pruned;
            for (Candidate c : candidates) {
                if (remove(c) && retention.markPruned(c, reason)) {
                    pruned++;
                    bytes[0] += c.ciphertextBytes();
                }
            }
            if (candidates.size() < BATCH || pruned == before) break;
        }
        return pruned;
    }

    /** Oldest first until the device is under its cap; one visible gap row per source. */
    private int cap(SystemLogRetentionRepository.DeviceUsage usage, long capBytes, String passId, long[] bytes) {
        long live = usage.ciphertextBytes();
        Map<String, long[]> bySource = new LinkedHashMap<>();
        int pruned = 0;
        outer:
        for (int round = 0; round < MAX_BATCHES && live > capBytes; round++) {
            List<Candidate> oldest = retention.oldestCapped(usage.deviceId(), BATCH);
            if (oldest.isEmpty()) break;
            int before = pruned;
            for (Candidate c : oldest) {
                if (live <= capBytes) break outer;
                if (remove(c) && retention.markPruned(c, "RETENTION_SHORTENED_BY_CAP")) {
                    pruned++;
                    live -= c.ciphertextBytes();
                    bytes[0] += c.ciphertextBytes();
                    long[] agg = bySource.computeIfAbsent(c.sourceKey(), k -> new long[3]);
                    agg[0]++;
                    agg[1] += c.plaintextBytes();
                    agg[2] += c.ciphertextBytes();
                }
            }
            if (pruned == before) break;
        }
        for (var e : bySource.entrySet()) {
            retention.recordCapGap(passId, usage.deviceId(), e.getKey(), e.getValue()[1],
                    "{\"artefacts\":" + e.getValue()[0] + ",\"ciphertext_bytes\":" + e.getValue()[2]
                            + ",\"cap_bytes\":" + capBytes + "}");
        }
        return pruned;
    }

    /** Deletes the encrypted file; true when it is gone (a file already missing counts, so the ledger row lands). */
    private boolean remove(Candidate c) {
        if (c.recoveryVolumePath() == null || c.recoveryVolumePath().isBlank()) return true;
        Path file = storeRoot.resolve(c.recoveryVolumePath()).normalize();
        if (!file.startsWith(storeRoot)) {
            LOG.log(System.Logger.Level.WARNING, "[SYSTEM_LOG_RETENTION] refusing a path outside the store root");
            return false;
        }
        try {
            Files.deleteIfExists(file);
            return true;
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "[SYSTEM_LOG_RETENTION] delete failed: {0}", e.getClass().getSimpleName());
            return false;
        }
    }
}
