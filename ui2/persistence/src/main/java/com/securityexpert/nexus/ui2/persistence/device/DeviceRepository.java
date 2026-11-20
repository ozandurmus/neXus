package com.securityexpert.nexus.ui2.persistence.device;

import java.util.Optional;

/**
 * {@code devices}/{@code endpoints} persistence port (contract §12/F12
 * placement: device repositories live in {@code persistence}). The
 * registering role check and every validation in contract §4 are the
 * service layer's job ({@code service} module, F12); this port only ever
 * performs the write or read it is asked to.
 *
 * <p>Per {@code docs/design/DATABASE_ROLES_AND_DATA_OWNERSHIP_DESIGN_2026_11_14.md} §2.5.3 / §4.2 Step 0, the port is
 * partitioned into {@link DeviceReadRepository} (read-only queries), {@link DeviceInventoryWriteRepository} (worker
 * confirmation and observed-fact updates), and {@link DeviceAdminRepository} (service-only draft registration,
 * disable, credential assignment, and cascading device deletion).</p>
 */
public interface DeviceRepository extends DeviceAdminRepository {

    enum BackupDisposition {
        KEEP,
        REMOVE
    }

    record DeleteResult(boolean deleted, long backupArtefactCount, boolean dispositionRequired) {
    }

    /** One device's ID with the hostname and cluster reference {@link #listAll()} shows for it. */
    record NameIdentity(String deviceId, Optional<String> hostname, Optional<String> clusterMemberRef) {
    }

    /** An enrolled Palo Alto firewall and the serial neXus recorded for it. */
    record PanSerial(String serial, String deviceId) {
    }
}
