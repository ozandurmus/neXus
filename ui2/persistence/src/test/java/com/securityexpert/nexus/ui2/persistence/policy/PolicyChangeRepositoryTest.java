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
        assertFalse(sql.get(0).contains("not h.initial_capture"));
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
        assertTrue(sql.get(0).contains("not h.initial_capture"));
        assertTrue(sql.get(0).contains("h.initial_capture"));
        assertTrue(sql.get(0).contains("as collected_at"));
        assertEquals(3, bindings.size()); // The marker is a constant predicate, never a bound user value.
        sql.clear(); bindings.clear();
        var summary = repository.summary(new PolicyChangeRepository.Filter(from, to, "", "", "", "", false), 100);
        // Counts come from real changes only, while the hidden initial captures are counted in the same scan.
        assertTrue(sql.get(0).contains("filter (where not h.initial_capture)"));
        assertTrue(sql.get(0).contains("count(*) filter (where h.initial_capture)"));
        assertFalse(sql.get(0).contains("and not h.initial_capture"));
        assertEquals(4L, summary.get("total")); assertEquals(3500L, summary.get("initialCaptures"));
        assertEquals(false, summary.get("initialCapturesIncluded"));
        sql.clear();
        repository.summary(new PolicyChangeRepository.Filter(from, to, "", "", "", "", true), 100);
        assertFalse(sql.get(0).contains("not h.initial_capture"));
        assertTrue(sql.get(0).contains("filter (where true)"));
    }
    @Test void v149MarkerAndRenumberFilterAreIdempotentTransactionalAndKeepHistory() throws Exception {
        String sql = java.nio.file.Files.readString(java.nio.file.Path.of(
            "../service/src/main/resources/db/migration/V149__policy_history_initial_capture_and_renumbering.sql"));
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
}
