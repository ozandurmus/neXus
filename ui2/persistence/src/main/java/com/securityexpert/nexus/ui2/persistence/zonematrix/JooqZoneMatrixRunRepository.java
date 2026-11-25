package com.securityexpert.nexus.ui2.persistence.zonematrix;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

/**
 * jOOQ implementation of {@link ZoneMatrixRunRepository} over the V267 tables. Plain SQL with bound values only; JSONB
 * columns are written and read through {@link ZoneMatrixRunJson}. The database enforces what this class relies on: one
 * {@code RUNNING} run per policy source (partial unique index), a finished run is final, findings are inserted only
 * while their run is {@code RUNNING} (V267 guard triggers), and updates on {@code zone_matrix_finding} touch only the
 * lifecycle columns granted by V267.
 */
public final class JooqZoneMatrixRunRepository implements ZoneMatrixRunRepository {
    private static final String UNIQUE_VIOLATION = "23505";
    private static final int MAX_LISTING = 500;
    private static final String RUN_COLUMNS = "run_id, version_id, version_number, content_sha256, policy_source_id, "
            + "engine_version, state, failure_reason, started_at, finished_at, snapshot_collected_at, snapshot_rule_count, "
            + "vendor, evaluated, coverage, coverage_items::text as coverage_items_json, collection_failures, "
            + "count_violation, count_compliant, count_not_evaluable, count_unknown, count_not_applicable, count_findings, "
            + "duration_ms";
    private static final String FINDING_COLUMNS = "f.policy_source_id, f.rule_uid, "
            + "f.inline_parent_path::text as inline_parent_path_json, f.violated_pairs::text as violated_pairs_json, "
            + "f.rule_id, f.rule_number, f.rule_name, f.section_id, f.section_name, "
            + "f.target_device_ids::text as target_device_ids_json, f.outcome, f.reason, f.highest_severity, "
            + "f.flags::text as flags_json, f.covering_rule_id, f.violated_cells::text as violated_cells_json, "
            + "f.undecided_parts::text as undecided_parts_json, f.nat_view, f.first_observed_at, "
            + "f.first_observed_any_version, f.last_observed_at, f.resolved_at, f.resolution_reason";

    private final TransactionBoundary transactions;

    public JooqZoneMatrixRunRepository(TransactionBoundary transactions) { this.transactions = transactions; }

    @Override public void start(NewRun run) {
        try {
            transactions.inTransaction(dsl -> dsl.execute("insert into zone_matrix_run (run_id, version_id, version_number, "
                    + "content_sha256, policy_source_id, engine_version, state, started_at, snapshot_collected_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, 'RUNNING', {6}, {7}::timestamptz)",
                    run.runId(), run.versionId(), run.versionNumber(), run.contentSha256(), run.policySourceId(),
                    run.engineVersion(), odt(run.startedAt()), odtOrNull(run.snapshotCollectedAt())));
        } catch (DataAccessException e) {
            if (UNIQUE_VIOLATION.equals(e.sqlState()) && !alreadyStored(run.runId())) {
                throw new RunningRunExists(runningRunOf(run.policySourceId()).orElse(null));
            }
            throw e;
        }
    }

    private boolean alreadyStored(String runId) {
        return transactions.inTransaction(dsl -> dsl.fetchOne(
                "select 1 from zone_matrix_run where run_id = {0}", runId) != null);
    }

