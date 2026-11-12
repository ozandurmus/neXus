package com.securityexpert.nexus.ui2.persistence.policy;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.*;
import org.junit.jupiter.api.Test;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * 2026-11-13: a running CP collection's checkpoints overwrote the live snapshot, so a 9,407-rule policy read as 6,262
 * rules for the length of every collection. A snapshot with missing rules must never replace one that has every rule.
 * The SQL semantics were also run against PostgreSQL on a temporary table (see the PR notes); these tests pin the
 * statement shape and the result mapping.
 */
class PolicySnapshotIntegrityGuardTest {
    private static PolicyCollectionRepository repository(List<String> sql, List<Object> bindings, int rows, Result<?> result) {
        return new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql()); bindings.addAll(Arrays.asList(context.bindings()));
            return new MockResult[] { new MockResult(rows, result) };
        }), SQLDialect.POSTGRES)));
    }

    @Test void upsertOnlyReplacesWithAnEqualOrMoreCompleteSnapshot() {
        List<String> sql = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = new PolicyCollectionRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            return new MockResult[] { new MockResult(1, context.sql().startsWith("select job_id")
                ? create.fetchFromStringData(new String[] { "job_id" }, new String[] { "job-1" }) : null) };
        }), SQLDialect.POSTGRES)));
        assertTrue(repository.checkpoint("job-1", 7,
            new PolicySnapshotRepository.Stored("policy-1", "2026-11-13T00:00:00Z", "{}", "{}"), "synthetic-actor"));
        String upsert = sql.stream().filter(q -> q.startsWith("insert into policy_snapshot")).findFirst().orElseThrow();
        assertTrue(upsert.contains("where policy_snapshot.collected_at < excluded.collected_at and (not exists"), upsert);
        assertTrue(upsert.contains("excluded.snapshot->'failures'"), upsert);
        assertTrue(upsert.contains("policy_snapshot.snapshot->'failures'"), upsert);
        // The advisory reasons and the explicit UNKNOWN NAT part keep a snapshot rule-complete, in step with PolicySnapshot.
        for (String reason : com.securityexpert.nexus.ui2.policy.PolicySnapshot.ADVISORY_REASONS) assertTrue(upsert.contains("'" + reason + "'"), reason);
        assertTrue(upsert.contains("'" + com.securityexpert.nexus.ui2.policy.PolicySnapshot.VENDOR_NAME_NOT_UNIQUE + "'"));
        assertTrue(upsert.contains("f->>'layerName' = 'NAT'"));
    }

    @Test void writtenIsFalseWhenTheGuardKeepsTheStoredSnapshot() {
        var create = DSL.using(SQLDialect.POSTGRES);
        var snapshots = new PolicySnapshotRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context ->
            new MockResult[] { new MockResult(0, create.fetchFromStringData(new String[] { "x" })) }), SQLDialect.POSTGRES)));
        assertFalse(snapshots.save(new PolicySnapshotRepository.Stored("policy-1", "2026-11-13T00:00:00Z", "{}", "{}"),
            "synthetic-actor", "policy_collect_checkpoint"));
    }

    @Test void ruleFactsCountsRulesOverSectionsAndReadsOnlyTheFailuresForCompleteness() {
        List<String> sql = new ArrayList<>(); List<Object> bindings = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = repository(sql, bindings, 1, create.fetchFromStringData(new String[] { "rules", "complete" }, new String[] { "9407", "true" }));
        var facts = repository.ruleFacts("policy-1").orElseThrow();
        assertEquals(9407, facts.rules());
        assertTrue(facts.ruleComplete());
        assertTrue(sql.get(0).contains("jsonb_array_length(coalesce(s->'rules'"), sql.get(0));
        assertTrue(sql.get(0).contains("not exists (select 1 from jsonb_array_elements(coalesce(snapshot->'failures'"), sql.get(0));
        assertEquals(List.of("policy-1"), bindings);
        assertTrue(repository(new ArrayList<>(), new ArrayList<>(), 0, create.fetchFromStringData(new String[] { "rules", "complete" }))
            .ruleFacts("policy-2").isEmpty());
    }

    @Test void revisionStateIsThisJobsRowForTheContainer() {
        List<String> sql = new ArrayList<>(); List<Object> bindings = new ArrayList<>();
        var create = DSL.using(SQLDialect.POSTGRES);
        var repository = repository(sql, bindings, 1, create.fetchFromStringData(new String[] { "change_state" }, new String[] { "UNCHANGED" }));
        assertEquals(Optional.of("UNCHANGED"), repository.revisionState("job-1", "container-ref"));
        assertTrue(sql.get(0).contains("from policy_revision where job_id = ? and container_ref = ?"), sql.get(0));
        assertEquals(List.of("job-1", "container-ref"), bindings);
    }
}
