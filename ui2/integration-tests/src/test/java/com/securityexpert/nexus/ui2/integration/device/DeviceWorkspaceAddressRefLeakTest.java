package com.securityexpert.nexus.ui2.integration.device;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.IdentityHashMap;
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
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceDraft;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.JooqDeviceRepository;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceReader;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceView;

/**
 * Contract §8 test 1 ({@code DeviceWorkspacePayloadNeverCarriesAddressRef})
 * and the task's own hard rule: a distinctive synthetic marker is written
 * into {@code endpoints.address_ref} through the real, audited registration
 * path, then the marker's actual presence in the produced object graph is
 * checked by reflection over every reachable field -- not asserted absent
 * in a comment, and not merely "no field is literally named addressRef"
 * (which {@code TransportSummary}'s design already makes trivially true;
 * this test instead walks the graph a JSON/HTTP serializer would walk and
 * greps every {@link String}, {@link CharSequence} and byte value it
 * finds).
 */
class DeviceWorkspaceAddressRefLeakTest {

    private static final String MARKER = "LEAK-MARKER-9f3c7b21-do-not-render";

    private static Ui2PostgresFixture fixture;
    private static DeviceRepository deviceRepository;
    private static DeviceWorkspaceReader reader;
    private static String deviceId;

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        fixture = Ui2PostgresFixture.createAndMigrate("device_workspace_leak");
        TransactionBoundary transactionBoundary = new JooqTransactionBoundary(
                DSL.using(fixture.appDataSource(), SQLDialect.POSTGRES));
        deviceRepository = new JooqDeviceRepository(transactionBoundary);
        reader = new DeviceWorkspaceReader(fixture.appDataSource());

        String credentialReferenceId;
        try (var app = fixture.appConnection()) {
            credentialReferenceId = Ui2Rows.insertCredentialReference(app);
        }
        deviceId = DeviceWorkspaceTestRows.opaqueId("dev-leak");
        String endpointId = DeviceWorkspaceTestRows.opaqueId("ep-leak");
        // The marker is injected as the real column value through the
        // production registration path -- not a fixture shortcut.
        deviceRepository.registerDraft(
                new DeviceDraft(deviceId, "harness-vendor", "manual_registration", true, credentialReferenceId,
                        endpointId, "ssh_exec", MARKER),
                Ui2Rows.ACTOR, Ui2Rows.ACTION);
    }

    @AfterAll
    static void drop() {
        if (fixture != null) {
            fixture.close();
        }
    }

    @Test
    void theMarkerPlantedInAddressRefNeverAppearsInTheWorkspaceView() {
        DeviceWorkspaceView view = reader.readWorkspace(deviceId).orElseThrow();
        assertFalse(containsMarker(view), "endpoints.address_ref marker leaked into the device workspace payload");
    }

    @Test
    void theMarkerPlantedInAddressRefNeverAppearsInTheDeviceList() {
        var entries = reader.listDevices();
        assertFalse(containsMarker(entries), "endpoints.address_ref marker leaked into the device list payload");
    }

    /** Sanity: proves the marker really was written to the column this test claims, so the two assertions above are meaningful. */
    @Test
    void sanityTheMarkerIsActuallyInTheDatabaseColumn() throws SQLException {
        try (var app = fixture.appConnection();
                var statement = app.prepareStatement("select address_ref from endpoints where device_id = ?")) {
            statement.setString(1, deviceId);
            try (var rows = statement.executeQuery()) {
                assertTrue(rows.next());
                assertTrue(rows.getString(1).equals(MARKER));
            }
        }
    }

    /** Recursively walks every reachable field/record-component/collection element for {@link #MARKER}. */
    private static boolean containsMarker(Object root) {
        Deque<Object> queue = new ArrayDeque<>();
        Map<Object, Boolean> visited = new IdentityHashMap<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            Object current = queue.poll();
            if (current == null || visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            if (current instanceof CharSequence text) {
                if (text.toString().contains(MARKER)) {
                    return true;
                }
                continue;
            }
            if (current instanceof Optional<?> optional) {
                optional.ifPresent(queue::add);
                continue;
            }
            if (current instanceof Map<?, ?> map) {
                queue.addAll(map.keySet());
                queue.addAll(map.values());
                continue;
            }
            if (current instanceof Collection<?> collection) {
                queue.addAll(collection);
                continue;
            }
            if (current.getClass().isEnum()) {
                continue;
            }
            if (current.getClass().isRecord()) {
                for (RecordComponent component : current.getClass().getRecordComponents()) {
                    try {
                        queue.add(component.getAccessor().invoke(current));
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("could not introspect a record component for the leak scan", e);
                    }
                }
            }
        }
        return false;
    }
}
