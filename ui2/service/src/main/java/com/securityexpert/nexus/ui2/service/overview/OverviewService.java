package com.securityexpert.nexus.ui2.service.overview;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.compliance.ComplianceService;

/**
 * {@code GET /api/v2/overview} (OVERVIEW_EXCEPTION_SCREEN_CONTRACT, FROZEN 2026-09-23): one aggregate over stored
 * evidence. Each section is computed on its own; a section that fails reads READ_FAILED and the others still
 * answer. Nothing here triggers a job, a compliance evaluation or a device command. Labels are returned raw
 * with a {@code label_kind}; the controller masks them for the AIView persona.
 */
@Service
public class OverviewService {

    private static final long DAY_S = 24 * 3600;

    private final TransactionBoundary tx;
    private final DeviceRepository deviceRepository;
    private final ComplianceService complianceService;

    public OverviewService(TransactionBoundary transactionBoundary, DeviceRepository deviceRepository,
            ComplianceService complianceService) {
        this.tx = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.complianceService = Objects.requireNonNull(complianceService, "complianceService");
    }

    /** Active = enrolled (the Inventory screen's predicate). */
    record Fleet(List<DeviceSummaryRecord> active, Map<String, DeviceSummaryRecord> byId, Map<String, List<String>> clusters) {
    }

    public Map<String, Object> build() {
        Instant now = Instant.now();
        List<DeviceSummaryRecord> all = deviceRepository.listAll();
        List<DeviceSummaryRecord> active = all.stream().filter(d -> d.enrollmentState() == DeviceEnrollmentState.ENROLLED).toList();
        Map<String, DeviceSummaryRecord> byId = new HashMap<>();
        all.forEach(d -> byId.put(d.deviceId(), d));
        Map<String, List<String>> clusters = new TreeMap<>();
        for (DeviceSummaryRecord d : active) {
            d.clusterMemberRef().ifPresent(ref -> clusters.computeIfAbsent(ref, k -> new ArrayList<>()).add(d.deviceId()));
        }
        Fleet fleet = new Fleet(active, byId, clusters);

        CompletableFuture<Object> evidence = section(() -> evidence());
        CompletableFuture<Object> attention = section(() -> attention(fleet, now));
        CompletableFuture<Object> inventoryAge = section(() -> inventoryAge(fleet, now));
        CompletableFuture<Object> compliance = section(this::compliance);
        CompletableFuture<Object> platform = section(() -> platform(fleet));
        CompletableFuture<Object> nexus = section(this::nexus);
        CompletableFuture.allOf(evidence, attention, inventoryAge, compliance, platform, nexus).join();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generated_at", now.toString());
        Map<String, Object> denominators = new LinkedHashMap<>();
        denominators.put("active_devices", active.size());
        denominators.put("enrolled_devices", all.size());
        denominators.put("clusters_enrolled", all.stream().map(d -> d.clusterMemberRef().orElse(null)).filter(Objects::nonNull).distinct().count());
        denominators.put("gateways", active.stream().filter(d -> "gateway".equals(d.role())).count());
        denominators.put("clusters", clusters.size());
        denominators.put("backup_targets", active.stream().filter(DeviceSummaryRecord::backupTarget).count());
        body.put("denominators", denominators);
        body.put("evidence", evidence.join());
        @SuppressWarnings("unchecked")
        Map<String, Object> att = attention.join() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of("state", "READ_FAILED");
        body.put("attention", att.getOrDefault("tiles", att));
        body.put("exceptions", att.getOrDefault("exceptions", Map.of("state", "READ_FAILED")));
        body.put("inventory_age", inventoryAge.join());
        body.put("compliance", compliance.join());
        body.put("platform", platform.join());
        body.put("nexus", nexus.join());
        return body;
    }

