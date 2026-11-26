package com.securityexpert.nexus.ui2.persistence.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.FindingRow;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.LayerB;
import com.securityexpert.nexus.ui2.persistence.logdetect.LogDetectionRepository.PendingChunk;

/**
 * {@link LogTemplateRepository} over V273. Plain, parameterised SQL like {@link JooqLogDetectionRepository}. The catalog
 * row (build and freeze) and the engine's REOPEN go through {@link AuditedTransactionBoundary}; the derived rows (templates,
 * baselines, counts, index states, findings) through the plain boundary.
 */
public final class JooqLogTemplateRepository implements LogTemplateRepository {
    public static final String CATALOG_BUILD_ACTION = "log_detection_settings_write";
    public static final String CATALOG_FREEZE_ACTION = "log_detection_settings_write";
    private static final int BATCH = 500;

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLogTemplateRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    private static String inList(Collection<String> values) {
        // Values are closed-set source keys or template ids, still passed as bind values: this only builds placeholders.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) sb.append(i == 0 ? "" : ", ").append('{').append(i).append('}');
        return sb.toString();
    }

    private static Object[] binds(Collection<String> values, Object... rest) {
        Object[] out = new Object[values.size() + rest.length];
        int i = 0;
        for (String v : values) out[i++] = v;
        for (Object o : rest) out[i++] = o;
        return out;
    }

    // ------------------------------------------------------------ catalog

