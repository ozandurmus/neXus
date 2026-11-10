package com.securityexpert.nexus.ui2.persistence.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LivePatchItem;

/**
 * Statement-level tests in the style of {@link JooqLifecycleCatalogRepositoryTest}: no PostgreSQL is available to the
 * persistence tests, so the statements and their bindings are captured with a mock connection. They prove which
 * statements run for which outcome; the V200 CHECK constraints themselves are exercised only on a real database (see the
 * worker's {@code LifecycleParsersRobustnessTest} for the parsers' agreement with the V200 patterns).
 */
class JooqDeviceLifecycleRepositoryTest {

    private record Statement(String sql, List<Object> bindings) { }

    private static JooqDeviceLifecycleRepository repository(List<Statement> statements) {
        return new JooqDeviceLifecycleRepository(new JooqTransactionBoundary(DSL.using(new MockConnection(ctx -> {
            statements.add(new Statement(ctx.sql(), new ArrayList<>(Arrays.asList(ctx.bindings()))));
            return new MockResult[] {new MockResult(1, null)};
        }), SQLDialect.POSTGRES)));
    }

    private static List<Statement> data(List<Statement> all) {
        return all.stream().filter(s -> s.sql().contains("device_license_item") || s.sql().contains("device_live_patch_item")
                || s.sql().contains("device_lifecycle_read")).toList();
    }

    private static LicenseItem item(String label) {
        return new LicenseItem("cp_cplic_print", "LICENSE", label, "VALID", LocalDate.of(2027, 1, 5), "2 features", null,
                "DIRECT_DEVICE", null);
    }

    @Test
    void aCollectedLicenseReadReplacesTheItemsOfTheDeviceAndKindThenRecordsTheRead() {
        List<Statement> all = new ArrayList<>();
        repository(all).recordLicenseRead("dev-1", "cp_cplic_print", "COLLECTED", null, List.of(item("CPSB-FW"), item("CPSB-AV")));
        List<Statement> sql = data(all);
        assertEquals(4, sql.size(), sql.toString());
        assertTrue(sql.get(0).sql().startsWith("delete from device_license_item where device_id"));
        assertEquals(List.of("dev-1", "cp_cplic_print"), sql.get(0).bindings());
        assertTrue(sql.get(1).sql().startsWith("insert into device_license_item"));
        assertTrue(sql.get(1).bindings().contains("CPSB-FW"));
        assertTrue(sql.get(1).bindings().contains("DIRECT_DEVICE"));
        assertTrue(sql.get(2).bindings().contains("CPSB-AV"));
        assertTrue(sql.get(3).sql().contains("insert into device_lifecycle_read"));
        assertTrue(sql.get(3).sql().contains("on conflict (device_id, read_kind) do update"));
        assertEquals(List.of("dev-1", "cp_cplic_print", "COLLECTED"), sql.get(3).bindings().subList(0, 3));
        assertTrue(sql.get(3).sql().contains("(select count(*) from device_license_item where device_id"),
                "the read row counts the rows that are stored after the write");
    }

    @Test
    void aCollectedReadWithNoItemsStillClearsTheKindAndRecordsZero() {
        List<Statement> all = new ArrayList<>();
        repository(all).recordLicenseRead("dev-1", "pan_license_info", "COLLECTED", null, List.of());
        List<Statement> sql = data(all);
        assertEquals(2, sql.size());
        assertTrue(sql.get(0).sql().startsWith("delete from device_license_item"));
        assertTrue(sql.get(1).sql().contains("(select count(*) from device_license_item"));
    }

    @Test
    void anyOtherOutcomeKeepsTheStoredItemsAndOnlyUpdatesTheReadRow() {
        for (String outcome : List.of("FAILED", "REFUSED", "UNPARSED", "EMPTY", "SKIPPED")) {
            List<Statement> all = new ArrayList<>();
            repository(all).recordLicenseRead("dev-1", "asa_show_license_all", outcome, "TimedOut", List.of(item("ignored")));
            List<Statement> sql = data(all);
            assertEquals(1, sql.size(), outcome + ": " + sql);
            assertFalse(sql.get(0).sql().contains("delete"), outcome);
            assertTrue(sql.get(0).sql().startsWith("insert into device_lifecycle_read"), outcome);
            assertFalse(sql.get(0).sql().contains("insert into device_license_item"), outcome);
            // The count is read from the stored rows, so a failed read does not report zero while the last answer is kept.
            assertTrue(sql.get(0).sql().contains("(select count(*) from device_license_item"), outcome);
            assertEquals(List.of("dev-1", "asa_show_license_all", outcome, "TimedOut"), sql.get(0).bindings().subList(0, 4), outcome);
        }
    }

