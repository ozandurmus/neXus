package com.securityexpert.nexus.ui2.service.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;
import org.springframework.stereotype.Service;

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
            /** The device's observed hostname at read time; null when the device has none recorded. */
            @JsonProperty("device_name") String deviceName,
            @JsonProperty("state") String state,
            @JsonProperty("outcome") String outcome,
            @JsonProperty("terminal_reason") String terminalReason,
            @JsonProperty("submitted_at") Instant submittedAt,
            @JsonProperty("finished_at") Instant finishedAt,
            @JsonProperty("duration_ms") Long durationMs) {

        public JobEvent(String jobId, String jobType, String targetDeviceId, String state, String terminalReason, Instant submittedAt) {
            this(jobId, jobType, targetDeviceId, null, state, null, terminalReason, submittedAt, null, null);
        }
    }

    /** Every filter optional; {@code text} matches job id, device id and terminal reason (case-insensitive substring). */
    public record JobQuery(Optional<String> state, Optional<String> jobType, Optional<String> deviceId,
            Optional<Instant> since, Optional<Instant> until, Optional<String> text, int page, int pageSize) {

        public JobQuery {
            page = Math.max(1, page);
            pageSize = Math.min(MAX_PAGE_SIZE, Math.max(1, pageSize));
        }
    }

    public record JobPage(
            @JsonProperty("items") List<JobEvent> items,
            @JsonProperty("page") int page,
            @JsonProperty("page_size") int pageSize,
            @JsonProperty("total") long total) {
    }

    public record Facets(@JsonProperty("states") List<String> states, @JsonProperty("job_types") List<String> jobTypes) {
    }

    /** The SQL {@code where} fragment and its bindings for a query -- pure, so it is unit-tested without a database. */
    record Where(String sql, List<Object> bindings) {
    }

    /** Jobs joined to their device for the name; the join is outer so a job whose device was removed still lists. */
    private static final String FROM = " from jobs j left join devices d on d.device_id = j.target_device_id";
    private static final String SELECT = "select j.job_id, j.job_type, j.target_device_id, d.observed_hostname as device_name, "
            + "j.state, j.outcome, j.terminal_reason, j.submitted_at, j.finished_at, "
            + "case when j.finished_at is not null then extract(epoch from (j.finished_at - j.submitted_at)) * 1000 "
            + "     else extract(epoch from (now() - j.submitted_at)) * 1000 end as duration_ms" + FROM;

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
        Object[] bindings = where.bindings().toArray();
        return transactionBoundary.inTransaction(dsl -> {
            long total = dsl.fetchOne("select count(*)" + FROM + where.sql(), bindings).get(0, Long.class);
            int offset = (query.page() - 1) * query.pageSize();
            List<Object> pageBindings = new ArrayList<>(where.bindings());
            pageBindings.add(query.pageSize());
            pageBindings.add(offset);
            List<JobEvent> items = dsl.fetch(SELECT + where.sql() + " order by j.submitted_at desc limit ? offset ?",
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
        Where where = whereOf(query);
        List<JobEvent> rows = transactionBoundary.inTransaction(dsl -> {
            List<Object> bindings = new ArrayList<>(where.bindings());
            bindings.add(MAX_EXPORT_ROWS + 1);
            return dsl.fetch(SELECT + where.sql() + " order by j.submitted_at desc limit ?", bindings.toArray())
                    .map(JobLogQueryService::toEvent);
        });
        return toCsv(rows);
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
            clauses.add("j.job_type = ?");
            bindings.add(s.strip());
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

    static String toCsv(List<JobEvent> rows) {
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

    private static JobEvent toEvent(Record row) {
        Number durNum = row.get("duration_ms", Number.class);
        Long dur = durNum != null ? durNum.longValue() : null;
        Timestamp subTs = row.get("submitted_at", Timestamp.class);
        Timestamp finTs = row.get("finished_at", Timestamp.class);
        return new JobEvent(
                row.get("job_id", String.class),
                row.get("job_type", String.class),
                row.get("target_device_id", String.class),
                row.get("device_name", String.class),
                row.get("state", String.class),
                row.get("outcome", String.class),
                row.get("terminal_reason", String.class),
                subTs != null ? subTs.toInstant() : null,
                finTs != null ? finTs.toInstant() : null,
                dur);
    }
}