    private static CompletableFuture<Object> section(Supplier<Object> supplier) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                Map<String, Object> failed = new LinkedHashMap<>();
                failed.put("state", "READ_FAILED");
                failed.put("reason", e.getClass().getSimpleName());
                return failed;
            }
        });
    }

    private <T> T q(Function<DSLContext, T> work) {
        return tx.inTransaction(work::apply);
    }

    private static String iso(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }

    private static Map<String, Object> chip(Timestamp at) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("at", iso(at));
        c.put("state", at == null ? "UNKNOWN" : "OK");
        return c;
    }

    private Object evidence() {
        Record r = q(dsl -> dsl.fetchOne("select "
                + "(select max(collected_at) from device_inventory_run) as inventory, "
                + "(select max(collected_at) from device_configuration_run) as configuration, "
                + "(select max(created_at) from backup_artefact) as backup, "
                + "(select max(finished_at) from jobs where state in ('COMPLETED','FAILED')) as jobs, "
                + "(select max(observed_at) from device_platform_facts) as platform_facts"));
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("inventory", chip(r.get("inventory", Timestamp.class)));
        e.put("configuration", chip(r.get("configuration", Timestamp.class)));
        Map<String, Object> compliance = chip(r.get("configuration", Timestamp.class));
        if (!complianceService.isEvaluationCacheWarm()) {
            compliance.put("at", null);
            compliance.put("state", "UNKNOWN");
        }
        e.put("compliance", compliance);
        e.put("backup", chip(r.get("backup", Timestamp.class)));
        e.put("jobs", chip(r.get("jobs", Timestamp.class)));
        e.put("platform_facts", chip(r.get("platform_facts", Timestamp.class)));
        return e;
    }

    private Object attention(Fleet fleet, Instant now) {
        List<String> activeIds = fleet.active().stream().map(DeviceSummaryRecord::deviceId).toList();
        String[] ids = activeIds.toArray(String[]::new);
        Map<String, Object> tiles = new LinkedHashMap<>();
        Map<String, Object> exceptions = new LinkedHashMap<>();

        // 1. failed jobs, 24 h
        Record jobs = q(dsl -> dsl.fetchOne("select count(*) filter (where state = 'FAILED' and finished_at >= now() - interval '24 hours') as failed, "
                + "count(*) filter (where finished_at >= now() - interval '24 hours') as terminal, "
                + "count(*) filter (where state = 'FAILED' and finished_at < now() - interval '24 hours') as failed_prev "
                + "from jobs where state in ('COMPLETED','FAILED') and finished_at >= now() - interval '48 hours'"));
        List<FailedJob> failedAll = q(dsl -> dsl.fetch("select job_type, target_device_id, terminal_reason, finished_at from jobs "
                + "where state = 'FAILED' and finished_at >= now() - interval '24 hours'")).map(r -> new FailedJob(
                r.get("job_type", String.class), r.get("target_device_id", String.class),
                r.get("terminal_reason", String.class), r.get("finished_at", Timestamp.class)));
        Map<String, Object> failedTile = new LinkedHashMap<>();
        failedTile.put("count", jobs.get("failed", Long.class));
        failedTile.put("terminal_24h", jobs.get("terminal", Long.class));
        failedTile.put("last_at", iso(failedAll.stream().map(FailedJob::finishedAt).filter(Objects::nonNull).max(Timestamp::compareTo).orElse(null)));
        // "since yesterday" (review §6): the same count over the 24 h before this window.
        failedTile.put("previous", jobs.get("failed_prev", Long.class));
        failedTile.put("state", "OK");
        tiles.put("failed_jobs_24h", failedTile);
        List<Map<String, Object>> failedRows = new ArrayList<>();
        for (Record r : q(dsl -> dsl.fetch("select job_id, job_type, target_device_id, terminal_reason, finished_at from jobs "
                + "where state = 'FAILED' and finished_at >= now() - interval '24 hours' order by finished_at desc limit 5"))) {
            String deviceId = r.get("target_device_id", String.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("job_id", r.get("job_id", String.class));
            row.put("job_type", r.get("job_type", String.class));
            row.put("device_id", deviceId);
            row.put("label", deviceLabel(fleet, deviceId));
            row.put("label_cluster", clusterOf(fleet, deviceId));
            row.put("terminal_reason", r.get("terminal_reason", String.class));
            row.put("finished_at", iso(r.get("finished_at", Timestamp.class)));
            failedRows.add(row);
        }
        exceptions.put("failed_jobs", Map.of("total", jobs.get("failed", Long.class), "rows", failedRows, "reasons", failureReasons(failedAll)));

        // 2. devices without inventory in 24 h
        Map<String, Timestamp> latestInv = latestInventory(ids);
        long stale = activeIds.stream().filter(id -> {
            Timestamp t = latestInv.get(id);
            return t == null || t.toInstant().isBefore(now.minusSeconds(DAY_S));
        }).count();
        Map<String, Timestamp> invDayAgo = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select device_id, max(collected_at) as at from device_inventory_run "
                + "where device_id = any({0}) and collected_at < now() - interval '24 hours' group by device_id", (Object) ids))) {
            invDayAgo.put(r.get("device_id", String.class), r.get("at", Timestamp.class));
        }
        long staleDayAgo = activeIds.stream().filter(id -> {
            Timestamp t = invDayAgo.get(id);
            return t == null || t.toInstant().isBefore(now.minusSeconds(2 * DAY_S));
        }).count();
        Map<String, Object> staleTile = new LinkedHashMap<>();
        staleTile.put("count", stale);
        staleTile.put("of", activeIds.size());
        staleTile.put("previous", staleDayAgo);
        staleTile.put("state", "OK");
        tiles.put("stale_inventory", staleTile);

        // 3. clusters with member DIFF
        Map<String, Record> latestDiff = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select distinct on (cluster_ref) cluster_ref, comparable, diff_section_count, "
                + "diff_setting_count, diff_sections, computed_at from cluster_member_diff order by cluster_ref, computed_at desc"))) {
            latestDiff.put(r.get("cluster_ref", String.class), r);
        }
        int comparable = 0;
        int withDiff = 0;
        List<Record> diffRows = new ArrayList<>();
        List<String> allDiffRefs = new ArrayList<>();
        for (String ref : fleet.clusters().keySet()) {
            Record r = latestDiff.get(ref);
            if (r == null || !Boolean.TRUE.equals(r.get("comparable", Boolean.class))) {
                continue;
            }
            comparable++;
            if (r.get("diff_section_count", Integer.class) > 0) {
                withDiff++;
                diffRows.add(r);
                allDiffRefs.add(ref);
            }
        }
        Map<String, Object> diffTile = new LinkedHashMap<>();
        diffTile.put("count", withDiff);
        diffTile.put("of", comparable);
        diffTile.put("unknown", fleet.clusters().size() - comparable);
        diffTile.put("state", comparable == 0 ? "UNKNOWN" : "OK");
        tiles.put("cluster_diff", diffTile);
        diffRows.sort((a, b) -> Integer.compare(b.get("diff_section_count", Integer.class), a.get("diff_section_count", Integer.class)));
        List<Map<String, Object>> clusterRows = new ArrayList<>();
        for (Record r : diffRows.subList(0, Math.min(5, diffRows.size()))) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("cluster_ref", r.get("cluster_ref", String.class));
            row.put("diff_section_count", r.get("diff_section_count", Integer.class));
            row.put("diff_setting_count", r.get("diff_setting_count", Integer.class));
            String[] sections = r.get("diff_sections", String[].class);
            row.put("diff_sections", sections == null ? List.of() : List.of(sections));
            row.put("computed_at", iso(r.get("computed_at", Timestamp.class)));
            clusterRows.add(row);
        }
        Map<String, Object> clusterExceptions = new LinkedHashMap<>();
        clusterExceptions.put("total", withDiff);
        clusterExceptions.put("rows", clusterRows);
        clusterExceptions.put("all_refs", allDiffRefs);
        clusterExceptions.put("unknown", fleet.clusters().size() - comparable);
        exceptions.put("cluster_diff", clusterExceptions);

        // 4. configuration changed since the previous collection
        List<Record> latestCfg = q(dsl -> dsl.fetch("select distinct on (r.device_id) r.device_id, r.run_id, r.change_state, "
                + "r.collected_at, (select count(*) from device_configuration_index i where i.run_id = r.run_id) as sections "
                + "from device_configuration_run r where r.is_primary and r.device_id = any({0}) "
                + "order by r.device_id, r.collected_at desc", (Object) ids));
        List<Record> changed = latestCfg.stream().filter(r -> "changed".equals(r.get("change_state", String.class)))
                .sorted((a, b) -> b.get("collected_at", Timestamp.class).compareTo(a.get("collected_at", Timestamp.class))).toList();
        tiles.put("config_changed", Map.of("count", changed.size(), "of", latestCfg.size(), "state", "OK"));
        List<Map<String, Object>> changeRows = new ArrayList<>();
        for (Record r : changed.subList(0, Math.min(5, changed.size()))) {
            String deviceId = r.get("device_id", String.class);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("device_id", deviceId);
            row.put("label", deviceLabel(fleet, deviceId));
            row.put("label_cluster", clusterOf(fleet, deviceId));
            row.put("run_id", r.get("run_id", String.class));
            row.put("sections_latest", r.get("sections", Long.class));
            row.put("collected_at", iso(r.get("collected_at", Timestamp.class)));
            changeRows.add(row);
        }
        exceptions.put("config_changes", Map.of("total", changed.size(), "rows", changeRows));

        // 5. backup targets without an archive
        List<String> targets = fleet.active().stream().filter(DeviceSummaryRecord::backupTarget).map(DeviceSummaryRecord::deviceId).toList();
        List<String> withArchive = q(dsl -> dsl.fetch("select distinct device_id from backup_artefact where artefact_class = 'backup' "
                + "and device_id = any({0})", (Object) targets.toArray(String[]::new)).map(r -> r.get(0, String.class)));
        long missing = targets.stream().filter(t -> !withArchive.contains(t)).count();
        List<String> withArchiveDayAgo = q(dsl -> dsl.fetch("select distinct device_id from backup_artefact where artefact_class = 'backup' "
                + "and created_at < now() - interval '24 hours' and device_id = any({0})", (Object) targets.toArray(String[]::new)).map(r -> r.get(0, String.class)));
        Map<String, Object> backupTile = new LinkedHashMap<>();
        backupTile.put("count", missing);
        backupTile.put("of", targets.size());
        // today's targets measured against yesterday's archives; a target added today counts as missing yesterday too
        backupTile.put("previous", targets.stream().filter(t -> !withArchiveDayAgo.contains(t)).count());
        backupTile.put("state", "OK");
        tiles.put("backup_missing", backupTile);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tiles", tiles);
        out.put("exceptions", exceptions);
        return out;
    }

    record FailedJob(String jobType, String deviceId, String reason, Timestamp finishedAt) {
    }

    /**
     * Failed jobs of the last 24 h grouped by why they failed (amendment A-2026-09-23): the reason code plus the
     * stable head of its detail, numbers folded, cut before a parenthesis, a nested colon or a "for <target>"
     * tail, so one cause is one row and no target name reaches the key. Largest group first, at most six.
     */
    static List<Map<String, Object>> failureReasons(List<FailedJob> failed) {
        Map<String, List<FailedJob>> groups = new LinkedHashMap<>();
        for (FailedJob f : failed) {
            groups.computeIfAbsent(reasonKey(f.reason()), k -> new ArrayList<>()).add(f);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        groups.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()))
                .limit(6)
                .forEach(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("reason", e.getKey());
                    row.put("count", e.getValue().size());
                    row.put("devices", e.getValue().stream().map(FailedJob::deviceId).filter(Objects::nonNull).distinct().count());
                    row.put("job_types", e.getValue().stream().map(FailedJob::jobType).filter(Objects::nonNull).distinct().sorted().toList());
                    row.put("last_at", iso(e.getValue().stream().map(FailedJob::finishedAt).filter(Objects::nonNull).max(Timestamp::compareTo).orElse(null)));
                    out.add(row);
                });
        return out;
    }

    static String reasonKey(String reason) {
        if (reason == null || reason.isBlank()) {
            return "no reason recorded";
        }
        String s = reason.strip();
        int colon = s.indexOf(':');
        String code = colon > 0 ? s.substring(0, colon).strip() : s;
        String detail = colon > 0 ? s.substring(colon + 1).strip() : "";
        int cut = detail.length();
        for (String stop : List.of(":", " (", " for ")) {
            int i = detail.indexOf(stop);
            if (i >= 0 && i < cut) {
                cut = i;
            }
        }
        detail = detail.substring(0, cut).replaceAll("\\d+", "N").strip();
        if (detail.length() > 72) {
            detail = detail.substring(0, 72).strip() + "…";
        }
        if (code.length() > 48) {
            code = code.substring(0, 48).strip() + "…";
        }
        return detail.isEmpty() ? code : code + ": " + detail;
    }

    /** Every device's newest inventory run, for the device list ({@code inventory_collected_at}). */
    public Map<String, Instant> latestInventoryAll() {
        Map<String, Instant> out = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select device_id, max(collected_at) as at from device_inventory_run group by device_id"))) {
            out.put(r.get("device_id", String.class), r.get("at", Timestamp.class).toInstant());
        }
        return out;
    }

    private Map<String, Timestamp> latestInventory(String[] ids) {
        Map<String, Timestamp> latest = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select device_id, max(collected_at) as at from device_inventory_run "
                + "where device_id = any({0}) group by device_id", (Object) ids))) {
            latest.put(r.get("device_id", String.class), r.get("at", Timestamp.class));
        }
        return latest;
    }

    private static String deviceLabel(Fleet fleet, String deviceId) {
        DeviceSummaryRecord d = fleet.byId().get(deviceId);
        return d == null ? null : d.observedHostname().orElse(null);
    }

    private static String clusterOf(Fleet fleet, String deviceId) {
        DeviceSummaryRecord d = fleet.byId().get(deviceId);
        return d == null ? null : d.clusterMemberRef().orElse(null);
    }

    private Object inventoryAge(Fleet fleet, Instant now) {
        String[] ids = fleet.active().stream().map(DeviceSummaryRecord::deviceId).toArray(String[]::new);
        Map<String, Timestamp> latest = latestInventory(ids);
        long lt24 = 0;
        long h24to72 = 0;
        long gt72 = 0;
        long never = 0;
        for (String id : ids) {
            Timestamp t = latest.get(id);
            if (t == null) {
                never++;
                continue;
            }
            long age = now.getEpochSecond() - t.toInstant().getEpochSecond();
            if (age < DAY_S) {
                lt24++;
            } else if (age <= 3 * DAY_S) {
                h24to72++;
            } else {
                gt72++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lt24h", lt24);
        out.put("h24_72", h24to72);
        out.put("gt72h", gt72);
        out.put("never", never);
        out.put("of", ids.length);
        return out;
    }

    private Object compliance() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!complianceService.isEvaluationCacheWarm()) {
            out.put("state", "UNKNOWN");
            out.put("reason", "not_evaluated");
            return out;
        }
        Map<String, Object> o = complianceService.getOverview();
        out.put("state", "OK");
        out.put("reason", null);
        out.put("evaluated", o.get("evaluated_firewalls"));
        out.put("of_firewalls", o.get("total_firewalls"));
        out.put("observed_pct", o.get("observed_compliance_pct"));
        out.put("assured_pct", o.get("assured_compliance_pct"));
        out.put("coverage_pct", o.get("evidence_coverage_pct"));
        out.put("critical_deficiencies", o.get("critical_deficiencies"));
        out.put("data_gaps", o.get("data_gaps"));
        List<Map<String, Object>> frameworks = new ArrayList<>();
        if (o.get("frameworks") instanceof List<?> list) {
            for (Object f : list) {
                if (f instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", m.get("framework"));
                    row.put("pass", m.get("pass_count"));
                    row.put("fail", m.get("fail_count"));
                    row.put("unavailable", m.get("data_unavailable_count"));
                    row.put("total", m.get("total_controls"));
                    row.put("score_pct", m.get("score_pct"));
                    frameworks.add(row);
                }
            }
        }
        out.put("frameworks", frameworks);
        return out;
    }

    private Object platform(Fleet fleet) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("devices", fleet.active().size());
        out.put("check_point", fleet.active().stream().filter(d -> "check_point".equals(d.vendorHint())).count());
        out.put("palo_alto", fleet.active().stream().filter(d -> "palo_alto".equals(d.vendorHint())).count());
        out.put("clusters", fleet.clusters().size());
        String[] cpGateways = fleet.active().stream().filter(d -> "check_point".equals(d.vendorHint()) && "gateway".equals(d.role()))
                .map(DeviceSummaryRecord::deviceId).toArray(String[]::new);
        Map<String, Long> levels = new TreeMap<>();
        Map<String, String> byDevice = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select device_id, hotfix_level from device_platform_facts where device_id = any({0})",
                (Object) cpGateways))) {
            byDevice.put(r.get("device_id", String.class), r.get("hotfix_level", String.class));
        }
        long unknown = 0;
        for (String id : cpGateways) {
            String level = byDevice.get(id);
            if (level == null) {
                unknown++;
            } else {
                levels.merge(level, 1L, Long::sum);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        levels.forEach((level, count) -> rows.add(Map.of("level", level, "count", count)));
        Map<String, Object> unknownRow = new LinkedHashMap<>();
        unknownRow.put("level", null);
        unknownRow.put("count", unknown);
        rows.add(unknownRow);
        out.put("hotfix_levels", rows);
        out.put("versions", versions(fleet));
        out.put("evidence_at", iso(q(dsl -> dsl.fetchOne("select max(observed_at) from device_platform_facts").get(0, Timestamp.class))));
        return out;
    }

    /**
     * Software versions per vendor, as the executive summary's donuts (PO 2026-09-23): Check Point major = the
     * software version (R81.20), minor = the jumbo hotfix take; Palo Alto major = the first two numbers of
     * PAN-OS (11.1), minor = the full version (11.1.10-h7). Unread values count under a null label (UNKNOWN).
     * Versions are opaque strings: grouped by equality, never parsed beyond the Palo Alto major prefix.
     * Hardware model (amendment A-2026-09-23): Check Point = the appliance family from {@code show asset system},
     * Palo Alto = the model {@code show system info} reports.
     */
    private Map<String, Object> versions(Fleet fleet) {
        String[] ids = fleet.active().stream().map(DeviceSummaryRecord::deviceId).toArray(String[]::new);
        Map<String, String> hotfix = new HashMap<>();
        Map<String, String> family = new HashMap<>();
        for (Record r : q(dsl -> dsl.fetch("select device_id, hotfix_level, platform_family from device_platform_facts where device_id = any({0})",
                (Object) ids))) {
            hotfix.put(r.get("device_id", String.class), r.get("hotfix_level", String.class));
            family.put(r.get("device_id", String.class), r.get("platform_family", String.class));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (String vendor : List.of("check_point", "palo_alto")) {
            Map<String, Long> major = new TreeMap<>();
            Map<String, Long> minor = new TreeMap<>();
            Map<String, Long> model = new TreeMap<>();
            long unknownMajor = 0;
            long unknownMinor = 0;
            long unknownModel = 0;
            for (DeviceSummaryRecord d : fleet.active()) {
                if (!vendor.equals(d.vendorHint())) {
                    continue;
                }
                String sw = d.observedSoftwareVersion().map(String::strip).filter(s -> !s.isEmpty()).orElse(null);
                String maj = null;
                String min = null;
                if ("check_point".equals(vendor)) {
                    maj = sw;
                    min = hotfix.get(d.deviceId());
                } else if (sw != null) {
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+\\.\\d+)").matcher(sw);
                    maj = m.find() ? m.group(1) : sw;
                    min = sw;
                }
                if (maj == null) {
                    unknownMajor++;
                } else {
                    major.merge(maj, 1L, Long::sum);
                }
                if (min == null) {
                    unknownMinor++;
                } else {
                    minor.merge(min, 1L, Long::sum);
                }
                String mdl = "check_point".equals(vendor) ? family.get(d.deviceId()) : d.observedModel().orElse(null);
                mdl = mdl == null || mdl.isBlank() ? null : mdl.strip();
                if (mdl == null) {
                    unknownModel++;
                } else {
                    model.merge(mdl, 1L, Long::sum);
                }
            }
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("major", slices(major, unknownMajor));
            v.put("minor", slices(minor, unknownMinor));
            v.put("model", slices(model, unknownModel));
            out.put(vendor, v);
        }
        return out;
    }

    private static List<Map<String, Object>> slices(Map<String, Long> counts, long unknown) {
        List<Map<String, Object>> rows = new ArrayList<>();
        counts.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(e -> rows.add(Map.of("label", e.getKey(), "count", e.getValue())));
        if (unknown > 0) {
            Map<String, Object> u = new LinkedHashMap<>();
            u.put("label", null);
            u.put("count", unknown);
            rows.add(u);
        }
        return rows;
    }

    private Object nexus() {
        Record r = q(dsl -> dsl.fetchOne("select "
                + "count(*) filter (where state = 'COMPLETED' and finished_at >= now() - interval '24 hours') as completed_24h, "
                + "count(*) filter (where state in ('REQUESTED','CLAIMED','EXECUTING')) as running, "
                + "min(submitted_at) filter (where state in ('CLAIMED','EXECUTING')) as oldest_running, "
                + "max(finished_at) filter (where state = 'COMPLETED' and job_type = 'cp_inventory_collect') as cp_inventory, "
                + "max(finished_at) filter (where state = 'COMPLETED' and job_type = 'pan_inventory_collect') as pan_inventory from jobs"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("completed_24h", r.get("completed_24h", Long.class));
        out.put("running", r.get("running", Long.class));
        out.put("oldest_running_submitted_at", iso(r.get("oldest_running", Timestamp.class)));
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("check_point", iso(r.get("cp_inventory", Timestamp.class)));
        last.put("palo_alto", iso(r.get("pan_inventory", Timestamp.class)));
        out.put("last_inventory", last);
        return out;
    }
}
