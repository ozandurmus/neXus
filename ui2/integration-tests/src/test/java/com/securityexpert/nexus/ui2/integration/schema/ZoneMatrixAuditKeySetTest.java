package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.zonematrix.JooqZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository;
import com.securityexpert.nexus.ui2.persistence.zonematrix.ZoneMatrixRunRepository.*;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ZM-37 (docs/design/ZONE_MATRIX_CONTRACT.md section 10.2; sections 3.5 and 8.2) against a real PostgreSQL: the exact
 * {@code audit_log} key set each zone matrix audit capture writes, and the absence of any DELETE surface on runs,
 * findings and revisions. Synthetic data only; every transaction is rolled back or belongs to the throw-away fixture.
 */
class ZoneMatrixAuditKeySetTest {
    private static Ui2PostgresFixture fixture;
    private static final String VERSION = "00000000-0000-4000-8000-0000000000b1";
    private static final String SHA = "b".repeat(64);
    private static final Instant STARTED = Instant.parse("2026-11-25T09:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-11-25T09:00:05Z");

    /** The keys the trigger {@code fn_audit_capture_zone_matrix_revision} (V220) builds with jsonb_build_object. */
    private static final Set<String> REVISION_KEYS = Set.of(
            "version_id", "revision", "change_kind", "zone_id", "content_sha256", "rationale_given");
    /** The keys the trigger {@code fn_audit_capture_zone_matrix_access} (V216) builds with jsonb_build_object. */
    private static final Set<String> ACCESS_KEYS = Set.of("kind", "version_id");
    /** Content, geometry, free text and witness fields: never in an audit row (ZM-37, section 8.2). */
    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "content", "blocks", "addresses", "witness", "name", "description", "rationale");

    @BeforeAll static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("zone_matrix_audit_keys");
    }

    @AfterAll static void close() { if (fixture != null) fixture.close(); }

    private static Set<String> auditKeys(Statement s, String table, String rowPk) throws SQLException {
        Set<String> keys = new TreeSet<>();
        try (ResultSet rows = s.executeQuery("SELECT jsonb_object_keys(after_state) FROM audit_log "
                + "WHERE table_name = '" + table + "' AND row_pk = '" + rowPk + "'")) {
            while (rows.next()) keys.add(rows.getString(1));
        }
        return keys;
    }

    private static long count(Statement s, String sql) throws SQLException {
        try (ResultSet rows = s.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private static boolean privilege(Statement s, String role, String table, String privilege) throws SQLException {
        try (ResultSet rows = s.executeQuery("SELECT has_table_privilege('" + role + "', 'public." + table + "', '" + privilege + "')")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    @Test void revisionAuditRowCarriesExactlyTheAllowlistedKeys() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            db.setAutoCommit(false);
            String id = "00000000-0000-4000-8000-0000000000c1";
            s.execute("INSERT INTO zone_matrix_version (version_id,state,revision,content,content_sha256,created_by,updated_by) "
                    + "VALUES ('" + id + "','DRAFT',1,'{}','" + SHA + "','synthetic-a','synthetic-a')");
            s.execute("INSERT INTO zone_matrix_revision (version_id,revision,actor,change_kind,content_sha256,content) "
                    + "VALUES ('" + id + "',1,'synthetic-a','DRAFT_CREATE','" + SHA + "','{}')");
            String rowPk = id + ":1";

            assertEquals(1, count(s, "SELECT count(*) FROM audit_log WHERE table_name='zone_matrix_revision' AND row_pk='" + rowPk + "'"));
            Set<String> keys = auditKeys(s, "zone_matrix_revision", rowPk);
            assertEquals(REVISION_KEYS, keys, "the revision audit row holds exactly the trigger's keys");
            for (String key : keys) assertFalse(FORBIDDEN_KEYS.contains(key), "forbidden audit key " + key);
            db.rollback();
        }
    }

    @Test void accessAuditRowCarriesExactlyTheAllowlistedKeys() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            db.setAutoCommit(false);
            String id = "00000000-0000-4000-8000-0000000000c2";
            s.execute("INSERT INTO zone_matrix_version (version_id,state,revision,content,content_sha256,created_by,updated_by) "
                    + "VALUES ('" + id + "','DRAFT',1,'{}','" + SHA + "','synthetic-a','synthetic-a')");
            String accessId;
            try (ResultSet rows = s.executeQuery("INSERT INTO zone_matrix_access_log (actor,kind,version_id) "
                    + "VALUES ('synthetic-a','BLOCKS_VIEW','" + id + "') RETURNING access_id")) {
                assertTrue(rows.next());
                accessId = rows.getString(1);
            }
            Set<String> keys = auditKeys(s, "zone_matrix_access_log", accessId);
            assertEquals(ACCESS_KEYS, keys, "the access audit row holds exactly the trigger's keys");
            for (String key : keys) assertFalse(FORBIDDEN_KEYS.contains(key), "forbidden audit key " + key);
            db.rollback();
        }
    }

    @Test void runAndFindingWritesHaveNoDeletePrivilegeAndNoAuditRow() throws Exception {
        try (Connection db = fixture.appConnection(); Statement s = db.createStatement()) {
            s.execute("INSERT INTO zone_matrix_version (version_id, number, state, revision, content, content_sha256, "
                    + "created_by, updated_by, published_by, published_at) VALUES ('" + VERSION + "', 1, 'ACTIVE', 1, "
                    + "'{}', '" + SHA + "', 'security-1', 'security-1', 'compliance-1', now())");
            long before = count(s, "SELECT count(*) FROM audit_log WHERE table_name IN ('zone_matrix_run', 'zone_matrix_finding')");

            ZoneMatrixRunRepository repository = new JooqZoneMatrixRunRepository(
                    new JooqTransactionBoundary(DSL.using(db, SQLDialect.POSTGRES)));
            String policy = "pol-audit-" + UUID.randomUUID();
            String runId = UUID.randomUUID().toString();
            repository.start(new NewRun(runId, VERSION, 1, SHA, policy, "step4-pin", STARTED));
            Counts counts = new Counts(1, 0, 0, 0, 0, 1);
            Coverage coverage = new Coverage("COMPLETE", List.of(), 0, true, "CP", STARTED, 1);
            assertTrue(repository.complete(runId, FINISHED, 5_000L, counts, coverage, List.of(finding(policy))));

            assertEquals(1, count(s, "SELECT count(*) FROM zone_matrix_finding WHERE run_id = '" + runId + "'"));
            long after = count(s, "SELECT count(*) FROM audit_log WHERE table_name IN ('zone_matrix_run', 'zone_matrix_finding')");
            assertEquals(before, after, "writing a run and its findings creates no audit_log row");

            for (String role : List.of("ui2_app")) {
                for (String table : List.of("zone_matrix_run", "zone_matrix_finding", "zone_matrix_revision")) {
                    assertFalse(privilege(s, role, table, "DELETE"), role + " holds no DELETE on " + table);
                }
            }
        }
    }

    private static Finding finding(String policy) {
        return new Finding(policy, "uid-audit-1", List.of("00Parent"), List.of("ZONE-A->ZONE-B"), "r-0001",
                1, "RULE-SYNTHETIC-1", "sec-1", "Pre rules", List.of("FW-TANGO-04"), "VIOLATION", null, "HIGH",
                List.of(), null,
                List.of(new Cell("zone-a", "zone-b", "ALLOW_LISTED", "HIGH", List.of("tcp/22"), "original",
                        new Witness("192.0.2.16/28", "198.51.100.77/32", "tcp/22", 3221225488L, 3325256781L))),
                List.of(), "original", FINISHED, null, FINISHED, null, null);
    }
}
