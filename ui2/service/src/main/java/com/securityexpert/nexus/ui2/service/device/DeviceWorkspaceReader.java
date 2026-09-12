package com.securityexpert.nexus.ui2.service.device;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * The read model behind both routes of
 * {@code UI2_0_B1_09_DEVICE_WORKSPACE_CONTRACT.md} (FROZEN) -- the device
 * list (§3.1) and the device workspace (§3.2, §4.2, §5). No HTTP route is
 * registered by this class; a controller composing this reader with
 * {@link DeviceWorkspaceAffordanceEvaluator} and the {@code E1}-{@code E6}
 * chain ({@code service.security}) is outside this layer's boundary (task
 * scope).
 *
 * <p>Plain JDBC over a {@link DataSource}, deliberately -- the {@code
 * service} module's build does not expose jOOQ on its own compile
 * classpath (it is {@code implementation}-scoped inside {@code
 * persistence}), and this task's scope excludes every
 * {@code build.gradle.kts}. A caller wires a {@code ui2_app}
 * {@link DataSource} (the same one {@link
 * com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary} would
 * otherwise wrap).</p>
 *
 * <h2>What this class deliberately never does</h2>
 * <ul>
 *   <li>Never selects {@code endpoints.address_ref} -- every {@code SELECT}
 *   against {@code endpoints} names an explicit column list that omits it
 *   (contract §4.1, §8 test 1).</li>
 *   <li>Never selects any {@code credential_references} column, and reads
 *   {@code devices.credential_reference_id} only to test it for
 *   {@code null} before discarding the raw value (§3.2, §8 test 2).</li>
 *   <li>Never queries {@code cp_inventory_projection} or any other table
 *   (§7, §8 test 11) -- the standing PO collection gate is enforced by this
 *   class simply never issuing that {@code SELECT}, not by a runtime
 *   check.</li>
 * </ul>
 */
public final class DeviceWorkspaceReader {

    private static final String DEVICE_LIST_SQL =
            "select device_id, vendor_hint, registration_source, created_at, is_test_target, "
                    + "enrollment_state, disabled from devices order by created_at desc, device_id";

    private static final String DEVICE_WORKSPACE_SQL =
            "select device_id, vendor_hint, registration_source, created_at, is_test_target, "
                    + "enrollment_state, disabled, credential_reference_id from devices where device_id = ?";

    /** Deliberately omits {@code address_ref} (contract §4.1). */
    private static final String ENDPOINTS_FOR_DEVICE_SQL =
            "select endpoint_id, transport_kind from endpoints where device_id = ? order by endpoint_id";

    private final DataSource dataSource;

    public DeviceWorkspaceReader(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** Contract §3.1: every {@code devices} row, newest first. Role filtering is the controller's job (out of scope here). */
    public List<DeviceListEntry> listDevices() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(DEVICE_LIST_SQL);
                ResultSet rows = statement.executeQuery()) {
            List<DeviceListEntry> entries = new ArrayList<>();
            while (rows.next()) {
                entries.add(new DeviceListEntry(
                        rows.getString("device_id"),
                        rows.getString("vendor_hint"),
                        rows.getTimestamp("created_at").toInstant(),
                        rows.getString("registration_source"),
                        EnrollmentStateView.fromColumnValue(rows.getString("enrollment_state")),
                        rows.getBoolean("disabled"),
                        rows.getBoolean("is_test_target")));
            }
            return entries;
        } catch (SQLException e) {
            throw new DeviceWorkspaceReadException("could not read the device list", e);
        }
    }

    /**
     * Contract §3.2/§4.2/§5: one device's identity, transport summary and
     * enrollment presentation. {@link Optional#empty()} when no such
     * {@code devices} row exists. The {@code action_affordance} map is
     * empty here -- populate it via
     * {@link DeviceWorkspaceAffordanceEvaluator} and
     * {@link #withActionAffordance}, a separate, RBAC-evaluating step this
     * class does not perform itself.
     */
    public Optional<DeviceWorkspaceView> readWorkspace(String deviceId) {
        Objects.requireNonNull(deviceId, "deviceId");
        try (Connection connection = dataSource.getConnection()) {
            Optional<DeviceRow> deviceRow;
            try (PreparedStatement statement = connection.prepareStatement(DEVICE_WORKSPACE_SQL)) {
                statement.setString(1, deviceId);
                try (ResultSet rows = statement.executeQuery()) {
                    deviceRow = rows.next() ? Optional.of(readDeviceRow(rows)) : Optional.empty();
                }
            }
            if (deviceRow.isEmpty()) {
                return Optional.empty();
            }

            List<TransportSummary.Entry> transports = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(ENDPOINTS_FOR_DEVICE_SQL)) {
                statement.setString(1, deviceId);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        transports.add(new TransportSummary.Entry(
                                rows.getString("endpoint_id"), rows.getString("transport_kind")));
                    }
                }
            }

            DeviceRow row = deviceRow.get();
            return Optional.of(new DeviceWorkspaceView(
                    row.deviceId(), row.vendorHint(), row.registrationSource(), row.createdAt(), row.isTestTarget(),
                    row.credentialConfigured(), row.enrollmentState(), row.disabled(),
                    TransportSummary.of(transports), Map.of()));
        } catch (SQLException e) {
            throw new DeviceWorkspaceReadException("could not read the device workspace", e);
        }
    }

    /** Attaches a separately computed {@code action_affordance} map to an already-read view. */
    public static DeviceWorkspaceView withActionAffordance(DeviceWorkspaceView view,
            Map<String, ActionAffordanceView> actionAffordance) {
        return new DeviceWorkspaceView(view.deviceId(), view.vendorHint(), view.registrationSource(),
                view.createdAt(), view.isTestTarget(), view.credentialConfigured(), view.enrollmentState(),
                view.disabled(), view.transport(), actionAffordance);
    }

    private static DeviceRow readDeviceRow(ResultSet rows) throws SQLException {
        return new DeviceRow(
                rows.getString("device_id"),
                rows.getString("vendor_hint"),
                rows.getString("registration_source"),
                rows.getTimestamp("created_at").toInstant(),
                rows.getBoolean("is_test_target"),
                rows.getString("credential_reference_id") != null,
                EnrollmentStateView.fromColumnValue(rows.getString("enrollment_state")),
                rows.getBoolean("disabled"));
    }

    private record DeviceRow(String deviceId, String vendorHint, String registrationSource,
            java.time.Instant createdAt, boolean isTestTarget, boolean credentialConfigured,
            EnrollmentStateView enrollmentState, boolean disabled) {
    }
}
