package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSoftwareHistoryRepository.Stretch;
import com.securityexpert.nexus.ui2.persistence.device.JooqDevicePlatformFactsRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceSoftwareHistoryRepository;

/**
 * V243 against a real PostgreSQL 16 server: change detection in the observation writers (same version bumps
 * last_seen_at, a new version or a hotfix-only change opens a stretch with the window start) and the discovery
 * backfill (consecutive identical versions collapse, a changed version opens a stretch with its window).
 */
class SoftwareVersionHistoryTest {

    private static Ui2PostgresFixture fixture;
    private static JooqDeviceRepository devices;
    private static JooqDevicePlatformFactsRepository facts;
    private static JooqDeviceSoftwareHistoryRepository history;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("software_version_history");
        var boundary = new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        devices = new JooqDeviceRepository(boundary);
        facts = new JooqDevicePlatformFactsRepository(boundary);
        history = new JooqDeviceSoftwareHistoryRepository(boundary);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) fixture.close();
    }

    private static String newDevice() throws SQLException {
        try (Connection app = fixture.appConnection()) {
            return Ui2Rows.insertDevice(app, Ui2Rows.insertCredentialReference(app), "ENROLLED");
        }
    }

    private static void refresh(String deviceId, String version) {
        devices.refreshObservedFacts(deviceId, Optional.empty(), Optional.empty(), Optional.of(version),
                Ui2Rows.ACTOR, "inventory_refresh");
    }

    private static void hotfix(String deviceId, String level) {
        facts.record(new DevicePlatformFacts(deviceId, Optional.empty(), Optional.of(level), Optional.empty(), Map.of(),
                Optional.empty(), "synthetic_read", Optional.empty()));
    }

    @Test
    void sameVersionBumpsLastSeenAndNewVersionOpensAStretchWithTheWindowStart() throws SQLException {
        String id = newDevice();
        refresh(id, "R81.20");
        List<Stretch> first = history.forDevice(id);
        assertEquals(1, first.size());
        assertEquals("R81.20", first.get(0).softwareVersion());
        assertTrue(first.get(0).previousSoftwareVersion().isEmpty());
        assertTrue(!first.get(0).isChange());

        refresh(id, "R81.20");
        List<Stretch> again = history.forDevice(id);
        assertEquals(1, again.size(), "an unchanged version must not open a stretch");
        assertTrue(!again.get(0).lastSeenAt().isBefore(first.get(0).lastSeenAt()));

        refresh(id, "R82");
        List<Stretch> changed = history.forDevice(id);
        assertEquals(2, changed.size());
        Stretch current = changed.get(0);
        assertEquals("R82", current.softwareVersion());
        assertEquals(Optional.of("R81.20"), current.previousSoftwareVersion());
        assertTrue(current.previousLastSeenAt().isPresent());
        assertTrue(!current.previousLastSeenAt().get().isAfter(current.firstSeenAt()));
        assertTrue(current.isChange());
        assertEquals("R81.20", changed.get(1).softwareVersion());
        assertTrue(history.changesSince(Instant.now().minusSeconds(3600), 50).stream().anyMatch(s -> s.deviceId().equals(id)));
    }

    @Test
    void firstHotfixFillsTheStretchButAHotfixOnlyChangeOpensANewOne() throws SQLException {
        String id = newDevice();
        refresh(id, "R81.20");
        hotfix(id, "Jumbo Take 90");
        List<Stretch> filled = history.forDevice(id);
        assertEquals(1, filled.size(), "the first hotfix read fills the open stretch, it is not a change");
        assertEquals(Optional.of("Jumbo Take 90"), filled.get(0).hotfixLevel());

        hotfix(id, "Jumbo Take 90");
        assertEquals(1, history.forDevice(id).size());

        hotfix(id, "Jumbo Take 99");
        List<Stretch> after = history.forDevice(id);
        assertEquals(2, after.size());
        assertEquals("R81.20", after.get(0).softwareVersion());
        assertEquals(Optional.of("Jumbo Take 99"), after.get(0).hotfixLevel());
        assertEquals(Optional.of("Jumbo Take 90"), after.get(0).previousHotfixLevel());
    }

    @Test
    void clearingTheObservedVersionRecordsNothing() throws SQLException {
        String id = newDevice();
        refresh(id, "R81.20");
        assertTrue(devices.clearObservedPlatformFacts(id, Ui2Rows.ACTOR, "clear"));
        assertEquals(1, history.forDevice(id).size());
    }

    @Test
    void discoveryBackfillCollapsesConsecutiveVersionsIntoStretchesWithWindows() throws SQLException, IOException {
        String id = newDevice();
        String key = "synthetic_vendor|" + Ui2Rows.opaqueId("stable");
        String stable = key.substring(key.indexOf('|') + 1);
        try (Connection app = fixture.appConnection()) {
            String credential = Ui2Rows.insertCredentialReference(app);
            app.setAutoCommit(false);
            Ui2Rows.setAuditContext(app, Ui2Rows.ACTOR, Ui2Rows.ACTION);
            try (Statement statement = app.createStatement()) {
                statement.execute("UPDATE devices SET discovery_match_key = '" + key + "' WHERE device_id = '" + id + "'");
                String[][] runs = { {"r1", "2026-09-01T00:00:00Z", "10.1"}, {"r2", "2026-09-02T00:00:00Z", "10.1"},
                        {"r3", "2026-09-10T00:00:00Z", "10.2"}, {"r4", "2026-09-11T00:00:00Z", "10.2"} };
                for (String[] run : runs) {
                    String runId = id + "-" + run[0];
                    statement.execute("INSERT INTO discovery_run(run_id, vendor, management_address, credential_reference_id, "
                            + "requested_by_actor_fingerprint, state, finished_at) VALUES ('" + runId + "', 'synthetic_vendor', "
                            + "'synthetic-address', '" + credential + "', 'synthetic-actor', 'FINISHED', '" + run[1] + "')");
                    statement.execute("INSERT INTO discovery_candidate(candidate_id, run_id, vendor, stable_identifier, kind, "
                            + "software_version, importable) VALUES ('" + runId + "-c', '" + runId + "', 'synthetic_vendor', '"
                            + stable + "', 'firewall', '" + run[2] + "', true)");
                }
                statement.execute("DELETE FROM device_software_version_history WHERE device_id = '" + id + "'");
                String sql = Files.readString(Ui2PostgresFixture.migrationDirectory().resolve("V243__device_software_version_history.sql"));
                String backfill = sql.substring(sql.indexOf("INSERT INTO device_software_version_history", sql.indexOf("-- Backfill 1")), sql.indexOf("-- Backfill 2"));
                statement.execute(backfill);
            }
            app.commit();
        }
        List<Stretch> rows = history.forDevice(id);
        assertEquals(2, rows.size(), "two runs per version collapse into one stretch each");
        assertEquals("10.2", rows.get(0).softwareVersion());
        assertEquals(Instant.parse("2026-09-10T00:00:00Z"), rows.get(0).firstSeenAt());
        assertEquals(Instant.parse("2026-09-11T00:00:00Z"), rows.get(0).lastSeenAt());
        assertEquals(Optional.of("10.1"), rows.get(0).previousSoftwareVersion());
        assertEquals(Optional.of(Instant.parse("2026-09-02T00:00:00Z")), rows.get(0).previousLastSeenAt());
        assertEquals("discovery_backfill", rows.get(0).source());
        assertEquals("10.1", rows.get(1).softwareVersion());
        assertTrue(rows.get(1).previousSoftwareVersion().isEmpty());
    }
}
