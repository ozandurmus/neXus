package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository.Settings;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogRetentionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogRetentionRepository.Candidate;

/** Retention of the system-log pool (phase B contract section 5.3) against an in-memory ledger and a temp store. */
class SystemLogRetentionPrunerTest {
    private static final Instant NOW = Instant.parse("2026-10-17T09:00:00Z");

    /** The ledger as rows: live until marked; the queries follow the SQL of JooqSystemLogRetentionRepository. */
    static final class Ledger implements SystemLogRetentionRepository {
        final Map<String, Candidate> live = new LinkedHashMap<>();
        final Map<String, String> pruned = new LinkedHashMap<>();
        final List<String> capGaps = new ArrayList<>();
        int ledgerDays = -1;

        void add(String table, String id, String device, String source, String file, long bytes, Instant at) {
            live.put(id, new Candidate(table, id, device, source, "artefact-" + id, file, bytes, bytes * 8, at));
        }

        private List<Candidate> where(java.util.function.Predicate<Candidate> p, int limit) {
            return live.values().stream().filter(p).sorted(Comparator.comparing(Candidate::collectedAt)).limit(limit).toList();
        }

        @Override public List<Candidate> expiredChunks(int days, int limit) {
            return where(c -> c.table().equals(Candidate.CHUNK) && c.collectedAt().isBefore(NOW.minus(Duration.ofDays(days))), limit);
        }
        @Override public List<Candidate> expiredFiles(String kind, int days, int limit) {
            return where(c -> c.table().equals(Candidate.FILE) && c.sourceKey().equals(kind)
                    && c.collectedAt().isBefore(NOW.minus(Duration.ofDays(days))), limit);
        }
        @Override public List<DeviceUsage> devicesOverCap(long cap) {
            Map<String, Long> sum = new LinkedHashMap<>();
            for (Candidate c : live.values())
                if (c.table().equals(Candidate.CHUNK) || c.sourceKey().equals("cp_sar_daily"))
                    sum.merge(c.deviceId(), c.ciphertextBytes(), Long::sum);
            return sum.entrySet().stream().filter(e -> e.getValue() > cap).map(e -> new DeviceUsage(e.getKey(), e.getValue())).toList();
        }
        @Override public List<Candidate> oldestCapped(String device, int limit) {
            return where(c -> c.deviceId().equals(device) && (c.table().equals(Candidate.CHUNK) || c.sourceKey().equals("cp_sar_daily")), limit);
        }
        @Override public boolean markPruned(Candidate c, String reason) {
            if (live.remove(c.rowId()) == null) return false;
            pruned.put(c.rowId(), reason);
            return true;
        }
        @Override public void recordCapGap(String pass, String device, String source, long bytes, String detail) {
            capGaps.add(device + "/" + source + "/" + bytes + "/" + pass + "/" + detail);
        }
        @Override public int pruneLedgerRows(int days) { ledgerDays = days; return 0; }
    }

    private static Path file(Path root, String name) throws IOException {
        Path p = root.resolve("check_point/device-1/" + name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, "ciphertext");
        return p;
    }

    private static SystemLogRetentionPruner pruner(Ledger ledger, Path root, Settings settings) {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        return new SystemLogRetentionPruner(ledger, () -> settings, root, clock);
    }

