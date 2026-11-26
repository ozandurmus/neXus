package com.securityexpert.nexus.ui2.persistence.logdetect;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * {@link LogDetectionRepository} over V272. Plain, parameterised SQL like the other system-log repositories. The
 * derived tables are written by the log-analysis job through the plain transaction boundary (no audit trigger, see the
 * migration header); the settings and the acknowledgement go through {@link AuditedTransactionBoundary}.
 */
public final class JooqLogDetectionRepository implements LogDetectionRepository {
    public static final String SETTINGS_ACTION = "log_detection_settings_write";
    public static final String ACK_ACTION = "log_finding_acknowledge";
    public static final String REOPEN_ACTION = "log_finding_reopen";
    private static final int MAX_FINDING_POINTERS = 40;
    private static final String FINDING_COLUMNS = "finding_id, device_id, source_key, rule_id, rule_version, kind, marker_id, "
            + "key_token, window_day, window_start, window_end, severity, severity_rule, severity_weight, provenance, "
            + "event_count, peak_bucket_count, history_days, baseline_days, presence_days, cohort_devices, "
            + "prevalence_devices, chronic, fleet_background, array_to_string(labels, ',') as labels, incident_group, "
            + "coverage_state, cast(pointers as text) as pointers, masking_rule_set, marker_list_version, config_version, "
            + "review_state, review_by, review_at, first_raised_at, updated_at, phase, template_id, template_text, "
            + "catalog_version, normaliser_version, array_to_string(kinds, ',') as kinds, rarity, change_factor, score, "
            + "z_score, baseline_median, baseline_mad, device_first_seen, fleet_first_seen";

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqLogDetectionRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    @Override
    public Settings settings() {
        return transactions.inTransaction(JooqLogDetectionRepository::selectSettings);
    }

    private static Settings selectSettings(DSLContext dsl) {
        return dsl.fetchOptional("select enabled, shadow_until, updated_at, updated_by, p2_enabled from log_detection_settings "
                + "where settings_id = 'default'")
                .map(r -> new Settings(Boolean.TRUE.equals(r.get("enabled", Boolean.class)), instant(r, "shadow_until"),
                        instant(r, "updated_at"), r.get("updated_by", String.class),
                        Boolean.TRUE.equals(r.get("p2_enabled", Boolean.class))))
                .orElse(new Settings(false, null, null, null, false));
    }

    /** The P1 switch and shadow period only: the P2 switch keeps its stored value (one statement, no read-then-write). */
    @Override
    public Settings writeSettings(String actorFingerprint, boolean enabled, Instant shadowUntil) {
        return audited.inTransaction(actorFingerprint, SETTINGS_ACTION, dsl -> {
            dsl.execute("insert into log_detection_settings(settings_id, enabled, shadow_until, updated_at, updated_by) "
                    + "values ('default', {0}, {1}, now(), {2}) on conflict (settings_id) do update set "
                    + "enabled = excluded.enabled, shadow_until = excluded.shadow_until, updated_at = now(), "
                    + "updated_by = excluded.updated_by", enabled, shadowUntil == null ? null : OffsetDateTime.ofInstant(
                            shadowUntil, java.time.ZoneOffset.UTC), actorFingerprint);
            return selectSettings(dsl);
        });
    }

    /** Both switches in one audited statement on the one settings row (the V272 audit trigger records it). */
    @Override
    public Settings writeSettings(String actorFingerprint, boolean enabled, Instant shadowUntil, boolean p2Enabled) {
        return audited.inTransaction(actorFingerprint, SETTINGS_ACTION, dsl -> {
            dsl.execute("insert into log_detection_settings(settings_id, enabled, shadow_until, updated_at, updated_by, p2_enabled) "
                    + "values ('default', {0}, {1}, now(), {2}, {3}) on conflict (settings_id) do update set "
                    + "enabled = excluded.enabled, shadow_until = excluded.shadow_until, updated_at = now(), "
                    + "updated_by = excluded.updated_by, p2_enabled = excluded.p2_enabled", enabled,
                    shadowUntil == null ? null : OffsetDateTime.ofInstant(shadowUntil, java.time.ZoneOffset.UTC),
                    actorFingerprint, p2Enabled);
            return selectSettings(dsl);
        });
    }

