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
