package com.securityexpert.nexus.ui2.integration.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory;
import com.securityexpert.nexus.ui2.persistence.snmp.JooqSnmpPollingRepository;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Outcome;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.PollResult;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;

/**
 * V268/V269 on a real database, as the application role: defaults (fleet switch OFF), constraints, audit of settings
 * writes, the nine SNMP_V3 read gates, and the repository's claim, record, rollup, prune and health paths.
 */
class SnmpDeviceMetricsSchemaTest {
    private static Ui2PostgresFixture fixture;
    private static JooqSnmpPollingRepository repository;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.create("snmp_device_metrics");
        fixture.runFlyway();
        TransactionBoundary tx = TransactionBoundaryFactory.fromDataSource(fixture.appDataSource());
        repository = new JooqSnmpPollingRepository(tx);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    private static String pilotDevice(Connection app, String vendor, String address) throws SQLException {
        String credential = Ui2Rows.insertCredentialReference(app);
        String device = Ui2Rows.insertDevice(app, credential, "ENROLLED");
        Ui2Rows.insertEndpoint(app, device);
        app.setAutoCommit(false);
        try (Statement s = app.createStatement()) {
            Ui2Rows.setAuditContext(app, "fixture-actor", "fixture_snmp");
            s.execute("update devices set vendor_hint = '" + vendor + "' where device_id = '" + device + "'");
            s.execute("update endpoints set address_ref = '" + address + "' where device_id = '" + device + "'");
            app.commit();
        } finally {
            app.setAutoCommit(true);
        }
        return device;
    }

    @Test
    void defaultsAreSwitchOffFiveMinutesThirtyFiveAndFourHundredDaysAndBothPilotVendors() {
        var c = repository.read();
        assertFalse(c.settings().enabled());
        assertEquals(300, c.settings().intervalS());
        assertEquals("VENDORS", c.settings().scopeMode());
        assertEquals(35, c.settings().rawRetentionDays());
        assertEquals(400, c.settings().hourlyRetentionDays());
        assertNull(c.settings().defaultCredentialReferenceId());
        assertEquals(List.of(Override.vendor("check_point", true, null), Override.vendor("palo_alto", true, null)),
                c.overrides());
    }

