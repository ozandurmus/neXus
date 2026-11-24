package com.securityexpert.nexus.ui2.persistence.snmp;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.HealthFigures;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.PollResult;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;

/**
 * SNMP device metrics over jOOQ (V268). The settings write is one audited transaction under {@link #WRITE_ACTION}; the
 * worker writes samples, poll state and hourly rows, which carry no audit trigger. A poll is claimed by a conditional
 * update of {@code snmp_poll_state.next_poll_at}, so two worker replicas never poll the same device in one interval.
 */
public final class JooqSnmpPollingRepository implements SnmpPollingRepository {

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public JooqSnmpPollingRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    // ------------------------------------------------------------------ settings (service and worker)

    @java.lang.Override
    public Configuration read() {
        return transactions.inTransaction(JooqSnmpPollingRepository::configuration);
    }

    /** Replaces the settings row and the whole override set in one audited transaction; returns what was stored. */
    @java.lang.Override
    public Configuration write(String actorFingerprint, Settings settings, List<Override> overrides) {
        Objects.requireNonNull(settings, "settings");
        List<Override> next = List.copyOf(overrides);
        return audited.inTransaction(actorFingerprint, WRITE_ACTION, dsl -> {
            dsl.execute("insert into snmp_polling_settings(settings_id, enabled, interval_s, default_credential_reference_id, "
                    + "scope_mode, raw_retention_days, hourly_retention_days, updated_at, updated_by) "
                    + "values ('default', {0}, {1}, {2}, {3}, {4}, {5}, now(), {6}) on conflict (settings_id) do update set "
                    + "enabled = excluded.enabled, interval_s = excluded.interval_s, "
                    + "default_credential_reference_id = excluded.default_credential_reference_id, "
                    + "scope_mode = excluded.scope_mode, raw_retention_days = excluded.raw_retention_days, "
                    + "hourly_retention_days = excluded.hourly_retention_days, updated_at = now(), "
                    + "updated_by = excluded.updated_by",
                    settings.enabled(), settings.intervalS(), settings.defaultCredentialReferenceId(), settings.scopeMode(),
                    settings.rawRetentionDays(), settings.hourlyRetentionDays(), actorFingerprint);
            List<String> keep = next.stream().map(Override::key).toList();
            for (Record r : dsl.fetch("select override_key from snmp_polling_override")) {
                String key = r.get("override_key", String.class);
                if (!keep.contains(key)) dsl.execute("delete from snmp_polling_override where override_key = {0}", key);
            }
            for (Override o : next) {
                dsl.execute("insert into snmp_polling_override(override_key, scope_kind, vendor, device_id, in_scope, "
                        + "credential_reference_id, updated_at, updated_by) values ({0}, {1}, {2}, {3}, {4}, {5}, now(), {6}) "
                        + "on conflict (override_key) do update set in_scope = excluded.in_scope, "
                        + "credential_reference_id = excluded.credential_reference_id, updated_at = now(), "
                        + "updated_by = excluded.updated_by where snmp_polling_override.in_scope is distinct from excluded.in_scope "
                        + "or snmp_polling_override.credential_reference_id is distinct from excluded.credential_reference_id",
                        o.key(), o.scopeKind(), o.vendor(), o.deviceId(), o.inScope(), o.credentialReferenceId(),
                        actorFingerprint);
            }
            return configuration(dsl);
        });
    }

    private static Configuration configuration(DSLContext dsl) {
        Optional<Record> row = dsl.fetchOptional("select enabled, interval_s, default_credential_reference_id, scope_mode, "
                + "raw_retention_days, hourly_retention_days, updated_at, updated_by from snmp_polling_settings "
                + "where settings_id = 'default'");
        Settings settings = row.map(r -> new Settings(Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                r.get("interval_s", Integer.class), r.get("default_credential_reference_id", String.class),
                r.get("scope_mode", String.class), r.get("raw_retention_days", Integer.class),
                r.get("hourly_retention_days", Integer.class))).orElse(Settings.DEFAULTS);
        List<Override> overrides = new ArrayList<>();
        for (Record r : dsl.fetch("select scope_kind, vendor, device_id, in_scope, credential_reference_id "
                + "from snmp_polling_override order by scope_kind desc, override_key")) {
            overrides.add(new Override(r.get("scope_kind", String.class), r.get("vendor", String.class),
                    r.get("device_id", String.class), Boolean.TRUE.equals(r.get("in_scope", Boolean.class)),
                    r.get("credential_reference_id", String.class)));
        }
        return new Configuration(settings, overrides, row.map(r -> instant(r, "updated_at")).orElse(null),
                row.map(r -> r.get("updated_by", String.class)).orElse(null));
    }

