package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.integration.support.Ui2PostgresFixture;
import com.securityexpert.nexus.ui2.integration.support.Ui2Rows;
import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.device.DeviceListEntry;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceReader;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceView;
import com.securityexpert.nexus.ui2.service.device.EnrollmentStateView;
import com.securityexpert.nexus.ui2.service.device.TransportSummary;

/**
 * {@code UI2_0_B1_09_DEVICE_WORKSPACE_CONTRACT.md} §8: the read-model
 * proofs that do not require simulating a role. Tests 8, 9, 10 by number,
 * plus the §3/§4.2 rendering rules tests 1-2 assume rather than restate.
 * Tests 1, 2 and 11 (the injected leak/gate proofs) live in their own
 * classes.
 */
class DeviceWorkspaceReaderTest {

    private static Ui2PostgresFixture fixture;
    private static DeviceRepository deviceRepository;
    private static DeviceWorkspaceReader reader;
    private static String credentialReferenceId;

    @BeforeAll
    static void migrate() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("device_workspace_reader");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        deviceRepository = new JooqDeviceRepository(transactionBoundary);
        reader = new DeviceWorkspaceReader(fixture.appDataSource());
        try (Connection app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void workspaceRendersIdentityTransportAndEnrollmentForARegisteredDevice() {
        String deviceId = DeviceWorkspaceTestRows.opaqueId("dev");
        String endpointId = DeviceWorkspaceTestRows.opaqueId("ep");
        deviceRepository.registerDraft(
                new DeviceDraft(deviceId, "harness-vendor", "manual_registration", true, credentialReferenceId,
                        endpointId, "ssh_exec", "opaque-address-ref"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);

        DeviceWorkspaceView view = reader.readWorkspace(deviceId).orElseThrow();

        assertEquals(deviceId, view.deviceId());
        assertEquals("harness-vendor", view.vendorHint());
        assertEquals("manual_registration", view.registrationSource());
        assertTrue(view.isTestTarget());
        assertTrue(view.credentialConfigured());
        assertEquals(EnrollmentStateView.DRAFT, view.enrollmentState());
        assertFalse(view.disabled());
        assertEquals(TransportSummary.Presence.PRESENT, view.transport().presence());
        assertEquals(1, view.transport().transports().size());
        assertEquals(endpointId, view.transport().transports().get(0).endpointId());
        assertEquals("ssh_exec", view.transport().transports().get(0).transportKind());
    }

    @Test
    void aDeviceWithNoEndpointRendersTransportMissingNotAnEmptyAddress() throws SQLException {
        String deviceId = DeviceWorkspaceTestRows.opaqueId("dev");
        String endpointId = DeviceWorkspaceTestRows.opaqueId("ep");
        deviceRepository.registerDraft(
                new DeviceDraft(deviceId, "harness-vendor", "manual_registration", true, credentialReferenceId,
                        endpointId, "ssh_exec", "opaque-address-ref"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);
        try (Connection app = fixture.appConnection()) {
            DeviceWorkspaceTestRows.deleteEndpoint(app, endpointId);
        }

        DeviceWorkspaceView view = reader.readWorkspace(deviceId).orElseThrow();

        assertEquals(TransportSummary.Presence.MISSING, view.transport().presence());
        assertTrue(view.transport().transports().isEmpty());
    }

    @Test
    void unknownDeviceIdReadsEmpty() {
        assertEquals(Optional.empty(), reader.readWorkspace("no-such-device-" + DeviceWorkspaceTestRows.opaqueId("x")));
    }

    @Test
    void listDevicesOrdersNewestFirst() throws InterruptedException {
        String first = DeviceWorkspaceTestRows.opaqueId("dev-a");
        deviceRepository.registerDraft(new DeviceDraft(first, "v", "manual_registration", true,
                credentialReferenceId, DeviceWorkspaceTestRows.opaqueId("ep"), "ssh_exec", "addr-a"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);
        Thread.sleep(5);
        String second = DeviceWorkspaceTestRows.opaqueId("dev-b");
        deviceRepository.registerDraft(new DeviceDraft(second, "v", "manual_registration", true,
                credentialReferenceId, DeviceWorkspaceTestRows.opaqueId("ep"), "ssh_exec", "addr-b"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);

        List<DeviceListEntry> entries = reader.listDevices();
        int firstIndex = indexOf(entries, first);
        int secondIndex = indexOf(entries, second);
        assertTrue(secondIndex < firstIndex, "the more recently registered device must sort first");
    }

    @Test
    void disabledEnrolledDeviceRendersBothFactsIndependently() {
        String deviceId = DeviceWorkspaceTestRows.opaqueId("dev");
        String endpointId = DeviceWorkspaceTestRows.opaqueId("ep");
        deviceRepository.registerDraft(new DeviceDraft(deviceId, "v", "manual_registration", true,
                credentialReferenceId, endpointId, "ssh_exec", "addr"), Ui2Rows.ACTOR, Ui2Rows.ACTION);
        assertTrue(deviceRepository.transitionEnrollmentState(deviceId, DeviceEnrollmentState.DRAFT,
                DeviceEnrollmentState.ENROLLED, Ui2Rows.ACTOR, Ui2Rows.ACTION));
        assertTrue(deviceRepository.setDisabled(deviceId, true, Ui2Rows.ACTOR, Ui2Rows.ACTION));

        DeviceWorkspaceView view = reader.readWorkspace(deviceId).orElseThrow();

        assertEquals(EnrollmentStateView.ENROLLED, view.enrollmentState());
        assertTrue(view.disabled());
    }

    @Test
    void anOutOfVocabularyEnrollmentStateFailsClosedToNotEvaluable() throws SQLException {
        String deviceId = DeviceWorkspaceTestRows.opaqueId("dev");
        deviceRepository.registerDraft(new DeviceDraft(deviceId, "v", "manual_registration", true,
                credentialReferenceId, DeviceWorkspaceTestRows.opaqueId("ep"), "ssh_exec", "addr"),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);
        // Dropping the CHECK constraint is DDL: only ui2_migrate (the
        // database owner) may run it, never ui2_app.
        try (Connection migrate = fixture.migrateConnection()) {
            DeviceWorkspaceTestRows.forceOutOfVocabularyEnrollmentState(migrate, deviceId, "SOMETHING_ELSE");
        }

        DeviceWorkspaceView view = reader.readWorkspace(deviceId).orElseThrow();

        assertEquals(EnrollmentStateView.NOT_EVALUABLE, view.enrollmentState());
        assertFalse(view.enrollmentState().permitsDeviceScopedActionEligibility());

        List<DeviceListEntry> entries = reader.listDevices();
        DeviceListEntry entry = entries.stream().filter(e -> e.deviceId().equals(deviceId)).findFirst().orElseThrow();
        assertEquals(EnrollmentStateView.NOT_EVALUABLE, entry.enrollmentState(),
                "an unrecognized value must never be hidden from the list, and never defaulted to ENROLLED");
    }

    /**
     * Test 10 ({@code NoFreshnessClaimWithoutFreshnessColumn}): reflectively
     * enumerates every record component this movement's payload types
     * declare and fails if any name suggests a freshness, staleness, "as of"
     * or health-tone claim, or if {@code created_at} appears under any name
     * but its own.
     */
    @Test
    void noPayloadTypeCarriesAFreshnessOrHealthToneComponent() {
        Set<String> forbiddenSubstrings = Set.of("lastseen", "last_seen", "asof", "as_of", "stale", "freshness",
                "health", "reachable", "livenesss", "uptime");
        Set<String> offendingComponents = new HashSet<>();
        for (Class<?> type : List.of(DeviceListEntry.class, DeviceWorkspaceView.class, TransportSummary.class,
                TransportSummary.Entry.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String lower = component.getName().toLowerCase(java.util.Locale.ROOT);
                for (String forbidden : forbiddenSubstrings) {
                    if (lower.contains(forbidden)) {
                        offendingComponents.add(type.getSimpleName() + "." + component.getName());
                    }
                }
            }
        }
        assertTrue(offendingComponents.isEmpty(), "freshness/health-tone components found: " + offendingComponents);
    }

    /**
     * Test 2 ({@code DeviceWorkspacePayloadCarriesOnlyDeclaredColumns}),
     * the reflective half: the closed field set contract §3 declares, and
     * nothing named after a forbidden column.
     */
    @Test
    void payloadTypesCarryOnlyDeclaredFields() {
        Set<String> workspaceComponents = componentNames(DeviceWorkspaceView.class);
        assertEquals(Set.of("deviceId", "vendorHint", "registrationSource", "createdAt", "isTestTarget",
                "credentialConfigured", "enrollmentState", "disabled", "transport", "actionAffordance"),
                workspaceComponents);

        Set<String> listComponents = componentNames(DeviceListEntry.class);
        assertEquals(Set.of("deviceId", "vendorHint", "registeredAt", "registrationSource", "enrollmentState",
                        "disabled", "isTestTarget"),
                listComponents);

        Set<String> transportComponents = componentNames(TransportSummary.class);
        assertEquals(Set.of("presence", "transports"), transportComponents);
        assertEquals(Set.of("endpointId", "transportKind"), componentNames(TransportSummary.Entry.class));
    }

    private static Set<String> componentNames(Class<?> type) {
        Set<String> names = new HashSet<>();
        for (RecordComponent component : type.getRecordComponents()) {
            names.add(component.getName());
        }
        return names;
    }

    private static int indexOf(List<DeviceListEntry> entries, String deviceId) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).deviceId().equals(deviceId)) {
                return i;
            }
        }
        throw new AssertionError("device not found in list: " + deviceId);
    }
}