    @Override
    public List<TrainingChunk> trainingChunks(Collection<String> sources, LocalDate from, LocalDate to, int limit) {
        if (sources.isEmpty()) return List.of();
        int n = sources.size();
        int bounded = Math.max(1, Math.min(limit, 200_000));
        return transactions.inTransaction(dsl -> dsl.fetch("select chunk_id, device_id, source_key, cast(log_day as text) as log_day "
                + "from system_log_chunk where pruned_at is null and source_key in (" + inList(sources) + ") "
                + "and log_day >= cast({" + n + "} as date) and log_day <= cast({" + (n + 1) + "} as date) "
                + "order by source_key, log_day, device_id, chunk_id limit {" + (n + 2) + "}",
                binds(sources, from.toString(), to.toString(), bounded))
                .map(r -> new TrainingChunk(r.get("chunk_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), LocalDate.parse(r.get("log_day", String.class)))));
    }

    @Override
    public Map<String, Set<LocalDate>> gapDays(Collection<String> sources, LocalDate from, LocalDate to) {
        if (sources.isEmpty()) return Map.of();
        int n = sources.size();
        Map<String, Set<LocalDate>> out = new TreeMap<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select distinct device_id, source_key, cast(cast(created_at at time zone 'Europe/Istanbul' as date) as text) as d "
                    + "from system_log_gap where source_key in (" + inList(sources) + ") "
                    + "and cast(created_at at time zone 'Europe/Istanbul' as date) between cast({" + n + "} as date) "
                    + "and cast({" + (n + 1) + "} as date)", binds(sources, from.toString(), to.toString()))
                    .forEach(r -> out.computeIfAbsent(r.get("device_id", String.class) + "|" + r.get("source_key", String.class),
                            k -> new TreeSet<>()).add(LocalDate.parse(r.get("d", String.class))));
            return null;
        });
        return out;
    }

    @Override
    public String nextCatalogVersion() {
        Integer max = transactions.inTransaction(dsl -> dsl.fetchOne(
                "select coalesce(max(cast(substring(catalog_version from 5) as integer)), 0) as n from log_template_catalog")
                .get("n", Integer.class));
        return "cat-" + (max + 1);
    }

    @Override
    public void saveDraft(String actorFingerprint, CatalogRow c, List<TemplateRow> templates, List<DeviceWindowRow> windows,
            List<DeviceStatRow> stats) {
        audited.inTransaction(actorFingerprint, CATALOG_BUILD_ACTION, dsl -> {
            dsl.execute("insert into log_template_catalog(catalog_version, state, previous_version, manifest, digest, "
                    + "template_count, diff, created_by) values ({0}, 'DRAFT', null, cast({1} as jsonb), {2}, {3}, "
                    + "cast({4} as jsonb), {5})", c.catalogVersion(), c.manifestJson(), c.digest(), c.templateCount(), "{}",
                    actorFingerprint);
            for (int i = 0; i < templates.size(); i += BATCH) {
                var batch = new ArrayList<org.jooq.Query>();
                for (TemplateRow t : templates.subList(i, Math.min(templates.size(), i + BATCH))) {
                    batch.add(dsl.query("insert into log_template(catalog_version, template_id, source_key, template_text, "
                            + "match_form, token_count, marker_id, severity_weight, template_class, provenance, line_count, "
                            + "prevalence_devices, cohort_devices, fleet_first_seen) values ({0}, {1}, {2}, {3}, {4}, {5}, "
                            + "{6}, {7}, {8}, {9}, {10}, {11}, {12}, cast({13} as date))", t.catalogVersion(), t.templateId(),
                            t.sourceKey(), t.templateText(), t.matchForm(), t.tokenCount(), t.markerId(), t.severityWeight(),
                            t.templateClass(), t.provenance(), t.lineCount(), t.prevalenceDevices(), t.cohortDevices(),
                            t.fleetFirstSeen() == null ? null : t.fleetFirstSeen().toString()));
                }
                dsl.batch(batch).execute();
            }
            for (int i = 0; i < windows.size(); i += BATCH) {
                var batch = new ArrayList<org.jooq.Query>();
                for (DeviceWindowRow w : windows.subList(i, Math.min(windows.size(), i + BATCH))) {
                    batch.add(dsl.query("insert into log_template_device_window(catalog_version, device_id, source_key, "
                            + "usable_days, first_day, last_day) values ({0}, {1}, {2}, {3}, cast({4} as date), cast({5} as date))",
                            w.catalogVersion(), w.deviceId(), w.sourceKey(), w.usableDays(), w.firstDay().toString(),
                            w.lastDay().toString()));
                }
                dsl.batch(batch).execute();
            }
            for (int i = 0; i < stats.size(); i += BATCH) {
                var batch = new ArrayList<org.jooq.Query>();
                for (DeviceStatRow s : stats.subList(i, Math.min(stats.size(), i + BATCH))) {
                    batch.add(dsl.query("insert into log_template_device_stat(catalog_version, device_id, source_key, "
                            + "template_id, segment, first_seen, last_seen, line_count, presence_days, usable_days, "
                            + "presence_share, periodicity, baseline_median, baseline_mad, buckets_used) values ({0}, {1}, "
                            + "{2}, {3}, {4}, cast({5} as date), cast({6} as date), {7}, {8}, {9}, {10}, {11}, {12}, {13}, {14})",
                            s.catalogVersion(), s.deviceId(), s.sourceKey(), s.templateId(), s.segment(),
                            s.firstSeen().toString(), s.lastSeen().toString(), s.lineCount(), s.presenceDays(),
                            s.usableDays(), round(s.presenceShare(), 4), round(s.periodicity(), 4), round(s.baselineMedian(), 2),
                            round(s.baselineMad(), 2), s.bucketsUsed()));
                }
                dsl.batch(batch).execute();
            }
            return null;
        });
    }

    private static java.math.BigDecimal round(double v, int scale) {
        return java.math.BigDecimal.valueOf(v).setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    private static final String CATALOG_COLUMNS = "catalog_version, state, previous_version, cast(manifest as text) as manifest, "
            + "digest, template_count, cast(diff as text) as diff, created_at, frozen_at";

    private static CatalogRow catalogRow(Record r) {
        return new CatalogRow(r.get("catalog_version", String.class), r.get("state", String.class),
                r.get("previous_version", String.class), r.get("manifest", String.class), r.get("digest", String.class),
                r.get("template_count", Integer.class), r.get("diff", String.class),
                JooqLogDetectionRepository.instant(r, "created_at"), JooqLogDetectionRepository.instant(r, "frozen_at"));
    }

    @Override
    public Optional<CatalogRow> catalog(String catalogVersion) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional("select " + CATALOG_COLUMNS
                + " from log_template_catalog where catalog_version = {0}", catalogVersion).map(JooqLogTemplateRepository::catalogRow));
    }