    // ------------------------------------------------------------------ worker

    /** Enrolled, not disabled pilot-vendor gateways with their first endpoint and poll state, by device id. */
    @java.lang.Override
    public List<Device> eligibleDevices() {
        return transactions.inTransaction(dsl -> dsl.fetch("select d.device_id, d.vendor_hint, "
                + "(select e.address_ref from endpoints e where e.device_id = d.device_id "
                + "order by e.created_at, e.endpoint_id limit 1) as address_ref, "
                + "coalesce(s.consecutive_misses, 0) as misses, s.next_poll_at, s.last_sys_uptime_cs "
                + "from devices d left join snmp_poll_state s on s.device_id = d.device_id "
                + "where not d.disabled and d.enrollment_state in ('ENROLLED', 'DEGRADED') and d.role = 'gateway' "
                + "and d.vendor_hint in ('check_point', 'palo_alto') order by d.device_id").stream()
                .map(r -> new Device(r.get("device_id", String.class), r.get("vendor_hint", String.class),
                        r.get("address_ref", String.class), r.get("misses", Integer.class), instant(r, "next_poll_at"),
                        r.get("last_sys_uptime_cs", Long.class)))
                .toList());
    }

    /** First sight of a device: sets its first (spread) poll time; does nothing when a state row exists. */
    @java.lang.Override
    public void schedule(String deviceId, Instant firstPollAt) {
        transactions.inTransaction(dsl -> dsl.execute("insert into snmp_poll_state(device_id, next_poll_at) "
                + "values ({0}, {1}) on conflict (device_id) do nothing", deviceId, ts(firstPollAt)));
    }

    /**
     * Claims a due poll: moves {@code next_poll_at} from the value this worker read to {@code leaseUntil}. False when
     * another worker claimed it first or the row changed.
     */
    @java.lang.Override
    public boolean claim(String deviceId, Instant expectedNextPollAt, Instant leaseUntil) {
        return transactions.inTransaction(dsl -> dsl.execute("update snmp_poll_state set next_poll_at = {1}, "
                + "updated_at = now() where device_id = {0} and next_poll_at = {2}",
                deviceId, ts(leaseUntil), ts(expectedNextPollAt))) == 1;
    }

    /** Writes the sample (when the outcome has one) and the next state in one transaction. */
    @java.lang.Override
    public void record(PollResult p) {
        transactions.inTransaction(dsl -> {
            Metrics m = p.metrics();
            if (p.outcome().writesSample()) {
                dsl.execute("insert into device_metric_sample(device_id, sampled_at, source, outcome, sys_uptime_cs, rebooted, "
                        + "cpu_pct, cpu_dataplane_pct, mem_pct, sessions) values ({0}, {1}, 'SNMP', {2}, {3}, {4}, {5}, {6}, "
                        + "{7}, {8}) on conflict (device_id, sampled_at) do nothing", p.deviceId(), ts(p.sampledAt()),
                        p.outcome().name(), m.sysUptimeCs(), p.rebooted(), m.cpuPct(), m.cpuDataplanePct(), m.memPct(),
                        m.sessions());
            }
            boolean answered = p.outcome() == SnmpPolling.Outcome.OK || p.outcome() == SnmpPolling.Outcome.PARTIAL;
            dsl.execute("insert into snmp_poll_state(device_id, consecutive_misses, last_outcome, last_poll_at, "
                    + "last_answer_at, next_poll_at, last_sys_uptime_cs, last_reboot_detected_at, updated_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, now()) on conflict (device_id) do update set "
                    + "consecutive_misses = excluded.consecutive_misses, last_outcome = excluded.last_outcome, "
                    + "last_poll_at = excluded.last_poll_at, "
                    + "last_answer_at = coalesce(excluded.last_answer_at, snmp_poll_state.last_answer_at), "
                    + "next_poll_at = excluded.next_poll_at, "
                    + "last_sys_uptime_cs = coalesce(excluded.last_sys_uptime_cs, snmp_poll_state.last_sys_uptime_cs), "
                    + "last_reboot_detected_at = coalesce(excluded.last_reboot_detected_at, "
                    + "snmp_poll_state.last_reboot_detected_at), updated_at = now()",
                    p.deviceId(), p.consecutiveMisses(), p.outcome().name(), ts(p.sampledAt()),
                    answered ? ts(p.sampledAt()) : null, ts(p.nextPollAt()), m.sysUptimeCs(),
                    p.rebooted() ? ts(p.sampledAt()) : null);
            return null;
        });
    }

