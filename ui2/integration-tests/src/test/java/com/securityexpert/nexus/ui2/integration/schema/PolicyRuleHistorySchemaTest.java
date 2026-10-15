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
    private int count(Connection db, String where) throws Exception {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("select count(*) from policy_rule_history where " + where)) {
            rows.next(); return rows.getInt(1);
        }
    }
    private void saveSections(Connection db, int revision, String sections) throws Exception {
        String snapshot = "{\"sections\":[" + sections + "],\"objects\":{\"s1\":{\"name\":\"OBJ-SERVICE-01\"}},\"failures\":[]}";
        try (var statement = db.prepareStatement("insert into policy_snapshot values ('policy-1', ?::timestamptz, '{}'::jsonb, ?::jsonb) "
                + "on conflict (policy_id) do update set collected_at=excluded.collected_at, snapshot=excluded.snapshot "
                + "where policy_snapshot.collected_at < excluded.collected_at")) {
            statement.setString(1, "2026-10-02T" + String.format("%02d", revision) + ":00:00Z"); statement.setString(2, snapshot); statement.executeUpdate();
        }
    }
    private String section(String name, String rules) {
        return "{\"source\":\"Shared\",\"name\":\"" + name + "\",\"rules\":[" + rules + "]}";
    }
    @Test void v149MarksInitialCaptureAndRecordsReordersButNotRenumberingAlone() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_history_renumber")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection()) {
                save(db, 1, rule("r1", 1, "s1", true) + "," + rule("r2", 2, "s1", true) + "," + rule("r3", 3, "s1", true), false);
                assertEquals(3, count(db)); assertEquals(3, count(db, "initial_capture and change_type='added'"));
                // An insertion at the top renumbers every rule below it; only the inserted rule is a change.
                save(db, 2, rule("r0", 1, "s1", true) + "," + rule("r1", 2, "s1", true) + "," + rule("r2", 3, "s1", true) + "," + rule("r3", 4, "s1", true), false);
                assertEquals(4, count(db)); assertEquals(1, count(db, "not initial_capture and change_type='added' and rule_key='uuid:r0'"));
                // A removal renumbers the rules below it; only the removed rule is a change.
                save(db, 3, rule("r0", 1, "s1", true) + "," + rule("r2", 2, "s1", true) + "," + rule("r3", 3, "s1", true), false);
                assertEquals(5, count(db)); assertEquals(1, count(db, "change_type='removed' and rule_key='uuid:r1'"));
                assertEquals(0, count(db, "change_type='modified'"));
                // A real reorder changes the rules' relative order and is recorded with the number diff.
                save(db, 4, rule("r0", 1, "s1", true) + "," + rule("r3", 2, "s1", true) + "," + rule("r2", 3, "s1", true), false);
                assertEquals(7, count(db));
                assertEquals(2, count(db, "change_type='modified' and changes = jsonb_build_array(jsonb_build_object('field','number','before',"
                    + "case rule_key when 'uuid:r2' then 2 else 3 end,'after',case rule_key when 'uuid:r2' then 3 else 2 end))"));
                // Renumbered and changed in another field: the number change is kept alongside.
                save(db, 5, rule("r3", 1, "s1", true) + "," + rule("r2", 2, "s1", false), false);
                assertEquals(9, count(db)); // r0 removed, r2 modified
                assertEquals(1, count(db, "change_type='modified' and rule_key='uuid:r2' and collected_at='2026-10-02T05:00:00Z' "
                    + "and changes @> '[{\"field\":\"number\"},{\"field\":\"enabled\"}]'"));
                assertEquals(0, count(db, "collected_at='2026-10-02T05:00:00Z' and rule_key='uuid:r3'"));
                // Moving a rule to another section is a change of that rule only; the section it left keeps its order.
                saveSections(db, 6, section("Layer A", rule("r0", 1, "s1", true)) + "," + section("Layer B", rule("r2", 2, "s1", true) + "," + rule("r3", 3, "s1", true)));
                int before = count(db);
                saveSections(db, 7, section("Layer A", rule("r0", 1, "s1", true) + "," + rule("r3", 2, "s1", true)) + "," + section("Layer B", rule("r2", 3, "s1", true)));
                assertEquals(before + 1, count(db));
                assertEquals(1, count(db, "collected_at='2026-10-02T07:00:00Z' and rule_key='uuid:r3' and changes @> '[{\"field\":\"section\"}]'"));
                assertEquals(3, count(db, "initial_capture"));
                // Backfill: forget the marker, re-run V149 twice (idempotent) and get the same first batch back.
                try (var statement = db.createStatement()) { statement.executeUpdate("update policy_rule_history set initial_capture=false"); }
            }
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                String v149 = migration("V149__policy_history_initial_capture_and_renumbering.sql");
                db.setAutoCommit(false);
                statement.execute(v149); statement.execute(v149);
                db.commit();
            }
            try (var db = fixture.appConnection()) {
                assertEquals(3, count(db, "initial_capture"));
                assertEquals(3, count(db, "initial_capture and collected_at='2026-10-02T01:00:00Z'"));
            }
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
                // Restore the current capture (V149 supersedes V132's function) before measuring it.
                try (var resource = getClass().getResourceAsStream("/db/migration/V132__policy_history_bounded_memory.sql");
                     var migration = fixture.migrateConnection(); var statement = migration.createStatement()) {
                    assertNotNull(resource);
                    statement.execute(new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
                    statement.execute(migration("V149__policy_history_initial_capture_and_renumbering.sql"));
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


    private String migration(String name) throws Exception {
        try (var resource = getClass().getResourceAsStream("/db/migration/" + name)) {
            assertNotNull(resource);
            return new String(resource.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private java.util.List<String> historyScenario(Connection db) throws Exception {
        String initial = rule("opaque-001", 1, "s1", true).replace("\"extras\":{}",
            "\"extras\":{\"time\":[\"s2\",\"s1\"],\"vpn\":[\"missing\"],"
            + "\"last-modified\":[\"synthetic-time-1\"],\"last-modifier\":[\"synthetic-editor\"]}");
        String fallback = rule("fallback", 2, "missing", true).replace("\"uuid\":\"fallback\"", "\"uuid\":\"\"");
        save(db, 1, initial + "," + fallback, false);
        save(db, 2, "", true);
        String modified = initial.replace("\"number\":1", "\"number\":3").replace("\"enabled\":true", "\"enabled\":false");
        save(db, 3, modified + "," + fallback, false);
        String countersOnly = modified.substring(0, modified.length() - 1) + ",\"hitCounts\":{\"hits\":99}}";
        save(db, 4, countersOnly + "," + fallback, false);
        // Object names and parent context are configuration history, unlike counters/editor metadata.
        try (var statement = db.createStatement()) {
            statement.executeUpdate("update policy_snapshot set collected_at='2026-10-02T05:00:00Z', "
                + "snapshot=jsonb_set(jsonb_set(snapshot, '{objects,s1,name}', '\"OBJ-RENAMED\"'), "
                + "'{sections,0,parentRuleId}', '\"synthetic-parent\"') where policy_id='policy-1'");
        }
        save(db, 6, modified + "," + modified + "," + fallback, false); // Ambiguous UUID is excluded.
        save(db, 7, modified, false); // Previous duplicate remains excluded; fallback is removed.
        save(db, 8, initial, false);
        save(db, 7, "", false); // Older publication cannot advance the baseline.
        save(db, 9, "", true);
        save(db, 10, "", false);
        var result = new java.util.ArrayList<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery(
                "select (to_jsonb(h) - 'revision_id')::text from policy_rule_history h order by collected_at, rule_key")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }

    @Test void boundedCaptureMatchesV122HistoryExactly() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_history_equivalence")) {
            fixture.runFlyway();
            String original = migration("V120__policy_rule_history.sql");
            original = original.substring(original.indexOf("CREATE FUNCTION fn_policy_history_rules("),
                original.indexOf("CREATE FUNCTION fn_policy_history_fields("))
                .replace("CREATE FUNCTION", "CREATE OR REPLACE FUNCTION");
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                statement.execute(original);
                statement.execute(migration("V122__policy_history_bulk_capture.sql"));
            }
            java.util.List<String> expected;
            try (var db = fixture.appConnection()) {
                expected = historyScenario(db);
                assertFalse(expected.isEmpty());
                try (var statement = db.createStatement()) { statement.executeUpdate("delete from policy_snapshot"); }
            }
            try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                statement.execute(migration("V132__policy_history_bounded_memory.sql"));
            }
            try (var db = fixture.appConnection()) {
                assertEquals(expected, historyScenario(db));
            }
        }
    }

    @Test @org.junit.jupiter.api.Timeout(180)
    void synthetic16000RuleSnapshotOver27MbPreservesHistoryAndRollback() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_history_large")) {
            fixture.runFlyway();
            var rules = new java.util.ArrayList<String>();
            var objects = new java.util.ArrayList<String>();
            for (int i = 0; i < 16000; i++) {
                rules.add(rule("rule-" + i, i + 1, "object-" + i, true));
                objects.add("\"object-" + i + "\":{\"name\":\"OBJ-SERVICE-" + i
                    + "\",\"description\":\"" + "synthetic ".repeat(160) + "\"}");
            }
            String body = "{\"sections\":[{\"source\":\"Shared\",\"name\":\"Layer\",\"rules\":["
                + String.join(",", rules) + "]}],\"objects\":{" + String.join(",", objects) + "},\"failures\":[]}";
            assertTrue(body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 27_000_000);
            try (var connection = fixture.appConnection()) {
                var db = org.jooq.impl.DSL.using(connection, org.jooq.SQLDialect.POSTGRES);
                db.execute("SET statement_timeout='45s'");
                db.execute("SET work_mem='4MB'");
                var repository = new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository(
                    new com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary(db));
                for (int revision = 1; revision <= 2; revision++) {
                    repository.save(new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository.Stored(
                        "policy-1", "2026-10-02T0" + revision + ":00:00Z", "{}", body),
                        "synthetic-actor", "policy_collect_publish");
                    assertEquals(16000, count(connection)); // Unchanged second publication adds nothing.
                }
                // A single changed rule in another full-size publication creates exactly one revision.
                String modified = body.replace("\"uuid\":\"rule-0\",\"name\":\"OBJ-RULE-01\",\"number\":1,\"enabled\":true",
                    "\"uuid\":\"rule-0\",\"name\":\"OBJ-RULE-01\",\"number\":1,\"enabled\":false");
                assertNotEquals(body, modified);
                repository.save(new com.securityexpert.nexus.ui2.persistence.policy.PolicySnapshotRepository.Stored(
                    "policy-1", "2026-10-02T03:00:00Z", "{}", modified), "synthetic-actor", "policy_collect_publish");
                assertEquals(16001, count(connection));
                assertTrue(db.fetchOne("select changes = '[{\"field\":\"enabled\",\"before\":true,\"after\":false}]'::jsonb "
                    + "from policy_rule_history where change_type='modified'").get(0, Boolean.class));
                connection.setAutoCommit(false);
                try {
                    db.execute("update policy_snapshot set snapshot='{\"sections\":[],\"objects\":{},\"failures\":[]}'::jsonb");
                    assertEquals(32001, count(connection));
                } finally {
                    connection.rollback();
                    connection.setAutoCommit(true);
                }
                assertEquals(16001, count(connection));
                assertTrue(db.fetchOne("select b.snapshot = p.snapshot from policy_rule_history_baseline b "
                    + "join policy_snapshot p using(policy_id)").get(0, Boolean.class));
                assertEquals(0, db.fetchOne("select count(*)::int from pg_class where relnamespace=pg_my_temp_schema() "
                    + "and relname like 'policy_json_%'").get(0, Integer.class));
            }
        }
    }

}
