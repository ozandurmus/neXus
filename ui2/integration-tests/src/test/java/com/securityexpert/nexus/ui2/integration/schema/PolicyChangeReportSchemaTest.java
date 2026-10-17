package com.securityexpert.nexus.ui2.integration.schema;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeRepository;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import java.time.Instant;
import java.nio.file.*;
import java.util.*;

class PolicyChangeReportSchemaTest {
    @Test void timestampFallbackFilteringAndStableKeysetsUseRealPostgres() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_report")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-09T00:00:00Z', '{\"name\":\"POL-ALPHA-01\",\"targets\":[{\"deviceId\":\"d1\",\"name\":\"FW-TANGO-04\"}]}', '{\"sections\":[],\"objects\":{},\"failures\":[]}')");
                for (String changedOn : Arrays.asList("2026-10-08T02:00:00+02:00", null, "2026-02-30T00:00:00Z", "not a timestamp", "2026-10-08T00:00:00", "2026-10-08T00:00:00+99:99")) {
                    try (var insert = db.prepareStatement("insert into policy_rule_history (policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changed_on,changed_by,changes) "
                            + "values ('p1','uuid:r1','r1',false,'modified','2026-10-08T00:00:00Z',?,'Synthetic actor','[{\"field\":\"name\",\"before\":\"RULE-ALPHA-01\",\"after\":\"RULE-BRAVO-02\"},{\"field\":\"number\",\"before\":1,\"after\":7}]')")) {
                        insert.setString(1, changedOn); insert.executeUpdate();
                    }
                }
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                long ceiling = repository.ceiling();
                var filter = new PolicyChangeRepository.Filter(Instant.parse("2026-10-08T00:00:00Z"), Instant.parse("2026-10-09T00:00:00Z"), "p1", "d1", "modified", "name");
                var rows = repository.rows(filter, ceiling, null);
                assertEquals(6, rows.size()); assertEquals(5, rows.stream().filter(PolicyChangeRepository.Row::observed).count());
                assertEquals("2026-10-08T00:00:00Z", Instant.parse(rows.get(0).time()).toString());
                assertEquals("RULE-BRAVO-02", rows.get(0).name()); assertEquals(7, rows.get(0).number());
                assertEquals(6L, repository.summary(filter, ceiling).get("total"));
                var second = repository.rows(filter, ceiling, new PolicyChangeRepository.Position(Instant.parse(rows.get(1).time()), rows.get(1).revision()));
                assertEquals(rows.subList(2, 6), second);
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes) values ('p1','uuid:r2','r2',false,'added','2026-10-08T00:00:00Z','[]')");
                assertEquals(6, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "p1", "missing", "", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "removed", ""), ceiling, null).size());
                assertEquals(0, repository.rows(new PolicyChangeRepository.Filter(filter.from(), filter.to(), "", "", "", "action"), ceiling, null).size());
                sql.execute("set enable_seqscan=off");
                try (var plan = sql.executeQuery("explain select revision_id from policy_rule_history where coalesce(fn_policy_change_time(changed_on),collected_at) >= '2026-10-08T00:00:00Z' order by coalesce(fn_policy_change_time(changed_on),collected_at) desc,revision_id desc limit 100")) {
                    var lines = new ArrayList<String>(); while (plan.next()) lines.add(plan.getString(1));
                    assertTrue(String.join("\n", lines).contains("policy_changes_time"));
                }
            }
        }
    }
    @Test void initialCapturesAreLeftOutOfTheDefaultReportAndCounted() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_initial")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection(); var sql = db.createStatement()) {
                sql.execute("insert into policy_snapshot values ('p1', '2026-10-09T00:00:00Z', '{\"name\":\"POL-ALPHA-01\",\"targets\":[]}', '{\"sections\":[],\"objects\":{},\"failures\":[]}')");
                sql.execute("insert into policy_rule_history(policy_id,rule_key,rule_id,identity_fallback,change_type,collected_at,changes,initial_capture) values "
                    + "('p1','uuid:r1','r1',false,'added','2026-10-08T00:00:00Z','[]',true),('p1','uuid:r2','r2',false,'added','2026-10-08T00:00:00Z','[]',true),"
                    + "('p1','uuid:r1','r1',false,'modified','2026-10-08T06:00:00Z','[{\"field\":\"action\",\"before\":\"Accept\",\"after\":\"Drop\"}]',false)");
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                long ceiling = repository.ceiling();
                Instant from = Instant.parse("2026-10-08T00:00:00Z"), to = Instant.parse("2026-10-09T00:00:00Z");
                var real = new PolicyChangeRepository.Filter(from, to, "", "", "", "", false);
                var rows = repository.rows(real, ceiling, null);
                assertEquals(1, rows.size()); assertFalse(rows.get(0).initialCapture());
                assertEquals("2026-10-08T06:00:00.000000Z", rows.get(0).collectedAt());
                var summary = repository.summary(real, ceiling);
                assertEquals(1L, summary.get("total")); assertEquals(2L, summary.get("initialCaptures"));
                assertEquals(0L, ((Map<?, ?>) summary.get("counts")).get("added"));
                var all = new PolicyChangeRepository.Filter(from, to, "", "", "", "", true);
                assertEquals(3, repository.rows(all, ceiling, null).size());
                assertEquals(3L, repository.summary(all, ceiling).get("total"));
                assertEquals(2, repository.rows(all, ceiling, null).stream().filter(PolicyChangeRepository.Row::initialCapture).count());
            }
        }
    }
    private static String natRule(String id) {
        return "{\"id\":\"" + id + "\",\"uuid\":\"" + id + "\",\"name\":\"OBJ-RULE-" + id + "\",\"number\":1,\"enabled\":true,"
            + "\"source\":{\"refs\":[],\"negated\":false},\"destination\":{\"refs\":[],\"negated\":false},\"service\":{\"refs\":[],\"negated\":false},"
            + "\"application\":{\"refs\":[],\"negated\":false},\"action\":\"source-nat\",\"log\":\"\",\"comment\":\"\",\"extras\":{}}";
    }
    private static String section(String name, String... ids) {
        return "{\"source\":\"Shared\",\"name\":\"" + name + "\",\"rules\":[" + String.join(",", Arrays.stream(ids).map(PolicyChangeReportSchemaTest::natRule).toList()) + "]}";
    }
    private static void publish(java.sql.Connection db, String policy, String vendor, int hour, String... sections) throws Exception {
        try (var statement = db.prepareStatement("insert into policy_snapshot values (?, ?::timestamptz, ?::jsonb, ?::jsonb) "
                + "on conflict (policy_id) do update set collected_at=excluded.collected_at, metadata=excluded.metadata, snapshot=excluded.snapshot "
                + "where policy_snapshot.collected_at < excluded.collected_at")) {
            statement.setString(1, policy); statement.setString(2, "2026-10-02T" + String.format("%02d", hour) + ":00:00Z");
            statement.setString(3, "{\"vendor\":\"" + vendor + "\",\"name\":\"POL-ALPHA-01\",\"targets\":[]}");
            statement.setString(4, "{\"sections\":[" + String.join(",", sections) + "],\"objects\":{},\"failures\":[]}");
            statement.executeUpdate();
        }
    }
    @Test void firstAppearanceOfPanNatAndDefaultSectionsIsInitialCaptureAndLaterAdditionsDiffNormally() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_nat_first")) {
            fixture.runFlyway();
            try (var db = fixture.appConnection()) {
                var repository = new PolicyChangeRepository(new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
                Instant from = Instant.parse("2026-10-02T00:00:00Z"), to = Instant.parse("2026-10-03T00:00:00Z");
                var real = new PolicyChangeRepository.Filter(from, to, "", "", "", "", false);
                var all = new PolicyChangeRepository.Filter(from, to, "", "", "", "", true);
                // Security rules only: the policy's own initial capture (V150).
                publish(db, "p-pan", "PAN", 1, section("Pre rules", "r1"));
                // The release that parses NAT and default rules: the next snapshot carries three new sections' rules.
                publish(db, "p-pan", "PAN", 2, section("Pre rules", "r1"), section("Pre NAT rules", "n1", "n2"), section("Post default rules", "d1"));
                long ceiling = repository.ceiling();
                assertEquals(0, repository.rows(real, ceiling, null).size(), "first capture of a section kind yields no added event");
                var summary = repository.summary(real, ceiling);
                assertEquals(0L, summary.get("total")); assertEquals(0L, ((Map<?, ?>) summary.get("counts")).get("added"));
                assertEquals(4L, summary.get("initialCaptures"), "r1 plus the three first-appearance rules are all counted as initial");
                assertEquals(4, repository.rows(all, ceiling, null).stream().filter(PolicyChangeRepository.Row::initialCapture).count());
                // The next capture adds one NAT rule to a kind that already exists: exactly one event.
                publish(db, "p-pan", "PAN", 3, section("Pre rules", "r1"), section("Pre NAT rules", "n1", "n2", "n3"), section("Post default rules", "d1"));
                ceiling = repository.ceiling();
                var rows = repository.rows(real, ceiling, null);
                assertEquals(1, rows.size()); assertEquals("added", rows.get(0).changeType()); assertEquals("uuid:n3", rows.get(0).ruleKey());
                assertFalse(rows.get(0).initialCapture());
                summary = repository.summary(real, ceiling);
                assertEquals(1L, summary.get("total")); assertEquals(1L, ((Map<?, ?>) summary.get("counts")).get("added"));
                assertEquals(4L, summary.get("initialCaptures"));
                // A security rule added in the same release window is an ordinary change.
                publish(db, "p-pan", "PAN", 4, section("Pre rules", "r1", "r2"), section("Pre NAT rules", "n1", "n2", "n3"), section("Post default rules", "d1"));
                assertEquals(2, repository.rows(real, repository.ceiling(), null).size());
                // A Check Point policy never gets the Palo Alto classification, whatever its section is called.
                publish(db, "p-cp", "CP", 1, section("Pre NAT rules", "c1"));
                publish(db, "p-cp", "CP", 2, section("Pre NAT rules", "c1", "c2"));
                var cp = repository.rows(new PolicyChangeRepository.Filter(from, to, "p-cp", "", "", "", false), repository.ceiling(), null);
                assertEquals(1, cp.size()); assertEquals("uuid:c2", cp.get(0).ruleKey());
            }
        }
    }
    @Test void indexMigrationIsIdempotentAndRollbackSafe() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("policy_changes_rollback")) {
            fixture.runFlyway();
            Path path = Path.of("../service/src/main/resources/db/migration/V145__policy_change_report_indexes.sql");
            String migration = Files.readString(path);
            try (var db = fixture.migrateConnection(); var sql = db.createStatement()) {
                db.setAutoCommit(false);
                sql.execute("drop index policy_changes_time");
                sql.execute(migration); sql.execute(migration); db.rollback();
                try (var rows = sql.executeQuery("select to_regclass('policy_changes_time') is not null")) {
                    assertTrue(rows.next()); assertTrue(rows.getBoolean(1));
                }
                db.rollback();
            }
        }
    }
}
