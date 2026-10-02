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
}
