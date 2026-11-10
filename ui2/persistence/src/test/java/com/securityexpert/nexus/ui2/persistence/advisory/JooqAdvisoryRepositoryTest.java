package com.securityexpert.nexus.ui2.persistence.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredAdvisory;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredKnownExploited;
import com.securityexpert.nexus.ui2.persistence.advisory.AdvisoryRepository.StoredRule;

/**
 * Statement-level tests in the style of the lifecycle repository tests: the statements and their bindings are captured
 * with a mock connection (no PostgreSQL is available here); the V204 CHECK constraints are exercised on a real database
 * only, by the orchestrator's dry run.
 */
class JooqAdvisoryRepositoryTest {

    private record Statement(String sql, List<Object> bindings) { }

    private static JooqAdvisoryRepository repository(List<Statement> statements, Function<String, MockResult[]> answer) {
        return new JooqAdvisoryRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            statements.add(new Statement(ctx.sql(), new ArrayList<>(Arrays.asList(ctx.bindings()))));
            return answer.apply(ctx.sql());
        }), SQLDialect.POSTGRES)));
    }

    private static JooqAdvisoryRepository repository(List<Statement> statements) {
        return repository(statements, sql -> new MockResult[] {new MockResult(1, null)});
    }

    private static StoredAdvisory advisory(String id, StoredRule... rules) {
        return new StoredAdvisory(id, "paloalto", "Title of " + id, "HIGH", new BigDecimal("8.8"), List.of("CVE-2026-0001"),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 6, 1), "https://security.example.test/" + id, "PARSED", null,
                "0".repeat(64), null, List.of(rules));
    }

    @Test
    void anAdvisoryIsUpsertedAndItsOldRulesReplaced() {
        List<Statement> all = new ArrayList<>();
        repository(all).upsertAdvisories(List.of(advisory("paloalto:CVE-2026-0001",
                new StoredRule("paloalto:pan-os", "11.1", "[{\"k\":1}]", "Upgrade to 11.1.4"),
                new StoredRule("paloalto:pan-os", "10.2", "[]", null))));
        List<Statement> sql = all.stream().filter(s -> s.sql().contains("advisory")).toList();
        assertEquals(4, sql.size(), sql.toString());
        assertTrue(sql.get(0).sql().startsWith("insert into advisory("));
        assertTrue(sql.get(0).sql().contains("on conflict (advisory_id) do update"));
        assertEquals("paloalto:CVE-2026-0001", sql.get(0).bindings().get(0));
        assertTrue(sql.get(1).sql().startsWith("delete from advisory_product_rule where advisory_id"));
        assertEquals(List.of("paloalto:CVE-2026-0001"), sql.get(1).bindings());
        assertTrue(sql.get(2).sql().startsWith("insert into advisory_product_rule"));
        assertTrue(sql.get(2).sql().contains("on conflict (advisory_id, product_key, train)"));
        assertTrue(sql.get(2).bindings().contains("Upgrade to 11.1.4"));
        assertTrue(sql.get(3).bindings().contains("10.2"));
    }

    @Test
    void anEmptyBatchRunsNothing() {
        List<Statement> all = new ArrayList<>();
        repository(all).upsertAdvisories(List.of());
        repository(all).upsertAdvisories(null);
        assertTrue(all.isEmpty());
    }

    @Test
    void theKnownExploitedSetIsReplacedAsAWhole() {
        List<Statement> all = new ArrayList<>();
        repository(all).replaceKnownExploited(List.of(new StoredKnownExploited("CVE-2026-0001", LocalDate.of(2026, 1, 2), true),
                new StoredKnownExploited("CVE-2026-0002", LocalDate.of(2026, 1, 3), false)));
        List<Statement> sql = all.stream().filter(s -> s.sql().contains("known_exploited_cve")).toList();
        assertEquals(3, sql.size());
        assertEquals("delete from known_exploited_cve", sql.get(0).sql());
        assertTrue(sql.get(1).sql().startsWith("insert into known_exploited_cve"));
        assertEquals("CVE-2026-0002", sql.get(2).bindings().get(0));
    }

    @Test
    void runsAreOpenedWithoutOverwritingCountedAndClosed() {
        List<Statement> all = new ArrayList<>();
        var repo = repository(all);
        repo.openRun("run-00000001", "fortinet");
        repo.addToRun("run-00000001", "fortinet", 1, 2, 3, 4);
        repo.closeRun("run-00000001", "fortinet", "PARTIAL", 5, 1, "FETCH_TIMEOUT");
        List<Statement> sql = all.stream().filter(s -> s.sql().contains("advisory_feed_run")).toList();
        assertEquals(4, sql.size());
        assertTrue(sql.get(0).sql().contains("on conflict (run_id, source) do nothing"));
        assertTrue(sql.get(1).sql().contains("documents_received = documents_received +"));
        assertEquals(List.of(1, 2, 3, 4, "run-00000001", "fortinet"), sql.get(1).bindings(), "placeholders bind in order of appearance");
        assertTrue(sql.get(2).sql().contains("on conflict (run_id, source) do nothing"), "closing creates a run that received nothing");
        assertTrue(sql.get(3).sql().contains("ended_at = now()"));
        assertEquals(List.of("PARTIAL", 5, 1, "FETCH_TIMEOUT", "run-00000001", "fortinet"), sql.get(3).bindings());
    }

    @Test
    void advisoriesAreReadWithTheirRulesGroupedPerAdvisory() {
        var ctx = DSL.using(SQLDialect.POSTGRES);
        Function<String, MockResult[]> answer = sql -> {
            if (sql.contains("from advisory_product_rule")) {
                Result<Record> r = result(ctx, DSL.field("advisory_id", String.class), DSL.field("product_key", String.class),
                        DSL.field("train", String.class), DSL.field("rule", String.class), DSL.field("fixed_text", String.class));
                r.add(row(ctx, r, "paloalto:CVE-2026-0001", "paloalto:pan-os", "11.1", "[1]", "Fix 11.1.4"));
                r.add(row(ctx, r, "paloalto:CVE-2026-0001", "paloalto:pan-os", "10.2", "[2]", null));
                return new MockResult[] {new MockResult(2, r)};
            }
            Result<Record> r = result(ctx, DSL.field("advisory_id", String.class), DSL.field("source", String.class),
                    DSL.field("title", String.class), DSL.field("severity", String.class),
                    DSL.field("cvss_score", BigDecimal.class), DSL.field("cve_ids", SQLDataType.VARCHAR.getArrayDataType()),
                    DSL.field("published_on", Date.class), DSL.field("updated_on", Date.class),
                    DSL.field("source_url", String.class), DSL.field("parse_state", String.class),
                    DSL.field("parse_note", String.class), DSL.field("content_sha256", String.class),
                    DSL.field("loaded_at", OffsetDateTime.class));
            Record a = ctx.newRecord(r.fields());
            a.fromArray("paloalto:CVE-2026-0001", "paloalto", "A title", "CRITICAL", new BigDecimal("9.8"),
                    new String[] {"CVE-2026-0001"}, Date.valueOf("2026-05-01"), null, "https://security.example.test/a",
                    "NEEDS_REVIEW", "SELF_CHECK_FAILED", "1".repeat(64), OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC));
            r.add(a);
            return new MockResult[] {new MockResult(1, r)};
        };
        List<Statement> all = new ArrayList<>();
        List<StoredAdvisory> read = repository(all, answer).advisories(Optional.of("paloalto"));
        assertEquals(1, read.size());
        StoredAdvisory a = read.get(0);
        assertEquals("NEEDS_REVIEW", a.parseState());
        assertEquals("SELF_CHECK_FAILED", a.parseNote());
        assertEquals(List.of("CVE-2026-0001"), a.cveIds());
        assertEquals(LocalDate.of(2026, 5, 1), a.publishedOn());
        assertEquals(2, a.rules().size());
        assertEquals("[1]", a.rules().get(0).ruleJson());
        assertEquals("Fix 11.1.4", a.rules().get(0).fixedText());
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), a.loadedAt());
        assertTrue(all.stream().allMatch(s -> s.bindings().equals(List.of("paloalto"))), "both reads filter by the source");
        List<Statement> unfiltered = new ArrayList<>();
        repository(unfiltered, answer).advisories(Optional.empty());
        assertFalse(unfiltered.stream().anyMatch(s -> s.sql().contains("where")), unfiltered.toString());
    }

    @Test
    void sourceSummariesAndRunsAreReadByTheirOwnQueries() {
        var ctx = DSL.using(SQLDialect.POSTGRES);
        Function<String, MockResult[]> answer = sql -> {
            Result<Record> r = result(ctx, DSL.field("source", String.class), DSL.field("advisories", Long.class),
                    DSL.field("needs_review", Long.class), DSL.field("oldest", Date.class), DSL.field("newest", Date.class));
            Record row = ctx.newRecord(r.fields());
            row.fromArray("cisco", 7L, 2L, Date.valueOf("2024-01-01"), Date.valueOf("2026-10-01"));
            r.add(row);
            return new MockResult[] {new MockResult(1, r)};
        };
        var summaries = repository(new ArrayList<>(), answer).sourceSummaries();
        assertEquals(1, summaries.size());
        assertEquals(7, summaries.get(0).advisories());
        assertEquals(2, summaries.get(0).needsReview());
        assertEquals(LocalDate.of(2024, 1, 1), summaries.get(0).oldest());
        List<Statement> all = new ArrayList<>();
        Function<String, MockResult[]> none = sql -> new MockResult[] {new MockResult(0, result(ctx, DSL.field("source", String.class)))};
        repository(all, none).latestRuns();
        repository(all, none).lastGoodRuns();
        assertTrue(all.get(0).sql().contains("distinct on (source)"));
        assertTrue(all.get(1).sql().contains("outcome in ('OK', 'PARTIAL')"));
    }

    private static Result<Record> result(org.jooq.DSLContext ctx, org.jooq.Field<?>... fields) {
        return ctx.newResult(fields);
    }

    private static Record row(org.jooq.DSLContext ctx, Result<Record> r, Object... values) {
        Record record = ctx.newRecord(r.fields());
        record.fromArray(values);
        return record;
    }
}
