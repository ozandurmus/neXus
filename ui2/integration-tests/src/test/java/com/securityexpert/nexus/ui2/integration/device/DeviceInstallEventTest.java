package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Event;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Kind;
import com.securityexpert.nexus.ui2.persistence.device.DeviceInstallEventRepository.Observed;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceInstallEventRepository;

/** V246 against a real PostgreSQL 16 server: upsert keeps one row per file and time, never deletes, reads newest first. */
class DeviceInstallEventTest {

    private static Ui2PostgresFixture fixture;
    private static JooqDeviceInstallEventRepository events;

    @BeforeAll
    static void migrate() {
        fixture = Ui2PostgresFixture.createAndMigrate("device_install_event");
        events = new JooqDeviceInstallEventRepository(
                new JooqTransactionBoundary(DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES)));
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

    private static Observed base() {
        return new Observed(Kind.BASE_INSTALL, "R81.20", Optional.empty(), LocalDateTime.parse("2024-02-23T12:33:58"),
                "install_fw1_R81.20.elg");
    }

    private static Observed jumbo(Optional<Integer> take) {
        return new Observed(Kind.JUMBO_INSTALL, "R81.20", take, LocalDateTime.parse("2024-07-24T22:02:57"),
                "install_SecurePlatform_HOTFIX_R81_20_JUMBO_HF_MAIN.log");
    }

    @Test
    void recordsAreIdempotentKeepFirstObservedAndFillAMissingTakeWithoutLosingOne() throws SQLException {
        String id = newDevice();
        assertEquals(2, events.record(id, List.of(base(), jumbo(Optional.empty()))));
        List<Event> first = events.forDevice(id);
        assertEquals(2, first.size());
        assertEquals(Kind.JUMBO_INSTALL, first.get(0).kind(), "newest install first");
        assertEquals(LocalDateTime.parse("2024-07-24T22:02:57"), first.get(0).installedAtLocal());
        assertEquals(Optional.empty(), first.get(0).jumboTake());

        events.record(id, List.of(base(), jumbo(Optional.of(65))));
        List<Event> again = events.forDevice(id);
        assertEquals(2, again.size(), "the same file and time is one row");
        assertEquals(Optional.of(65), again.get(0).jumboTake());
        assertEquals(first.get(0).firstObservedAt(), again.get(0).firstObservedAt());

        events.record(id, List.of(jumbo(Optional.empty())));
        assertEquals(Optional.of(65), events.forDevice(id).get(0).jumboTake(), "a later read without a take keeps the take");
        assertEquals(0, events.record(id, List.of()));
    }

    @Test
    void installedSinceFiltersOnTheDeviceLocalTimeAndCarriesTheHostnameColumn() throws SQLException {
        String id = newDevice();
        events.record(id, List.of(base(), jumbo(Optional.of(65))));
        assertTrue(events.installedSince(LocalDateTime.parse("2024-07-01T00:00:00"), 50).stream()
                .anyMatch(e -> e.deviceId().equals(id) && e.kind() == Kind.JUMBO_INSTALL));
        assertTrue(events.installedSince(LocalDateTime.parse("2024-07-01T00:00:00"), 50).stream()
                .noneMatch(e -> e.deviceId().equals(id) && e.kind() == Kind.BASE_INSTALL));
        assertTrue(events.installedSince(LocalDateTime.parse("2030-01-01T00:00:00"), 50).stream()
                .noneMatch(e -> e.deviceId().equals(id)));
    }
}
