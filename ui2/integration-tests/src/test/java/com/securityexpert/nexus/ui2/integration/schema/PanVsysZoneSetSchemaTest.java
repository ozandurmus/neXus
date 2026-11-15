package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.JooqPanVsysZoneRepository;
import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneSet;

/**
 * V229 (docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md, DECIDED): pan_vsys_zone_set is written as a difference by the app
 * role, audited with the vsys and the zone names redacted, removed with its device, refuses malformed names, and the
 * migration can be applied again. Synthetic names only; a non-ASCII zone name proves the UTF8 round trip.
 */
class PanVsysZoneSetSchemaTest {

    static final Instant T1 = Instant.parse("2026-11-15T03:00:00Z");
    static final Instant T2 = Instant.parse("2026-11-15T04:00:00Z");

    @Test
    void zoneSetsAreStoredByDifferenceAuditedRedactedRemovedWithTheDeviceAndTheMigrationRunsAgain() throws Exception {
        try (var fixture = Ui2PostgresFixture.create("pan_vsys_zone_set")) {
            fixture.runFlyway();
            String device;
            try (Connection db = fixture.migrateConnection()) {
                device = Ui2Rows.insertDevice(db, Ui2Rows.insertCredentialReference(db), "ENROLLED");
                assertEquals("ARRAY:NO", scalar(db, "select data_type || ':' || is_nullable from information_schema.columns "
                        + "where table_name='pan_vsys_zone_set' and column_name='zone_names'"), "an array, never NULL");
                assertEquals("2", scalar(db, "select tier from audit_redaction_policy where table_name='pan_vsys_zone_set' and column_name='zone_names'"));
                assertEquals("2", scalar(db, "select tier from audit_redaction_policy where table_name='pan_vsys_zone_set' and column_name='vsys'"));
                assertEquals("c", scalar(db, "select confdeltype from pg_constraint where conrelid='pan_vsys_zone_set'::regclass and contype='f'"),
                        "device deletion cascades");
                assertEquals("true", scalar(db, "select has_table_privilege('ui2_app', 'pan_vsys_zone_set', 'SELECT,INSERT,UPDATE,DELETE')::text"));
            }
            var repository = new JooqPanVsysZoneRepository(new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
            var vsys1 = new PanVsysZoneSet(device, "vsys1", Set.of("ZONE-ALPHA", "ZONE-BRAVO", "ZONE-ÇÖĞÜŞ", "ZONE,\"Q\"\\{x} y"), T1);
            var vsys2 = new PanVsysZoneSet(device, "vsys2", Set.of("ZONE-ALPHA"), T1);
            var vsys3 = new PanVsysZoneSet(device, "vsys3", Set.of(), T1);

            // first read: three rows (a virtual system with no zone is a row with an empty set)
            assertEquals(3, repository.replace(device, T1, List.of(vsys1, vsys2, vsys3), "system:worker", "inventory_pan_zone_sets"));
            assertEquals(Map.of("vsys1", vsys1, "vsys2", vsys2, "vsys3", vsys3), repository.findForDevices(List.of(device, "absent-device")).get(device));
            assertFalse(repository.findForDevices(List.of(device, "absent-device")).containsKey("absent-device"));
            assertEquals(Set.of("ZONE-ALPHA", "ZONE-BRAVO", "ZONE-ÇÖĞÜŞ", "ZONE,\"Q\"\\{x} y"), repository.findForDevices(List.of(device)).get(device).get("vsys1").zones(),
                    "UTF8 round trip, and a name with a comma, quote, backslash, brace and space");

            // the same read again: nothing is touched, nothing is audited
            assertEquals(0, repository.replace(device, T1, List.of(vsys1, vsys2, vsys3), "system:worker", "inventory_pan_zone_sets"));
            try (Connection db = fixture.migrateConnection()) {
                assertEquals("3", scalar(db, "select count(*) from audit_log where table_name='pan_vsys_zone_set'"));
                assertEquals("0", scalar(db, "select count(*) from audit_log where table_name='pan_vsys_zone_set' and (after_state::text like '%ZONE-%' "
                        + "or after_state::text like '%vsys1%' or after_state::text like '%vsys2%')"), "vsys and the zone names are redacted in the audit trail");
            }

            // a newer document: vsys1 gains a zone, vsys2 is unchanged but newer (only read_at moves), vsys3 is gone
            var vsys1b = new PanVsysZoneSet(device, "vsys1", Set.of("ZONE-ALPHA", "ZONE-BRAVO", "ZONE-ÇÖĞÜŞ", "ZONE,\"Q\"\\{x} y", "ZONE-CHARLIE"), T2);
            var vsys2b = new PanVsysZoneSet(device, "vsys2", Set.of("ZONE-ALPHA"), T2);
            assertEquals(3, repository.replace(device, T2, List.of(vsys1b, vsys2b), "system:worker", "inventory_pan_zone_sets"));
            assertEquals(Map.of("vsys1", vsys1b, "vsys2", vsys2b), repository.findForDevices(List.of(device)).get(device));

            // an older read never moves the stored one back
            assertEquals(0, repository.replace(device, T1, List.of(vsys1), "system:worker", "inventory_pan_zone_sets"));
            assertEquals(Map.of("vsys1", vsys1b, "vsys2", vsys2b), repository.findForDevices(List.of(device)).get(device));

            // an unenrolled device is skipped, not failed; a second device's rows are never touched
            assertEquals(0, repository.replace("absent-device", T2, List.of(new PanVsysZoneSet("absent-device", "vsys1", Set.of("ZONE-X"), T2)),
                    "system:worker", "inventory_pan_zone_sets"));

            // malformed rows are refused by the table itself
            try (Connection db = fixture.migrateConnection()) {
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                for (String bad : List.of("ARRAY['']::text[]", "ARRAY['ZONE-A', NULL]::text[]", "NULL", "(select array_agg('z' || g) from generate_series(1, 1025) g)")) {
                    try (var statement = db.createStatement()) {
                        statement.execute("savepoint s");
                        assertThrows(SQLException.class, () -> statement.execute("insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) "
                                + "values ('" + device + "', 'vsys9', " + bad + ", now())"), bad);
                        statement.execute("rollback to savepoint s");
                    }
                }
                try (var statement = db.createStatement()) {
                    assertThrows(SQLException.class, () -> statement.execute("insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at) "
                            + "values ('" + device + "', 'vsys1', ARRAY['ZONE-A'], now())"), "one row per virtual system");
                }
                db.rollback();
            }

            // the device goes: its sets go with it, audited
            try (Connection db = fixture.migrateConnection()) {
                db.setAutoCommit(false);
                Ui2Rows.setAuditContext(db, Ui2Rows.ACTOR, Ui2Rows.ACTION);
                try (var statement = db.createStatement()) { statement.execute("delete from devices where device_id = '" + device + "'"); }
                db.commit();
                assertEquals("0", scalar(db, "select count(*) from pan_vsys_zone_set"));
            }

            // dry run (the V64 rule): the migration applied again, in a transaction, changes nothing
            try (var resource = getClass().getResourceAsStream("/db/migration/V229__pan_vsys_zone_set.sql")) {
                assertNotNull(resource);
                String sql = new String(resource.readAllBytes(), StandardCharsets.UTF_8);
                try (var db = fixture.migrateConnection(); var statement = db.createStatement()) {
                    db.setAutoCommit(false);
                    statement.execute(sql);
                    assertEquals("2", scalar(db, "select count(*) from audit_redaction_policy where table_name='pan_vsys_zone_set'"));
                    assertEquals("1", scalar(db, "select count(*) from pg_trigger where tgrelid='pan_vsys_zone_set'::regclass and tgname='trg_audit_pan_vsys_zone_set'"));
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
