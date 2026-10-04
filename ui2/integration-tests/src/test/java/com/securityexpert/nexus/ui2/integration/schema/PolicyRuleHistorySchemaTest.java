package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import org.junit.jupiter.api.Test;
import java.sql.Connection;

class PolicyRuleHistorySchemaTest {
    private String rule(String id, int position, String service, boolean enabled) {
        return "{\"id\":\"" + id + "\",\"uuid\":\"" + id + "\",\"name\":\"OBJ-RULE-01\",\"number\":" + position
            + ",\"enabled\":" + enabled + ",\"source\":{\"refs\":[],\"negated\":false},\"destination\":{\"refs\":[],\"negated\":false},\"service\":{\"refs\":[\"" + service
            + "\"],\"negated\":false},\"application\":{\"refs\":[],\"negated\":false},\"action\":\"Accept\",\"log\":\"Log\",\"comment\":\"\",\"extras\":{}}";
    }
    private void save(Connection db, int revision, String rules, boolean partial) throws Exception {
        String snapshot = "{\"sections\":[{\"source\":\"Shared\",\"name\":\"Pre rules\",\"rules\":[" + rules + "]}],\"objects\":{\"s1\":{\"name\":\"OBJ-SERVICE-01\"},\"s2\":{\"name\":\"OBJ-SERVICE-02\"}},\"failures\":" + (partial ? "[{}]" : "[]") + "}";
        try (var statement = db.prepareStatement("insert into policy_snapshot values ('policy-1', ?::timestamptz, '{}'::jsonb, ?::jsonb) "
                + "on conflict (policy_id) do update set collected_at=excluded.collected_at, snapshot=excluded.snapshot "
                + "where policy_snapshot.collected_at < excluded.collected_at")) {
            statement.setString(1, "2026-10-02T" + String.format("%02d", revision) + ":00:00Z"); statement.setString(2, snapshot); statement.executeUpdate();
        }
    }
    private int count(Connection db) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("select count(*) from policy_rule_history")) {
            rows.next(); return rows.getInt(1);
        }
    }
    @Test void applicationRoleRecordsAddsRemovalsFieldDiffsMovesAndIgnoresPartialOrOlderSnapshots() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_rule_history")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection()) {
                save(db, 1, rule("r1", 1, "s1", true), false);
                assertEquals(1, count(db));
                save(db, 2, "", true); assertEquals(1, count(db));
                save(db, 3, rule("r1", 2, "s2", false) + "," + rule("r2", 1, "s1", true), false);
                assertEquals(3, count(db));
                try (var query = db.createStatement(); var rows = query.executeQuery("select changes::text from policy_rule_history where change_type='modified'")) {
                    assertTrue(rows.next()); String diff = rows.getString(1);
                    assertTrue(diff.contains("service")); assertTrue(diff.contains("OBJ-SERVICE-02"));
                    assertTrue(diff.contains("number")); assertTrue(diff.contains("enabled"));
                }
                save(db, 4, rule("r1", 2, "s2", false), false); assertEquals(4, count(db));
                save(db, 3, "", false); assertEquals(4, count(db));
                save(db, 5, rule("r1", 2, "s2", false), false); assertEquals(4, count(db));
                String counted = rule("r1", 2, "s2", false);
                counted = counted.substring(0, counted.length() - 1) + ",\"hitCounts\":{\"hits\":5,\"lastHit\":\"2026-07-01T00:00:00Z\"}}";
                save(db, 6, counted, false); assertEquals(4, count(db));
                try (var query = db.createStatement(); var rows = query.executeQuery("select changed_on, changed_by from policy_rule_history where change_type='removed'")) {
                    assertTrue(rows.next()); assertNull(rows.getString(1)); assertNull(rows.getString(2));
                }
            }
        }
    }
    @Test void nameFallbackIsFlaggedAndRepeatedOpaqueIdsAreNotJoinedByGuessing() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_history_fallback")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection()) {
                save(db, 1, rule("r1", 1, "s1", true).replace("\"uuid\":\"r1\"", "\"uuid\":\"\""), false);
                try (var query = db.createStatement(); var rows = query.executeQuery("select identity_fallback from policy_rule_history")) {
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                }
                save(db, 2, rule("r2", 1, "s1", true) + "," + rule("r2", 2, "s1", true), false);
                assertEquals(2, count(db)); // Unique fallback removal only; duplicated UUID is excluded.
            }
        }
    }
    @Test @org.junit.jupiter.api.Timeout(60)
    void synthetic3500RulePackageHasBoundedCheckpointAndHistoryWrites() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_history_bulk")) {
            fixture.runFlyway();
            long buildStarted = System.nanoTime();
            var rules = new java.util.ArrayList<String>();
            var objects = new java.util.ArrayList<String>();
            for (int i = 0; i < 3500; i++) {
                rules.add(rule("rule-" + i, i + 1, "object-" + i, true));
                objects.add("\"object-" + i + "\":{\"name\":\"OBJ-SERVICE-" + i + "\",\"description\":\"" + "synthetic ".repeat(30) + "\"}");
            }
            String body = "{\"sections\":[{\"source\":\"Shared\",\"name\":\"Layer\",\"rules\":[" + String.join(",", rules)
                + "]}],\"objects\":{" + String.join(",", objects) + "},\"failures\":[]}";
            long buildNanos = System.nanoTime() - buildStarted;
            var tx = new com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary(org.jooq.impl.DSL.using(fixture.appDataSource(), org.jooq.SQLDialect.POSTGRES));
            // Each real database statement has a bound too: timeout failures must not hang CI.
            com.securityexpert.nexus.ui2.persistence.TransactionBoundary bounded = new com.securityexpert.nexus.ui2.persistence.TransactionBoundary() {
                @Override public <T> T inTransaction(java.util.function.Function<org.jooq.DSLContext, T> work) {
                    return tx.inTransaction(db -> { db.execute("SET LOCAL statement_timeout='15s'"); return work.apply(db); });
                }
            };
            var repository = new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository(bounded);
            // Measure the original trigger with the same package, with a five-second ceiling.
            // A timeout is a lower bound, not a completed legacy measurement.
            String legacy;
            try (var resource = getClass().getResourceAsStream("/db/migration/V120__policy_rule_history.sql")) {
                assertNotNull(resource);
                legacy = new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            legacy = legacy.substring(legacy.indexOf("CREATE FUNCTION fn_capture_policy_rule_history()"),
                legacy.indexOf("CREATE TRIGGER policy_rule_history_capture"))
                .replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION");
            long legacyNanos; boolean legacyTimedOut = false;
            try (var migration = fixture.migrateConnection(); var statement = migration.createStatement()) {
                statement.execute(legacy);
            }
            long legacyStarted = System.nanoTime();
            try (var db = fixture.appConnection()) {
                try (var statement = db.createStatement()) { statement.execute("SET statement_timeout='5s'"); }
                try (var statement = db.prepareStatement("insert into policy_snapshot values ('legacy-policy', '2026-10-02T00:00:00Z', '{}'::jsonb, ?::jsonb)")) {
                    statement.setString(1, body); statement.executeUpdate();
                } catch (java.sql.SQLException timeout) {
                    if (!"57014".equals(timeout.getSQLState())) throw timeout;
                    legacyTimedOut = true;
                }
                legacyNanos = System.nanoTime() - legacyStarted;
                try (var statement = db.createStatement()) { statement.executeUpdate("delete from policy_snapshot where policy_id='legacy-policy'"); }
            } finally {
                try (var resource = getClass().getResourceAsStream("/db/migration/V122__policy_history_bulk_capture.sql");
                     var migration = fixture.migrateConnection(); var statement = migration.createStatement()) {
                    assertNotNull(resource);
                    statement.execute(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            String partial = body.replace("\"failures\":[]", "\"failures\":[{}]");
            long checkpointStarted = System.nanoTime();
            repository.save(new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository.Stored(
                "policy-1", "2026-10-02T01:00:00Z", "{}", partial), "synthetic-actor", "policy_collect_checkpoint");
            long checkpointNanos = System.nanoTime() - checkpointStarted;
            try (var db = fixture.appConnection()) { assertEquals(0, count(db)); }
            long historyStarted = System.nanoTime();
            repository.save(new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository.Stored(
                "policy-1", "2026-10-02T02:00:00Z", "{}", body), "synthetic-actor", "policy_collect_publish");
            long historyNanos = System.nanoTime() - historyStarted;
            try (var db = fixture.appConnection()) { assertEquals(3500, count(db)); }
            long unchangedStarted = System.nanoTime();
            repository.save(new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository.Stored(
                "policy-1", "2026-10-02T03:00:00Z", "{}", body), "synthetic-actor", "policy_collect_publish");
            long unchangedNanos = System.nanoTime() - unchangedStarted;
            try (var db = fixture.appConnection()) { assertEquals(3500, count(db)); }
            long limit = java.time.Duration.ofSeconds(15).toNanos();
            assertTrue(buildNanos < limit); assertTrue(checkpointNanos < limit);
            assertTrue(historyNanos < limit); assertTrue(unchangedNanos < limit);
            assertTrue(historyNanos < legacyNanos, "Bulk history must improve on the original trigger's measured time or timeout lower bound");
            System.out.printf("Synthetic policy rules=3500 buildMs=%d jsonbCheckpointMs=%d legacyHistoryMs=%d legacyTimedOut=%s historyMs=%d unchangedMs=%d%n",
                buildNanos / 1_000_000, checkpointNanos / 1_000_000, legacyNanos / 1_000_000, legacyTimedOut,
                historyNanos / 1_000_000, unchangedNanos / 1_000_000);
        }
    }

}
