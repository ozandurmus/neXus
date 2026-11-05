package com.securityexpert.nexus.ui2.service.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Jobs screen reads. Product Owner P0 (2026-09-22, backlog
 * {@code jobs_screen_history_filter_pagination_export}): the whole history
 * with server-side filters, numbered pages and a CSV export -- the earlier
 * "last 50 rows, filter in the browser" hid everything older than a
 * fleet-backup's worth of jobs.
 */
@Service
public final class JobLogQueryService {

    private static final int RECENT_LIMIT = 50;
    public static final int MAX_PAGE_SIZE = 200;
    /** The export is one HTTP response; beyond this the operator narrows the filter. */
    public static final int MAX_EXPORT_ROWS = 50_000;

    public record JobEvent(
            @JsonProperty("job_id") String jobId,
            @JsonProperty("job_type") String jobType,
            @JsonProperty("target_device_id") String targetDeviceId,
            /** The resolved target label (device, cluster/failover unit, policy source/container, packet case or fleet); null when no target is recorded. */
            @JsonProperty("device_name") String deviceName,
            @JsonProperty("state") String state,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("terminal_reason") String terminalReason,
            @JsonProperty("submitted_at") Instant submittedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") Long durationMs,
            @JsonProperty("has_transcript") boolean hasTranscript,
            /** Log lines a system-log collection run stored, summed over its chunk ledger rows (read-only addition,
             *  Phase 2 follow-up: the RESULT cell says {@code Collected · 1,234 lines}). Null for every other job type,
             *  and for a collection run that left no chunk row -- never 0 in place of "not recorded". */
            @JsonProperty("lines_stored") Long linesStored,
            /** Closed target kind ({@code device}, {@code cluster}, {@code failover_unit}, {@code policy_source}, {@code policy_container}, {@code packet_analysis_case}, {@code fleet}). */
            @JsonProperty("target_kind") String targetKind,
            /** Internal cluster or fallback reference for server-side AIView masking; never serialised raw. */
            @JsonIgnore String clusterRef) {

        public JobEvent(String jobId, String jobType, String targetDeviceId, String state, String terminalReason, Instant submittedAt) {
            this(jobId, jobType, targetDeviceId, null, state, null, terminalReason, submittedAt, null, null, false, null, null, null);
        }

        public JobEvent(String jobId, String jobType, String targetDeviceId, String deviceName, String state,
                String outcome, String terminalReason, Instant submittedAt, Instant finishedAt, Long durationMs) {
            this(jobId, jobType, targetDeviceId, deviceName, state, outcome, terminalReason, submittedAt,
                    finishedAt, durationMs, false, null, null, null);
        }

        public JobEvent(String jobId, String jobType, String targetDeviceId, String deviceName, String state,
                String outcome, String terminalReason, Instant submittedAt, Instant finishedAt, Long durationMs,
                boolean hasTranscript) {
            this(jobId, jobType, targetDeviceId, deviceName, state, outcome, terminalReason, submittedAt,
                    finishedAt, durationMs, hasTranscript, null, null, null);
        }

        public JobEvent(String jobId, String jobType, String targetDeviceId, String deviceName, String state,
                String outcome, String terminalReason, Instant submittedAt, Instant finishedAt, Long durationMs,
                boolean hasTranscript, Long linesStored) {
            this(jobId, jobType, targetDeviceId, deviceName, state, outcome, terminalReason, submittedAt,
                    finishedAt, durationMs, hasTranscript, linesStored, null, null);
        }
    }

    /** Every filter optional; {@code text} matches job id, device id and terminal reason (case-insensitive substring). */
    public record JobQuery(Optional<String> state, Optional<String> jobType, Optional<String> deviceId,
            Optional<Instant> since, Optional<Instant> until, Optional<String> text, int page, int pageSize,
            Optional<String> sort, Optional<String> order) {

        public JobQuery(Optional<String> state, Optional<String> jobType, Optional<String> deviceId,
                Optional<Instant> since, Optional<Instant> until, Optional<String> text, int page, int pageSize) {
            this(state, jobType, deviceId, since, until, text, page, pageSize, Optional.empty(), Optional.empty());
        }

        public JobQuery {
            page = Math.max(1, page);
            pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
            sort = sort == null ? Optional.empty() : sort.map(String::strip).filter(s -> !s.isEmpty());
            order = order == null ? Optional.empty() : order.map(String::strip).filter(s -> !s.isEmpty());
            orderByOf(sort.orElse(""), order.orElse(""));
        }
    }