    @Test
    void theNineSnmpGatesAreSeededAsSignedOffReads() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            assertEquals(9, Ui2Rows.count(app, "select count(*) from gate_registry where transport_kind = 'SNMP_V3' "
                    + "and action_class = 'read' and sign_off_state = 'SIGNED_OFF' and timeout_s = 3"));
            assertEquals(0, Ui2Rows.count(app, "select count(*) from gate_registry where transport_kind = 'SNMP_V3' "
                    + "and action_class <> 'read'"));
        }
    }

    @Test
    void constraintsRefuseBadSettingsOverridesAndSamples() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = pilotDevice(app, "check_point", "192.0.2.30");
            for (String bad : List.of(
                    "update snmp_polling_settings set interval_s = 30",
                    "update snmp_polling_settings set interval_s = 7200",
                    "update snmp_polling_settings set scope_mode = 'ALL'",
                    "update snmp_polling_settings set default_credential_reference_id = 'no-such-reference'",
                    "insert into snmp_polling_override(override_key, scope_kind, vendor, in_scope) "
                            + "values ('vendor:cisco_asa', 'VENDOR', 'cisco_asa', true)",
                    "insert into snmp_polling_override(override_key, scope_kind, vendor, device_id, in_scope) "
                            + "values ('vendor:x', 'VENDOR', 'check_point', '" + device + "', true)",
                    "insert into device_metric_sample(device_id, sampled_at, outcome, cpu_pct) "
                            + "values ('" + device + "', now(), 'SNMP_NO_ANSWER', 12)",
                    "insert into device_metric_sample(device_id, sampled_at, outcome, cpu_pct) "
                            + "values ('" + device + "', now(), 'OK', 101)",
                    "insert into device_metric_sample(device_id, sampled_at, outcome, source) "
                            + "values ('" + device + "', now(), 'OK', 'SSH')")) {
                app.setAutoCommit(false);
                try {
                    Ui2Rows.setAuditContext(app, "fixture-actor", "fixture_snmp");
                    assertThrows(SQLException.class, () -> {
                        try (Statement s = app.createStatement()) {
                            s.execute(bad);
                        }
                    }, bad);
                } finally {
                    app.rollback();
                    app.setAutoCommit(true);
                }
            }
        }
    }

    @Test
    void aSettingsWriteIsAuditedAndTheNamedCredentialCannotBeDeleted() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(app);
            String device = pilotDevice(app, "palo_alto", "192.0.2.31");
            var stored = repository.write("fixture-admin", new Settings(false, 600, credential, "VENDORS", 35, 400),
                    List.of(Override.vendor("check_point", false, null), Override.vendor("palo_alto", true, credential),
                            Override.device(device, false, null)));
            assertEquals(600, stored.settings().intervalS());
            assertEquals("fixture-admin", stored.updatedBy());
            assertEquals(3, stored.overrides().size());
            assertTrue(Ui2Rows.count(app, "select count(*) from audit_log where table_name = 'snmp_polling_settings' "
                    + "and action_id = 'snmp_polling_settings_write' and actor_fingerprint = 'fixture-admin'") >= 1);
            assertEquals(1, Ui2Rows.count(app, "select count(*) from audit_log where table_name = 'snmp_polling_override' "
                    + "and row_pk = 'device:" + device + "' and operation = 'INSERT'"));
            app.setAutoCommit(false);
            try {
                Ui2Rows.setAuditContext(app, "fixture-actor", "fixture_snmp");
                assertThrows(SQLException.class, () -> {
                    try (Statement s = app.createStatement()) {
                        s.execute("delete from credential_references where credential_reference_id = '" + credential + "'");
                    }
                });
            } finally {
                app.rollback();
                app.setAutoCommit(true);
            }
            // Back to the defaults; the device row is removed by the replace.
            repository.write("fixture-admin", Settings.DEFAULTS, List.of(Override.vendor("check_point", true, null),
                    Override.vendor("palo_alto", true, null)));
            assertEquals(0, Ui2Rows.count(app, "select count(*) from snmp_polling_override where scope_kind = 'DEVICE'"));
        }
    }

    @Test
    void claimRecordRollupPruneAndHealthWorkEndToEnd() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = pilotDevice(app, "check_point", "192.0.2.32");
            assertTrue(repository.eligibleDevices().stream().anyMatch(d -> d.deviceId().equals(device)
                    && d.vendor().equals("check_point") && d.addressRef().equals("192.0.2.32") && d.nextPollAt() == null));
            Instant hour = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(1));
            repository.schedule(device, hour);
            repository.schedule(device, hour.plusSeconds(99));
            var d = repository.eligibleDevices().stream().filter(x -> x.deviceId().equals(device)).findFirst().orElseThrow();
            assertEquals(hour, d.nextPollAt(), "a second schedule does not move the first");
            assertTrue(repository.claim(device, hour, hour.plusSeconds(1800)));
            assertFalse(repository.claim(device, hour, hour.plusSeconds(1800)), "a claim is taken once");

            for (int i = 0; i < 12; i++) {
                Instant at = hour.plusSeconds(300L * i);
                repository.record(new PollResult(device, at, Outcome.OK, new Metrics(1000L + i,
                        BigDecimal.valueOf(10 + i), null, BigDecimal.valueOf(50), 100L + i), false, 0, at.plusSeconds(300)));
            }
            repository.record(new PollResult(device, hour.plusSeconds(3599), Outcome.SNMP_NO_ANSWER,
                    new Metrics(1L, BigDecimal.ONE, null, null, null), false, 1, hour.plusSeconds(3899)));
            assertEquals(0, Ui2Rows.count(app, "select count(*) from device_metric_sample where device_id = '" + device
                    + "' and outcome = 'SNMP_NO_ANSWER' and cpu_pct is not null"), "no answer stores nulls");
            assertEquals(1, Ui2Rows.count(app, "select consecutive_misses from snmp_poll_state where device_id = '"
                    + device + "'"));
            assertEquals(1011, Ui2Rows.count(app, "select last_sys_uptime_cs from snmp_poll_state where device_id = '"
                    + device + "'"), "a poll without an uptime keeps the last one");

            assertTrue(repository.rollup(hour, hour.plus(Duration.ofHours(1))) >= 1);
            assertEquals(13, Ui2Rows.count(app, "select samples from device_metric_hourly where device_id = '" + device + "'"));
            assertEquals(12, Ui2Rows.count(app, "select answered from device_metric_hourly where device_id = '" + device + "'"));
            assertEquals(21, Ui2Rows.count(app, "select cpu_max::int from device_metric_hourly where device_id = '" + device + "'"));
            assertEquals(21, Ui2Rows.count(app, "select cpu_p95::int from device_metric_hourly where device_id = '" + device + "'"));

            var health = repository.health(hour.minus(Duration.ofDays(7))).get(device);
            assertEquals(13, health.samples());
            assertEquals(12, health.answered());
            assertEquals(0, new BigDecimal("21").compareTo(health.cpuP95()));
            assertEquals(0, new BigDecimal("50").compareTo(health.memP95()));
            assertEquals(111L, health.sessionsMax());
            assertEquals("SNMP_NO_ANSWER", health.lastOutcome().orElseThrow());

            int[] pruned = repository.prune(hour.plus(Duration.ofHours(2)), hour.plus(Duration.ofHours(2)));
            // Other tests in this class store samples too; this device's 13 are among those pruned and none is left.
            assertTrue(pruned[0] >= 13);
            assertTrue(pruned[1] >= 1);
            assertEquals(0, Ui2Rows.count(app, "select count(*) from device_metric_sample where device_id = '" + device + "'"));
        }
    }

    @Test
    void dailyFiguresAreNearestRankPerLocalDayFromAnsweredPollsOnly() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = pilotDevice(app, "palo_alto", "192.0.2.33");
            // 23:30 and 00:30 Europe/Istanbul (UTC+3) fall on two local days although both are one UTC day.
            Instant late = Instant.parse("2026-11-10T20:30:00Z");
            Instant early = Instant.parse("2026-11-10T21:30:00Z");
            repository.record(new PollResult(device, late, Outcome.OK, new Metrics(1L, BigDecimal.valueOf(20),
                    BigDecimal.valueOf(70), BigDecimal.valueOf(40), 10L), false, 0, late.plusSeconds(300)));
            repository.record(new PollResult(device, late.plusSeconds(300), Outcome.SNMP_NO_ANSWER, Metrics.NONE, false, 1,
                    late.plusSeconds(600)));
            repository.record(new PollResult(device, early, Outcome.PARTIAL, new Metrics(2L, BigDecimal.valueOf(30), null,
                    null, 11L), false, 0, early.plusSeconds(300)));
            var days = repository.daily(late.minusSeconds(3600), "Europe/Istanbul").get(device);
            assertEquals(2, days.size());
            assertEquals(java.time.LocalDate.of(2026, 11, 10), days.get(0).day());
            assertEquals(2, days.get(0).samples());
            assertEquals(1, days.get(0).answered());
            assertEquals(0, new BigDecimal("20").compareTo(days.get(0).cpuP95()));
            assertEquals(0, new BigDecimal("70").compareTo(days.get(0).cpuDataplaneP95()));
            assertEquals(java.time.LocalDate.of(2026, 11, 11), days.get(1).day());
            assertNull(days.get(1).memP95(), "a day without a memory answer has no memory figure");
            assertThrows(java.time.DateTimeException.class, () -> repository.daily(late, "Nowhere/Invalid"));
            assertEquals(Map.of("PARTIAL", 1), repository.lastOutcomeCounts(early.minusSeconds(1)).entrySet().stream()
                    .filter(e -> e.getKey().equals("PARTIAL")).collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
                            Map.Entry::getValue)));
            assertTrue(repository.lastOutcomeCounts(Instant.now().plusSeconds(60)).isEmpty(), "older polls are not counted");
        }
    }

    @Test
    void aCredentialNamedBySnmpPollingIsInUseForTheCredentialStore() throws SQLException {
        var credentials = new com.securityexpert.nexus.ui2.persistence.credential.JooqCredentialRepository(
                TransactionBoundaryFactory.fromDataSource(fixture.appDataSource()));
        try (Connection app = fixture.appConnection()) {
            String fleet = Ui2Rows.insertCredentialReference(app);
            String vendor = Ui2Rows.insertCredentialReference(app);
            String unused = Ui2Rows.insertCredentialReference(app);
            assertFalse(credentials.isCredentialReferenceInUse(fleet));
            repository.write("fixture-admin", new Settings(false, 300, fleet, "VENDORS", 35, 400),
                    List.of(Override.vendor("check_point", true, vendor), Override.vendor("palo_alto", true, null)));
            assertTrue(credentials.isCredentialReferenceInUse(fleet), "the fleet default");
            assertTrue(credentials.isCredentialReferenceInUse(vendor), "a vendor row");
            assertFalse(credentials.isCredentialReferenceInUse(unused));
            repository.write("fixture-admin", Settings.DEFAULTS, List.of(Override.vendor("check_point", true, null),
                    Override.vendor("palo_alto", true, null)));
            assertFalse(credentials.isCredentialReferenceInUse(fleet));
        }
    }

    @Test
    void pruneDeletesInBoundedBatchesAndLeavesNewerRows() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            String device = pilotDevice(app, "check_point", "192.0.2.34");
            int old = JooqSnmpPollingRepository.PRUNE_BATCH_ROWS * 2 + 7;
            try (Statement s = app.createStatement()) {
                s.execute("insert into device_metric_sample(device_id, sampled_at, outcome) select '" + device + "', "
                        + "timestamptz '2025-01-01 00:00:00+00' + make_interval(secs => g), 'SNMP_NO_ANSWER' "
                        + "from generate_series(1, " + old + ") g");
                s.execute("insert into device_metric_sample(device_id, sampled_at, outcome) values ('" + device
                        + "', now(), 'SNMP_NO_ANSWER')");
            }
            int[] pruned = repository.prune(Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2000-01-01T00:00:00Z"));
            assertEquals(old, pruned[0], "three batches: two full and one short");
            assertEquals(0, pruned[1]);
            assertEquals(1, Ui2Rows.count(app, "select count(*) from device_metric_sample where device_id = '" + device + "'"));
        }
    }
}
