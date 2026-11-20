package com.securityexpert.nexus.ui2.persistence.device;

import java.util.List;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.NameIdentity;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.PanSerial;

/**
 * Read-only {@code devices}/{@code endpoints} persistence port for worker and read-projection modules
 * ({@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §2.5.3, §4.2 Step 0).
 *
 * <p>Exposes only {@code SELECT} operations so worker modules that hold {@code SELECT} (or narrow column-level
 * {@code UPDATE}) on {@code devices} and {@code endpoints} never depend on the administrative create/delete/disable
 * methods of {@link DeviceAdminRepository}.</p>
 */
public interface DeviceReadRepository {

    Optional<DeviceRecord> find(String deviceId);

    Optional<EndpointRecord> findEndpoint(String endpointId);

    /** The one endpoint a manually-registered device carries (B1-4b contract §4: one device, one endpoint at this maturity). */
    Optional<EndpointRecord> findEndpointByDeviceId(String deviceId);

    /**
     * The device (if any) that already owns an endpoint at this exact address
     * reference -- the Product Owner's rule (2026-09-22): a device is never
     * registered twice under the same address. Case-insensitive, whitespace
     * ignored; a disabled device still counts (delete it first).
     */
    default Optional<String> findDeviceIdByEndpointAddress(String addressRef) {
        return Optional.empty();
    }

    /** The V12 facts a confirmed device carries, for a read model (GET /devices, GET /devices/{id}). */
    Optional<DeviceConfirmFacts> findConfirmFacts(String deviceId);

    /** GET /devices (DeviceSummary rows), newest first -- every device, its vendor and its observed facts. */
    List<DeviceSummaryRecord> listAll();

    /**
     * Every device's {@link NameIdentity}, oldest registration first (ties by device ID) -- the masking layer's
     * input for keeping device pseudonyms unique. The default reverses {@link #listAll()}'s newest-first order.
     */
    default List<NameIdentity> listNameIdentities() {
        List<NameIdentity> out = new java.util.ArrayList<>(listAll().stream()
                .map(d -> new NameIdentity(d.deviceId(), d.observedHostname(), d.clusterMemberRef())).toList());
        java.util.Collections.reverse(out);
        return out;
    }

    /**
     * Enrolled Palo Alto firewalls with their recorded serial -- the exact relation the Policy screen uses to show a
     * firewall by hostname where Panorama names it by serial. The serial is the identity baseline
     * ({@code recorded_identity_primary}), else the platform-facts serial; a device that carries neither is absent.
     * Never a heuristic: callers match the whole value or not at all.
     */
    default List<PanSerial> listPanSerials() {
        return List.of();
    }

    /** Read-eligible devices whose first endpoint uses the requested transport. */
    default List<DeviceSummaryRecord> listReadCollectionTargets(String transport) {
        return listAll().stream().filter(d -> find(d.deviceId()).filter(DeviceRecord::permitsReadCollection).isPresent()
                && findEndpointByDeviceId(d.deviceId()).filter(e -> transport.equals(e.transportKind())).isPresent()).toList();
    }

    /**
     * The same coalesced view {@link #listAll()} produces (device's own confirmed facts, falling
     * back to its discovery candidate's, per {@code DEVICE_SUMMARY_SELECT}), narrowed to one
     * device -- so a value such as {@code observedModel()} is available before that device has
     * ever been confirmed, whenever its discovery candidate already carried one (e.g. a Check
     * Point Management Server's own "hardware" field).
     */
    default Optional<DeviceSummaryRecord> findSummary(String deviceId) {
        return listAll().stream().filter(summary -> summary.deviceId().equals(deviceId)).findFirst();
    }

    /** Returns members of a specific cluster (by cluster_member_ref or candidate display name) directly without full scan. */
    default List<DeviceSummaryRecord> findMembersByClusterRef(String clusterMemberRef) {
        return listAll().stream()
                .filter(summary -> summary.clusterMemberRef().equals(Optional.ofNullable(clusterMemberRef)))
                .toList();
    }
}
