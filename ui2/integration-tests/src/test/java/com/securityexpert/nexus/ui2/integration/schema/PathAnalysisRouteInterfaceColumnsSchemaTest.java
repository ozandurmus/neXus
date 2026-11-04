package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;

/**
 * V184 (path analysis A1, PO record 2026-11-03 D-1): six nullable columns (NULL = UNKNOWN), closed vocabularies enforced
 * by CHECK, routing_context tier-2 redacted, and a re-run is a no-op.
 */
class PathAnalysisRouteInterfaceColumnsSchemaTest {

    private static final String[][] COLUMNS = {
            {"device_route", "is_active", "boolean"}, {"device_route", "is_ecmp", "boolean"},
            {"device_route", "metric", "bigint"}, {"device_route", "next_hop_kind", "text"},
            {"device_interface", "routing_context", "text"}, {"device_interface", "evidence_grade", "text"}};

    @Test
    void columnsAreNullableClosedRedactedAndTheMigrationIsIdempotent() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("path_analysis_a1_columns")) {
            fixture.runFlyway();
            try (var db = fixture.migrateConnection()) {
                for (String[] column : COLUMNS) {
                    assertEquals(column[2] + ":YES", scalar(db, "select data_type || ':' || is_nullable from "
                            + "information_schema.columns where table_name='" + column[0] + "' and column_name='"
                            + column[1] + "'"), column[0] + "." + column[1]);
                    assertEquals("0", scalar(db, "select count(*) from information_schema.columns where table_name='"
                            + column[0] + "' and column_name='" + column[1] + "' and column_default is not null"),
                            "no default: an absent value stays NULL (UNKNOWN)");
                }
                String routeChecks = scalar(db, "select string_agg(pg_get_constraintdef(oid), ' ') from pg_constraint "
                        + "where conrelid='device_route'::regclass and contype='c'");
                assertTrue(routeChecks.contains("'IP'") && routeChecks.contains("'CONNECTED'")
                        && routeChecks.contains("'DISCARD'") && routeChecks.contains("'NEXT_VR'"), routeChecks);
                assertTrue(routeChecks.contains("4294967295"), routeChecks);
                String interfaceChecks = scalar(db, "select string_agg(pg_get_constraintdef(oid), ' ') from pg_constraint "
                        + "where conrelid='device_interface'::regclass and contype='c'");
                assertTrue(interfaceChecks.contains("'RUNTIME'") && interfaceChecks.contains("'CONFIG_DERIVED'"),
                        interfaceChecks);
                assertEquals("2", scalar(db, "select tier from audit_redaction_policy "
                        + "where table_name='device_interface' and column_name='routing_context'"));
                assertEquals("0", scalar(db, "select count(*) from audit_redaction_policy where "
                        + "(table_name='device_route' and column_name in ('is_active','is_ecmp','metric','next_hop_kind')) "
                        + "or (table_name='device_interface' and column_name='evidence_grade')"),
                        "enums, booleans and the metric are audited in full (AuditPresentationAllowlist)");
            }
            try (var resource = getClass().getResourceAsStream(
                    "/db/migration/V184__path_analysis_route_interface_columns.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    statement.execute(sql);
                    assertEquals("1", scalar(db, "select count(*) from audit_redaction_policy "
                            + "where table_name='device_interface' and column_name='routing_context'"));
                    db.rollback();
                }
            }
        }
    }

    private static String scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
