package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository;

/**
 * API latency work 2026-11-14: rule counts (policy catalog) and current-snapshot rule labels (policy changes, change
 * feed) are kept per (policy, snapshot version) instead of expanding every snapshot on every read. A replaced
 * snapshot must be recounted and relabelled on the next read; a removed policy must leave the catalog.
 */
class PolicySnapshotVersionCacheSchemaTest {

    private static String rule(String uuid, String name, int number) {
        return "{\"id\":\"" + uuid + "\",\"uuid\":\"" + uuid + "\",\"name\":\"" + name + "\",\"number\":" + number + "}";
    }

    private static String snapshot(String... rules) {
        return "{\"sections\":[{\"source\":\"Shared\",\"name\":\"SEC-ALPHA\",\"rules\":[" + String.join(",", rules)
                + "]}],\"objects\":{},\"failures\":[]}";
    }

    @Test
    void ruleCountsFollowTheSnapshotVersion() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_rule_count_cache")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-09T00:00:00Z', '{\"name\":\"POL-ALPHA-01\"}', '"
                        + snapshot(rule("a", "RULE-A", 1), rule("b", "RULE-B", 2)) + "')");
                sql.execute("insert into policy_snapshot values ('p2', '2026-10-09T00:00:00Z', '{\"name\":\"POL-BRAVO-02\"}', '"
                        + snapshot(rule("c", "RULE-C", 1)) + "')");
                var repository = new PolicySnapshotRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                assertEquals(java.util.List.of(2, 1), repository.catalogEntries().stream().map(PolicySnapshotRepository.CatalogEntry::ruleCount).toList());
                // the same answer from the kept counts
                assertEquals(java.util.List.of(2, 1), repository.catalogEntries().stream().map(PolicySnapshotRepository.CatalogEntry::ruleCount).toList());
                sql.execute("update policy_snapshot set collected_at = '2026-10-10T00:00:00Z', snapshot = '"
                        + snapshot(rule("a", "RULE-A", 1), rule("b", "RULE-B", 2), rule("d", "RULE-D", 3)) + "' where policy_id = 'p1'");
                assertEquals(java.util.List.of(3, 1), repository.catalogEntries().stream().map(PolicySnapshotRepository.CatalogEntry::ruleCount).toList());
                sql.execute("delete from policy_snapshot where policy_id = 'p2'");
                var entries = repository.catalogEntries();
                assertEquals(1, entries.size());
                assertTrue(entries.get(0).metadataJson().contains("POL-ALPHA-01"));
            }
        }
    }

    @Test
    void currentSnapshotLabelsFollowTheSnapshotVersion() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_current_label_cache")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                // the policy exists without rules first (no history rows from its trigger), then two revisions whose own
                // history carries no name or number, then the snapshot gains the rule: the rows its trigger writes for that
                // have higher revision ids and a later time, so they are not these revisions' own labels
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-08T00:00:00Z', '{\"name\":\"POL-ALPHA-01\",\"targets\":[]}', '"
                        + snapshot() + "')");
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes) "
                        + "values ('p1','uuid:r1','r1',false,'modified','2026-10-08T06:00:00Z','[{\"field\":\"action\",\"before\":\"Accept\",\"after\":\"Drop\"}]'),"
                        + "('p1','uuid:ghost','ghost',false,'modified','2026-10-08T07:00:00Z','[{\"field\":\"action\",\"before\":\"Accept\",\"after\":\"Drop\"}]')");
                sql.execute("update policy_snapshot set collected_at = '2026-10-09T00:00:00Z', snapshot = '"
                        + snapshot(rule("r1", "RULE-OLD", 4)) + "' where policy_id = 'p1'");
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                var filter = new PolicyChangeRepository.Filter(Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"),
                        "", "", "", "", false);
                var rows = repository.rows(filter, repository.ceiling(), null);
                var r1 = rows.stream().filter(r -> r.ruleKey().equals("uuid:r1")).findFirst().orElseThrow();
                assertEquals("RULE-OLD", r1.name());
                assertEquals(4, r1.number());
                assertTrue(r1.currentLabel());
                var ghost = rows.stream().filter(r -> r.ruleKey().equals("uuid:ghost")).findFirst().orElseThrow();
                assertEquals(null, ghost.name());
                assertTrue(!ghost.currentLabel());
                // the snapshot is replaced (newer collected_at): the next read labels from the new snapshot (the history
                // trigger's own rows for this replacement fall outside the filter window)
                sql.execute("update policy_snapshot set collected_at = '2026-10-10T00:00:00Z', snapshot = '"
                        + snapshot(rule("r1", "RULE-NEW", 9)) + "' where policy_id = 'p1'");
                var after = repository.rows(filter, repository.ceiling(), null).stream()
                        .filter(r -> r.ruleKey().equals("uuid:r1")).findFirst().orElseThrow();
                assertEquals("RULE-NEW", after.name());
                assertEquals(9, after.number());
                assertTrue(after.currentLabel());
            }
        }
    }
}