    @Override
    public List<CatalogRow> catalogs() {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + CATALOG_COLUMNS + " from log_template_catalog "
                + "order by cast(substring(catalog_version from 5) as integer) desc limit 100")
                .map(JooqLogTemplateRepository::catalogRow));
    }

    @Override
    public Optional<CatalogRow> activeCatalog() {
        return transactions.inTransaction(dsl -> dsl.fetchOptional("select " + CATALOG_COLUMNS + " from log_template_catalog "
                + "where state = 'FROZEN' order by cast(substring(catalog_version from 5) as integer) desc limit 1")
                .map(JooqLogTemplateRepository::catalogRow));
    }

    @Override
    public List<TemplateRow> templates(String catalogVersion) {
        return transactions.inTransaction(dsl -> dsl.fetch("select catalog_version, template_id, source_key, template_text, "
                + "match_form, token_count, marker_id, severity_weight, template_class, provenance, line_count, "
                + "prevalence_devices, cohort_devices, cast(fleet_first_seen as text) as fleet_first_seen from log_template "
                + "where catalog_version = {0} order by source_key, template_id", catalogVersion)
                .map(r -> new TemplateRow(r.get("catalog_version", String.class), r.get("template_id", String.class),
                        r.get("source_key", String.class), r.get("template_text", String.class),
                        r.get("match_form", String.class), r.get("token_count", Integer.class), r.get("marker_id", String.class),
                        r.get("severity_weight", java.math.BigDecimal.class).doubleValue(),
                        r.get("template_class", String.class), r.get("provenance", String.class),
                        r.get("line_count", Long.class), r.get("prevalence_devices", Integer.class),
                        r.get("cohort_devices", Integer.class), day(r.get("fleet_first_seen", String.class)))));
    }

    private static LocalDate day(String text) {
        return text == null ? null : LocalDate.parse(text);
    }

    @Override
    public Optional<CatalogRow> freeze(String actorFingerprint, String catalogVersion, String previousVersion, String diffJson) {
        return audited.inTransaction(actorFingerprint, CATALOG_FREEZE_ACTION, dsl -> {
            int updated = dsl.execute("update log_template_catalog set state = 'FROZEN', frozen_at = now(), frozen_by = {1}, "
                    + "previous_version = {2}, diff = cast({3} as jsonb) where catalog_version = {0} and state = 'DRAFT'",
                    catalogVersion, actorFingerprint, previousVersion, diffJson);
            if (updated == 0) return Optional.<CatalogRow>empty();
            return dsl.fetchOptional("select " + CATALOG_COLUMNS + " from log_template_catalog where catalog_version = {0}",
                    catalogVersion).map(JooqLogTemplateRepository::catalogRow);
        });
    }

    @Override
    public List<DeviceStatRow> deviceStats(String catalogVersion, String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> dsl.fetch("select catalog_version, device_id, source_key, template_id, segment, "
                + "cast(first_seen as text) as first_seen, cast(last_seen as text) as last_seen, line_count, presence_days, "
                + "usable_days, presence_share, periodicity, baseline_median, baseline_mad, buckets_used "
                + "from log_template_device_stat where catalog_version = {0} and device_id = {1} and source_key = {2} "
                + "order by template_id, segment", catalogVersion, deviceId, sourceKey)
                .map(r -> new DeviceStatRow(r.get("catalog_version", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("template_id", String.class), r.get("segment", String.class),
                        LocalDate.parse(r.get("first_seen", String.class)), LocalDate.parse(r.get("last_seen", String.class)),
                        r.get("line_count", Long.class), r.get("presence_days", Integer.class),
                        r.get("usable_days", Integer.class), JooqLogDetectionRepository.decimal(r, "presence_share"),
                        JooqLogDetectionRepository.decimal(r, "periodicity"),
                        JooqLogDetectionRepository.decimal(r, "baseline_median"),
                        JooqLogDetectionRepository.decimal(r, "baseline_mad"), r.get("buckets_used", Integer.class))));
    }

    @Override
    public Optional<DeviceWindowRow> deviceWindow(String catalogVersion, String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional("select usable_days, cast(first_day as text) as first_day, "
                + "cast(last_day as text) as last_day from log_template_device_window where catalog_version = {0} "
                + "and device_id = {1} and source_key = {2}", catalogVersion, deviceId, sourceKey)
                .map(r -> new DeviceWindowRow(catalogVersion, deviceId, sourceKey, r.get("usable_days", Integer.class),
                        LocalDate.parse(r.get("first_day", String.class)), LocalDate.parse(r.get("last_day", String.class)))));
    }

    @Override
    public Map<String, Integer> catalogPrevalence(String catalogVersion, String sourceKey, Collection<String> templateIds) {
        if (templateIds.isEmpty()) return Map.of();
        int n = templateIds.size();
        Map<String, Integer> out = new TreeMap<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select template_id, prevalence_devices from log_template where template_id in (" + inList(templateIds)
                    + ") and catalog_version = {" + n + "} and source_key = {" + (n + 1) + "}",
                    binds(templateIds, catalogVersion, sourceKey))
                    .forEach(r -> out.put(r.get("template_id", String.class), r.get("prevalence_devices", Integer.class)));
            return null;
        });
        return out;
    }

    // ------------------------------------------------------------ online

    @Override
    public List<PendingChunk> pendingChunks(String catalogVersion, String maskingRuleSet, String normaliserVersion,
            Collection<String> sources, int limit) {
        if (sources.isEmpty()) return List.of();
        int n = sources.size();
        int bounded = Math.max(1, Math.min(limit, 500));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select c.chunk_id, c.device_id, c.source_key, c.job_id, cast(c.log_day as text) as log_day "
                        + "from system_log_chunk c where c.pruned_at is null and c.source_key in (" + inList(sources) + ") "
                        + "and exists (select 1 from system_log_run r where r.device_id = c.device_id and r.job_id = c.job_id) "
                        + "and not exists (select 1 from log_template_index_state s where s.chunk_id = c.chunk_id "
                        + "and s.catalog_version = {" + n + "} and s.masking_rule_set = {" + (n + 1) + "} "
                        + "and s.normaliser_version = {" + (n + 2) + "} "
                        + "and (s.status not in ('MASKING_UNAVAILABLE', 'STORE_UNAVAILABLE', 'UNREADABLE') "
                        + "or s.indexed_at > now() - interval '15 minutes')) "
                        + "order by c.collected_at, c.chunk_id limit {" + (n + 3) + "}",
                binds(sources, catalogVersion, maskingRuleSet, normaliserVersion, bounded))
                .map(r -> new PendingChunk(r.get("chunk_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("job_id", String.class),
                        LocalDate.parse(r.get("log_day", String.class)))));
    }

    @Override
    public void replaceChunkCounts(TemplateIndexState state, List<CountRow> rows, List<ProvisionalTemplate> provisional) {
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from log_template_count where chunk_id = {0} and catalog_version = {1}", state.chunkId(),
                    state.catalogVersion());
            for (ProvisionalTemplate p : provisional) {
                dsl.execute("insert into log_provisional_template(template_id, source_key, template_text, first_seen_day) "
                        + "values ({0}, {1}, {2}, cast({3} as date)) on conflict (template_id) do update set "
                        + "first_seen_day = least(log_provisional_template.first_seen_day, excluded.first_seen_day)",
                        p.templateId(), p.sourceKey(), p.templateText(), p.firstSeenDay().toString());
            }
            for (int i = 0; i < rows.size(); i += BATCH) {
                var batch = new ArrayList<org.jooq.Query>();
                for (CountRow c : rows.subList(i, Math.min(rows.size(), i + BATCH))) {
                    batch.add(dsl.query("insert into log_template_count(count_id, chunk_id, catalog_version, device_id, "
                            + "source_key, template_id, provisional, bucket_start, severity_weight, marker_id, line_count, "
                            + "first_line_time, last_line_time, pointers) values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, "
                            + "{8}, {9}, {10}, {11}, {12}, cast({13} as jsonb)) on conflict (count_id) do update set "
                            + "line_count = excluded.line_count, first_line_time = excluded.first_line_time, "
                            + "last_line_time = excluded.last_line_time, severity_weight = excluded.severity_weight, "
                            + "marker_id = excluded.marker_id, pointers = excluded.pointers", c.countId(), c.chunkId(),
                            c.catalogVersion(), c.deviceId(), c.sourceKey(), c.templateId(), c.provisional(), c.bucketStart(),
                            round(c.severityWeight(), 2), c.markerId(), c.lineCount(), c.firstLineTime(), c.lastLineTime(),
                            JooqLogDetectionRepository.pointersJson(c.pointers())));
                }
                dsl.batch(batch).execute();
            }
            upsertState(dsl, state);
            return null;
        });
    }

    @Override
    public void recordIndexState(TemplateIndexState state) {
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from log_template_count where chunk_id = {0} and catalog_version = {1}", state.chunkId(),
                    state.catalogVersion());
            upsertState(dsl, state);
            return null;
        });
    }

    private static void upsertState(DSLContext dsl, TemplateIndexState s) {
        dsl.execute("insert into log_template_index_state(chunk_id, catalog_version, masking_rule_set, normaliser_version, "
                        + "device_id, source_key, job_id, log_day, status, lines, matched, provisional, unparsed, indexed_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, cast({7} as date), {8}, {9}, {10}, {11}, {12}, now()) "
                        + "on conflict (chunk_id, catalog_version, masking_rule_set, normaliser_version) do update set "
                        + "status = excluded.status, lines = excluded.lines, matched = excluded.matched, "
                        + "provisional = excluded.provisional, unparsed = excluded.unparsed, indexed_at = now()",
                s.chunkId(), s.catalogVersion(), s.maskingRuleSet(), s.normaliserVersion(), s.deviceId(), s.sourceKey(),
                s.jobId(), s.logDay().toString(), s.status(), s.lines(), s.matched(), s.provisional(), s.unparsed());
    }

    @Override
    public List<CountRow> counts(String catalogVersion, String deviceId, String sourceKey, LocalDate from, LocalDate toExclusive) {
        return transactions.inTransaction(dsl -> dsl.fetch("select count_id, chunk_id, catalog_version, device_id, source_key, "
                + "template_id, provisional, bucket_start, severity_weight, marker_id, line_count, first_line_time, "
                + "last_line_time, cast(pointers as text) as pointers from log_template_count where catalog_version = {0} "
                + "and device_id = {1} and source_key = {2} and bucket_start >= cast({3} as date) "
                + "and bucket_start < cast({4} as date) order by bucket_start, template_id, count_id",
                catalogVersion, deviceId, sourceKey, from.toString(), toExclusive.toString())
                .map(r -> new CountRow(r.get("count_id", String.class), r.get("chunk_id", String.class),
                        r.get("catalog_version", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("template_id", String.class),
                        Boolean.TRUE.equals(r.get("provisional", Boolean.class)), r.get("bucket_start", LocalDateTime.class),
                        r.get("severity_weight", java.math.BigDecimal.class).doubleValue(), r.get("marker_id", String.class),
                        r.get("line_count", Integer.class), r.get("first_line_time", LocalDateTime.class),
                        r.get("last_line_time", LocalDateTime.class),
                        JooqLogDetectionRepository.parsePointers(r.get("pointers", String.class)))));
    }

    @Override
    public Set<String> templatesBefore(String catalogVersion, String deviceId, String sourceKey, LocalDate day) {
        Set<String> out = new TreeSet<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select distinct template_id from log_template_count where catalog_version = {0} and device_id = {1} "
                    + "and source_key = {2} and bucket_start < cast({3} as date)", catalogVersion, deviceId, sourceKey,
                    day.toString()).forEach(r -> out.add(r.get("template_id", String.class)));
            return null;
        });
        return out;
    }

    @Override
    public List<LocalDate> indexedDays(String catalogVersion, String deviceId, String sourceKey, LocalDate from,
            LocalDate toExclusive) {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct cast(log_day as text) as d from log_template_index_state "
                + "where catalog_version = {0} and device_id = {1} and source_key = {2} and status = 'INDEXED' "
                + "and log_day >= cast({3} as date) and log_day < cast({4} as date) order by d", catalogVersion, deviceId,
                sourceKey, from.toString(), toExclusive.toString()).map(r -> LocalDate.parse(r.get("d", String.class))));
    }

    @Override
    public Map<String, Integer> indexedDaysPerDevice(String catalogVersion, String sourceKey, LocalDate from, LocalDate toExclusive) {
        Map<String, Integer> out = new TreeMap<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select device_id, count(distinct log_day) as n from log_template_index_state where catalog_version = {0} "
                    + "and source_key = {1} and status = 'INDEXED' and log_day >= cast({2} as date) "
                    + "and log_day < cast({3} as date) group by device_id", catalogVersion, sourceKey, from.toString(),
                    toExclusive.toString()).forEach(r -> out.put(r.get("device_id", String.class), r.get("n", Integer.class)));
            return null;
        });
        return out;
    }

    @Override
    public Map<String, Set<String>> templateDevices(String catalogVersion, String sourceKey, Collection<String> templateIds,
            LocalDate from, LocalDate toInclusive) {
        if (templateIds.isEmpty()) return Map.of();
        int n = templateIds.size();
        Map<String, Set<String>> out = new TreeMap<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select distinct template_id, device_id from log_template_count where template_id in ("
                    + inList(templateIds) + ") and catalog_version = {" + n + "} and source_key = {" + (n + 1) + "} "
                    + "and bucket_start >= cast({" + (n + 2) + "} as date) and bucket_start < cast({" + (n + 3) + "} as date) + 1",
                    binds(templateIds, catalogVersion, sourceKey, from.toString(), toInclusive.toString()))
                    .forEach(r -> out.computeIfAbsent(r.get("template_id", String.class), k -> new TreeSet<>())
                            .add(r.get("device_id", String.class)));
            return null;
        });
        return out;
    }

    @Override
    public List<String> devicesWithCounts(String catalogVersion, String sourceKey, LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct device_id from log_template_count "
                + "where catalog_version = {0} and source_key = {1} and bucket_start >= cast({2} as date) "
                + "and bucket_start < cast({2} as date) + 1 order by device_id", catalogVersion, sourceKey, day.toString())
                .map(r -> r.get("device_id", String.class)));
    }

    @Override
    public int deleteStaleLayerBFindings(String deviceId, String sourceKey, LocalDate day, Collection<String> keepFindingIds) {
        List<String> keep = new ArrayList<>(keepFindingIds);
        int n = keep.size();
        String notIn = n == 0 ? "" : " and finding_id not in (" + inList(keep) + ")";
        return transactions.inTransaction(dsl -> dsl.execute("delete from log_finding where phase = 'P2' and review_state = 'OPEN' "
                + "and device_id = {" + n + "} and source_key = {" + (n + 1) + "} and window_day = cast({" + (n + 2) + "} as date)"
                + notIn, binds(keep, deviceId, sourceKey, day.toString())));
    }

    @Override
    public Map<String, String> provisionalTexts(Collection<String> templateIds) {
        if (templateIds.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        transactions.inTransaction(dsl -> {
            dsl.fetch("select template_id, template_text from log_provisional_template where template_id in ("
                    + inList(templateIds) + ")", binds(templateIds))
                    .forEach(r -> out.put(r.get("template_id", String.class), r.get("template_text", String.class)));
            return null;
        });
        return out;
    }

    // ------------------------------------------------------------ layer-B findings

    @Override
    public void upsertLayerBFinding(FindingRow f) {
        transactions.inTransaction(dsl -> {
            upsertLayerB(dsl, f);
            return null;
        });
    }

    @Override
    public void reopenLayerBFinding(String actorFingerprint, FindingRow f, String note) {
        audited.inTransaction(actorFingerprint, JooqLogDetectionRepository.REOPEN_ACTION, dsl -> {
            upsertLayerB(dsl, f);
            dsl.execute("update log_finding set review_state = 'OPEN', review_by = null, review_at = null, updated_at = now() "
                    + "where finding_id = {0}", f.findingId());
            dsl.execute("insert into log_finding_action(action_id, finding_id, action, actor, note) "
                    + "values ({0}, {1}, 'REOPEN', {2}, {3})", UUID.randomUUID().toString(), f.findingId(), actorFingerprint,
                    note);
            return null;
        });
    }

    private static void upsertLayerB(DSLContext dsl, FindingRow f) {
        LayerB b = Objects.requireNonNull(f.layerB(), "a layer-B finding carries its layer-B part");
        if ("CRITICAL".equals(f.severity())) {
            throw new IllegalArgumentException("a statistical (layer B) finding is never CRITICAL (design section 7.1)");
        }
        dsl.execute("insert into log_finding(finding_id, device_id, source_key, layer, rule_id, rule_version, kind, "
                        + "marker_id, key_token, window_day, window_start, window_end, severity, severity_rule, "
                        + "severity_weight, provenance, event_count, peak_bucket_count, history_days, baseline_days, "
                        + "presence_days, cohort_devices, prevalence_devices, chronic, fleet_background, labels, "
                        + "incident_group, coverage_state, pointers, masking_rule_set, marker_list_version, "
                        + "config_version, review_state, review_by, review_at, first_raised_at, updated_at, phase, template_id, "
                        + "template_text, catalog_version, normaliser_version, kinds, rarity, change_factor, score, z_score, "
                        + "baseline_median, baseline_mad, device_first_seen, fleet_first_seen) "
                        + "values ({0}, {1}, {2}, 'B', {3}, {4}, {5}, {6}, {7}, cast({8} as date), {9}, {10}, {11}, {12}, "
                        + "{13}, {14}, {15}, {16}, {17}, {18}, {19}, {20}, {21}, {22}, {23}, cast({24} as text[]), {25}, "
                        + "{26}, cast({27} as jsonb), {28}, {29}, {30}, {31}, {32}, {33}, now(), now(), 'P2', {34}, {35}, "
                        + "{36}, {37}, cast({38} as text[]), {39}, {40}, {41}, {42}, {43}, {44}, cast({45} as date), "
                        + "cast({46} as date)) "
                        + "on conflict (finding_id) do update set rule_id = excluded.rule_id, "
                        + "rule_version = excluded.rule_version, kind = excluded.kind, window_start = excluded.window_start, "
                        + "window_end = excluded.window_end, severity = excluded.severity, "
                        + "severity_rule = excluded.severity_rule, severity_weight = excluded.severity_weight, "
                        + "provenance = excluded.provenance, event_count = excluded.event_count, "
                        + "peak_bucket_count = excluded.peak_bucket_count, history_days = excluded.history_days, "
                        + "baseline_days = excluded.baseline_days, presence_days = excluded.presence_days, "
                        + "cohort_devices = excluded.cohort_devices, prevalence_devices = excluded.prevalence_devices, "
                        + "labels = excluded.labels, incident_group = excluded.incident_group, "
                        + "coverage_state = excluded.coverage_state, pointers = excluded.pointers, "
                        + "masking_rule_set = excluded.masking_rule_set, marker_list_version = excluded.marker_list_version, "
                        + "config_version = excluded.config_version, template_text = excluded.template_text, "
                        + "catalog_version = excluded.catalog_version, normaliser_version = excluded.normaliser_version, "
                        + "kinds = excluded.kinds, rarity = excluded.rarity, change_factor = excluded.change_factor, "
                        + "score = excluded.score, z_score = excluded.z_score, baseline_median = excluded.baseline_median, "
                        + "baseline_mad = excluded.baseline_mad, device_first_seen = excluded.device_first_seen, "
                        + "fleet_first_seen = excluded.fleet_first_seen, updated_at = now() "
                        + "where log_finding.phase = 'P2'",  // never the review columns; never a layer-A row
                f.findingId(), f.deviceId(), f.sourceKey(), f.ruleId(), f.ruleVersion(), f.kind(), f.markerId(),
                f.keyToken(), f.windowDay().toString(), f.windowStart(), f.windowEnd(), f.severity(), f.severityRule(),
                round(f.severityWeight(), 2), f.provenance(), f.eventCount(), f.peakBucketCount(), f.historyDays(),
                f.baselineDays(), f.presenceDays(), f.cohortDevices(), f.prevalenceDevices(), f.chronic(),
                f.fleetBackground(), "{" + String.join(",", f.labels()) + "}", f.incidentGroup(), f.coverageState(),
                JooqLogDetectionRepository.pointersJson(f.pointers()), f.maskingRuleSet(), f.markerListVersion(),
                f.configVersion(), f.reviewState(), f.reviewBy(),
                f.reviewAt() == null ? null : OffsetDateTime.ofInstant(f.reviewAt(), ZoneOffset.UTC), b.templateId(),
                b.templateText(), b.catalogVersion(), b.normaliserVersion(), "{" + String.join(",", b.kinds()) + "}",
                round(b.rarity(), 4), round(b.change(), 4), round(b.score(), 2), b.z() == null ? null : round(clampZ(b.z()), 2),
                b.baselineMedian() == null ? null : round(b.baselineMedian(), 2),
                b.baselineMad() == null ? null : round(b.baselineMad(), 2),
                b.deviceFirstSeen() == null ? null : b.deviceFirstSeen().toString(),
                b.fleetFirstSeen() == null ? null : b.fleetFirstSeen().toString());
    }

    /** NUMERIC(12, 2) holds |z| below 1e10; a larger z (a burst against a zero baseline) is stored at the bound. */
    private static double clampZ(double z) {
        return Math.max(-9_999_999_999.0, Math.min(9_999_999_999.0, z));
    }
}
