package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * V180 shape tests over a mocked connection: audited transactions, the per-device lock, the history predicate and
 * the retention pin. The behaviour over real rows is {@code BackupSizeAnomalyRetentionDatabaseTest} (integration-tests,
 * needs PostgreSQL).
 */
class JooqBackupSizeAnomalyRepositoryTest {

    private final List<String> sql = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();

    private TransactionBoundaryHolder boundary() {
        var dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            bindings.add(List.of(ctx.bindings()));
            return new MockResult[] {new MockResult(0, DSL.using(SQLDialect.POSTGRES).newResult())};
        }), SQLDialect.POSTGRES);
        return new TransactionBoundaryHolder(new JooqTransactionBoundary(dsl));
    }

    private record TransactionBoundaryHolder(JooqTransactionBoundary boundary) {
    }

    @Test
    void anUnknownOrNonStandardArtefactIsNotCheckedAndNothingIsWritten() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary().boundary());
        assertTrue(repository.inspect("art-00000001", "system:worker").isEmpty());
        assertTrue(sql.stream().anyMatch(s -> s.contains("set local app.actor_fingerprint")
                || s.contains("SET LOCAL app.actor_fingerprint")), "audited: " + sql);
        String read = sql.stream().filter(s -> s.contains("from backup_artefact a")).findFirst().orElseThrow();
        assertTrue(read.contains("a.artefact_class = 'backup'"));
        assertTrue(read.contains("coalesce(a.backup_type, 'standard') = 'standard'"), "snapshots are never checked");
        assertTrue(read.contains(JooqRetentionQueries.andNotRemoved("a")));
        assertTrue(sql.stream().noneMatch(s -> s.contains("insert into backup_deviation_record")));
    }

    @Test
    void acceptResolvesEveryOpenRowOfTheDeviceWithActorAndReason() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary().boundary());
        repository.accept("dev-00000001", "actor-fp", "backup_size_anomaly_accept", "  logs moved off the box  ");
        String update = sql.stream().filter(s -> s.startsWith("update backup_deviation_record")).findFirst().orElseThrow();
        assertTrue(update.contains("deviation_class = 'size_anomaly' and resolved_at is null"));
        int index = sql.indexOf(update);
        assertEquals(List.of("actor-fp", "logs moved off the box", "dev-00000001"), bindings.get(index), "bound in SQL order");
        assertTrue(sql.stream().anyMatch(s -> s.contains("backup_size_anomaly_accept")), "the action id is set");
    }

    @Test
    void acceptRefusesABlankReason() {
        var repository = new JooqBackupSizeAnomalyRepository(boundary().boundary());
        assertThrows(IllegalArgumentException.class, () -> repository.accept("dev-00000001", "actor-fp", "a", " "));
        assertThrows(IllegalArgumentException.class, () -> repository.accept("dev-00000001", "actor-fp", "a", null));
    }

    @Test
    void theAgeRuleNeverOffersAPinnedLastGoodOrTheNewestOpenAnomaly() {
        var queries = new JooqRetentionQueries(boundary().boundary());
        queries.expiredStandardBackups(7);
        String statement = sql.get(0);
        assertTrue(statement.contains(JooqBackupSizeAnomalyRepository.isPinnedLastGood("a")), statement);
        assertTrue(statement.contains(JooqRetentionQueries.NOT_SIZE_ANOMALY_KEPT), statement);
        assertTrue(statement.contains("backup_baseline"), "the baseline rule stays");
    }

    @Test
    void supersededCopiesAreOpenAnomaliesWithANewerOpenAnomalyAndNeverTheBaselineOrAPinnedLastGood() {
        var queries = new JooqRetentionQueries(boundary().boundary());
        queries.supersededSizeAnomalies();
        String statement = sql.get(0);
        assertTrue(statement.contains(JooqBackupSizeAnomalyRepository.isOpenAnomaly("a")));
        assertTrue(statement.contains(JooqBackupSizeAnomalyRepository.isOpenAnomaly("n")));
        assertTrue(statement.contains("(n.created_at, n.artefact_id) > (a.created_at, a.artefact_id)"));
        assertTrue(statement.contains(JooqRetentionQueries.andNotRemoved("a")));
        assertTrue(statement.contains("backup_baseline"));
        assertTrue(statement.contains("not " + JooqBackupSizeAnomalyRepository.isPinnedLastGood("a")));
        assertTrue(statement.contains("coalesce(a.backup_type, 'standard') = 'standard'"));
    }
}
