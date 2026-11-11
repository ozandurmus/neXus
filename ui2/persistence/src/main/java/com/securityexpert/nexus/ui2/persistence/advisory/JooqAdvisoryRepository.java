package com.securityexpert.nexus.ui2.persistence.advisory;

import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/** Plain-SQL implementation of {@link AdvisoryRepository} over the V205 tables. */
public final class JooqAdvisoryRepository implements AdvisoryRepository {

    private static final String RUN_COLUMNS = "run_id, source, started_at, ended_at, outcome, documents_received, rejected, "
            + "advisories_stored, advisories_needing_review, documents_fetched, documents_failed, reason";

    private final TransactionBoundary transactions;

    public JooqAdvisoryRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public void upsertAdvisories(List<StoredAdvisory> advisories) {
        if (advisories == null || advisories.isEmpty()) return;
        transactions.inTransaction(dsl -> {
            for (StoredAdvisory a : advisories) {
                dsl.execute("insert into advisory(advisory_id, source, title, severity, cvss_score, cve_ids, published_on, "
                        + "updated_on, source_url, parse_state, parse_note, content_sha256, loaded_at) "
                        + "values ({0}, {1}, {2}, {3}, {4}, {5}::text[], {6}, {7}, {8}, {9}, {10}, {11}, now()) "
                        + "on conflict (advisory_id) do update set source = excluded.source, title = excluded.title, "
                        + "severity = excluded.severity, cvss_score = excluded.cvss_score, cve_ids = excluded.cve_ids, "
                        + "published_on = excluded.published_on, updated_on = excluded.updated_on, "
                        + "source_url = excluded.source_url, parse_state = excluded.parse_state, "
                        + "parse_note = excluded.parse_note, content_sha256 = excluded.content_sha256, loaded_at = now()",
                        a.advisoryId(), a.source(), a.title(), a.severity(), a.cvssScore(), a.cveIds().toArray(new String[0]),
                        date(a.publishedOn()), date(a.updatedOn()), a.sourceUrl(), a.parseState(), a.parseNote(),
                        a.contentSha256());
                dsl.execute("delete from advisory_product_rule where advisory_id = {0}", a.advisoryId());
                for (StoredRule r : a.rules()) {
                    dsl.execute("insert into advisory_product_rule(advisory_id, product_key, train, rule, fixed_text) "
                            + "values ({0}, {1}, {2}, {3}, {4}) on conflict (advisory_id, product_key, train) do update set "
                            + "rule = excluded.rule, fixed_text = excluded.fixed_text",
                            a.advisoryId(), r.productKey(), r.train(), r.ruleJson(), r.fixedText());
                }
            }
            return null;
        });
    }

    @Override
    public void replaceKnownExploited(List<StoredKnownExploited> entries) {
        List<StoredKnownExploited> rows = entries == null ? List.of() : entries;
        transactions.inTransaction(dsl -> {
            dsl.execute("delete from known_exploited_cve");
            for (StoredKnownExploited e : rows) {
                dsl.execute("insert into known_exploited_cve(cve_id, date_added, ransomware_use, loaded_at) "
                        + "values ({0}, {1}, {2}, now()) on conflict (cve_id) do update set date_added = excluded.date_added, "
                        + "ransomware_use = excluded.ransomware_use, loaded_at = now()",
                        e.cveId(), date(e.dateAdded()), e.ransomwareUse());
            }
            return null;
        });
    }

    @Override
    public void openRun(String runId, String source) {
        transactions.inTransaction(dsl -> dsl.execute("insert into advisory_feed_run(run_id, source, started_at) "
                + "values ({0}, {1}, now()) on conflict (run_id, source) do nothing", runId, source));
    }

    @Override
    public void addToRun(String runId, String source, int documentsReceived, int rejected, int advisoriesStored,
            int advisoriesNeedingReview) {
        transactions.inTransaction(dsl -> dsl.execute("update advisory_feed_run set documents_received = documents_received + {2}, "
                + "rejected = rejected + {3}, advisories_stored = advisories_stored + {4}, "
                + "advisories_needing_review = advisories_needing_review + {5} where run_id = {0} and source = {1}",
                runId, source, documentsReceived, rejected, advisoriesStored, advisoriesNeedingReview));
    }

    @Override
    public void closeRun(String runId, String source, String outcome, Integer documentsFetched, Integer documentsFailed,
            String reason) {
        transactions.inTransaction(dsl -> {
            dsl.execute("insert into advisory_feed_run(run_id, source, started_at) values ({0}, {1}, now()) "
                    + "on conflict (run_id, source) do nothing", runId, source);
            return dsl.execute("update advisory_feed_run set ended_at = now(), outcome = {2}, documents_fetched = {3}, "
                    + "documents_failed = {4}, reason = {5} where run_id = {0} and source = {1}",
                    runId, source, outcome, documentsFetched, documentsFailed, reason);
        });
    }

    @Override
    public Optional<FeedRun> run(String runId, String source) {
        return transactions.inTransaction(dsl -> dsl.fetch("select " + RUN_COLUMNS
                + " from advisory_feed_run where run_id = {0} and source = {1}", runId, source).stream()
                .map(JooqAdvisoryRepository::run).findFirst());
    }