    /**
     * Writes (or rewrites) the hourly p95 and maximum of every complete hour in [fromHour, toHour). p95 is nearest-rank
     * ({@code percentile_disc}), as the sar figures; NULL values (no answer) take no part.
     */
    @java.lang.Override
    public int rollup(Instant fromHour, Instant toHour) {
        return transactions.inTransaction(dsl -> dsl.execute("insert into device_metric_hourly(device_id, hour_start, "
                + "samples, answered, cpu_p95, cpu_max, cpu_dataplane_p95, cpu_dataplane_max, mem_p95, mem_max, sessions_p95, "
                + "sessions_max, rebooted) select device_id, date_trunc('hour', sampled_at) as hour_start, count(*), "
                + "count(*) filter (where outcome in ('OK', 'PARTIAL')), "
                + "percentile_disc(0.95) within group (order by cpu_pct), max(cpu_pct), "
                + "percentile_disc(0.95) within group (order by cpu_dataplane_pct), max(cpu_dataplane_pct), "
                + "percentile_disc(0.95) within group (order by mem_pct), max(mem_pct), "
                + "percentile_disc(0.95) within group (order by sessions), max(sessions), bool_or(rebooted) "
                + "from device_metric_sample where sampled_at >= {0} and sampled_at < {1} "
                + "group by device_id, date_trunc('hour', sampled_at) "
                + "on conflict (device_id, hour_start) do update set samples = excluded.samples, answered = excluded.answered, "
                + "cpu_p95 = excluded.cpu_p95, cpu_max = excluded.cpu_max, cpu_dataplane_p95 = excluded.cpu_dataplane_p95, "
                + "cpu_dataplane_max = excluded.cpu_dataplane_max, mem_p95 = excluded.mem_p95, mem_max = excluded.mem_max, "
                + "sessions_p95 = excluded.sessions_p95, sessions_max = excluded.sessions_max, rebooted = excluded.rebooted",
                ts(fromHour), ts(toHour)));
    }

    /** Deletes raw samples older than rawCutoff and hourly rows older than hourlyCutoff; returns {raw, hourly}. */
    /** Rows one prune statement deletes at most; each batch is its own short transaction. */
    public static final int PRUNE_BATCH_ROWS = 5_000;
    /** Batches per table per call; what is left waits for the next maintenance run. */
    public static final int PRUNE_MAX_BATCHES = 40;

    /**
     * Deletes raw samples older than rawCutoff and hourly rows older than hourlyCutoff, in batches of
     * {@link #PRUNE_BATCH_ROWS} rows, each in its own transaction, at most {@link #PRUNE_MAX_BATCHES} per table per call
     * (200,000 rows), so a backlog after an outage or a shorter retention never takes one long lock. Returns {raw, hourly}.
     */
    @java.lang.Override
    public int[] prune(Instant rawCutoff, Instant hourlyCutoff) {
        return new int[] {
                pruneBatches("device_metric_sample", "sampled_at", rawCutoff),
                pruneBatches("device_metric_hourly", "hour_start", hourlyCutoff)};
    }

    private int pruneBatches(String table, String column, Instant cutoff) {
        int total = 0;
        for (int batch = 0; batch < PRUNE_MAX_BATCHES; batch++) {
            int deleted = transactions.inTransaction(dsl -> dsl.execute("delete from " + table + " where ctid in "
                    + "(select ctid from " + table + " where " + column + " < {0} limit {1})", ts(cutoff), PRUNE_BATCH_ROWS));
            total += deleted;
            if (deleted < PRUNE_BATCH_ROWS) break;
        }
        return total;
    }

    // ------------------------------------------------------------------ service reads

