package com.securityexpert.nexus.ui2.service.systemlog;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogCollectionRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.CpwdChunkRef;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.CrashAlertRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.FleetRunRow;
import com.securityexpert.nexus.ui2.persistence.diagnostic.SystemLogReadRepository.SarFileRef;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionService;

/**
 * Operations &gt; System health ("who in the fleet is struggling"; docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md):
 * one row per monitored device from what the system-log programme already stores, nothing collected for it.
 *
 * <ul>
 *   <li><b>CPU</b>: the stored {@code cp_sar_daily} texts of the last {@value #WINDOW_DAYS} days, each read once into
 *       a per-day p95 ({@link SarDailyText}); the day of a file is the device mtime's calendar day in
 *       {@value JobWindowPolicy#ZONE} (the name holds only a day number and is stored as a digest). A device without a
 *       stored sar day is UNKNOWN. Palo Alto has no sar: NOT_APPLICABLE.</li>
 *   <li><b>Memory</b>: the sar memory section's {@code %memused} p95, when the report has one; else UNKNOWN.</li>
 *   <li><b>Process restarts</b>: restart lines of the stored {@code cp_cpwd_elg} chunks of the window
 *       ({@link CpwdRestartLines}, rule {@value CpwdRestartLines#RULE_ID}, PROVISIONAL), per device-local hour. A
 *       device whose cpwd source ran ({@code COLLECTED} or {@code NO_NEW_DATA}) with no chunk has 0 restarts; a device
 *       whose cpwd source never ran, or last ended in a failure outcome, is UNKNOWN.</li>
 *   <li><b>Crash/dump</b>: {@code device_crash_alert} rows of the window (new files only; present-before-monitoring
 *       files never alarm, contract section 7); UNKNOWN until the crash listing has run once.</li>
 *   <li><b>Collection</b>: the newest run outcome per source ({@code system_log_run}); OK, FAILED (a source ended
 *       UNKNOWN or STORAGE_REFUSED), UNSUPPORTED, OFF (management server while the setting is off) or NEVER.</li>
 * </ul>
 *
 * <p><b>State word</b> (fixed rule, shown on screen; no composite score): each fact is CRITICAL, ATTENTION, NORMAL,
 * UNKNOWN or NOT_APPLICABLE and the device takes the worst of its facts in the order CRITICAL &gt; ATTENTION &gt;
 * UNKNOWN &gt; NORMAL (a fact that is not evidenced is never read as healthy). Critical: a new crash/dump alert in the
 * window, or CPU p95 above the threshold on {@value #CPU_DAYS_CRITICAL} consecutive stored days. Attention: CPU p95
 * above the threshold on one or two days, or {@value #RESTARTS_PER_HOUR_ATTENTION} or more restarts in one hour. A
 * device with no applicable fact at all is UNKNOWN.</p>
 *
 * <p><b>Cost.</b> A sar text can be tens of MiB; the fleet is read in the background, each file and chunk parsed once
 * and kept as counters by id (an artefact is immutable), and a snapshot is served until it is older than
 * {@link #SNAPSHOT_TTL}, when the next request triggers a refresh and still answers from the snapshot. The first
 * request after a start answers {@code COMPUTING} until the first snapshot exists.</p>
 */
public final class SystemHealthService {
    public static final int WINDOW_DAYS = 7;
    public static final int DEFAULT_CPU_P95_THRESHOLD_PERCENT = 85;
    public static final int CPU_DAYS_CRITICAL = 3;
    public static final int RESTARTS_PER_HOUR_ATTENTION = 3;
    public static final Duration SNAPSHOT_TTL = Duration.ofMinutes(10);
    /** The fleet-wide crash alert read is bounded; a window with more alerts than this is reported truncated. */
    static final int ALERT_LIMIT = 200;
    static final ZoneId ZONE = ZoneId.of(JobWindowPolicy.ZONE);
    private static final System.Logger LOG = System.getLogger(SystemHealthService.class.getName());
    private static final List<String> STATE_ORDER = List.of("CRITICAL", "ATTENTION", "UNKNOWN", "NORMAL");

    /** One stored sar day of a device as the snapshot keeps it. */
    record SarDay(LocalDate day, String fileId, SarDailyText.Summary summary, String status) {
    }