    @Override public Optional<String> runningRunOf(String policySourceId) {
        return transactions.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne(
                "select run_id from zone_matrix_run where policy_source_id = {0} and state = 'RUNNING'", policySourceId))
                .map(r -> r.get("run_id", String.class)));
    }

    @Override public boolean complete(String runId, Instant finishedAt, long durationMillis, Counts counts,
            Coverage coverage, List<Finding> findings) {
        return transactions.inTransaction(dsl -> {
            Record row = dsl.fetchOne("select state from zone_matrix_run where run_id = {0} for update", runId);
            if (row == null || !STATE_RUNNING.equals(row.get("state", String.class))) return false;
            for (Finding f : findings) insertFinding(dsl, runId, f);
            markRunComplete(dsl, runId, finishedAt, durationMillis, counts, coverage);
            return true;
        });
    }

    @Override public boolean complete(String runId, Instant finishedAt, long durationMillis, Coverage coverage,
            Function<PriorHistory, CompletionPlan> planner) {
        return transactions.inTransaction(dsl -> {
            Record row = dsl.fetchOne(
                    "select state, policy_source_id from zone_matrix_run where run_id = {0} for update", runId);
            if (row == null || !STATE_RUNNING.equals(row.get("state", String.class))) return false;
            String policySourceId = row.get("policy_source_id", String.class);

            Record latestRow = dsl.fetchOne("select run_id from zone_matrix_run "
                    + "where policy_source_id = {0} and state = 'COMPLETE' "
                    + "order by finished_at desc, started_at desc, run_id desc limit 1", policySourceId);
            String latestCompleteRunId = latestRow == null ? null : latestRow.get("run_id", String.class);

            List<PriorFinding> priors = dsl.fetch("select f.finding_id, f.run_id, r.version_id, r.version_number, "
                    + "r.finished_at as run_finished_at, r.evaluated as run_evaluated, r.coverage as run_coverage, "
                    + "r.collection_failures as run_collection_failures, " + FINDING_COLUMNS
                    + " from zone_matrix_finding f join zone_matrix_run r on r.run_id = f.run_id "
                    + "where f.policy_source_id = {0} and r.state = 'COMPLETE' "
                    + "order by r.finished_at asc, r.started_at asc, r.run_id asc, f.finding_id asc", policySourceId)
                    .stream().map(JooqZoneMatrixRunRepository::priorFinding).toList();

            CompletionPlan plan = planner.apply(new PriorHistory(latestCompleteRunId, priors));

            for (ResolutionUpdate res : plan.resolutions()) {
                Set<String> targetPairs = Set.copyOf(res.violatedPairs());
                for (PriorFinding pf : priors) {
                    Finding f = pf.finding();
                    if (f.resolvedAt() != null) continue;
                    if (f.policySourceId().equals(policySourceId)
                            && f.ruleUid().equals(res.ruleUid())
                            && f.inlineParentPath().equals(res.inlineParentPath())
                            && Set.copyOf(f.violatedPairs()).equals(targetPairs)) {
                        dsl.execute("update zone_matrix_finding "
                                + "set resolved_at = {1}, resolution_reason = {2} "
                                + "where finding_id = {0} and resolved_at is null",
                                pf.findingId(), odt(res.resolvedAt()), res.resolutionReason());
                    }
                }
            }

            for (Finding f : plan.findingsToInsert()) insertFinding(dsl, runId, f);
            markRunComplete(dsl, runId, finishedAt, durationMillis, plan.counts(), coverage);
            return true;
        });
    }

    private static void markRunComplete(DSLContext dsl, String runId, Instant finishedAt, long durationMillis,
            Counts counts, Coverage coverage) {
        int updated = dsl.execute("update zone_matrix_run set state = 'COMPLETE', finished_at = {1}, "
                + "snapshot_collected_at = {2}::timestamptz, snapshot_rule_count = {3}::integer, vendor = {4}::text, "
                + "evaluated = {5}, coverage = {6}, coverage_items = {7}::jsonb, collection_failures = {8}, "
                + "count_violation = {9}, count_compliant = {10}, count_not_evaluable = {11}, count_unknown = {12}, "
                + "count_not_applicable = {13}, count_findings = {14}, duration_ms = {15} "
                + "where run_id = {0} and state = 'RUNNING'",
                runId, odt(finishedAt), odtOrNull(coverage.snapshotCollectedAt()), coverage.snapshotRuleCount(),
                coverage.vendor(), coverage.evaluated(), coverage.coverage(),
                ZoneMatrixRunJson.write(coverage.itemCodes()), coverage.collectionFailures(),
                counts.violation(), counts.compliant(), counts.notEvaluable(), counts.unknown(),
                counts.notApplicable(), counts.findings(), durationMillis);
        if (updated != 1) throw new IllegalStateException("the zone matrix run left RUNNING inside its own lock");
    }

    private static void insertFinding(DSLContext dsl, String runId, Finding f) {
        dsl.execute("insert into zone_matrix_finding (run_id, policy_source_id, rule_uid, inline_parent_path, "
                + "violated_pairs, rule_id, rule_number, rule_name, section_id, section_name, target_device_ids, outcome, "
                + "reason, highest_severity, flags, covering_rule_id, violated_cells, undecided_parts, nat_view, "
                + "first_observed_at, first_observed_any_version, last_observed_at, resolved_at, resolution_reason) "
                + "values ({0}, {1}, {2}, {3}::jsonb, {4}::jsonb, {5}::text, {6}::integer, {7}::text, {8}::text, {9}::text, "
                + "{10}::jsonb, {11}, {12}::text, {13}::text, {14}::jsonb, {15}::text, {16}::jsonb, {17}::jsonb, {18}::text, "
                + "{19}, {20}::timestamptz, {21}, {22}::timestamptz, {23}::text)",
                runId, f.policySourceId(), f.ruleUid(), ZoneMatrixRunJson.write(f.inlineParentPath()),
                ZoneMatrixRunJson.write(f.violatedPairs()), f.ruleId(), f.ruleNumber(), f.ruleName(), f.sectionId(),
                f.sectionName(), ZoneMatrixRunJson.write(f.targetDeviceIds()), f.outcome(), f.reason(),
                f.highestSeverity(), ZoneMatrixRunJson.write(f.flags()), f.coveringRuleId(),
                ZoneMatrixRunJson.write(cellsJson(f.violatedCells())), ZoneMatrixRunJson.write(f.undecidedParts()),
                f.natView(), odt(f.firstObservedAt()), odtOrNull(f.firstObservedAnyVersion()), odt(f.lastObservedAt()),
                odtOrNull(f.resolvedAt()), f.resolutionReason());
    }

    @Override public boolean fail(String runId, FailureReason reason, Instant finishedAt) {
        return fail(runId, reason, finishedAt, null);
    }

    @Override public boolean fail(String runId, FailureReason reason, Instant finishedAt, Instant snapshotCollectedAt) {
        return transactions.inTransaction(dsl -> dsl.execute("update zone_matrix_run set state = 'FAILED', "
                + "failure_reason = {1}, finished_at = {2}, "
                + "snapshot_collected_at = coalesce({3}::timestamptz, snapshot_collected_at) "
                + "where run_id = {0} and state = 'RUNNING'",
                runId, reason.name(), odt(finishedAt), odtOrNull(snapshotCollectedAt))) == 1;
    }

    @Override public int failInterrupted(Instant finishedAt) {
        return transactions.inTransaction(dsl -> dsl.execute("update zone_matrix_run set state = 'FAILED', "
                + "failure_reason = {0}, finished_at = {1} where state = 'RUNNING'",
                FailureReason.INTERRUPTED_AT_STARTUP.name(), odt(finishedAt)));
    }

    @Override public Optional<Run> latestComplete(String policySourceId) {
        return transactions.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne("select " + RUN_COLUMNS
                + " from zone_matrix_run where policy_source_id = {0} and state = 'COMPLETE' "
                + "order by finished_at desc, started_at desc, run_id desc limit 1", policySourceId))
                .map(JooqZoneMatrixRunRepository::run));
    }

    @Override public Optional<Run> latestRun(String policySourceId) {
        return transactions.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne("select " + RUN_COLUMNS
                + " from zone_matrix_run where policy_source_id = {0} "
                + "order by coalesce(finished_at, started_at) desc, started_at desc, run_id desc limit 1",
                policySourceId)).map(JooqZoneMatrixRunRepository::run));
    }

    @Override public Optional<Run> latestFinishedForVersion(String policySourceId, String versionId) {
        return transactions.inTransaction(dsl -> Optional.ofNullable(dsl.fetchOne("select " + RUN_COLUMNS
                + " from zone_matrix_run where policy_source_id = {0} and version_id = {1} "
                + "and state in ('COMPLETE', 'FAILED') "
                + "order by finished_at desc, started_at desc, run_id desc limit 1",
                policySourceId, versionId)).map(JooqZoneMatrixRunRepository::run));
    }

    @Override public List<Finding> findingsOf(String completeRunId) {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + FINDING_COLUMNS
                + " from zone_matrix_finding f join zone_matrix_run r on r.run_id = f.run_id "
                + "where f.run_id = {0} and r.state = 'COMPLETE' order by f.finding_id", completeRunId)
                .stream().map(JooqZoneMatrixRunRepository::finding).toList());
    }

    @Override public List<ResolvedByVersionFinding> resolvedByVersionChange(Instant since) {
        return transactions.inTransaction(dsl -> {
            var rows = dsl.fetch("select f.finding_id, "
                    + "r_from.version_id as from_version_id, r_from.version_number as from_version_number, "
                    + "r_res.version_id as res_version_id, r_res.version_number as res_version_number, "
                    + FINDING_COLUMNS
                    + " from zone_matrix_finding f "
                    + "join zone_matrix_run r_from on r_from.run_id = f.run_id and r_from.state = 'COMPLETE' "
                    + "left join lateral ("
                    + "  select r2.version_id, r2.version_number "
                    + "  from zone_matrix_run r2 "
                    + "  where r2.policy_source_id = f.policy_source_id "
                    + "    and r2.state = 'COMPLETE' "
                    + "    and r2.finished_at = f.resolved_at "
                    + "    and r2.run_id <> f.run_id "
                    + "  order by (case when r2.version_id <> r_from.version_id then 0 else 1 end), "
                    + "           r2.started_at asc, r2.run_id asc "
                    + "  limit 1"
                    + ") r_res on true "
                    + "where f.resolution_reason = 'MATRIX_VERSION_CHANGED' "
                    + "  and f.resolved_at is not null "
                    + "  and ({0}::timestamptz is null or f.resolved_at >= {0}::timestamptz) "
                    + "order by f.resolved_at desc, f.finding_id desc",
                    odtOrNull(since));

            record DedupKey(String policySourceId, String ruleUid, List<String> parentPath, Set<String> pairs,
                    Instant firstObservedAt, Instant resolvedAt) {}
            Map<DedupKey, ResolvedByVersionFinding> deduplicated = new LinkedHashMap<>();
            for (Record r : rows) {
                Finding f = finding(r);
                DedupKey key = new DedupKey(f.policySourceId(), f.ruleUid(), f.inlineParentPath(),
                        Set.copyOf(f.violatedPairs()), f.firstObservedAt(), f.resolvedAt());
                String fromVersionId = r.get("from_version_id", String.class);
                int fromVersionNumber = r.get("from_version_number", Integer.class);
                ResolvedByVersionFinding existing = deduplicated.get(key);
                if (existing == null) {
                    deduplicated.put(key, new ResolvedByVersionFinding(
                            fromVersionId,
                            fromVersionNumber,
                            r.get("res_version_id", String.class),
                            r.get("res_version_number", Integer.class),
                            f.resolvedAt(),
                            f));
                } else if (!fromVersionId.equals(existing.resolvedInVersionId())
                        && existing.resolvedFromVersionId().equals(existing.resolvedInVersionId())) {
                    // When an incomplete run on the new version carried the finding before the next complete run
                    // resolved it, recover the earlier proven version id from the older row of the same open chain.
                    deduplicated.put(key, new ResolvedByVersionFinding(
                            fromVersionId,
                            fromVersionNumber,
                            existing.resolvedInVersionId(),
                            existing.resolvedInVersionNumber(),
                            existing.resolvedAt(),
                            existing.finding()));
                }
            }
            return List.copyOf(deduplicated.values());
        });
    }

    @Override public List<Run> runs(int limit) {
        int bounded = Math.max(0, Math.min(limit, MAX_LISTING));
        return transactions.inTransaction(dsl -> dsl.fetch("select " + RUN_COLUMNS
                + " from zone_matrix_run order by started_at desc, run_id desc limit {0}", bounded)
                .stream().map(JooqZoneMatrixRunRepository::run).toList());
    }

    // ---------------------------------------------------------------- mapping

    static List<Map<String, Object>> cellsJson(List<Cell> cells) {
        List<Map<String, Object>> out = new ArrayList<>(cells.size());
        for (Cell c : cells) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("source_zone", c.sourceZone());
            m.put("destination_zone", c.destinationZone());
            m.put("statement", c.statement());
            m.put("severity", c.severity());
            m.put("not_allowed_services", c.notAllowedServices());
            m.put("nat_view", c.natView());
            if (c.witness() == null) {
                m.put("witness", null);
            } else {
                Map<String, Object> w = new LinkedHashMap<>();
                w.put("source_range", c.witness().sourceRange());
                w.put("destination_range", c.witness().destinationRange());
                w.put("service", c.witness().service());
                w.put("sample_source_ip", c.witness().sampleSourceIp());
                w.put("sample_destination_ip", c.witness().sampleDestinationIp());
                m.put("witness", w);
            }
            out.add(m);
        }
        return out;
    }

    static List<Cell> cells(String json) {
        Object parsed = ZoneMatrixRunJson.parse(json);
        if (parsed == null) return List.of();
        List<Cell> out = new ArrayList<>();
        for (Object o : (List<?>) parsed) {
            Map<?, ?> m = (Map<?, ?>) o;
            Witness witness = null;
            if (m.get("witness") instanceof Map<?, ?> w) {
                witness = new Witness((String) w.get("source_range"), (String) w.get("destination_range"),
                        (String) w.get("service"), longOf(w.get("sample_source_ip")), longOf(w.get("sample_destination_ip")));
            }
            List<String> notAllowed = new ArrayList<>();
            if (m.get("not_allowed_services") instanceof List<?> l) for (Object s : l) notAllowed.add((String) s);
            out.add(new Cell((String) m.get("source_zone"), (String) m.get("destination_zone"), (String) m.get("statement"),
                    (String) m.get("severity"), notAllowed, (String) m.get("nat_view"), witness));
        }
        return List.copyOf(out);
    }

    private static long longOf(Object o) { return o instanceof Long l ? l : 0L; }

    private static PriorFinding priorFinding(Record r) {
        Boolean evaluated = r.get("run_evaluated", Boolean.class);
        String coverage = r.get("run_coverage", String.class);
        Integer failures = r.get("run_collection_failures", Integer.class);
        boolean completeScope = Boolean.TRUE.equals(evaluated)
                && "COMPLETE".equals(coverage)
                && (failures == null || failures == 0);
        return new PriorFinding(
                r.get("finding_id", Long.class),
                r.get("run_id", String.class),
                r.get("version_id", String.class),
                r.get("version_number", Integer.class),
                instant(r, "run_finished_at"),
                completeScope,
                finding(r));
    }

    private static Finding finding(Record r) {
        return new Finding(
                r.get("policy_source_id", String.class),
                r.get("rule_uid", String.class),
                ZoneMatrixRunJson.strings(r.get("inline_parent_path_json", String.class)),
                ZoneMatrixRunJson.strings(r.get("violated_pairs_json", String.class)),
                r.get("rule_id", String.class),
                r.get("rule_number", Integer.class),
                r.get("rule_name", String.class),
                r.get("section_id", String.class),
                r.get("section_name", String.class),
                ZoneMatrixRunJson.strings(r.get("target_device_ids_json", String.class)),
                r.get("outcome", String.class),
                r.get("reason", String.class),
                r.get("highest_severity", String.class),
                ZoneMatrixRunJson.strings(r.get("flags_json", String.class)),
                r.get("covering_rule_id", String.class),
                cells(r.get("violated_cells_json", String.class)),
                ZoneMatrixRunJson.strings(r.get("undecided_parts_json", String.class)),
                r.get("nat_view", String.class),
                instant(r, "first_observed_at"),
                instant(r, "first_observed_any_version"),
                instant(r, "last_observed_at"),
                instant(r, "resolved_at"),
                r.get("resolution_reason", String.class));
    }

    private static Run run(Record r) {
        Integer violation = r.get("count_violation", Integer.class);
        Counts counts = violation == null ? null : new Counts(violation, r.get("count_compliant", Integer.class),
                r.get("count_not_evaluable", Integer.class), r.get("count_unknown", Integer.class),
                r.get("count_not_applicable", Integer.class), r.get("count_findings", Integer.class));
        return new Run(
                r.get("run_id", String.class),
                r.get("version_id", String.class),
                r.get("version_number", Integer.class),
                r.get("content_sha256", String.class),
                r.get("policy_source_id", String.class),
                r.get("engine_version", String.class),
                r.get("state", String.class),
                r.get("failure_reason", String.class),
                instant(r, "started_at"),
                instant(r, "finished_at"),
                instant(r, "snapshot_collected_at"),
                r.get("snapshot_rule_count", Integer.class),
                r.get("vendor", String.class),
                r.get("evaluated", Boolean.class),
                r.get("coverage", String.class),
                ZoneMatrixRunJson.strings(r.get("coverage_items_json", String.class)),
                r.get("collection_failures", Integer.class),
                counts,
                r.get("duration_ms", Long.class));
    }

    private static Instant instant(Record r, String field) {
        OffsetDateTime value = r.get(field, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime odt(Instant instant) { return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC); }

    private static OffsetDateTime odtOrNull(Instant instant) { return instant == null ? null : odt(instant); }
}