    @Override
    public List<StoredAdvisory> advisories(Optional<String> source) {
        return transactions.inTransaction(dsl -> {
            Map<String, List<StoredRule>> rules = new LinkedHashMap<>();
            var ruleRows = source.isPresent()
                    ? dsl.fetch("select r.advisory_id, r.product_key, r.train, r.rule, r.fixed_text from advisory_product_rule r "
                            + "join advisory a on a.advisory_id = r.advisory_id where a.source = {0} "
                            + "order by r.advisory_id, r.product_key, r.train", source.get())
                    : dsl.fetch("select advisory_id, product_key, train, rule, fixed_text from advisory_product_rule "
                            + "order by advisory_id, product_key, train");
            for (Record row : ruleRows) {
                rules.computeIfAbsent(row.get("advisory_id", String.class), k -> new ArrayList<>()).add(new StoredRule(
                        row.get("product_key", String.class), row.get("train", String.class), row.get("rule", String.class),
                        row.get("fixed_text", String.class)));
            }
            String columns = "advisory_id, source, title, severity, cvss_score, cve_ids, published_on, updated_on, source_url, "
                    + "parse_state, parse_note, content_sha256, loaded_at";
            var rows = source.isPresent()
                    ? dsl.fetch("select " + columns + " from advisory where source = {0} order by advisory_id", source.get())
                    : dsl.fetch("select " + columns + " from advisory order by advisory_id");
            List<StoredAdvisory> result = new ArrayList<>();
            for (Record row : rows) {
                String id = row.get("advisory_id", String.class);
                String[] cves = row.get("cve_ids", String[].class);
                OffsetDateTime loaded = row.get("loaded_at", OffsetDateTime.class);
                result.add(new StoredAdvisory(id, row.get("source", String.class), row.get("title", String.class),
                        row.get("severity", String.class), row.get("cvss_score", java.math.BigDecimal.class),
                        cves == null ? List.of() : List.of(cves), local(row.get("published_on", Date.class)),
                        local(row.get("updated_on", Date.class)), row.get("source_url", String.class),
                        row.get("parse_state", String.class), row.get("parse_note", String.class),
                        row.get("content_sha256", String.class), loaded == null ? null : loaded.toInstant(),
                        rules.getOrDefault(id, List.of())));
            }
            return result;
        });
    }

    @Override
    public List<StoredKnownExploited> knownExploited() {
        return transactions.inTransaction(dsl -> dsl.fetch("select cve_id, date_added, ransomware_use from known_exploited_cve "
                + "order by cve_id").stream().map(row -> new StoredKnownExploited(row.get("cve_id", String.class),
                local(row.get("date_added", Date.class)), Boolean.TRUE.equals(row.get("ransomware_use", Boolean.class)))).toList());
    }

    @Override
    public List<FeedRun> latestRuns() {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct on (source) " + RUN_COLUMNS
                + " from advisory_feed_run order by source, started_at desc, run_id desc").stream()
                .map(JooqAdvisoryRepository::run).toList());
    }

    @Override
    public List<FeedRun> lastGoodRuns() {
        return transactions.inTransaction(dsl -> dsl.fetch("select distinct on (source) " + RUN_COLUMNS
                + " from advisory_feed_run where outcome in ('OK', 'PARTIAL') order by source, ended_at desc, run_id desc").stream()
                .map(JooqAdvisoryRepository::run).toList());
    }

    @Override
    public List<SourceSummary> sourceSummaries() {
        return transactions.inTransaction(dsl -> dsl.fetch("select source, count(*) as advisories, "
                + "count(*) filter (where parse_state = 'NEEDS_REVIEW') as needs_review, min(published_on) as oldest, "
                + "max(published_on) as newest from advisory group by source order by source").stream()
                .map(row -> new SourceSummary(row.get("source", String.class), number(row.get("advisories")),
                        number(row.get("needs_review")), local(row.get("oldest", Date.class)),
                        local(row.get("newest", Date.class)))).toList());
    }

    private static FeedRun run(Record row) {
        OffsetDateTime started = row.get("started_at", OffsetDateTime.class);
        OffsetDateTime ended = row.get("ended_at", OffsetDateTime.class);
        return new FeedRun(row.get("run_id", String.class), row.get("source", String.class),
                started == null ? null : started.toInstant(), ended == null ? null : ended.toInstant(),
                row.get("outcome", String.class), number(row.get("documents_received")), number(row.get("rejected")),
                number(row.get("advisories_stored")), number(row.get("advisories_needing_review")),
                row.get("documents_fetched", Integer.class), row.get("documents_failed", Integer.class),
                row.get("reason", String.class));
    }

    private static int number(Object value) { return value instanceof Number n ? n.intValue() : 0; }

    private static Date date(LocalDate value) { return value == null ? null : Date.valueOf(value); }

    private static LocalDate local(Date value) { return value == null ? null : value.toLocalDate(); }
}