    record Fact(String level, String reason) {
        static final Fact NOT_APPLICABLE = new Fact("NOT_APPLICABLE", null);
    }

    record DeviceHealth(DeviceSummaryRecord device, boolean target, String state, String stateReason,
            Fact cpu, List<SarDay> sarDays, Double newestCpuP95, int daysOver, int consecutiveOver, Double memP95,
            Fact restarts, CpwdRestartLines.Summary cpwd, Fact crash, int newAlerts, Instant newestAlertAt,
            String collectionState, String collectionReason, Instant lastJobAt, String lastJobId,
            Map<String, String> outcomes, LocalDate logsDay) {
    }

    record Snapshot(Instant computedAt, long durationMs, int threshold, List<DeviceHealth> devices, boolean alertsTruncated,
            boolean storeAvailable) {
    }

    private final SystemLogReadRepository repository;
    private final SystemLogCollectionRepository settings;
    private final Supplier<List<DeviceSummaryRecord>> targets;
    private final DeviceRepository devices;
    private final ArtefactStore store; // null when the service has no store mounted
    private final int threshold;
    private final Map<String, SarDailyText.Summary> sarByFile = new ConcurrentHashMap<>();
    private final Map<String, CpwdRestartLines.Summary> cpwdByChunk = new ConcurrentHashMap<>();
    private final AtomicBoolean computing = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "system-health-refresh");
        t.setDaemon(true);
        return t;
    });
    private volatile Snapshot latest;

    public SystemHealthService(SystemLogReadRepository repository, SystemLogCollectionRepository settings,
            Supplier<List<DeviceSummaryRecord>> targets, DeviceRepository devices, ArtefactStore store, int thresholdPercent) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.store = store;
        this.threshold = thresholdPercent > 0 && thresholdPercent <= 100 ? thresholdPercent : DEFAULT_CPU_P95_THRESHOLD_PERCENT;
    }

    // ---------------------------------------------------------------- serving

    /** The JSON-shaped answer: the newest snapshot (refreshed in the background when stale) or COMPUTING. */
    public Map<String, Object> health(Instant now) {
        Snapshot snapshot = latest;
        String computation;
        if (snapshot == null) {
            refreshAsync(now);
            computation = "COMPUTING";
        } else if (Duration.between(snapshot.computedAt(), now).compareTo(SNAPSHOT_TTL) > 0) {
            refreshAsync(now);
            computation = "REFRESHING";
        } else {
            computation = "READY";
        }
        return render(snapshot, computation, now);
    }

    /** Starts a background refresh unless one is running. */
    public void refreshAsync(Instant now) {
        if (!computing.compareAndSet(false, true)) return;
        executor.execute(() -> {
            try {
                latest = compute(now);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "system health refresh failed cause={0}", e.getClass().getSimpleName());
            } finally {
                computing.set(false);
            }
        });
    }

    /** Computes a snapshot on the caller's thread (the scheduled refresh and the tests). */
    public Snapshot refreshNow(Instant now) {
        Snapshot snapshot = compute(now);
        latest = snapshot;
        return snapshot;
    }

    // ---------------------------------------------------------------- computing

    Snapshot compute(Instant now) {
        long started = System.nanoTime();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        LocalDate since = today.minusDays(WINDOW_DAYS);
        Instant alertsSince = now.minus(Duration.ofDays(WINDOW_DAYS));
        boolean includeManagement = settings.settings().includeManagementServers();

        Map<String, Map<String, FleetRunRow>> runs = new HashMap<>();
        for (FleetRunRow row : repository.fleetLastRuns()) {
            runs.computeIfAbsent(row.deviceId(), k -> new HashMap<>()).put(row.sourceKey(), row);
        }
        List<CrashAlertRow> alertRows = repository.crashAlerts(Optional.empty(), ALERT_LIMIT);
        boolean alertsTruncated = alertRows.size() >= ALERT_LIMIT
                && alertRows.get(alertRows.size() - 1).createdAt() != null
                && alertRows.get(alertRows.size() - 1).createdAt().isAfter(alertsSince);
        Map<String, List<CrashAlertRow>> alertsByDevice = new HashMap<>();
        for (CrashAlertRow a : alertRows) {
            if (a.createdAt() != null && !a.createdAt().isBefore(alertsSince)) {
                alertsByDevice.computeIfAbsent(a.deviceId(), k -> new ArrayList<>()).add(a);
            }
        }

        List<DeviceSummaryRecord> listed = new ArrayList<>(targets.get());
        Set<String> targetIds = new HashSet<>();
        for (DeviceSummaryRecord d : listed) targetIds.add(d.deviceId());
        // Management servers of the two vendors are listed too, so the "collection off" reason is visible (mockup row MDS).
        for (DeviceSummaryRecord d : devices.listAll()) {
            if ("management_server".equals(d.role()) && SystemLogCollectionService.isSupportedVendor(d.vendorHint())
                    && !targetIds.contains(d.deviceId())) {
                listed.add(d);
            }
        }

        Set<String> sarSeen = new HashSet<>();
        Set<String> cpwdSeen = new HashSet<>();
        List<DeviceHealth> out = new ArrayList<>();
        for (DeviceSummaryRecord d : listed) {
            boolean target = targetIds.contains(d.deviceId());
            Map<String, FleetRunRow> deviceRuns = runs.getOrDefault(d.deviceId(), Map.of());
            out.add(one(d, target, includeManagement, deviceRuns, alertsByDevice.getOrDefault(d.deviceId(), List.of()),
                    today, since, sarSeen, cpwdSeen));
        }
        sarByFile.keySet().retainAll(sarSeen);
        cpwdByChunk.keySet().retainAll(cpwdSeen);
        out.sort(Comparator.comparingInt((DeviceHealth h) -> STATE_ORDER.indexOf(h.state()))
                .thenComparing(h -> h.newestCpuP95() == null ? -1.0 : h.newestCpuP95(), Comparator.reverseOrder())
                .thenComparing(h -> h.device().observedHostname().orElse(""))
                .thenComparing(h -> h.device().deviceId()));
        return new Snapshot(now, (System.nanoTime() - started) / 1_000_000L, threshold, List.copyOf(out), alertsTruncated, store != null);
    }

    private DeviceHealth one(DeviceSummaryRecord d, boolean target, boolean includeManagement, Map<String, FleetRunRow> runs,
            List<CrashAlertRow> alerts, LocalDate today, LocalDate since, Set<String> sarSeen, Set<String> cpwdSeen) {
        boolean checkPoint = "check_point".equals(d.vendorHint());
        boolean management = "management_server".equals(d.role());

        // ---- collection
        Instant lastJobAt = null;
        String lastJobId = null;
        for (FleetRunRow r : runs.values()) {
            if (r.createdAt() != null && (lastJobAt == null || r.createdAt().isAfter(lastJobAt))) {
                lastJobAt = r.createdAt();
                lastJobId = r.jobId();
            }
        }
        Map<String, String> outcomes = new TreeMap<>();
        for (FleetRunRow r : runs.values()) {
            if (r.jobId() != null && r.jobId().equals(lastJobId)) outcomes.put(r.sourceKey(), r.outcome());
        }
        String collectionState;
        String collectionReason;
        if (management && !includeManagement) {
            collectionState = "OFF";
            collectionReason = "SYSTEM_LOG_DISABLED_ON_MANAGEMENT";
        } else if (runs.isEmpty()) {
            collectionState = "NEVER";
            collectionReason = "NO_COLLECTION_RUN_RECORDED";
        } else {
            List<String> failed = new ArrayList<>();
            boolean unsupported = false;
            for (var e : outcomes.entrySet()) {
                if ("UNKNOWN".equals(e.getValue()) || "STORAGE_REFUSED".equals(e.getValue())) failed.add(e.getKey() + ":" + e.getValue());
                if ("UNSUPPORTED".equals(e.getValue())) unsupported = true;
            }
            if (!failed.isEmpty()) {
                collectionState = "FAILED";
                collectionReason = String.join(", ", failed);
            } else if (unsupported) {
                collectionState = "UNSUPPORTED";
                collectionReason = "UNSUPPORTED";
            } else {
                collectionState = "OK";
                collectionReason = null;
            }
        }

        // ---- CPU and memory (sar)
        Fact cpu;
        List<SarDay> sarDays = List.of();
        Double newestP95 = null;
        Double memP95 = null;
        int daysOver = 0, consecutive = 0;
        if (!checkPoint || management) {
            cpu = Fact.NOT_APPLICABLE;
        } else if (store == null) {
            cpu = new Fact("UNKNOWN", "SYSTEM_LOG_STORE_UNAVAILABLE");
        } else {
            sarDays = sarDays(d.deviceId(), since, today, sarSeen);
            if (sarDays.isEmpty()) {
                cpu = new Fact("UNKNOWN", "NO_SAR_DAY_STORED");
            } else {
                int streak = 0, longest = 0;
                boolean anyP95 = false;
                for (SarDay day : sarDays) {
                    Double p95 = day.summary() == null ? null : day.summary().cpuBusyP95();
                    if (p95 == null) {
                        streak = 0;
                        continue;
                    }
                    anyP95 = true;
                    if (p95 > threshold) {
                        daysOver++;
                        streak++;
                        longest = Math.max(longest, streak);
                    } else {
                        streak = 0;
                    }
                }
                consecutive = longest;
                SarDay newest = sarDays.get(sarDays.size() - 1);
                newestP95 = newest.summary() == null ? null : newest.summary().cpuBusyP95();
                memP95 = newest.summary() == null ? null : newest.summary().memUsedP95();
                if (!anyP95) cpu = new Fact("UNKNOWN", "SAR_TEXT_WITHOUT_CPU_SAMPLES");
                else if (consecutive >= CPU_DAYS_CRITICAL) cpu = new Fact("CRITICAL", "CPU_P95_OVER_THRESHOLD_" + consecutive + "_DAYS_IN_A_ROW");
                else if (daysOver > 0) cpu = new Fact("ATTENTION", "CPU_P95_OVER_THRESHOLD_" + daysOver + "_DAYS");
                else cpu = new Fact("NORMAL", null);
            }
        }

        // ---- process restarts (cpwd)
        Fact restarts;
        CpwdRestartLines.Summary cpwd = CpwdRestartLines.Summary.EMPTY;
        if (!checkPoint || management) {
            restarts = Fact.NOT_APPLICABLE;
        } else {
            FleetRunRow cpwdRun = runs.get("cp_cpwd_elg");
            if (cpwdRun == null) {
                restarts = new Fact("UNKNOWN", "CPWD_NOT_COLLECTED");
            } else if (store == null) {
                restarts = new Fact("UNKNOWN", "SYSTEM_LOG_STORE_UNAVAILABLE");
            } else {
                cpwd = cpwdSummary(d.deviceId(), since, cpwdSeen);
                boolean ran = "COLLECTED".equals(cpwdRun.outcome()) || "NO_NEW_DATA".equals(cpwdRun.outcome())
                        || "BACKLOG".equals(cpwdRun.outcome()) || "GAP".equals(cpwdRun.outcome());
                if (!ran && cpwd.lines() == 0) {
                    restarts = new Fact("UNKNOWN", "CPWD_LAST_OUTCOME_" + cpwdRun.outcome());
                } else if (cpwd.maxRestartsInOneHour() >= RESTARTS_PER_HOUR_ATTENTION) {
                    restarts = new Fact("ATTENTION", "RESTARTS_" + cpwd.maxRestartsInOneHour() + "_IN_ONE_HOUR");
                } else {
                    restarts = new Fact("NORMAL", null);
                }
            }
        }

        // ---- crash and dump files
        Fact crash;
        int newAlerts = alerts.size();
        Instant newestAlertAt = null;
        for (CrashAlertRow a : alerts) {
            if (newestAlertAt == null || a.createdAt().isAfter(newestAlertAt)) newestAlertAt = a.createdAt();
        }
        if (!checkPoint || management) {
            crash = Fact.NOT_APPLICABLE;
        } else if (newAlerts > 0) {
            crash = new Fact("CRITICAL", "NEW_CRASH_OR_DUMP_FILE_ALERT_" + newAlerts);
        } else if (runs.containsKey("cp_crash_listing")) {
            crash = new Fact("NORMAL", null);
        } else {
            crash = new Fact("UNKNOWN", "CRASH_LISTING_NOT_RUN");
        }

        // ---- the state word: the worst fact
        String state = "UNKNOWN";
        String stateReason = "NO_APPLICABLE_FACT";
        int worst = Integer.MAX_VALUE;
        for (Fact f : List.of(crash, cpu, restarts)) {
            int rank = STATE_ORDER.indexOf(f.level());
            if (rank >= 0 && rank < worst) {
                worst = rank;
                state = f.level();
                stateReason = f.reason();
            }
        }
        if (worst == Integer.MAX_VALUE && "OFF".equals(collectionState)) stateReason = "COLLECTION_OFF";

        LocalDate logsDay = !sarDays.isEmpty() ? sarDays.get(sarDays.size() - 1).day()
                : lastJobAt != null ? lastJobAt.atZone(ZONE).toLocalDate() : null;
        return new DeviceHealth(d, target, state, stateReason, cpu, sarDays, newestP95, daysOver, consecutive, memP95,
                restarts, cpwd, crash, newAlerts, newestAlertAt, collectionState, collectionReason, lastJobAt, lastJobId,
                outcomes, logsDay);
    }

    /** The stored sar days of the window, oldest first, one per calendar day (the newest fetch of a day wins). */
    private List<SarDay> sarDays(String deviceId, LocalDate since, LocalDate today, Set<String> seen) {
        Map<LocalDate, SarDay> byDay = new TreeMap<>();
        for (SarFileRef ref : repository.sarFileRefs(deviceId, 2 * WINDOW_DAYS)) {
            if (ref.prunedAt() != null) continue;
            LocalDate day = Instant.ofEpochSecond(ref.mtime()).atZone(ZONE).toLocalDate();
            if (day.isBefore(since) || day.isAfter(today) || byDay.containsKey(day)) continue;
            seen.add(ref.fileId());
            SarDailyText.Summary summary = null;
            if ("STORED".equals(ref.status())) {
                summary = sarByFile.computeIfAbsent(ref.fileId(), id -> parseSar(ref));
                if (summary != null && summary.cpuSamples() == 0 && !summary.headerSeen()) summary = null;
            }
            byDay.put(day, new SarDay(day, ref.fileId(), summary, summary == null ? ("STORED".equals(ref.status()) ? "UNREADABLE" : ref.status()) : "PARSED"));
        }
        return List.copyOf(byDay.values());
    }

    private SarDailyText.Summary parseSar(SarFileRef ref) {
        if (ref.artefactRef() == null || ref.wrappedDataKey() == null) return null;
        try (InputStream in = store.retrieve(new ArtefactRef(ref.artefactRef()), ref.wrappedDataKey(), true)) {
            return SarDailyText.parse(in);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "sar text unreadable file={0} cause={1}", ref.fileId(), e.getClass().getSimpleName());
            return null;
        }
    }

    private CpwdRestartLines.Summary cpwdSummary(String deviceId, LocalDate since, Set<String> seen) {
        CpwdRestartLines.Summary total = CpwdRestartLines.Summary.EMPTY;
        for (CpwdChunkRef ref : repository.cpwdChunkRefs(deviceId, since.toString(), 1000)) {
            if (ref.prunedAt() != null) continue;
            seen.add(ref.chunkId());
            CpwdRestartLines.Summary one = cpwdByChunk.computeIfAbsent(ref.chunkId(), id -> parseCpwd(ref));
            if (one != null) total = total.plus(one);
        }
        return total;
    }

    private CpwdRestartLines.Summary parseCpwd(CpwdChunkRef ref) {
        if (ref.artefactRef() == null || ref.wrappedDataKey() == null) return null;
        try (InputStream in = store.retrieve(new ArtefactRef(ref.artefactRef()), ref.wrappedDataKey(), true)) {
            return CpwdRestartLines.count(in);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "cpwd chunk unreadable chunk={0} cause={1}", ref.chunkId(), e.getClass().getSimpleName());
            return null;
        }
    }

    // ---------------------------------------------------------------- rendering

    Map<String, Object> render(Snapshot snapshot, String computation, Instant now) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> comp = new LinkedHashMap<>();
        comp.put("state", computation);
        comp.put("computed_at", snapshot == null ? null : snapshot.computedAt().toString());
        comp.put("duration_ms", snapshot == null ? null : snapshot.durationMs());
        comp.put("snapshot_ttl_seconds", SNAPSHOT_TTL.toSeconds());
        comp.put("store_available", snapshot == null ? store != null : snapshot.storeAvailable());
        body.put("computation", comp);
        body.put("window_days", WINDOW_DAYS);
        body.put("cpu_p95_threshold_percent", threshold);
        body.put("time_basis", "sar days and restart hours are device-local; alerts and job times are instants");
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("critical", List.of("a new crash or dump file alert in the last " + WINDOW_DAYS + " days (files present before monitoring never alarm)",
                "CPU p95 above " + threshold + " % on " + CPU_DAYS_CRITICAL + " stored sar days in a row"));
        rules.put("attention", List.of("CPU p95 above " + threshold + " % on 1 or 2 stored sar days",
                RESTARTS_PER_HOUR_ATTENTION + " or more cpwd process restarts in one hour"));
        rules.put("normal", "every applicable fact evidenced and none of the above");
        rules.put("unknown", "a fact with no evidence (no sar day, cpwd not collected, crash listing not run) outranks Normal; a device with no applicable fact is UNKNOWN");
        rules.put("state_order", STATE_ORDER);
        rules.put("p95", "nearest rank over the day's samples of the sar \"all\" CPU row, busy = 100 - %idle");
        Map<String, Object> restartRule = new LinkedHashMap<>();
        restartRule.put("id", CpwdRestartLines.RULE_ID);
        restartRule.put("status", CpwdRestartLines.RULE_STATUS);
        restartRule.put("text", CpwdRestartLines.RULE_TEXT);
        rules.put("restart_rule", restartRule);
        body.put("rules", rules);

        List<DeviceHealth> rows = snapshot == null ? List.of() : snapshot.devices();
        Map<String, Object> kpis = new LinkedHashMap<>();
        if (snapshot == null) {
            kpis.put("monitored", null);
            kpis.put("crash_alerts", null);
            kpis.put("cpu_over_threshold", null);
            kpis.put("collection_not_ok", null);
        } else {
            Map<String, Integer> byVendor = new TreeMap<>();
            int monitored = 0;
            for (DeviceHealth h : rows) {
                if (!h.target()) continue;
                monitored++;
                byVendor.merge(h.device().vendorHint(), 1, Integer::sum);
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("devices", monitored);
            m.put("by_vendor", byVendor);
            m.put("not_collected_listed", rows.size() - monitored);
            kpis.put("monitored", m);

            int alertDevices = 0, alerts = 0;
            for (DeviceHealth h : rows) {
                if (h.newAlerts() > 0) alertDevices++;
                alerts += h.newAlerts();
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("devices", alertDevices);
            c.put("alerts", alerts);
            c.put("truncated", snapshot.alertsTruncated());
            kpis.put("crash_alerts", c);

            int over = 0, evidenced = 0, unknown = 0, notApplicable = 0;
            LocalDate yesterday = now.atZone(ZONE).toLocalDate().minusDays(1);
            for (DeviceHealth h : rows) {
                if ("NOT_APPLICABLE".equals(h.cpu().level())) { notApplicable++; continue; }
                if (h.sarDays().isEmpty() || h.newestCpuP95() == null) { unknown++; continue; }
                evidenced++;
                SarDay newest = h.sarDays().get(h.sarDays().size() - 1);
                if (!newest.day().isBefore(yesterday) && h.newestCpuP95() > threshold) over++;
            }
            Map<String, Object> cpu = new LinkedHashMap<>();
            cpu.put("devices", over);
            cpu.put("evidenced", evidenced);
            cpu.put("unknown", unknown);
            cpu.put("not_applicable", notApplicable);
            cpu.put("basis", "newest stored sar day is yesterday or today and its p95 is above the threshold");
            kpis.put("cpu_over_threshold", cpu);

            int notOk = 0;
            Map<String, Integer> reasons = new TreeMap<>();
            for (DeviceHealth h : rows) {
                if ("OK".equals(h.collectionState())) continue;
                notOk++;
                reasons.merge(h.collectionState(), 1, Integer::sum);
            }
            Map<String, Object> coll = new LinkedHashMap<>();
            coll.put("devices", notOk);
            coll.put("by_state", reasons);
            kpis.put("collection_not_ok", coll);
        }
        body.put("kpis", kpis);

        List<Map<String, Object>> devicesOut = new ArrayList<>();
        for (DeviceHealth h : rows) devicesOut.add(renderDevice(h));
        body.put("devices", devicesOut);
        return body;
    }

    private Map<String, Object> renderDevice(DeviceHealth h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("device_id", h.device().deviceId());
        m.put("hostname", h.device().observedHostname().orElse(null));
        m.put("vendor_hint", h.device().vendorHint());
        m.put("role", h.device().role());
        m.put("cluster_member_ref", h.device().clusterMemberRef().orElse(null));
        m.put("monitored", h.target());
        m.put("state", h.state());
        m.put("state_reason", h.stateReason());

        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("level", h.cpu().level());
        cpu.put("reason", h.cpu().reason());
        cpu.put("threshold_percent", threshold);
        cpu.put("newest_p95_percent", round(h.newestCpuP95()));
        cpu.put("days_over_threshold", h.daysOver());
        cpu.put("longest_run_over_threshold", h.consecutiveOver());
        List<Map<String, Object>> days = new ArrayList<>();
        for (SarDay day : h.sarDays()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("day", day.day().toString());
            d.put("status", day.status());
            d.put("p95_percent", day.summary() == null ? null : round(day.summary().cpuBusyP95()));
            d.put("max_percent", day.summary() == null ? null : round(day.summary().cpuBusyMax()));
            d.put("samples", day.summary() == null ? null : day.summary().cpuSamples());
            d.put("cpus", day.summary() == null || day.summary().cpuCount() < 0 ? null : day.summary().cpuCount());
            d.put("unparsed_rows", day.summary() == null ? null : day.summary().unparsedRows());
            days.add(d);
        }
        cpu.put("days", days);
        cpu.put("day_basis", "device mtime of the sar file, calendar day in " + JobWindowPolicy.ZONE);
        m.put("cpu", cpu);

        Map<String, Object> mem = new LinkedHashMap<>();
        mem.put("level", "NOT_APPLICABLE".equals(h.cpu().level()) ? "NOT_APPLICABLE" : h.memP95() == null ? "UNKNOWN" : "EVIDENCED");
        mem.put("reason", "NOT_APPLICABLE".equals(h.cpu().level()) ? null : h.memP95() == null ? (h.sarDays().isEmpty() ? "NO_SAR_DAY_STORED" : "SAR_TEXT_WITHOUT_MEMORY_SECTION") : null);
        mem.put("newest_used_p95_percent", round(h.memP95()));
        m.put("memory", mem);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("level", h.restarts().level());
        r.put("reason", h.restarts().reason());
        boolean restartsKnown = !"UNKNOWN".equals(h.restarts().level()) && !"NOT_APPLICABLE".equals(h.restarts().level());
        r.put("count", restartsKnown ? h.cpwd().restartLines() : null);
        r.put("max_in_one_hour", restartsKnown ? h.cpwd().maxRestartsInOneHour() : null);
        r.put("termination_lines", restartsKnown ? h.cpwd().terminationLines() : null);
        r.put("busiest_hour", restartsKnown ? busiestHour(h.cpwd()) : null);
        r.put("rule", CpwdRestartLines.RULE_ID);
        m.put("restarts", r);

        Map<String, Object> c = new LinkedHashMap<>();
        c.put("level", h.crash().level());
        c.put("reason", h.crash().reason());
        c.put("new_alerts", "NOT_APPLICABLE".equals(h.crash().level()) ? null : h.newAlerts());
        c.put("newest_alert_at", h.newestAlertAt() == null ? null : h.newestAlertAt().toString());
        m.put("crash", c);

        Map<String, Object> coll = new LinkedHashMap<>();
        coll.put("state", h.collectionState());
        coll.put("reason", h.collectionReason());
        coll.put("last_job_at", h.lastJobAt() == null ? null : h.lastJobAt().toString());
        coll.put("last_job_id", h.lastJobId());
        coll.put("outcomes", h.outcomes());
        m.put("collection", coll);
        m.put("logs_day", h.logsDay() == null ? null : h.logsDay().toString());
        return m;
    }

    private static String busiestHour(CpwdRestartLines.Summary s) {
        String best = null;
        int max = 0;
        for (var e : s.restartsPerHour().entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                best = e.getKey();
            }
        }
        return best == null ? null : best + ":00 device time";
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10.0) / 10.0;
    }
}
