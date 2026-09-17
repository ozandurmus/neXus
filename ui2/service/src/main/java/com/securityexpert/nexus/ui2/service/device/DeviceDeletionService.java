package com.securityexpert.nexus.ui2.service.device;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;

/** Deletes one enrolled device and its persistence-owned records. */
public final class DeviceDeletionService {

    private final DeviceRepository deviceRepository;

    public DeviceDeletionService(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    public boolean deleteDevice(String deviceId, String actorFingerprint, String actionId) {
        return deviceRepository.deleteDevice(deviceId, actorFingerprint, actionId);
    }
}
