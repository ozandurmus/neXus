package com.securityexpert.nexus.ui2.service.device;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;

/**
 * {@code GET /devices/{id}} and {@code GET /devices} (WORKER.md "Routes").
 * A thin read composition over {@link DeviceRepository} and {@link
 * JobRecordDao} -- the JSON shape itself is the controller's job (adminApi.ts
 * is the fixed side of this contract), this class only assembles the
 * domain values a route needs.
 */
public final class DeviceQueryService {

    public sealed interface DetailOutcome {
        record Found(DeviceRecord device, DeviceConfirmFacts facts, Optional<JobRow> job) implements DetailOutcome {
        }

        record NotFound() implements DetailOutcome {
        }
    }

    private final DeviceRepository deviceRepository;
    private final JobRecordDao jobRecordDao;

    public DeviceQueryService(DeviceRepository deviceRepository, JobRecordDao jobRecordDao) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.jobRecordDao = Objects.requireNonNull(jobRecordDao, "jobRecordDao");
    }

    /** {@code GET /devices/{id}}: 404 shape when unknown; {@code job} is the most recent job targeting this device, or none. */
    public DetailOutcome deviceDetail(String deviceId) {
        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return new DetailOutcome.NotFound();
        }
        DeviceConfirmFacts facts = deviceRepository.findConfirmFacts(deviceId)
                .orElseThrow(() -> new IllegalStateException(
                        "devices row exists but its confirm-fact columns could not be read: device_id=" + deviceId));
        Optional<JobRow> job = jobRecordDao.findMostRecentByTargetDeviceId(deviceId);
        return new DetailOutcome.Found(device.get(), facts, job);
    }

    /** {@code GET /devices}: every device, newest first. */
    public List<DeviceSummaryRecord> listDevices() {
        return deviceRepository.listAll();
    }
}