    public record JobPage(
            @JsonProperty("items") List<JobEvent> items,
            @JsonProperty("page") int page,
            @JsonProperty("page_size") int pageSize,
            @JsonProperty("total") long total,
            @JsonProperty("can_cancel") boolean canCancel) {
        public JobPage(List<JobEvent> items, int page, int pageSize, long total) {
            this(items, page, pageSize, total, false);
        }
        public JobPage withCancellationAccess(boolean allowed) {
            return new JobPage(items, page, pageSize, total, allowed);
        }
        public JobPage withTranscriptAccess(boolean allowed) {
            if (allowed) return this;
            return new JobPage(items.stream().map(event -> new JobEvent(event.jobId(), event.jobType(),
                    event.targetDeviceId(), event.deviceName(), event.state(), event.outcome(), event.terminalReason(),
                    event.submittedAt(), event.finishedAt(), event.durationMs(), false, event.linesStored(),
                    event.targetKind(), event.clusterRef())).toList(), page, pageSize, total, canCancel);
        }
    }

    /**
     * The headline counts Overview and Operations show, from one query. One window for both screens (UI review
     * 2026-09-23: Operations counted jobs SUBMITTED in 24 h, Overview jobs FINISHED in 24 h, and two jobs in
     * OUTCOME_UNKNOWN / REJECTED were shown nowhere): every terminal state finished in the last 24 h, each counted,
     * so {@code total_24h = completed + failed + outcome_unknown + rejected + cancelled}; in-flight jobs apart.
     */
    public record Stats(@JsonProperty("total") long total, @JsonProperty("total_24h") long total24h,
            @JsonProperty("completed_24h") long completed24h, @JsonProperty("failed_24h") long failed24h,
            @JsonProperty("outcome_unknown_24h") long outcomeUnknown24h, @JsonProperty("rejected_24h") long rejected24h,
            @JsonProperty("cancelled_24h") long cancelled24h, @JsonProperty("submitted_24h") long submitted24h,
            @JsonProperty("running") long running,
            /** Every job ever recorded, by state (upper-case state word to count); the Jobs screen's state pills read
             *  their counts here, so a pill never shows a number from a different window than the list it filters. */
            @JsonProperty("by_state") Map<String, Long> byState) {
    }

