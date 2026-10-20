package com.securityexpert.nexus.ui2.service.systemlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.CpwdChunkRef;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.CrashAlertRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.FleetRunRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.SarFileRef;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;

/**
 * The System health aggregation over really stored synthetic sar texts and cpwd chunks (the fixture's encrypted store), a
 * repository double and a fixed device list. Pseudonym device names and documentation hosts only.
 */
class SystemHealthServiceTest {
    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");
    /** 2026-10-20 09:00 Europe/Istanbul. */
    private static final Instant NOW = LocalDate.of(2026, 10, 20).atTime(9, 0).atZone(ZONE).toInstant();
    private static final String CP_HOT = "cp-hot", CP_CALM = "cp-calm", CP_NEW = "cp-new", PAN = "pan-1", MDS = "mds-1";

    @TempDir
    Path root;
    SystemLogReadFixture fx;
    List<DeviceSummaryRecord> targets;
    List<DeviceSummaryRecord> all;
    List<FleetRunRow> runs = new ArrayList<>();
    List<CrashAlertRow> alerts = new ArrayList<>();
    Map<String, List<SarFileRef>> sar = new java.util.HashMap<>();
    Map<String, List<CpwdChunkRef>> cpwd = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        fx = new SystemLogReadFixture(root);
        targets = List.of(device(CP_HOT, "gateway", "check_point", "FW-TANGO-04"), device(CP_CALM, "gateway", "check_point", "FW-ROMEO-01-M1"),
                device(CP_NEW, "gateway", "check_point", "FW-JULIET-06"), device(PAN, "gateway", "palo_alto", "FW-BRAVO-02"));
        all = new ArrayList<>(targets);
        all.add(device(MDS, "management_server", "check_point", "MDS-ALPHA-01"));
        when(fx.devices.listAll()).thenAnswer(i -> all);
        when(fx.repository.fleetLastRuns()).thenAnswer(i -> runs);
        when(fx.repository.crashAlerts(eq(Optional.empty()), anyInt())).thenAnswer(i -> alerts);
        when(fx.repository.sarFileRefs(anyString(), anyInt())).thenAnswer(i -> sar.getOrDefault(i.getArgument(0), List.of()));
        when(fx.repository.cpwdChunkRefs(anyString(), anyString(), anyInt())).thenAnswer(i -> cpwd.getOrDefault(i.getArgument(0), List.of()));
    }

    private static DeviceSummaryRecord device(String id, String role, String vendor, String host) {
        return new DeviceSummaryRecord(id, role, vendor, DeviceEnrollmentState.ENROLLED, Optional.of(host), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    private SystemHealthService service() {
        return new SystemHealthService(fx.repository, fx.settings, () -> targets, fx.devices, fx.store, 85);
    }

    private void ran(String device, String job, Instant at, String... sourceOutcomes) {
        for (String so : sourceOutcomes) {
            String[] parts = so.split("=");
            runs.add(new FleetRunRow(device, parts[0], parts[1], job, at));
        }
    }

    /** A sar text whose every sample has the given busy percentage. */
    private static byte[] sarText(double busy, int samples, boolean memory) {
        StringBuilder sb = new StringBuilder("Linux 3.10.0-synthetic (gw.example.invalid)\t10/19/2026\t_x86_64_\t(2 CPU)\n\n");
        sb.append("00:00:01 CPU %usr %nice %sys %iowait %steal %irq %soft %guest %gnice %idle\n");
        for (int i = 0; i < samples; i++) {
            String t = String.format("%02d:%02d:01", i / 6, (i % 6) * 10);
            sb.append(String.format(java.util.Locale.ROOT, "%s all 1 0 1 0 0 0 0 0 0 %.2f\n", t, 100 - busy));
            sb.append(t).append(" 0 1 0 1 0 0 0 0 0 0 50.00\n").append(t).append(" 1 1 0 1 0 0 0 0 0 0 50.00\n");
        }
        if (memory) {
            sb.append("\n00:00:01 kbmemfree kbavail kbmemused %memused kbbuffers kbcached kbcommit %commit kbactive kbinact kbdirty\n");
            sb.append("00:10:01 1 2 3 71.00 4 5 6 7 8 9 10\n");
        }
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private void storeSar(String device, LocalDate day, byte[] text) throws IOException {
        try (ArtefactStore.ArtefactHandle handle = fx.store.open(device, "job-sar", "check_point", true)) {
            handle.sink().write(text);
            var meta = handle.finish();
            long mtime = day.atTime(23, 53).atZone(ZONE).toEpochSecond();
            sar.computeIfAbsent(device, k -> new ArrayList<>()).add(new SarFileRef("sar-" + device + "-" + day, device, mtime,
                    text.length, "STORED", NOW, null, "artefact-" + day, meta.ref().value(), meta.wrappedDataKey()));
        }
    }

    private void storeCpwd(String device, String chunkId, String text) throws IOException {
        try (ArtefactStore.ArtefactHandle handle = fx.store.open(device, "job-cpwd", "check_point", true)) {
            handle.sink().write(text.getBytes(StandardCharsets.ISO_8859_1));
            var meta = handle.finish();
            cpwd.computeIfAbsent(device, k -> new ArrayList<>()).add(new CpwdChunkRef(chunkId, device, "2026-10-19", text.length(), 3,
                    NOW, null, "artefact-" + chunkId, meta.ref().value(), meta.wrappedDataKey()));
        }
    }

    private static String cpwdLine(String time, String text) {
        return "[cpwd 1 2]@gw.example.invalid[19 Oct " + time + "] " + text + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Map<String, Object> body, String deviceId) {
        for (Map<String, Object> d : (List<Map<String, Object>>) body.get("devices")) {
            if (deviceId.equals(d.get("device_id"))) return d;
        }
        throw new AssertionError("no row for " + deviceId);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> part(Map<String, Object> row, String key) {
        return (Map<String, Object>) row.get(key);
    }

    @Test
    @SuppressWarnings("unchecked")
    void beforeTheFirstSnapshotTheAnswerIsComputingWithNoFigures() {
        var body = service().health(NOW);
        assertEquals("COMPUTING", part(body, "computation").get("state"));
        assertNull(part(body, "kpis").get("monitored"), "no figure is invented before the first computation");
        assertEquals(List.of(), body.get("devices"));
        assertEquals(85, body.get("cpu_p95_threshold_percent"));
        assertEquals("PROVISIONAL", ((Map<String, Object>) part(body, "rules").get("restart_rule")).get("status"));
    }

    @Test
    void theWorstFactNamesTheStateAndRowsSortWorstFirst() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 19);
        // cp-hot: three days above the threshold in a row, a restart storm, memory section present.
        storeSar(CP_HOT, d.minusDays(2), sarText(90, 12, false));
        storeSar(CP_HOT, d.minusDays(1), sarText(91, 12, false));
        storeSar(CP_HOT, d, sarText(92, 12, true));
        storeCpwd(CP_HOT, "c-hot", cpwdLine("05:41:14", "Restarting process FWD") + cpwdLine("05:42:00", "Restarting process FWD")
                + cpwdLine("05:43:00", "Restarting process FWD"));
        ran(CP_HOT, "job-h", NOW.minusSeconds(600), "cp_messages=COLLECTED", "cp_cpwd_elg=COLLECTED", "cp_crash_listing=COLLECTED", "cp_sar_daily=COLLECTED");
        // cp-calm: one day over the threshold five days ago, nothing else; cpwd ran empty.
        storeSar(CP_CALM, d.minusDays(5), sarText(88, 6, false));
        storeSar(CP_CALM, d, sarText(20, 6, false));
        ran(CP_CALM, "job-c", NOW.minusSeconds(900), "cp_messages=COLLECTED", "cp_cpwd_elg=NO_NEW_DATA", "cp_crash_listing=NO_NEW_DATA", "cp_sar_daily=COLLECTED");
        // cp-new: never collected.
        // pan-1: collected, no CPU, restart or crash source.
        ran(PAN, "job-p", NOW.minusSeconds(300), "pan_system=COLLECTED", "pan_config=UNKNOWN");

        var service = service();
        var snapshot = service.refreshNow(NOW);
        var body = service.health(NOW);

        assertEquals("READY", part(body, "computation").get("state"));
        var hot = row(body, CP_HOT);
        assertEquals("CRITICAL", hot.get("state"));
        assertEquals("CPU_P95_OVER_THRESHOLD_3_DAYS_IN_A_ROW", hot.get("state_reason"));
        assertEquals(92.0, part(hot, "cpu").get("newest_p95_percent"));
        assertEquals(3, part(hot, "cpu").get("longest_run_over_threshold"));
        assertEquals(71.0, part(hot, "memory").get("newest_used_p95_percent"));
        assertEquals("ATTENTION", part(hot, "restarts").get("level"));
        assertEquals(3, part(hot, "restarts").get("max_in_one_hour"));
        assertEquals("Oct 19 05:00 device time", part(hot, "restarts").get("busiest_hour"));
        assertEquals("NORMAL", part(hot, "crash").get("level"));
        assertEquals("OK", part(hot, "collection").get("state"));
        assertEquals("2026-10-19", hot.get("logs_day"));

        var calm = row(body, CP_CALM);
        assertEquals("ATTENTION", calm.get("state"));
        assertEquals("CPU_P95_OVER_THRESHOLD_1_DAYS", calm.get("state_reason"));
        assertEquals(0, part(calm, "restarts").get("count"), "a cpwd source that ran with no chunk is zero restarts, evidenced");
        assertEquals("UNKNOWN", part(calm, "memory").get("level"));
        assertEquals("SAR_TEXT_WITHOUT_MEMORY_SECTION", part(calm, "memory").get("reason"));

        var fresh = row(body, CP_NEW);
        assertEquals("UNKNOWN", fresh.get("state"));
        assertEquals("NO_SAR_DAY_STORED", part(fresh, "cpu").get("reason"));
        assertEquals("CPWD_NOT_COLLECTED", part(fresh, "restarts").get("reason"));
        assertNull(part(fresh, "restarts").get("count"), "no count stands in for UNKNOWN");
        assertEquals("CRASH_LISTING_NOT_RUN", part(fresh, "crash").get("reason"));
        assertEquals("NEVER", part(fresh, "collection").get("state"));

        var pan = row(body, PAN);
        assertEquals("UNKNOWN", pan.get("state"));
        assertEquals("NO_APPLICABLE_FACT", pan.get("state_reason"));
        assertEquals("NOT_APPLICABLE", part(pan, "cpu").get("level"));
        assertEquals("FAILED", part(pan, "collection").get("state"));
        assertEquals("pan_config:UNKNOWN", part(pan, "collection").get("reason"));

        var mds = row(body, MDS);
        assertEquals("OFF", part(mds, "collection").get("state"));
        assertEquals("SYSTEM_LOG_DISABLED_ON_MANAGEMENT", part(mds, "collection").get("reason"));
        assertEquals("COLLECTION_OFF", mds.get("state_reason"));
        assertEquals(false, mds.get("monitored"));

        @SuppressWarnings("unchecked")
        List<String> order = ((List<Map<String, Object>>) body.get("devices")).stream().map(r -> (String) r.get("device_id")).toList();
        assertEquals(CP_HOT, order.get(0));
        assertEquals(CP_CALM, order.get(1));
        assertEquals(List.of(PAN, CP_NEW, MDS), order.subList(2, 5), "UNKNOWN rows follow, by pseudonym name");

        var kpis = part(body, "kpis");
        assertEquals(4, part(kpis, "monitored").get("devices"));
        assertEquals(Map.of("check_point", 3, "palo_alto", 1), part(kpis, "monitored").get("by_vendor"));
        assertEquals(1, part(kpis, "cpu_over_threshold").get("devices"), "only the newest-day breach counts for 24 h");
        assertEquals(2, part(kpis, "cpu_over_threshold").get("evidenced"));
        assertEquals(1, part(kpis, "cpu_over_threshold").get("unknown"));
        assertEquals(0, part(kpis, "crash_alerts").get("devices"));
        assertEquals(3, part(kpis, "collection_not_ok").get("devices"));
        assertEquals(Map.of("FAILED", 1, "NEVER", 1, "OFF", 1), part(kpis, "collection_not_ok").get("by_state"));
        assertEquals(5, snapshot.devices().size());
    }

    @Test
    void aNewCrashAlertIsCriticalAndTheSnapshotIsReusedUntilItIsStale() throws Exception {
        ran(CP_CALM, "job-c", NOW.minusSeconds(900), "cp_cpwd_elg=NO_NEW_DATA", "cp_crash_listing=COLLECTED");
        alerts.add(new CrashAlertRow("a-1", CP_CALM, "UNKNOWN", 2, 1_790_000_000L, "job-c", NOW.minusSeconds(800), null));
        alerts.add(new CrashAlertRow("a-old", CP_CALM, "UNKNOWN", 1, 1_780_000_000L, "job-old", NOW.minusSeconds(30L * 86400), null));
        storeSar(CP_CALM, LocalDate.of(2026, 10, 19), sarText(10, 6, false));

        var service = service();
        service.refreshNow(NOW);
        var body = service.health(NOW.plusSeconds(60));
        var calm = row(body, CP_CALM);
        assertEquals("CRITICAL", calm.get("state"));
        assertEquals("NEW_CRASH_OR_DUMP_FILE_ALERT_1", calm.get("state_reason"));
        assertEquals(1, part(calm, "crash").get("new_alerts"), "the alert outside the window is not counted");
        assertEquals(1, part(part(body, "kpis"), "crash_alerts").get("devices"));

        int retrievals = fx.store.retrievals.get();
        service.refreshNow(NOW.plusSeconds(120));
        assertEquals(retrievals, fx.store.retrievals.get(), "a stored sar text is parsed once and kept as counters by id");
        assertEquals("READY", part(service.health(NOW.plusSeconds(180)), "computation").get("state"));
        assertEquals("REFRESHING", part(service.health(NOW.plusSeconds(3600)), "computation").get("state"),
                "a stale snapshot is still answered while the refresh runs in the background");
    }

    @Test
    void withoutAnArtefactStoreTheCpuAndRestartFactsAreUnknownAndNothingElseIsLost() {
        ran(CP_CALM, "job-c", NOW.minusSeconds(900), "cp_cpwd_elg=COLLECTED", "cp_crash_listing=COLLECTED");
        var service = new SystemHealthService(fx.repository, fx.settings, () -> targets, fx.devices, null, 0);
        service.refreshNow(NOW);
        var body = service.health(NOW);
        assertEquals(SystemHealthService.DEFAULT_CPU_P95_THRESHOLD_PERCENT, body.get("cpu_p95_threshold_percent"));
        assertEquals(false, part(body, "computation").get("store_available"));
        var calm = row(body, CP_CALM);
        assertEquals("SYSTEM_LOG_STORE_UNAVAILABLE", part(calm, "cpu").get("reason"));
        assertEquals("SYSTEM_LOG_STORE_UNAVAILABLE", part(calm, "restarts").get("reason"));
        assertEquals("NORMAL", part(calm, "crash").get("level"));
        assertEquals("UNKNOWN", calm.get("state"), "an unevidenced fact outranks a normal one");
        assertTrue(fx.store.retrievals.get() == 0);
    }

    @Test
    void aCorruptOrUnreadableSarDayIsListedWithoutAFigure() throws Exception {
        LocalDate d = LocalDate.of(2026, 10, 19);
        storeSar(CP_CALM, d, sarText(30, 6, false));
        // A second file for the day before whose bytes are not on the volume.
        long mtime = d.minusDays(1).atTime(23, 53).atZone(ZONE).toEpochSecond();
        sar.get(CP_CALM).add(new SarFileRef("sar-missing", CP_CALM, mtime, 10, "STORED", NOW, null, "artefact-x",
                root.resolve("nowhere").toString(), new byte[48]));
        // And a CORRUPT row two days before: never opened.
        sar.get(CP_CALM).add(new SarFileRef("sar-corrupt", CP_CALM, mtime - 86400, 10, "CORRUPT", NOW, null, "artefact-y", "ref", new byte[48]));
        ran(CP_CALM, "job-c", NOW.minusSeconds(900), "cp_cpwd_elg=NO_NEW_DATA", "cp_crash_listing=COLLECTED");

        var service = service();
        service.refreshNow(NOW);
        var cpu = part(row(service.health(NOW), CP_CALM), "cpu");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> days = (List<Map<String, Object>>) cpu.get("days");
        assertEquals(3, days.size());
        assertEquals("CORRUPT", days.get(0).get("status"));
        assertEquals("UNREADABLE", days.get(1).get("status"));
        assertNull(days.get(1).get("p95_percent"));
        assertEquals("PARSED", days.get(2).get("status"));
        assertEquals(30.0, days.get(2).get("p95_percent"));
        assertEquals("NORMAL", cpu.get("level"));
        assertNotNull(cpu.get("day_basis"));
        assertFalse(days.get(0).containsValue("artefact-y"), "no artefact reference leaves the service");
    }
}
