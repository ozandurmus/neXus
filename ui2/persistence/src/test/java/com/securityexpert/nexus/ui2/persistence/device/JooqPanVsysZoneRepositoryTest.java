package com.securityexpert.nexus.ui2.persistence.device;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;

/**
 * V229: the statements {@link JooqPanVsysZoneRepository} issues for a difference, over a recorded connection (the real
 * table is exercised by PanVsysZoneSetSchemaTest against PostgreSQL). Synthetic names only.
 */
class JooqPanVsysZoneRepositoryTest {

    static final Instant T1 = Instant.parse("2026-11-15T03:00:00Z");
    static final Instant T2 = Instant.parse("2026-11-15T04:00:00Z");

    /** A connection that answers the three selects of {@code replace} from the given stored rows and records every statement. */
    private static JooqPanVsysZoneRepository repository(List<String> sql, List<Object> bindings, boolean deviceExists,
            Map<String, Object[]> stored) {
        var create = DSL.using(SQLDialect.POSTGRES);
        return new JooqPanVsysZoneRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(context -> {
            sql.add(context.sql());
            bindings.addAll(Arrays.asList(context.bindings()));
            if (context.sql().startsWith("select 1 from devices")) {
                var rows = create.newResult(DSL.field("one", Integer.class));
                if (deviceExists) rows.add(create.newRecord(DSL.field("one", Integer.class)));
                return new MockResult[] {new MockResult(rows.size(), rows)};
            }
            if (context.sql().startsWith("select vsys, zone_names, read_at")) {
                Field<?>[] fields = {DSL.field("vsys", String.class), DSL.field("zone_names", String[].class), DSL.field("read_at", OffsetDateTime.class)};
                var rows = create.newResult(fields);
                stored.forEach((vsys, values) -> {
                    var row = create.newRecord(fields);
                    row.set(DSL.field("vsys", String.class), vsys);
                    row.set(DSL.field("zone_names", String[].class), (String[]) values[0]);
                    row.set(DSL.field("read_at", OffsetDateTime.class), OffsetDateTime.ofInstant((Instant) values[1], ZoneOffset.UTC));
                    rows.add(row);
                });
                return new MockResult[] {new MockResult(rows.size(), rows)};
            }
            return new MockResult[] {new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));
    }

    private static long writes(List<String> sql) {
        return sql.stream().filter(s -> s.startsWith("insert into pan_vsys_zone_set") || s.startsWith("update pan_vsys_zone_set")
                || s.startsWith("delete from pan_vsys_zone_set")).count();
    }

    @Test
    void anUnchangedReadWritesNothingAndAChangedOrNewerOneUpdatesOnlyThatRow() {
        Map<String, Object[]> stored = new java.util.LinkedHashMap<>();
        stored.put("vsys1", new Object[] {new String[] {"ZONE-A", "ZONE-B"}, T1});
        stored.put("vsys2", new Object[] {new String[] {"ZONE-C"}, T1});
        var same = List.of(new PanVsysZoneSet("d1", "vsys1", Set.of("ZONE-A", "ZONE-B"), T1), new PanVsysZoneSet("d1", "vsys2", Set.of("ZONE-C"), T1));
        List<String> sql = new ArrayList<>();
        assertEquals(0, repository(sql, new ArrayList<>(), true, stored).replace("d1", T1, same, "system:worker", "inventory_pan_zone_sets"));
        assertEquals(0, writes(sql), sql.toString());

        sql.clear();
        var changed = List.of(new PanVsysZoneSet("d1", "vsys1", Set.of("ZONE-A", "ZONE-B", "ZONE-D"), T2), new PanVsysZoneSet("d1", "vsys3", Set.of(), T2));
        List<Object> bindings = new ArrayList<>();
        assertEquals(3, repository(sql, bindings, true, stored).replace("d1", T2, changed, "system:worker", "inventory_pan_zone_sets"),
            "vsys1 updated, vsys3 inserted, vsys2 (not in the new read) deleted");
        assertEquals(3, writes(sql), sql.toString());
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("delete from pan_vsys_zone_set where device_id = ? and vsys = ?")));
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("update pan_vsys_zone_set set zone_names = ")), sql.toString());
        assertTrue(sql.stream().anyMatch(s -> s.startsWith("insert into pan_vsys_zone_set(device_id, vsys, zone_names, read_at)")), sql.toString());
        assertTrue(sql.stream().anyMatch(s -> s.contains("set local app.action_id") || s.contains("SET LOCAL app.action_id")), "audit context first: " + sql);
        assertTrue(bindings.contains("{\"ZONE-A\",\"ZONE-B\",\"ZONE-D\"}"), "names are bound sorted, as one array literal: " + bindings);
        assertTrue(bindings.contains("{}"), "a virtual system with no zone is an empty array: " + bindings);
    }

    @Test
    void aSameNamesReadWithANewerTimeOnlyMovesTheReadTime() {
        Map<String, Object[]> stored = Map.of("vsys1", new Object[] {new String[] {"ZONE-A"}, T1});
        List<String> sql = new ArrayList<>();
        assertEquals(1, repository(sql, new ArrayList<>(), true, stored).replace("d1", T2,
            List.of(new PanVsysZoneSet("d1", "vsys1", Set.of("ZONE-A"), T2)), "system:worker", "inventory_pan_zone_sets"));
        assertEquals(1, writes(sql));
    }

    @Test
    void anOlderReadAndAnUnenrolledDeviceWriteNothing() {
        Map<String, Object[]> stored = Map.of("vsys1", new Object[] {new String[] {"ZONE-A"}, T2});
        List<String> sql = new ArrayList<>();
        var older = List.of(new PanVsysZoneSet("d1", "vsys1", Set.of("ZONE-A", "ZONE-B"), T1));
        assertEquals(0, repository(sql, new ArrayList<>(), true, stored).replace("d1", T1, older, "system:worker", "inventory_pan_zone_sets"));
        assertEquals(0, writes(sql));
        sql.clear();
        assertEquals(0, repository(sql, new ArrayList<>(), false, Map.of()).replace("d1", T1, older, "system:worker", "inventory_pan_zone_sets"));
        assertEquals(0, writes(sql));
    }

    @Test
    void oneSetPerVirtualSystemOfTheNamedDeviceAndAnAuditContext() {
        var repo = repository(new ArrayList<>(), new ArrayList<>(), true, Map.of());
        var a = new PanVsysZoneSet("d1", "vsys1", Set.of("ZONE-A"), T1);
        assertThrows(IllegalArgumentException.class, () -> repo.replace("d1", T1, List.of(a, a), "system:worker", "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.replace("d2", T1, List.of(a), "system:worker", "x"));
        assertThrows(IllegalArgumentException.class, () -> repo.replace("d1", T1, List.of(a), " ", "x"), "an audited write names its actor");
    }

    @Test
    void noDevicesIsNoQuery() {
        List<String> sql = new ArrayList<>();
        assertEquals(Map.of(), repository(sql, new ArrayList<>(), true, Map.of()).findForDevices(List.of()));
        assertEquals(Map.of(), repository(sql, new ArrayList<>(), true, Map.of()).findForDevices(null));
        assertEquals(List.of(), sql);
    }

    @Test
    void aZoneSetIsSortedDistinctAndBounded() {
        var set = new PanVsysZoneSet("d1", "vsys1", new java.util.LinkedHashSet<>(List.of("ZONE-B", "ZONE-A")), T1);
        assertEquals(List.of("ZONE-A", "ZONE-B"), List.copyOf(set.zones()));
        assertThrows(UnsupportedOperationException.class, () -> set.zones().add("ZONE-C"));
        assertThrows(IllegalArgumentException.class, () -> new PanVsysZoneSet("d1", "vsys1", Set.of(" "), T1));
        assertThrows(IllegalArgumentException.class, () -> new PanVsysZoneSet("d1", "vsys1", Set.of("Z".repeat(129)), T1));
        assertThrows(IllegalArgumentException.class, () -> new PanVsysZoneSet("d1", " ", Set.of("ZONE-A"), T1));
        var many = new java.util.HashSet<String>();
        for (int i = 0; i <= PanVsysZoneSet.MAX_ZONES; i++) many.add("ZONE-" + i);
        assertThrows(IllegalArgumentException.class, () -> new PanVsysZoneSet("d1", "vsys1", many, T1));
        assertEquals(Set.of(), new PanVsysZoneSet("d1", "vsys1", Set.of(), T1).zones(), "a virtual system with no zone is a fact");
    }
}