    /**
     * Per-state counts as the pills need them: the state word upper-cased and trimmed, blank or null states dropped,
     * duplicates (two spellings of one state) summed, in the order they arrived. Pure, so it is unit-tested without a store.
     */
    static Map<String, Long> byStateOf(List<Map.Entry<String, Long>> rows) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (var row : rows) {
            String state = row.getKey() == null ? "" : row.getKey().strip().toUpperCase(java.util.Locale.ROOT);
            if (state.isEmpty()) continue;
            out.merge(state, row.getValue() == null ? 0L : row.getValue(), Long::sum);
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    public Stats stats() {
        return transactionBoundary.inTransaction(dsl -> {
            List<Map.Entry<String, Long>> byState = dsl.fetch("select state, count(*) as n from jobs group by state order by state")
                    .map(row -> Map.entry(String.valueOf(row.get("state", String.class)), row.get("n", Long.class)));
            var r = dsl.fetchOne("select count(*) as total, "
                    + "count(*) filter (where state in ('COMPLETED','FAILED','OUTCOME_UNKNOWN','REJECTED','CANCELLED') "
                    + "and finished_at >= now() - interval '24 hours') as total_24h, "
                    + "count(*) filter (where state = 'COMPLETED' and finished_at >= now() - interval '24 hours') as completed_24h, "
                    + "count(*) filter (where state = 'FAILED' and finished_at >= now() - interval '24 hours') as failed_24h, "
                    + "count(*) filter (where state = 'OUTCOME_UNKNOWN' and finished_at >= now() - interval '24 hours') as outcome_unknown_24h, "
                    + "count(*) filter (where state = 'REJECTED' and finished_at >= now() - interval '24 hours') as rejected_24h, "
                    + "count(*) filter (where state = 'CANCELLED' and finished_at >= now() - interval '24 hours') as cancelled_24h, "
                    + "count(*) filter (where submitted_at >= now() - interval '24 hours') as submitted_24h, "
                    + "count(*) filter (where state in ('REQUESTED', 'CLAIMED', 'EXECUTING')) as running from jobs");
            return new Stats(r.get("total", Long.class), r.get("total_24h", Long.class), r.get("completed_24h", Long.class),
                    r.get("failed_24h", Long.class), r.get("outcome_unknown_24h", Long.class), r.get("rejected_24h", Long.class),
                    r.get("cancelled_24h", Long.class), r.get("submitted_24h", Long.class), r.get("running", Long.class),
                    byStateOf(byState));
        });
    }

    public record Facets(@JsonProperty("states") List<String> states, @JsonProperty("job_types") List<String> jobTypes) {
    }

    /** The SQL {@code where} fragment and its bindings for a query -- pure, so it is unit-tested without a database. */
    record Where(String sql, List<Object> bindings) {
    }

    /** Jobs joined to their device for the name; the join is outer so a job whose device was removed still lists. */
    private static final String FROM = " from jobs j left join devices d on d.device_id = j.target_device_id";
    /** Job types whose operational target is a cluster or failover unit rather than a single member device. */
    public static final List<String> FAILOVER_JOB_TYPES = List.of(
            "cp_failover_readiness", "pan_failover_readiness",
            "cp_cluster_failover", "pan_cluster_failover",
            "cp_failover_resume", "pan_failover_config_sync");
    /** The job types whose run stores log chunks; only their rows carry {@code lines_stored} (the CASE keeps the subquery off every other row). */
    static final List<String> LOG_COLLECT_JOB_TYPES = List.of("cp_system_log_collect", "pan_system_log_collect");
    /** Lines stored by one collection run: its chunk ledger rows (kept after pruning), by the device index the table has. */
    static final String LINES_STORED_SQL = "case when j.job_type in ('cp_system_log_collect', 'pan_system_log_collect') "
            + "then (select sum(c.lines) from system_log_chunk c where c.device_id = j.target_device_id and c.job_id = j.job_id) "
            + "else null end as lines_stored";
    /**
     * Bounded lateral joins that resolve the job's target across all shapes:
     * device (coalescing discovery_candidate display_name and cluster reference like JooqDeviceRepository),
     * cluster / failover unit (failover_run, failover_resume, pan_config_sync),
     * policy source or container (policy_collection_request, policy_snapshot, discovery_run),
     * and packet analysis case (packet_analysis_case).
     */
    private static final String TARGET_JOINS = " left join lateral ( "
            + "    select c.display_name, c.cluster_reference, c.parent_candidate_id "
            + "    from discovery_candidate c "
            + "    where ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d.discovery_match_key "
            + "       or (c.vendor || '|' || c.stable_identifier) = d.discovery_match_key "
            + "       or c.stable_identifier = d.recorded_identity_primary) "
            + "    order by (c.parent_candidate_id is null) desc limit 1 "
            + ") dc on d.device_id is not null "
            + "left join lateral ( "
            + "    select c2.display_name from discovery_candidate c2 "
            + "    where c2.candidate_id = dc.parent_candidate_id "
            + "       or c2.stable_identifier = coalesce(d.cluster_member_ref, dc.cluster_reference) "
            + "    limit 1 "
            + ") c_parent on d.device_id is not null "
            + "left join lateral ( "
            + "    select coalesce(nullif(btrim(fr.cluster_ref), ''), nullif(btrim(fs.cluster_ref), ''), nullif(btrim(pcs.cluster_ref), '')) as cluster_ref, "
            + "           coalesce(nullif(btrim(fr.vs_id), ''), nullif(btrim(fr_res.vs_id), '')) as vs_id "
            + "    from (select 1) one "
            + "    left join failover_run fr on fr.job_id = j.job_id "
            + "    left join failover_resume fs on fs.job_id = j.job_id "
            + "    left join failover_run fr_res on fr_res.run_id = fs.run_id "
            + "    left join pan_config_sync pcs on pcs.job_id = j.job_id "
            + "    where fr.job_id is not null or fs.job_id is not null or pcs.job_id is not null "
            + "    limit 1 "
            + ") fo on j.job_type in ('cp_failover_readiness', 'pan_failover_readiness', 'cp_cluster_failover', 'pan_cluster_failover', 'cp_failover_resume', 'pan_failover_config_sync') "
            + "left join lateral ( "
            + "    select coalesce(pcr.source_id, d_mgr.device_id) as source_device_id, "
            + "           nullif(btrim(pcr.domain_ref), '') as domain_ref, "
            + "           coalesce( "
            + "               (select nullif(btrim(ps.metadata->>'containerName'), '') from policy_snapshot ps "
            + "                where ps.metadata->>'sourceId' = coalesce(pcr.source_id, d_mgr.device_id) "
            + "                  and ps.metadata->>'containerId' = nullif(btrim(pcr.domain_ref), '') limit 1), "
            + "               nullif(btrim(pcr.domain_ref), '') "
            + "           ) as container_name, "
            + "           coalesce( "
            + "               nullif(btrim(d_src.observed_hostname), ''), "
            + "               nullif(btrim(dc_src.display_name), ''), "
            + "               nullif(btrim(d_mgr.observed_hostname), ''), "
            + "               nullif(btrim(dc_mgr.display_name), ''), "
            + "               nullif(btrim(dr.management_address), '') "
            + "           ) as source_name "
            + "    from (select 1) one "
            + "    left join policy_collection_request pcr on pcr.job_id = j.job_id "
            + "    left join devices d_src on d_src.device_id = pcr.source_id "
            + "    left join lateral ( "
            + "        select c.display_name from discovery_candidate c "
            + "        where ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d_src.discovery_match_key "
            + "           or (c.vendor || '|' || c.stable_identifier) = d_src.discovery_match_key "
            + "           or c.stable_identifier = d_src.recorded_identity_primary) "
            + "        order by (c.parent_candidate_id is null) desc limit 1 "
            + "    ) dc_src on d_src.device_id is not null "
            + "    left join discovery_run dr on dr.run_id = j.target_ref "
            + "    left join lateral ( "
            + "        select dm.device_id, dm.observed_hostname, dm.discovery_match_key, dm.recorded_identity_primary "
            + "        from devices dm join endpoints em on em.device_id = dm.device_id "
            + "        where dr.run_id is not null "
            + "          and (em.address_ref = dr.management_address or split_part(em.address_ref, ':', 1) = split_part(dr.management_address, ':', 1)) "
            + "        order by (dm.vendor_hint = dr.vendor) desc, (dm.role = 'management_server') desc, dm.created_at asc limit 1 "
            + "    ) d_mgr on pcr.source_id is null and dr.run_id is not null "
            + "    left join lateral ( "
            + "        select c.display_name from discovery_candidate c "
            + "        where ((c.vendor || '|' || coalesce(c.owning_domain, '') || '|' || c.stable_identifier) = d_mgr.discovery_match_key "
            + "           or (c.vendor || '|' || c.stable_identifier) = d_mgr.discovery_match_key "
            + "           or c.stable_identifier = d_mgr.recorded_identity_primary) "
            + "        order by (c.parent_candidate_id is null) desc limit 1 "
            + "    ) dc_mgr on d_mgr.device_id is not null "
            + "    where pcr.job_id is not null or dr.run_id is not null limit 1 "
            + ") pol on j.target_kind = 'discovery_run' or j.job_type in ('cp_policy_collect', 'pan_policy_collect') "
            + "left join packet_analysis_case pac on j.target_kind = 'packet_analysis_case' and pac.case_id = j.target_ref";
    static final String SELECT = "select j.job_id, j.job_type, "
            + "coalesce(j.target_device_id, pol.source_device_id) as target_device_id, "
            + "coalesce(nullif(btrim(d.observed_hostname), ''), nullif(btrim(dc.display_name), ''), "
            + "(select nullif(btrim(split_part(e.address_ref, ':', 1)), '') from endpoints e where e.device_id = d.device_id order by e.created_at asc limit 1)) as device_name, "
            + "coalesce(fo.cluster_ref, nullif(btrim(c_parent.display_name), ''), nullif(btrim(d.cluster_member_ref), ''), nullif(btrim(dc.cluster_reference), '')) as cluster_ref, "
            + "fo.vs_id as failover_vs_id, "
            + "pol.domain_ref as policy_domain_ref, "
            + "pol.container_name as policy_container_name, "
            + "pol.source_name as policy_source_name, "
            + "nullif(btrim(pac.title), '') as packet_case_title, "
            + "j.target_kind as job_target_kind, "
            + "j.target_ref as job_target_ref, "
            + "j.state, j.outcome, CASE WHEN j.ssh_identity_unverified THEN concat_ws('; ', nullif(j.terminal_reason, ''), 'host_key_mismatch_warned: collected under an unverified identity') ELSE j.terminal_reason END AS terminal_reason, j.submitted_at, j.finished_at, "
            + "j.transcript_artefact_ref is not null as has_transcript, "
            + "case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 "
            + "     else extract(epoch from (now() - j.submitted_at)) * 1000 end as duration_ms, "
            + LINES_STORED_SQL + FROM + TARGET_JOINS;

    /** Closed allow-list of sortable columns mapped to constant SQL expressions; client text is never interpolated. */
    static final Map<String, String> SORT_COLUMNS = Map.ofEntries(
            Map.entry("device", "lower(nullif(d.observed_hostname, ''))"),
            Map.entry("device_name", "lower(nullif(d.observed_hostname, ''))"),
            Map.entry("target_device_id", "j.target_device_id"),
            Map.entry("job_type", "j.job_type"),
            Map.entry("type", "j.job_type"),
            Map.entry("state", "j.state"),
            Map.entry("duration", "case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 else extract(epoch from (now() - j.submitted_at)) * 1000 end"),
            Map.entry("duration_ms", "case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 else extract(epoch from (now() - j.submitted_at)) * 1000 end"),
            Map.entry("result", "nullif(coalesce(nullif(j.outcome, ''), j.terminal_reason), '')"),
            Map.entry("outcome", "nullif(j.outcome, '')"),
            Map.entry("terminal_reason", "nullif(j.terminal_reason, '')"),
            Map.entry("submitted_at", "j.submitted_at"),
            Map.entry("finished_at", "j.finished_at"));

    static String orderByOf(JobQuery query) {
        return orderByOf(query.sort().orElse(""), query.order().orElse(""));
    }

    static String orderByOf(String sort, String order) {
        String s = sort == null ? "" : sort.strip();
        String o = order == null ? "" : order.strip();
        if (s.isEmpty()) {
            if (!o.isEmpty()) throw new IllegalArgumentException("order requires sort");
            return " order by j.submitted_at desc nulls last, j.job_id desc";
        }
        String column = SORT_COLUMNS.get(s);
        if (column == null) throw new IllegalArgumentException("Unknown sort column: " + s);
        String dir = o.isEmpty() ? "asc" : o.toLowerCase(java.util.Locale.ROOT);
        if (!dir.equals("asc") && !dir.equals("desc")) throw new IllegalArgumentException("Unknown sort order: " + o);
        return " order by " + column + " " + dir + " nulls last, j.job_id asc";
    }

    private final TransactionBoundary transactionBoundary;

    public JobLogQueryService(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
    }

    public List<JobEvent> recent() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(SELECT + " order by j.submitted_at desc limit {0}", RECENT_LIMIT)
                .map(JobLogQueryService::toEvent));
    }

    public JobPage query(JobQuery query) {
        Where where = whereOf(query);
        String orderBy = orderByOf(query);
        Object[] bindings = where.bindings().toArray();
        return transactionBoundary.inTransaction(dsl -> {
            long total = dsl.fetchOne("select count(*)" + FROM + where.sql(), bindings).get(0, Long.class);
            int offset = (query.page() - 1) * query.pageSize();
            List<Object> pageBindings = new ArrayList<>(where.bindings());
            pageBindings.add(query.pageSize());
            pageBindings.add(offset);
            List<JobEvent> items = dsl.fetch(SELECT + where.sql() + orderBy + " limit ? offset ?",
                    pageBindings.toArray()).map(JobLogQueryService::toEvent);
            return new JobPage(items, query.page(), query.pageSize(), total);
        });
    }

    public Facets facets() {
        return transactionBoundary.inTransaction(dsl -> new Facets(
                dsl.fetch("select distinct state from jobs order by state").map(r -> r.get(0, String.class)),
                dsl.fetch("select distinct job_type from jobs order by job_type").map(r -> r.get(0, String.class))));
    }

    /** The filtered history as CSV, newest first, at most {@link #MAX_EXPORT_ROWS} rows; the last line says so when cut. */
    public String exportCsv(JobQuery query) {
        return toCsv(exportRows(query));
    }

    /**
     * The rows {@link #exportCsv} renders, at most {@link #MAX_EXPORT_ROWS} + 1 (the extra row marks truncation).
     * A masked caller maps each row through the record masking before {@link #toCsv}: the target label can be a cluster,
     * container, policy source or packet case name that text masking alone does not know (security review 2026-11-05).
     */
    public List<JobEvent> exportRows(JobQuery query) {
        Where where = whereOf(query);
        String orderBy = orderByOf(query);
        return transactionBoundary.inTransaction(dsl -> {
            List<Object> bindings = new ArrayList<>(where.bindings());
            bindings.add(MAX_EXPORT_ROWS + 1);
            return dsl.fetch(SELECT + where.sql() + orderBy + " limit ?", bindings.toArray())
                    .map(JobLogQueryService::toEvent);
        });
    }

    static Where whereOf(JobQuery query) {
        List<String> clauses = new ArrayList<>();
        List<Object> bindings = new ArrayList<>();
        query.state().filter(s -> !s.isBlank()).ifPresent(s -> {
            // One state, or a comma-separated set (the Operations screen's Queue tab is REQUESTED,CLAIMED).
            List<String> states = java.util.Arrays.stream(s.split(",")).map(String::strip).filter(x -> !x.isEmpty())
                    .map(x -> x.toUpperCase(java.util.Locale.ROOT)).toList();
            if (states.size() == 1) {
                clauses.add("j.state = ?");
                bindings.add(states.get(0));
            } else if (!states.isEmpty()) {
                clauses.add("j.state in (" + String.join(", ", java.util.Collections.nCopies(states.size(), "?")) + ")");
                bindings.addAll(states);
            }
        });
        query.jobType().filter(s -> !s.isBlank()).ifPresent(s -> {
            // An exact type, or a vendor-neutral suffix (Overview links: "inventory_collect" matches
            // cp_inventory_collect and pan_inventory_collect; "backup" matches every *_backup type).
            clauses.add("(j.job_type = ? or j.job_type like ?)");
            bindings.add(s.strip());
            bindings.add("%\\_" + s.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_"));
        });
        query.deviceId().filter(s -> !s.isBlank()).ifPresent(s -> {
            clauses.add("j.target_device_id = ?");
            bindings.add(s.strip());
        });
        query.since().ifPresent(t -> {
            clauses.add("j.submitted_at >= ?");
            bindings.add(Timestamp.from(t));
        });
        query.until().ifPresent(t -> {
            clauses.add("j.submitted_at <= ?");
            bindings.add(Timestamp.from(t));
        });
        query.text().filter(s -> !s.isBlank()).ifPresent(s -> {
            String like = "%" + s.strip().toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            clauses.add("(lower(j.job_id) like ? or lower(j.target_device_id) like ? or lower(coalesce(d.observed_hostname, '')) like ? "
                    + "or lower(coalesce(j.terminal_reason, '')) like ?)");
            bindings.add(like);
            bindings.add(like);
            bindings.add(like);
            bindings.add(like);
        });
        return new Where(clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses), bindings);
    }

    public static String toCsv(List<JobEvent> rows) {
        StringBuilder csv = new StringBuilder("job_id,job_type,device_name,target_device_id,state,outcome,submitted_at,finished_at,duration_ms,terminal_reason\r\n");
        int emitted = 0;
        for (JobEvent row : rows) {
            if (emitted == MAX_EXPORT_ROWS) {
                csv.append("# export truncated at ").append(MAX_EXPORT_ROWS).append(" rows; narrow the filter\r\n");
                break;
            }
            csv.append(csvCell(row.jobId())).append(',').append(csvCell(row.jobType())).append(',')
                    .append(csvCell(row.deviceName())).append(',').append(csvCell(row.targetDeviceId())).append(',')
                    .append(csvCell(row.state())).append(',').append(csvCell(row.outcome())).append(',')
                    .append(csvCell(row.submittedAt() == null ? "" : row.submittedAt().toString())).append(',')
                    .append(csvCell(row.finishedAt() == null ? "" : row.finishedAt().toString())).append(',')
                    .append(row.durationMs() == null ? "" : row.durationMs().toString()).append(',')
                    .append(csvCell(row.terminalReason())).append("\r\n");
            emitted++;
        }
        return csv.toString();
    }

    /** RFC 4180 quoting; a leading formula character is prefixed so a spreadsheet never executes a device's reason text. */
    static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        String v = value;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    static JobEvent toEvent(Record row) {
        Number durNum = row.get("duration_ms", Number.class);
        Long dur = durNum != null ? durNum.longValue() : null;
        Timestamp subTs = row.get("submitted_at", Timestamp.class);
        Timestamp finTs = row.get("finished_at", Timestamp.class);
        // Absent from an older row shape (a caller selecting without the column): no claim, not 0.
        Number linesNum = row.field("lines_stored") == null ? null : row.get("lines_stored", Number.class);
        String jobType = row.get("job_type", String.class);
        String targetDeviceId = row.get("target_device_id", String.class);
        String rawDeviceName = optionalString(row, "device_name");
        String clusterRef = optionalString(row, "cluster_ref");
        String failoverVsId = optionalString(row, "failover_vs_id");
        String policyDomainRef = optionalString(row, "policy_domain_ref");
        String policyContainerName = optionalString(row, "policy_container_name");
        String policySourceName = optionalString(row, "policy_source_name");
        String packetCaseTitle = optionalString(row, "packet_case_title");
        String jobTargetKind = optionalString(row, "job_target_kind");
        String jobTargetRef = optionalString(row, "job_target_ref");
        Number fleetCountNum = row.field("target_device_count") == null ? null : row.get("target_device_count", Number.class);
        Integer fleetCount = fleetCountNum != null && fleetCountNum.intValue() > 0
                ? Integer.valueOf(fleetCountNum.intValue())
                : ("fleet".equalsIgnoreCase(jobTargetKind) ? parsePositiveCount(jobTargetRef) : null);

        String resolvedName;
        String resolvedKind;
        String resolvedClusterRef = null;
        if (fleetCount != null) {
            resolvedName = "Fleet (" + fleetCount + " devices)";
            resolvedKind = "fleet";
        } else if (jobType != null && FAILOVER_JOB_TYPES.contains(jobType) && failoverVsId != null) {
            resolvedName = "VSID " + failoverVsId;
            resolvedKind = "failover_unit";
            resolvedClusterRef = clusterRef;
        } else if (jobType != null && FAILOVER_JOB_TYPES.contains(jobType) && clusterRef != null) {
            resolvedName = clusterRef;
            resolvedKind = "cluster";
            resolvedClusterRef = clusterRef;
        } else if (policyDomainRef != null) {
            resolvedName = firstNonBlank(policyContainerName, policyDomainRef);
            resolvedKind = "policy_container";
            resolvedClusterRef = jobTargetRef;
        } else if ((policySourceName != null
                || "discovery_run".equals(jobTargetKind)
                || "cp_policy_collect".equals(jobType)
                || "pan_policy_collect".equals(jobType))
                && firstNonBlank(policySourceName, rawDeviceName, targetDeviceId, jobTargetRef) != null) {
            resolvedName = firstNonBlank(policySourceName, rawDeviceName);
            resolvedKind = "policy_source";
            resolvedClusterRef = jobTargetRef;
        } else if (("packet_analysis_case".equals(jobTargetKind) || "packet_analysis_run".equals(jobType))
                && firstNonBlank(packetCaseTitle, rawDeviceName, jobTargetRef, targetDeviceId) != null) {
            resolvedName = firstNonBlank(packetCaseTitle, rawDeviceName, jobTargetRef);
            resolvedKind = "packet_analysis_case";
            resolvedClusterRef = jobTargetRef;
        } else if ((targetDeviceId != null && !targetDeviceId.isBlank()) || rawDeviceName != null) {
            resolvedName = rawDeviceName;
            resolvedKind = "device";
            resolvedClusterRef = clusterRef;
        } else {
            resolvedName = null;
            resolvedKind = null;
        }

        return new JobEvent(
                row.get("job_id", String.class),
                jobType,
                targetDeviceId,
                resolvedName,
                row.get("state", String.class),
                row.get("outcome", String.class),
                row.get("terminal_reason", String.class),
                subTs != null ? subTs.toInstant() : null,
                finTs != null ? finTs.toInstant() : null,
                dur,
                Boolean.TRUE.equals(row.get("has_transcript", Boolean.class)),
                linesNum == null ? null : linesNum.longValue(),
                resolvedKind,
                resolvedClusterRef);
    }

    private static String optionalString(Record row, String column) {
        if (row.field(column) == null) {
            return null;
        }
        String value = row.get(column, String.class);
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return null;
    }

    private static Integer parsePositiveCount(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(raw.strip());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
