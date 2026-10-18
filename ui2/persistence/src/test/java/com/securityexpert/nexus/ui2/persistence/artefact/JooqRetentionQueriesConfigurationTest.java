package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * The configuration-class retention reads (V164, PO decision 2026-10-18), shape only: the SQL targets the
 * configuration class, takes the days as a bound parameter, and carries the one shared liveness predicate. The
 * cutoff and the latest-archive rule against real rows are proved in {@code ConfigurationArchiveRetentionDatabaseTest}
 * (integration-tests, needs PostgreSQL).
 */
class JooqRetentionQueriesConfigurationTest {

    private final List<String> sql = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();

    private JooqRetentionQueries queries() {
        var dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            bindings.add(List.of(ctx.bindings()));
            return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())};
        }), SQLDialect.POSTGRES);
        return new JooqRetentionQueries(new JooqTransactionBoundary(dsl));
    }

    @Test
    void expiredConfigurationArchivesFilterByClassCreatedAtAndTheSharedLivenessPredicate() {
        queries().expiredConfigurationArchives(30);

        String statement = sql.get(0);
        assertTrue(statement.contains("a.artefact_class = 'configuration'"));
        assertTrue(statement.contains("a.created_at < now() - ("));
        assertTrue(statement.contains(JooqRetentionQueries.andNotRemoved("a")), "one shared liveness predicate");
        assertEquals(1, statement.split(java.util.regex.Pattern.quote("artefact_retention_ledger"), -1).length - 1,
                "no double filtering");
        assertTrue(statement.contains("backup_baseline"), "a device's baseline is never offered");
        assertEquals(List.of("30"), bindings.get(0));
    }

    @Test
    void theLatestArchiveCheckComparesAgainstNewerLiveArchivesOfTheSameDeviceAndClass() {
        // The mocked empty result means "row unknown": the answer errs towards keeping.
        assertTrue(queries().isLatestLiveConfigurationArchive("unknown-artefact"));

        String statement = sql.get(0);
        assertTrue(statement.contains("n.device_id = a.device_id"));
        assertTrue(statement.contains("n.artefact_class = 'configuration'"));
        assertTrue(statement.contains("(n.created_at, n.artefact_id) > (a.created_at, a.artefact_id)"));
        assertTrue(statement.contains(JooqRetentionQueries.andNotRemoved("n")));
    }

    @Test
    void theSnapshotReadUsesTheSharedLivenessPredicateToo() {
        queries().snapshotsBeyondDepth(4);

        assertTrue(sql.get(0).contains(JooqRetentionQueries.andNotRemoved("x")));
    }
}