    /** Per device figures since {@code since}, from the raw samples; devices without a sample are absent. */
    @java.lang.Override
    public Map<String, HealthFigures> health(Instant since) {
        return transactions.inTransaction(dsl -> {
            Map<String, String> lastOutcome = new HashMap<>();
            for (Record r : dsl.fetch("select device_id, last_outcome from snmp_poll_state where last_outcome is not null")) {
                lastOutcome.put(r.get("device_id", String.class), r.get("last_outcome", String.class));
            }
            Map<String, HealthFigures> out = new HashMap<>();
            for (Record r : dsl.fetch("select device_id, count(*) as samples, "
                    + "count(*) filter (where outcome in ('OK', 'PARTIAL')) as answered, "
                    + "percentile_disc(0.95) within group (order by cpu_pct) as cpu_p95, "
                    + "percentile_disc(0.95) within group (order by cpu_dataplane_pct) as cpu_dp_p95, "
                    + "percentile_disc(0.95) within group (order by mem_pct) as mem_p95, max(sessions) as sessions_max, "
                    + "max(sampled_at) filter (where outcome in ('OK', 'PARTIAL')) as newest "
                    + "from device_metric_sample where sampled_at >= {0} group by device_id", ts(since))) {
                String id = r.get("device_id", String.class);
                out.put(id, new HealthFigures(id, r.get("samples", Integer.class), r.get("answered", Integer.class),
                        r.get("cpu_p95", BigDecimal.class), r.get("cpu_dp_p95", BigDecimal.class),
                        r.get("mem_p95", BigDecimal.class), r.get("sessions_max", Long.class), instant(r, "newest"),
                        Optional.ofNullable(lastOutcome.get(id))));
            }
            for (var e : lastOutcome.entrySet()) {
                out.putIfAbsent(e.getKey(), new HealthFigures(e.getKey(), 0, 0, null, null, null, null, null,
                        Optional.of(e.getValue())));
            }
            return out;
        });
    }

    @java.lang.Override
    public Map<String, List<SnmpPolling.DailyFigures>> daily(Instant since, String zoneId) {
        java.time.ZoneId.of(zoneId); // refuses an unknown zone before it reaches SQL
        return transactions.inTransaction(dsl -> {
            Map<String, List<SnmpPolling.DailyFigures>> out = new HashMap<>();
            for (Record r : dsl.fetch("select device_id, (sampled_at at time zone {1})::date as day, count(*) as samples, "
                    + "count(*) filter (where outcome in ('OK', 'PARTIAL')) as answered, "
                    + "percentile_disc(0.95) within group (order by cpu_pct) as cpu_p95, max(cpu_pct) as cpu_max, "
                    + "percentile_disc(0.95) within group (order by cpu_dataplane_pct) as cpu_dp_p95, "
                    + "percentile_disc(0.95) within group (order by mem_pct) as mem_p95 "
                    + "from device_metric_sample where sampled_at >= {0} group by device_id, day order by device_id, day",
                    ts(since), zoneId)) {
                out.computeIfAbsent(r.get("device_id", String.class), k -> new ArrayList<>()).add(
                        new SnmpPolling.DailyFigures(r.get("day", java.time.LocalDate.class), r.get("samples", Integer.class),
                                r.get("answered", Integer.class), r.get("cpu_p95", BigDecimal.class),
                                r.get("cpu_max", BigDecimal.class), r.get("cpu_dp_p95", BigDecimal.class),
                                r.get("mem_p95", BigDecimal.class)));
            }
            return out;
        });
    }

    @java.lang.Override
    public Map<String, Integer> lastOutcomeCounts(Instant since) {
        return transactions.inTransaction(dsl -> {
            Map<String, Integer> out = new java.util.TreeMap<>();
            for (Record r : dsl.fetch("select last_outcome, count(*) as n from snmp_poll_state where last_outcome is not null "
                    + "and last_poll_at >= {0} group by last_outcome", ts(since))) {
                out.put(r.get("last_outcome", String.class), r.get("n", Integer.class));
            }
            return out;
        });
    }

    @java.lang.Override
    public Map<String, String> lastOutcomes() {
        return transactions.inTransaction(dsl -> {
            Map<String, String> out = new HashMap<>();
            for (Record r : dsl.fetch("select device_id, last_outcome from snmp_poll_state where last_outcome is not null")) {
                out.put(r.get("device_id", String.class), r.get("last_outcome", String.class));
            }
            return out;
        });
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(Record r, String column) {
        OffsetDateTime value = r.get(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