    @Test
    void rawChunksAndSarAgeOutAfter30DaysAndHcpAfter7(@TempDir Path root) throws Exception {
        Ledger ledger = new Ledger();
        Path oldChunk = file(root, "a.enc"), newChunk = file(root, "b.enc"), oldHcp = file(root, "c.enc"), newHcp = file(root, "d.enc"),
                oldSar = file(root, "e.enc");
        ledger.add(Candidate.CHUNK, "c-old", "device-1", "cp_messages", "check_point/device-1/a.enc", 10, NOW.minus(Duration.ofDays(31)));
        ledger.add(Candidate.CHUNK, "c-new", "device-1", "pan_system", "check_point/device-1/b.enc", 10, NOW.minus(Duration.ofDays(29)));
        ledger.add(Candidate.FILE, "h-old", "device-1", "cp_hcp_report", "check_point/device-1/c.enc", 10, NOW.minus(Duration.ofDays(8)));
        ledger.add(Candidate.FILE, "h-new", "device-1", "cp_hcp_report", "check_point/device-1/d.enc", 10, NOW.minus(Duration.ofDays(6)));
        ledger.add(Candidate.FILE, "s-old", "device-1", "cp_sar_daily", "check_point/device-1/e.enc", 10, NOW.minus(Duration.ofDays(31)));

        var summary = pruner(ledger, root, Settings.DEFAULTS).runOnce();

        assertEquals(Map.of("c-old", "RETENTION", "h-old", "RETENTION", "s-old", "RETENTION"), ledger.pruned);
        assertEquals(1, summary.expiredChunks());
        assertEquals(2, summary.expiredFiles());
        assertFalse(Files.exists(oldChunk));
        assertFalse(Files.exists(oldHcp));
        assertFalse(Files.exists(oldSar));
        assertTrue(Files.exists(newChunk));
        assertTrue(Files.exists(newHcp));
        assertTrue(ledger.capGaps.isEmpty());
        assertEquals(180, ledger.ledgerDays, "gap, run and alert rows: 180 days");
    }

    @Test
    void aDeviceOverItsCapLosesItsOldestArtefactsFirstAndTheEarlyDeletionIsVisible(@TempDir Path root) throws Exception {
        Ledger ledger = new Ledger();
        for (int i = 0; i < 5; i++) {
            file(root, i + ".enc");
            ledger.add(Candidate.CHUNK, "c" + i, "device-1", i % 2 == 0 ? "cp_messages" : "cp_fwd_elg",
                    "check_point/device-1/" + i + ".enc", 30L * 1024 * 1024, NOW.minus(Duration.ofDays(10 - i)));
        }
        ledger.add(Candidate.CHUNK, "other", "device-2", "cp_messages", null, 10, NOW.minus(Duration.ofDays(20)));
        Settings settings = new Settings(30, false, 100L * 1024 * 1024, 7, 30, Settings.DEFAULT_SPIKE_DATA_CAP_BYTES, 180, false);

        var summary = pruner(ledger, root, settings).runOnce();

        assertEquals(2, summary.cappedArtefacts(), "150 MiB over a 100 MiB cap: the two oldest go");
        assertEquals(Map.of("c0", "RETENTION_SHORTENED_BY_CAP", "c1", "RETENTION_SHORTENED_BY_CAP"), ledger.pruned);
        assertFalse(Files.exists(root.resolve("check_point/device-1/0.enc")));
        assertTrue(Files.exists(root.resolve("check_point/device-1/2.enc")));
        assertEquals(2, ledger.capGaps.size(), "one visible gap row per source");
        assertTrue(ledger.capGaps.get(0).startsWith("device-1/cp_messages/" + 30L * 1024 * 1024 * 8 + "/system-log-retention:2026-10-17/"));
        assertTrue(ledger.live.containsKey("other"), "another device under its cap is untouched");
    }

    @Test
    void aFileThatCannotBeDeletedKeepsItsRowForTheNextDay(@TempDir Path root) throws Exception {
        Ledger ledger = new Ledger();
        Path dir = root.resolve("check_point/device-1/held.enc");
        Files.createDirectories(dir.resolve("child")); // a non-empty directory cannot be deleted
        ledger.add(Candidate.CHUNK, "c-old", "device-1", "cp_messages", "check_point/device-1/held.enc", 10, NOW.minus(Duration.ofDays(40)));
        ledger.add(Candidate.CHUNK, "c-out", "device-1", "cp_messages", "../outside.enc", 10, NOW.minus(Duration.ofDays(40)));
        pruner(ledger, root, Settings.DEFAULTS).runOnce();
        assertTrue(ledger.pruned.isEmpty(), "no ledger row without the bytes gone; nothing outside the store root");
        assertEquals(2, ledger.live.size());
    }

    @Test
    void itRunsOnceADayEvenWhenTickedHourly(@TempDir Path root) {
        Ledger ledger = new Ledger();
        var pruner = pruner(ledger, root, Settings.DEFAULTS);
        assertTrue(pruner.runIfDue());
        assertFalse(pruner.runIfDue());
    }
}
