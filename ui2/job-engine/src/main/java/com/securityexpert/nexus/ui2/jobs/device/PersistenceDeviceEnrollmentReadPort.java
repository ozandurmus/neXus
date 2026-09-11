package com.securityexpert.nexus.ui2.jobs.device;

import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;

/**
 * {@link DeviceEnrollmentReadPort} adapter over {@code persistence}'s
 * {@link DeviceRepository}. Lives in {@code job-engine} rather than
 * {@code persistence} because the interface it implements is declared
 * here (F12); {@code job-engine} is already allowed to depend on {@code
 * persistence} (contract §2 row), so this adapter introduces no new
 * dependency edge and no cycle ({@code persistence} never depends back on
 * {@code job-engine}).
 */
public final class PersistenceDeviceEnrollmentReadPort implements DeviceEnrollmentReadPort {

    private final DeviceRepository deviceRepository;

    public PersistenceDeviceEnrollmentReadPort(DeviceRepository deviceRepository) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
    }

    @Override
    public Optional<DeviceEnrollmentSnapshot> findEnrollment(String deviceId) {
        return deviceRepository.find(deviceId)
                .map(device -> new DeviceEnrollmentSnapshot(device.deviceId(), device.enrollmentState(),
                        device.disabled()));
    }
}
