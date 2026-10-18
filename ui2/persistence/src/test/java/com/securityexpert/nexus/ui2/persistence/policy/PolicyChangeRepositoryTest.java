package com.securityexpert.nexus.ui2.persistence.policy;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import java.time.Instant;
import java.util.*;

class PolicyChangeRepositoryTest {
    @Test void optionalFiltersAreBoundIndexedPredicatesAndPagingNeverUsesOffset() {
        var sql = new ArrayList<String>(); var bindings = new ArrayList<Object>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            return new MockResult[] { new MockResult(0, create.fetchFromStringData(new String[] { "revision_id" })) };
        }), SQLDialect.POSTGRES)));
        repository.rows(new PolicyChangeRepository.Filter(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"),
            "policy-001", "device-001", "modified", "source"), 100, new PolicyChangeRepository.Position(Instant.parse("2026-10-08T00:00:00Z"), 80));
        String query = sql.get(0);
        assertTrue(query.contains("h.policy_id = ?")); assertTrue(query.contains("h.change_type = ?"));
        assertTrue(query.contains("h.changes @>")); assertTrue(query.contains("s.metadata->'targets' @>"));
        assertTrue(query.contains("h.revision_id) < (?::timestamptz, ?)")); assertFalse(query.toLowerCase().contains("offset"));
        assertTrue(query.contains("limit 500"));
        assertEquals(List.of("2026-10-01T00:00:00Z", "2026-10-09T00:00:00Z", 100L, "policy-001", "device-001", "modified", "source", "2026-10-08T00:00:00Z", 80L), bindings);
        bindings.clear(); sql.clear();
        repository.rows(new PolicyChangeRepository.Filter(Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"), "", "", "", ""), 100, null);
        assertFalse(sql.get(0).contains("h.change_type = ?")); assertFalse(sql.get(0).contains("h.policy_id = ?"));
        assertEquals(3, bindings.size());
        // Including initial captures adds no exclusion to the page selection (the marker is only read for the page's own rows).
        assertFalse(sql.get(0).substring(0, sql.get(0).indexOf("cand as")).contains("initial_capture"));
        assertTrue(sql.get(0).contains("(h.initial_capture or fr.revision_id is not null) as initial_capture"));
    }
    @Test void initialCapturesAreExcludedFromRowsAndCountsButStillReportedAsHidden() {
        var sql = new ArrayList<String>(); var bindings = new ArrayList<Object>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            if (context.sql().contains("initial_captures")) {
                return new MockResult[] { new MockResult(1, create.fetchFromStringData(
                    new String[] { "total", "actors", "rules", "added", "modified", "removed", "initial_captures" },
                    new String[] { "4", "1", "3", "1", "3", "0", "3500" })) };
            }
            return new MockResult[] { new MockResult(0, create.fetchFromStringData(new String[] { "revision_id" })) };
        }), SQLDialect.POSTGRES)));
        var from = Instant.parse("2026-10-01T00:00:00Z"); var to = Instant.parse("2026-10-09T00:00:00Z");
        repository.rows(new PolicyChangeRepository.Filter(from, to, "", "", "", "", false), 100, null);
        // Initial captures are cut by the stored marker (the partial index predicate) before any per-row work.
        assertTrue(sql.get(0).contains(" and not h.initial_capture), "));
        assertTrue(sql.get(0).contains("false as initial_capture"));
        assertTrue(sql.get(0).contains("as collected_at"));
        assertEquals(3, bindings.size()); // The marker is a constant predicate, never a bound user value.
        sql.clear(); bindings.clear();
        var summary = repository.summary(new PolicyChangeRepository.Filter(from, to, "", "", "", "", false), 100);
        // Counts come from real changes only, while the hidden initial captures are counted by one plain count of the marked rows.
        assertTrue(sql.get(0).contains("count(*) filter (where fr.revision_id is null)::bigint as total"));
        assertTrue(sql.get(0).contains("and h.initial_capture))::bigint as initial_captures"));
        assertEquals(6, bindings.size()); // The same predicates run for the real changes and for the initial-capture count.
        assertEquals(4L, summary.get("total")); assertEquals(3500L, summary.get("initialCaptures"));
        assertEquals(false, summary.get("initialCapturesIncluded"));
        sql.clear();
        repository.summary(new PolicyChangeRepository.Filter(from, to, "", "", "", "", true), 100);
        assertTrue(sql.get(0).contains("count(*)::bigint as total"));
        assertTrue(sql.get(0).contains("count(*) filter (where h.initial_capture or fr.revision_id is not null)::bigint as initial_captures"));
    }
    @Test void v149MarkerAndRenumberFilterAreIdempotentTransactionalAndKeepHistory() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
            "../service/src/main/resources/db/migration/V150__policy_history_initial_capture_and_renumbering.sql"));
        assertTrue(sql.contains("ALTER TABLE policy_rule_history ADD COLUMN IF NOT EXISTS initial_capture BOOLEAN NOT NULL DEFAULT false"));
        assertTrue(sql.contains("CREATE INDEX IF NOT EXISTS policy_changes_time_excluding_initial"));
        assertTrue(sql.contains("AND NOT h.initial_capture;"));
        assertTrue(sql.contains("CREATE OR REPLACE FUNCTION fn_capture_policy_rule_history()"));
        assertTrue(sql.contains("first_capture := NOT FOUND;"));
        assertTrue(sql.contains("IF item.order_kept AND (before_fields - 'number') IS NOT DISTINCT FROM (after_fields - 'number') THEN CONTINUE; END IF;"));
        // Bounded-memory settings from V132 stay on the trigger function.
        assertTrue(sql.contains("SET work_mem = '4MB'")); assertTrue(sql.contains("SET hash_mem_multiplier = 1"));
        String upper = sql.replaceAll("(?m)--.*$", "").toUpperCase(java.util.Locale.ROOT);
        assertFalse(upper.contains("DELETE FROM")); assertFalse(upper.contains("TRUNCATE"));
        assertFalse(upper.contains("CONCURRENTLY")); assertFalse(upper.contains("COMMIT;"));
        assertFalse(upper.contains("DROP "));
        var versions = new java.util.TreeSet<Integer>();
        try (var files = java.nio.file.Files.list(java.nio.file.Path.of("../service/src/main/resources/db/migration"))) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V[0-9]+__.*\\.sql"))
                .forEach(n -> assertTrue(versions.add(Integer.valueOf(n.substring(1, n.indexOf("__")))), n));
        }
        assertTrue(versions.contains(149));
    }

    // First appearance of a Palo Alto NAT/default section kind (SQL shape; semantics run in PolicyChangeReportSchemaTest).
    private static final Instant FROM = Instant.parse("2026-10-08T00:00:00Z"), TO = Instant.parse("2026-10-09T00:00:00Z");

    private List<String> run(java.util.function.Consumer<PolicyChangeRepository> action) {
        var statements = new ArrayList<String>();
        var db = DSL.using(new MockConnection(context -> {
            statements.add(context.sql());
            return new MockResult[] { new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult()) };
        }), SQLDialect.POSTGRES);
        try { action.accept(new PolicyChangeRepository(new JooqTransactionBoundary(db))); } catch (RuntimeException expectedForEmptyMock) { /* only the SQL matters */ }
        return statements;
    }

    @Test void defaultReportExcludesFirstAppearanceOfPanNatAndDefaultSectionsAndKeepsTheIndexablePredicate() {
        var filter = new PolicyChangeRepository.Filter(FROM, TO, "", "", "", "", false);
        for (String sql : run(repository -> { repository.rows(filter, 1, null); repository.summary(filter, 1); })) {
            if (!sql.contains("policy_rule_history")) continue;
            assertTrue(sql.contains("and not h.initial_capture)"), sql); // the partial-index predicate stays a separate conjunct
            for (String name : List.of("Pre NAT rules", "Post NAT rules", "Pre default rules", "Post default rules"))
                assertTrue(sql.contains("'" + name + "'"), name);
            assertTrue(sql.contains("s.metadata->>'vendor' = 'PAN'"), "Check Point section names are never classified");
            assertTrue(sql.contains("e.collected_at < t.collected_at"), "only an earlier batch makes a later addition a change");
        }
    }

    @Test void firstAppearanceIsProbedOncePerPolicyBatchAndKindAndOnlyForCandidateRows() {
        var filter = new PolicyChangeRepository.Filter(FROM, TO, "", "", "", "", false);
        for (String sql : run(repository -> { repository.rows(filter, 1, null); repository.summary(filter, 1); })) {
            if (!sql.contains("policy_rule_history")) continue;
            // The jsonb section kind is extracted only for 'added' revisions of Palo Alto policies; every other row gets null.
            assertTrue(sql.contains("case when h.change_type = 'added' and s.metadata->>'vendor' = 'PAN' then (select c->>'after'"), sql);
            // One existence probe per distinct (policy, batch, kind), not one per row.
            assertTrue(sql.contains("(select distinct policy_id, collected_at, kind from cand where kind in ("), sql);
            assertEquals(1, sql.split("select 1 as earlier from policy_rule_history e", -1).length - 1, sql);
        }
    }

    @Test void labelsAndSnapshotExpansionAreComputedOnlyForThePageRowsAndOncePerPolicy() {
        var filter = new PolicyChangeRepository.Filter(FROM, TO, "", "", "", "", false);
        String sql = String.join("\n", run(repository -> repository.rows(filter, 1, null)));
        int limit = sql.indexOf("limit 500"), labels = sql.indexOf("lab as materialized");
        assertTrue(limit > 0 && labels > limit, "the page is limited before any label lookup");
        assertEquals(1, sql.split("limit 500", -1).length - 1);
        // The snapshot is expanded in one CTE, for the distinct policies of the page rows that still lack a label, and
        // only the wanted rule keys are kept; it is never expanded per row.
        assertEquals(1, sql.split("fn_policy_history_rules", -1).length - 1, sql);
        assertTrue(sql.contains("from (select distinct policy_id from missing) m join policy_snapshot ps"), sql);
        assertTrue(sql.contains("where name is null or number is null"), sql);
        assertTrue(sql.contains("where x.rule_key = any(k.keys)"), "only the wanted rule keys are kept");
        assertTrue(sql.contains("order by m.policy_id, x.rule_key, x.ordinal"), "the first rule in expansion order wins");
        assertFalse(sql.contains("lateral (select rule from fn_policy_history_rules"));
    }

    @Test void theInitialFlagOfEveryRowAndTheInitialCountUseTheSameDefinition() {
        var filter = new PolicyChangeRepository.Filter(FROM, TO, "", "", "", "", true);
        var sql = String.join("\n", run(repository -> { repository.rows(filter, 1, null); repository.summary(filter, 1); }));
        assertTrue(sql.contains("(h.initial_capture or fr.revision_id is not null) as initial_capture"));
        assertTrue(sql.contains("filter (where h.initial_capture or fr.revision_id is not null)::bigint as initial_captures"));
        assertEquals(2, sql.split("first_rev as materialized", -1).length - 1, "the same first-appearance definition serves rows and summary");
    }
}