    @Override
    public List<PendingChunk> pendingChunks(String maskingRuleSet, String markerListVersion, int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select c.chunk_id, c.device_id, c.source_key, c.job_id, cast(c.log_day as text) as log_day "
                        + "from system_log_chunk c where c.pruned_at is null "
                        + "and exists (select 1 from system_log_run r where r.device_id = c.device_id and r.job_id = c.job_id) "
                        + "and not exists (select 1 from log_index_state s where s.chunk_id = c.chunk_id "
                        + "and s.masking_rule_set = {0} and s.marker_list_version = {1} "
                        + "and (s.status not in ('MASKING_UNAVAILABLE', 'STORE_UNAVAILABLE', 'UNREADABLE') "
                        + "or s.indexed_at > now() - interval '15 minutes')) "
                        + "order by c.log_day desc, c.collected_at, c.chunk_id limit {2}", maskingRuleSet, markerListVersion, bounded)
                .map(r -> new PendingChunk(r.get("chunk_id", String.class), r.get("device_id", String.class),
                        r.get("source_key", String.class), r.get("job_id", String.class),
                        LocalDate.parse(r.get("log_day", String.class)))));
    }

    @Override
    public void replaceChunkIndex(IndexState state, List<EventRow> events) {
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from log_event_count where chunk_id = {0}", state.chunkId());
            for (EventRow e : events) {
                dsl.execute("insert into log_event_count(event_id, chunk_id, device_id, source_key, marker_id, key_token, "
                                + "bucket_start, severity_weight, line_count, first_line_time, last_line_time, pointers) "
                                + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, {8}, {9}, {10}, cast({11} as jsonb)) "
                                + "on conflict (event_id) do update set line_count = excluded.line_count, "
                                + "first_line_time = excluded.first_line_time, last_line_time = excluded.last_line_time, "
                                + "severity_weight = excluded.severity_weight, pointers = excluded.pointers",
                        e.eventId(), e.chunkId(), e.deviceId(), e.sourceKey(), e.markerId(), e.keyToken(), e.bucketStart(),
                        e.severityWeight(), e.lineCount(), e.firstLineTime(), e.lastLineTime(), pointersJson(e.pointers()));
            }
            upsertState(dsl, state);
            return null;
        });
    }

    @Override
    public void recordIndexState(IndexState state) {
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from log_event_count where chunk_id = {0}", state.chunkId());
            upsertState(dsl, state);
            return null;
        });
    }

    private static void upsertState(DSLContext dsl, IndexState s) {
        dsl.execute("insert into log_index_state(chunk_id, masking_rule_set, marker_list_version, device_id, source_key, "
                        + "job_id, log_day, status, lines, matched, unparsed, indexed_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}, cast({6} as date), {7}, {8}, {9}, {10}, now()) "
                        + "on conflict (chunk_id, masking_rule_set, marker_list_version) do update set status = excluded.status, "
                        + "lines = excluded.lines, matched = excluded.matched, unparsed = excluded.unparsed, indexed_at = now()",
                s.chunkId(), s.maskingRuleSet(), s.markerListVersion(), s.deviceId(), s.sourceKey(), s.jobId(),
                s.logDay().toString(), s.status(), s.lines(), s.matched(), s.unparsed());
    }

    @Override
    public List<LocalDate> daysOfJob(String deviceId, String jobId) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select distinct cast(e.bucket_start as date) as d from log_event_count e "
                        + "join log_index_state s on s.chunk_id = e.chunk_id where s.device_id = {0} and s.job_id = {1} order by d",
                deviceId, jobId).map(r -> r.get("d", java.sql.Date.class).toLocalDate()));
    }

    @Override
    public List<String> devicesWithEvidence(LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct device_id from log_event_count "
                + "where bucket_start >= cast({0} as date) and bucket_start < cast({0} as date) + 1 order by device_id",
                day.toString()).map(r -> r.get("device_id", String.class)));
    }

    @Override
    public List<DayEvidence> dayEvidence(String deviceId, LocalDate day) {
        return transactions.inTransaction(dsl -> {
            var rows = dsl.fetch("select source_key, marker_id, key_token, severity_weight, line_count, first_line_time, "
                    + "last_line_time, cast(pointers as text) as pointers from log_event_count where device_id = {0} "
                    + "and bucket_start >= cast({1} as date) and bucket_start < cast({1} as date) + 1 "
                    + "order by source_key, marker_id, key_token, first_line_time, event_id", deviceId, day.toString());
            Map<String, List<Record>> grouped = new LinkedHashMap<>();
            for (Record r : rows) {
                grouped.computeIfAbsent(r.get("source_key", String.class) + "\u0000" + r.get("marker_id", String.class) + "\u0000"
                        + r.get("key_token", String.class), k -> new ArrayList<>()).add(r);
            }
            List<DayEvidence> out = new ArrayList<>();
            for (List<Record> group : grouped.values()) {
                Record head = group.get(0);
                long total = 0;
                int peak = 0;
                double weight = 0;
                LocalDateTime first = null;
                LocalDateTime last = null;
                List<Pointer> pointers = new ArrayList<>();
                for (Record r : group) {
                    int n = r.get("line_count", Integer.class);
                    total += n;
                    peak = Math.max(peak, n);
                    weight = Math.max(weight, r.get("severity_weight", java.math.BigDecimal.class).doubleValue());
                    LocalDateTime f = r.get("first_line_time", LocalDateTime.class);
                    LocalDateTime l = r.get("last_line_time", LocalDateTime.class);
                    if (first == null || f.isBefore(first)) first = f;
                    if (last == null || l.isAfter(last)) last = l;
                    for (Pointer p : parsePointers(r.get("pointers", String.class))) {
                        if (pointers.size() < MAX_FINDING_POINTERS) pointers.add(p);
                    }
                }
                out.add(new DayEvidence(head.get("source_key", String.class), head.get("marker_id", String.class),
                        head.get("key_token", String.class), weight, total, peak, first, last, pointers));
            }
            return out;
        });
    }

    private static final String COMPLETE_DAY_SQL = "not exists (select 1 from system_log_chunk c "
            + "where c.device_id = s.device_id and c.source_key = s.source_key and c.log_day = s.log_day "
            + "and c.pruned_at is null "
            + "and exists (select 1 from system_log_run r where r.device_id = c.device_id and r.job_id = c.job_id) "
            + "and not exists (select 1 from log_index_state s2 where s2.chunk_id = c.chunk_id "
            + "and s2.masking_rule_set = s.masking_rule_set and s2.marker_list_version = s.marker_list_version "
            + "and s2.status = 'INDEXED'))";

    private static boolean hasUnprocessedHistory(DSLContext dsl, String deviceId, String sourceKey, LocalDate day) {
        return Boolean.TRUE.equals(dsl.fetchOne("select exists (select 1 from system_log_chunk c "
                + "where c.device_id = {0} and c.source_key = {1} and c.pruned_at is null "
                + "and c.log_day >= cast({2} as date) - 28 and c.log_day < cast({2} as date) "
                + "and exists (select 1 from system_log_run r where r.device_id = c.device_id and r.job_id = c.job_id) "
                + "and not exists (select 1 from log_index_state s where s.chunk_id = c.chunk_id)) as pending",
                deviceId, sourceKey, day.toString()).get("pending", Boolean.class));
    }

    @Override
    public Baseline baseline(String deviceId, String sourceKey, String markerId, LocalDate day) {
        return transactions.inTransaction(dsl -> {
            if (hasUnprocessedHistory(dsl, deviceId, sourceKey, day)) {
                return new Baseline(0, 0, List.of());
            }
            List<LocalDate> usable = dsl.fetch(
                    "select distinct s.log_day as d from log_index_state s where s.device_id = {0} and s.source_key = {1} "
                            + "and s.status = 'INDEXED' and s.log_day < cast({2} as date) and s.log_day >= cast({2} as date) - 28 "
                            + "and " + COMPLETE_DAY_SQL
                            + " and not exists (select 1 from system_log_gap g where g.device_id = s.device_id "
                            + "and g.source_key = s.source_key and cast(g.created_at at time zone 'Europe/Istanbul' as date) = s.log_day) "
                            + "order by d", deviceId, sourceKey, day.toString())
                    .map(r -> r.get("d", java.sql.Date.class).toLocalDate());
            Map<LocalDate, Long> counts = new LinkedHashMap<>();
            dsl.fetch("select cast(bucket_start as date) as d, sum(line_count) as n from log_event_count where device_id = {0} "
                            + "and source_key = {1} and marker_id = {2} and bucket_start >= cast({3} as date) - 28 "
                            + "and bucket_start < cast({3} as date) group by d", deviceId, sourceKey, markerId, day.toString())
                    .forEach(r -> counts.put(r.get("d", java.sql.Date.class).toLocalDate(), r.get("n", Long.class)));
            List<Long> perDay = new ArrayList<>();
            int present = 0;
            for (LocalDate d : usable) {
                long n = counts.getOrDefault(d, 0L);
                perDay.add(n);
                if (n > 0) present++;
            }
            return new Baseline(usable.size(), present, perDay);
        });
    }

    @Override
    public int historyDays(String deviceId, String sourceKey) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select count(distinct log_day) as n from log_index_state where device_id = {0} and source_key = {1} "
                        + "and status = 'INDEXED'", deviceId, sourceKey).get("n", Integer.class));
    }

    @Override
    public int historyDays(String deviceId, String sourceKey, LocalDate day) {
        return transactions.inTransaction(dsl -> {
            if (hasUnprocessedHistory(dsl, deviceId, sourceKey, day)) {
                return dsl.fetchOne("select count(distinct s.log_day) as n from log_index_state s "
                        + "where s.device_id = {0} and s.source_key = {1} and s.status = 'INDEXED' "
                        + "and s.log_day = cast({2} as date) and " + COMPLETE_DAY_SQL,
                        deviceId, sourceKey, day.toString()).get("n", Integer.class);
            }
            return dsl.fetchOne("select count(distinct s.log_day) as n from log_index_state s "
                    + "where s.device_id = {0} and s.source_key = {1} and s.status = 'INDEXED' "
                    + "and s.log_day <= cast({2} as date) and " + COMPLETE_DAY_SQL,
                    deviceId, sourceKey, day.toString()).get("n", Integer.class);
        });
    }

    @Override
    public List<CoverageRow> readyCoverageAfter(String deviceId, String sourceKey, LocalDate afterDay) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select c.device_id, c.source_key, c.job_id, cast(c.window_day as text) as window_day, c.state, c.reason, c.history_days "
                        + "from log_coverage c where c.device_id = {0} and c.source_key = {1} and c.window_day > cast({2} as date) "
                        + "and c.state in ('NOT_EVALUABLE', 'BASELINE_LEARNING', 'EVALUATED') "
                        + "and not exists (select 1 from system_log_chunk ch where ch.device_id = c.device_id "
                        + "and ch.source_key = c.source_key and ch.pruned_at is null "
                        + "and ch.log_day >= c.window_day - 28 and ch.log_day < c.window_day "
                        + "and exists (select 1 from system_log_run r where r.device_id = ch.device_id and r.job_id = ch.job_id) "
                        + "and not exists (select 1 from log_index_state s where s.chunk_id = ch.chunk_id)) "
                        + "order by c.window_day, c.job_id", deviceId, sourceKey, afterDay.toString())
                .map(r -> new CoverageRow(r.get("device_id", String.class), r.get("source_key", String.class),
                        r.get("job_id", String.class), LocalDate.parse(r.get("window_day", String.class)),
                        r.get("state", String.class), r.get("reason", String.class), r.get("history_days", Integer.class))));
    }

    @Override
    public List<LocalDate> readyEvidenceDaysAfter(String deviceId, String sourceKey, LocalDate fromDay) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select distinct cast(e.bucket_start as date) as d from log_event_count e "
                        + "where e.device_id = {0} and e.source_key = {1} and e.bucket_start >= cast({2} as date) "
                        + "and not exists (select 1 from system_log_chunk ch where ch.device_id = e.device_id "
                        + "and ch.source_key = e.source_key and ch.pruned_at is null "
                        + "and ch.log_day >= cast(e.bucket_start as date) - 28 and ch.log_day < cast(e.bucket_start as date) "
                        + "and exists (select 1 from system_log_run r where r.device_id = ch.device_id and r.job_id = ch.job_id) "
                        + "and not exists (select 1 from log_index_state s where s.chunk_id = ch.chunk_id)) "
                        + "order by d", deviceId, sourceKey, fromDay.toString())
                .map(r -> r.get("d", java.sql.Date.class).toLocalDate()));
    }

    @Override
    public int cohortSize(String sourceKey, LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select count(distinct device_id) as n from log_coverage where source_key = {0} "
                        + "and window_day = cast({1} as date) and state in ('EVALUATED', 'BASELINE_LEARNING')",
                sourceKey, day.toString()).get("n", Integer.class));
    }

    @Override
    public int prevalence(String sourceKey, String markerId, LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select count(distinct device_id) as n from log_event_count where source_key = {0} and marker_id = {1} "
                        + "and bucket_start >= cast({2} as date) and bucket_start < cast({2} as date) + 1",
                sourceKey, markerId, day.toString()).get("n", Integer.class));
    }

    @Override
    public RunFacts runFacts(String deviceId, String sourceKey, String jobId) {
        return transactions.inTransaction(dsl -> {
            String outcome = dsl.fetchOptional("select outcome from system_log_run where device_id = {0} and job_id = {1} "
                    + "and source_key = {2}", deviceId, jobId, sourceKey).map(r -> r.get("outcome", String.class)).orElse(null);
            List<String> kinds = dsl.fetch("select distinct kind from system_log_gap where device_id = {0} and job_id = {1} "
                    + "and source_key = {2} order by kind", deviceId, jobId, sourceKey).map(r -> r.get("kind", String.class));
            int chunks = dsl.fetchOne("select count(*) as n from system_log_chunk where device_id = {0} and job_id = {1} "
                    + "and source_key = {2}", deviceId, jobId, sourceKey).get("n", Integer.class);
            return new RunFacts(outcome, kinds, chunks);
        });
    }

    @Override
    public long[] lineCounts(String deviceId, String sourceKey, String jobId) {
        return transactions.inTransaction(dsl -> {
            Record r = dsl.fetchOne("select coalesce(sum(lines), 0) as lines, coalesce(sum(unparsed), 0) as unparsed "
                    + "from log_index_state where device_id = {0} and source_key = {1} and job_id = {2} and status = 'INDEXED'",
                    deviceId, sourceKey, jobId);
            return new long[] {r.get("lines", Long.class), r.get("unparsed", Long.class)};
        });
    }

    @Override
    public Map<String, Integer> indexStatuses(String deviceId, String sourceKey, String jobId) {
        return transactions.inTransaction(dsl -> {
            Map<String, Integer> out = new LinkedHashMap<>();
            dsl.fetch("select status, count(*) as n from log_index_state where device_id = {0} and source_key = {1} "
                    + "and job_id = {2} group by status order by status", deviceId, sourceKey, jobId)
                    .forEach(r -> out.put(r.get("status", String.class), r.get("n", Integer.class)));
            return out;
        });
    }

    @Override
    public void upsertCoverage(CoverageRow c) {
        transactions.inTransaction(dsl -> {
            dsl.execute("insert into log_coverage(device_id, source_key, job_id, window_day, state, reason, history_days, "
                            + "evaluated_at) values ({0}, {1}, {2}, cast({3} as date), {4}, {5}, {6}, now()) "
                            + "on conflict (device_id, source_key, job_id) do update set window_day = excluded.window_day, "
                            + "state = excluded.state, reason = excluded.reason, history_days = excluded.history_days, "
                            + "evaluated_at = now()", c.deviceId(), c.sourceKey(), c.jobId(), c.windowDay().toString(),
                    c.state(), c.reason(), c.historyDays());
            return null;
        });
    }

    @Override
    public int markEvaluationFailed(String deviceId, LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.execute(
                "update log_coverage c set state = 'EVALUATION_FAILED', reason = 'EVALUATION_FAILED', evaluated_at = now() "
                        + "where exists (select 1 from log_event_count e join log_index_state s on s.chunk_id = e.chunk_id "
                        + "where s.device_id = c.device_id and s.job_id = c.job_id and e.device_id = c.device_id "
                        + "and (cast({0} as text) is null or e.device_id = {0}) "
                        + "and e.bucket_start >= cast({1} as date) and e.bucket_start < cast({1} as date) + 1)",
                deviceId, day.toString()));
    }

    @Override
    public List<CoverageRow> failedEvaluations(int limit) {
        int bounded = Math.max(1, Math.min(limit, 500));
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select device_id, source_key, job_id, cast(window_day as text) as window_day, state, reason, history_days "
                        + "from log_coverage where state = 'EVALUATION_FAILED' "
                        + "order by evaluated_at, device_id, source_key, job_id limit {0}", bounded)
                .map(r -> new CoverageRow(r.get("device_id", String.class), r.get("source_key", String.class),
                        r.get("job_id", String.class), LocalDate.parse(r.get("window_day", String.class)),
                        r.get("state", String.class), r.get("reason", String.class), r.get("history_days", Integer.class))));
    }

    @Override
    public Optional<FindingRow> finding(String findingId) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional("select " + FINDING_COLUMNS
                + " from log_finding where finding_id = {0}", findingId).map(JooqLogDetectionRepository::finding));
    }

    @Override
    public void upsertFinding(FindingRow f) {
        transactions.inTransaction(dsl -> {
            upsertFinding(dsl, f);
            return null;
        });
    }

    @Override
    public void reopenFinding(String actorFingerprint, FindingRow f, String note) {
        audited.inTransaction(actorFingerprint, REOPEN_ACTION, dsl -> {
            upsertFinding(dsl, f);
            dsl.execute("update log_finding set review_state = 'OPEN', review_by = null, review_at = null, updated_at = now() "
                    + "where finding_id = {0}", f.findingId());
            dsl.execute("insert into log_finding_action(action_id, finding_id, action, actor, note) "
                    + "values ({0}, {1}, 'REOPEN', {2}, {3})", UUID.randomUUID().toString(), f.findingId(), actorFingerprint,
                    note);
            return null;
        });
    }

    private static void upsertFinding(DSLContext dsl, FindingRow f) {
        dsl.execute("insert into log_finding(finding_id, device_id, source_key, layer, rule_id, rule_version, kind, "
                        + "marker_id, key_token, window_day, window_start, window_end, severity, severity_rule, "
                        + "severity_weight, provenance, event_count, peak_bucket_count, history_days, baseline_days, "
                        + "presence_days, cohort_devices, prevalence_devices, chronic, fleet_background, labels, "
                        + "incident_group, coverage_state, pointers, masking_rule_set, marker_list_version, "
                        + "config_version, review_state, review_by, review_at, first_raised_at, updated_at) "
                        + "values ({0}, {1}, {2}, 'A', {3}, {4}, {5}, {6}, {7}, cast({8} as date), {9}, {10}, {11}, {12}, "
                        + "{13}, {14}, {15}, {16}, {17}, {18}, {19}, {20}, {21}, {22}, {23}, cast({24} as text[]), {25}, "
                        + "{26}, cast({27} as jsonb), {28}, {29}, {30}, {31}, {32}, {33}, now(), now()) "
                        + "on conflict (finding_id) do update set source_key = excluded.source_key, "
                        + "rule_id = excluded.rule_id, rule_version = excluded.rule_version, kind = excluded.kind, "
                        + "window_start = excluded.window_start, window_end = excluded.window_end, "
                        + "severity = excluded.severity, severity_rule = excluded.severity_rule, "
                        + "severity_weight = excluded.severity_weight, provenance = excluded.provenance, "
                        + "event_count = excluded.event_count, peak_bucket_count = excluded.peak_bucket_count, "
                        + "history_days = excluded.history_days, baseline_days = excluded.baseline_days, "
                        + "presence_days = excluded.presence_days, cohort_devices = excluded.cohort_devices, "
                        + "prevalence_devices = excluded.prevalence_devices, chronic = excluded.chronic, "
                        + "fleet_background = excluded.fleet_background, labels = excluded.labels, "
                        + "incident_group = excluded.incident_group, coverage_state = excluded.coverage_state, "
                        + "pointers = excluded.pointers, masking_rule_set = excluded.masking_rule_set, "
                        + "marker_list_version = excluded.marker_list_version, config_version = excluded.config_version, "
                        + "updated_at = now()",  // never the review columns: see LogDetectionRepository#upsertFinding
                f.findingId(), f.deviceId(), f.sourceKey(), f.ruleId(), f.ruleVersion(), f.kind(), f.markerId(),
                f.keyToken(), f.windowDay().toString(), f.windowStart(), f.windowEnd(), f.severity(), f.severityRule(),
                f.severityWeight(), f.provenance(), f.eventCount(), f.peakBucketCount(), f.historyDays(),
                f.baselineDays(), f.presenceDays(), f.cohortDevices(), f.prevalenceDevices(), f.chronic(),
                f.fleetBackground(), "{" + String.join(",", f.labels()) + "}", f.incidentGroup(), f.coverageState(),
                pointersJson(f.pointers()), f.maskingRuleSet(), f.markerListVersion(), f.configVersion(), f.reviewState(),
                f.reviewBy(), f.reviewAt() == null ? null : OffsetDateTime.ofInstant(f.reviewAt(), java.time.ZoneOffset.UTC));
    }

    @Override
    public List<FindingRow> findings(FindingQuery q) {
        int bounded = Math.max(1, Math.min(q.limit(), 1000));
        return transactions.inTransaction(dsl -> dsl.fetch("select " + FINDING_COLUMNS + " from log_finding "
                + "where (cast({0} as text) is null or device_id = {0}) and (cast({1} as text) is null or severity = {1}) "
                + "and (cast({2} as text) is null or review_state = {2}) "
                + "and (cast({3} as text) is null or window_day >= cast({3} as date)) "
                + "order by case severity when 'CRITICAL' then 0 when 'ATTENTION' then 1 when 'UNKNOWN' then 2 else 3 end, "
                + "window_day desc, severity_weight desc, window_end desc, finding_id limit {4} offset {5}",
                q.deviceId(), q.severity(), q.reviewState(), q.sinceDay() == null ? null : q.sinceDay().toString(), bounded,
                q.offset())
                .map(JooqLogDetectionRepository::finding));
    }

    @Override
    public List<CoverageRow> coverage(String deviceId, LocalDate sinceDay) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select device_id, source_key, job_id, cast(window_day as text) as window_day, state, reason, history_days "
                        + "from log_coverage where (cast({0} as text) is null or device_id = {0}) "
                        + "and window_day >= cast({1} as date) order by window_day desc, device_id, source_key, job_id limit 5000",
                deviceId, sinceDay.toString())
                .map(r -> new CoverageRow(r.get("device_id", String.class), r.get("source_key", String.class),
                        r.get("job_id", String.class), LocalDate.parse(r.get("window_day", String.class)),
                        r.get("state", String.class), r.get("reason", String.class), r.get("history_days", Integer.class))));
    }

    @Override
    public List<BudgetRow> attentionCounts(LocalDate sinceDay) {
        return transactions.inTransaction(dsl -> dsl.fetch(
                "select device_id, cast(window_day as text) as day, severity, count(*) as n from log_finding "
                        + "where window_day >= cast({0} as date) and severity in ('CRITICAL', 'ATTENTION') "
                        + "and review_state <> 'SUPPRESSED' group by device_id, window_day, severity "
                        + "order by window_day desc, device_id", sinceDay.toString())
                .map(r -> new BudgetRow(r.get("device_id", String.class), LocalDate.parse(r.get("day", String.class)),
                        r.get("severity", String.class), r.get("n", Integer.class))));
    }

    @Override
    public Optional<FindingRow> acknowledge(String actorFingerprint, String findingId, String note) {
        return audited.inTransaction(actorFingerprint, ACK_ACTION, dsl -> {
            int updated = dsl.execute("update log_finding set review_state = 'ACKNOWLEDGED', review_by = {1}, review_at = now(), "
                    + "updated_at = now() where finding_id = {0}", findingId, actorFingerprint);
            if (updated == 0) return Optional.<FindingRow>empty();
            dsl.execute("insert into log_finding_action(action_id, finding_id, action, actor, note) "
                    + "values ({0}, {1}, 'ACKNOWLEDGE', {2}, {3})", UUID.randomUUID().toString(), findingId, actorFingerprint,
                    note);
            return dsl.fetchOptional("select " + FINDING_COLUMNS + " from log_finding where finding_id = {0}", findingId)
                    .map(JooqLogDetectionRepository::finding);
        });
    }

    @Override
    public int devicesWithCoverage(LocalDate day) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select count(distinct device_id) as n from log_coverage where window_day = cast({0} as date)", day.toString())
                .get("n", Integer.class));
    }

    // ------------------------------------------------------------ mapping

    static FindingRow finding(Record r) {
        String labels = r.get("labels", String.class);
        return new FindingRow(r.get("finding_id", String.class), r.get("device_id", String.class),
                r.get("source_key", String.class), r.get("rule_id", String.class), r.get("rule_version", String.class),
                r.get("kind", String.class), r.get("marker_id", String.class), r.get("key_token", String.class),
                r.get("window_day", java.sql.Date.class).toLocalDate(), r.get("window_start", LocalDateTime.class),
                r.get("window_end", LocalDateTime.class), r.get("severity", String.class), r.get("severity_rule", String.class),
                r.get("severity_weight", java.math.BigDecimal.class).doubleValue(), r.get("provenance", String.class),
                r.get("event_count", Long.class), r.get("peak_bucket_count", Integer.class),
                r.get("history_days", Integer.class), r.get("baseline_days", Integer.class),
                r.get("presence_days", Integer.class), r.get("cohort_devices", Integer.class),
                r.get("prevalence_devices", Integer.class), Boolean.TRUE.equals(r.get("chronic", Boolean.class)),
                Boolean.TRUE.equals(r.get("fleet_background", Boolean.class)),
                labels == null || labels.isEmpty() ? List.of() : Arrays.asList(labels.split(",")),
                r.get("incident_group", String.class), r.get("coverage_state", String.class),
                parsePointers(r.get("pointers", String.class)), r.get("masking_rule_set", String.class),
                r.get("marker_list_version", String.class), r.get("config_version", String.class),
                r.get("review_state", String.class), r.get("review_by", String.class), instant(r, "review_at"),
                instant(r, "first_raised_at"), instant(r, "updated_at"), layerB(r));
    }

    /** The layer-B part of a {@code P2} row (V273), null for a layer-A row. */
    static LayerB layerB(Record r) {
        if (!"P2".equals(r.get("phase", String.class))) return null;
        String kinds = r.get("kinds", String.class);
        return new LayerB(r.get("template_id", String.class), r.get("template_text", String.class),
                r.get("catalog_version", String.class), r.get("normaliser_version", String.class),
                kinds == null || kinds.isEmpty() ? List.of() : Arrays.asList(kinds.split(",")), decimal(r, "rarity"),
                decimal(r, "change_factor"), decimal(r, "score"), nullableDecimal(r, "z_score"),
                nullableDecimal(r, "baseline_median"), nullableDecimal(r, "baseline_mad"), date(r, "device_first_seen"),
                date(r, "fleet_first_seen"));
    }

    static double decimal(Record r, String field) {
        java.math.BigDecimal v = r.get(field, java.math.BigDecimal.class);
        return v == null ? 0 : v.doubleValue();
    }

    static Double nullableDecimal(Record r, String field) {
        java.math.BigDecimal v = r.get(field, java.math.BigDecimal.class);
        return v == null ? null : v.doubleValue();
    }

    static LocalDate date(Record r, String field) {
        java.sql.Date v = r.get(field, java.sql.Date.class);
        return v == null ? null : v.toLocalDate();
    }

    static Instant instant(Record r, String field) {
        OffsetDateTime value = r.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static final java.util.regex.Pattern POINTER_OBJECT = java.util.regex.Pattern.compile("\\{[^{}]*\\}");
    private static final java.util.regex.Pattern POINTER_CHUNK = java.util.regex.Pattern.compile("\"chunk_id\"\\s*:\\s*\"([A-Za-z0-9_.:-]{1,128})\"");
    private static final java.util.regex.Pattern POINTER_OFFSET = java.util.regex.Pattern.compile("\"offset\"\\s*:\\s*([0-9]{1,18})");
    private static final java.util.regex.Pattern POINTER_LENGTH = java.util.regex.Pattern.compile("\"length\"\\s*:\\s*([0-9]{1,9})");
    private static final java.util.regex.Pattern CHUNK_ID = java.util.regex.Pattern.compile("[A-Za-z0-9_.:-]{1,128}");

    /** {@code [{"chunk_id":..,"offset":..,"length":..}]}; the chunk id is a closed character set, so no escaping is needed. */
    static String pointersJson(List<Pointer> pointers) {
        StringBuilder sb = new StringBuilder("[");
        for (Pointer p : pointers) {
            if (!CHUNK_ID.matcher(p.chunkId()).matches()) throw new IllegalArgumentException("chunk id outside the closed character set");
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"chunk_id\":\"").append(p.chunkId()).append("\",\"offset\":").append(p.offset())
                    .append(",\"length\":").append(p.length()).append('}');
        }
        return sb.append(']').toString();
    }

    static List<Pointer> parsePointers(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Pointer> out = new ArrayList<>();
        var objects = POINTER_OBJECT.matcher(json);
        while (objects.find()) {
            String o = objects.group();
            var chunk = POINTER_CHUNK.matcher(o);
            var offset = POINTER_OFFSET.matcher(o);
            var length = POINTER_LENGTH.matcher(o);
            if (chunk.find() && offset.find() && length.find()) {
                out.add(new Pointer(chunk.group(1), Long.parseLong(offset.group(1)), Integer.parseInt(length.group(1))));
            }
        }
        return out;
    }
}