    @Test
    void anAnswerThatNamesOneMemberReplacesOnlyThatMembersRows() {
        List<Statement> all = new ArrayList<>();
        LicenseItem a = new LicenseItem("mc_device_license", "LICENSE", "SGOS", "VALID", LocalDate.of(2027, 1, 5), null, "FW-TANGO-04",
                "MANAGER_REPORTED", null);
        LicenseItem b = new LicenseItem("mc_device_license", "LICENSE", "Web Filter", "VALID", LocalDate.of(2027, 1, 5), null,
                "FW-TANGO-04", "MANAGER_REPORTED", null);
        repository(all).recordLicenseRead("dev-mc", "mc_device_license", "COLLECTED", null, List.of(a, b));
        List<Statement> sql = data(all);
        assertTrue(sql.get(0).sql().startsWith("delete from device_license_item where device_id"));
        assertTrue(sql.get(0).sql().contains("member_ref"));
        assertEquals(List.of("dev-mc", "mc_device_license", "FW-TANGO-04"), sql.get(0).bindings());

        // Items naming two members (or none) replace the whole kind.
        List<Statement> mixed = new ArrayList<>();
        LicenseItem other = new LicenseItem("mc_device_license", "LICENSE", "SGOS", "VALID", null, null, "FW-BRAVO-02", "MANAGER_REPORTED", null);
        repository(mixed).recordLicenseRead("dev-mc", "mc_device_license", "COLLECTED", null, List.of(a, other));
        assertFalse(data(mixed).get(0).sql().contains("member_ref"));
        assertEquals(List.of("dev-mc", "mc_device_license"), data(mixed).get(0).bindings());
    }

    @Test
    void aCollectedLivePatchReadReplacesTheDevicesRows() {
        List<Statement> all = new ArrayList<>();
        LivePatchItem patch = new LivePatchItem("sk1:cpd", "armed", "automatic", 1, 1, LocalDateTime.of(2026, 9, 12, 10, 0, 1),
                List.of("sk1", "CVE-2026-1234"), null);
        repository(all).recordLivePatchRead("dev-1", "cp_cplp_list", "COLLECTED", null, List.of(patch));
        List<Statement> sql = data(all);
        assertEquals(3, sql.size(), sql.toString());
        assertTrue(sql.get(0).sql().startsWith("delete from device_live_patch_item where device_id"));
        assertEquals(List.of("dev-1"), sql.get(0).bindings());
        assertTrue(sql.get(1).sql().contains("insert into device_live_patch_item"));
        assertTrue(sql.get(1).sql().contains("on conflict (device_id, patch_id) do update"));
        assertTrue(sql.get(1).bindings().contains("sk1:cpd"));
        assertTrue(sql.get(2).sql().contains("device_lifecycle_read"));
        assertTrue(sql.get(2).sql().contains("(select count(*) from device_live_patch_item where device_id"));
    }

    @Test
    void aFailedLivePatchReadKeepsTheRows() {
        List<Statement> all = new ArrayList<>();
        repository(all).recordLivePatchRead("dev-1", "cp_cplp_list", "FAILED", "TimedOut", List.of());
        List<Statement> sql = data(all);
        assertEquals(1, sql.size());
        assertTrue(sql.get(0).sql().contains("device_lifecycle_read"));
        assertEquals("FAILED", sql.get(0).bindings().get(2));
    }

    @Test
    void nullItemsAreTreatedAsNone() {
        List<Statement> all = new ArrayList<>();
        repository(all).recordLicenseRead("dev-1", "cp_cplic_print", "COLLECTED", null, null);
        repository(all).recordLivePatchRead("dev-1", "cp_cplp_list", "COLLECTED", null, null);
        assertEquals(4, data(all).size());
    }
}
