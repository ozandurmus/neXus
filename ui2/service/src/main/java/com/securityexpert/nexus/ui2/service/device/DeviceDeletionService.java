package com.securityexpert.nexus.ui2.service.device;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.BackupDisposition;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository.DeleteResult;

/** Deletes one enrolled device and its persistence-owned records. */
public final class DeviceDeletionService {

    private final DeviceRepository deviceRepository;

    public DeviceDeletionService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public DeleteResult deleteDevice(String deviceId, BackupDisposition backupDisposition,
            String actorFingerprint, String actionId) {
        return deviceRepository.deleteDevice(deviceId, backupDisposition, actorFingerprint, actionId);
    }
}
