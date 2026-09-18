package com.securityexpert.nexus.ui2.service.device.inventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.DeviceInventoryRepository;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRecordDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;

/**
 * {@code GET /devices/{id}/inventory} and {@code GET /clusters/{cluster_
 * member_ref}/inventory} (WORKER.md "Service read model"): the latest run
 * per device, and {@link ClusterInventoryMerger}'s pure merge fed by every
 * member's latest run. A thin read composition, like {@link
 * com.securityexpert.nexus.ui2.service.device.DeviceQueryService} -- the
 * JSON shape itself is the controller's job.
 */
public final class InventoryQueryService {

    public sealed interface DeviceInventoryOutcome {
        record Found(String deviceId, Optional<InventoryRun> run, Optional<String> virtualSystems) implements DeviceInventoryOutcome {
            public Found(String deviceId, Optional<InventoryRun> run) {
                this(deviceId, run, Optional.empty());
            }
        }

        record NotFound() implements DeviceInventoryOutcome {
        }
    }

    public sealed interface ClusterInventoryOutcome {
        record Found(String clusterMemberRef, List<DeviceSummaryRecord> members,
                List<ClusterInventoryMerger.MergedContext> contexts) implements ClusterInventoryOutcome {
        }

        /** No device carries this {@code cluster_member_ref}. */
        record NotFound() implements ClusterInventoryOutcome {
        }
    }

    private final DeviceRepository deviceRepository;
    private final JobRecordDao jobRecordDao;
    private final DeviceInventoryRepository deviceInventoryRepository;
    private final com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer topologyNamePseudonymizer;

    public InventoryQueryService(DeviceRepository deviceRepository, JobRecordDao jobRecordDao,
            DeviceInventoryRepository deviceInventoryRepository) {
        this(deviceRepository, jobRecordDao, deviceInventoryRepository, null);
    }

    public InventoryQueryService(DeviceRepository deviceRepository, JobRecordDao jobRecordDao,
            DeviceInventoryRepository deviceInventoryRepository,
            com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer topologyNamePseudonymizer) {
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
        this.jobRecordDao = Objects.requireNonNull(jobRecordDao, "jobRecordDao");
        this.deviceInventoryRepository = Objects.requireNonNull(deviceInventoryRepository, "deviceInventoryRepository");
        this.topologyNamePseudonymizer = topologyNamePseudonymizer;
    }

    /** 404 when {@code deviceId} is unknown; 200 with {@code collected_at} null and empty contexts when it has no run yet. */
    public DeviceInventoryOutcome deviceInventory(String deviceId) {
        if (deviceRepository.find(deviceId).isEmpty()) {
            return new DeviceInventoryOutcome.NotFound();
        }
        Optional<String> virtualSystems = deviceRepository.listAll().stream()
                .filter(d -> d.deviceId().equals(deviceId))
                .findFirst()
                .flatMap(DeviceSummaryRecord::virtualSystems);
        return new DeviceInventoryOutcome.Found(deviceId, deviceInventoryRepository.findLatestRun(deviceId), virtualSystems);
    }

    /** The job that produced a device's latest run, if any. */
    public Optional<JobRow> jobFor(InventoryRun run) {
        if (run == null) {
            return Optional.empty();
        }
        return jobRecordDao.find(run.jobId());
    }

    /** 404 when no device carries {@code clusterMemberRef} (WORKER.md "Routes"). */
    public ClusterInventoryOutcome clusterInventory(String clusterMemberRef) {
        List<DeviceSummaryRecord> members = deviceRepository.findMembersByClusterRef(clusterMemberRef);
        String queryRef = clusterMemberRef;
        if (members.isEmpty() && topologyNamePseudonymizer != null) {
            String resolved = topologyNamePseudonymizer.resolveClusterName(clusterMemberRef, () ->
                    deviceRepository.listAll().stream()
                            .map(s -> s.clusterMemberRef().orElse(null))
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList());
            if (resolved != null) {
                queryRef = resolved;
                members = deviceRepository.findMembersByClusterRef(queryRef);
            }
        }
        if (members.isEmpty()) {
            return new ClusterInventoryOutcome.NotFound();
        }
        List<String> memberIds = members.stream().map(DeviceSummaryRecord::deviceId).sorted().toList();
        Map<String, InventoryRun> latestRunByDeviceId = new LinkedHashMap<>();
        for (InventoryRun run : deviceInventoryRepository.findLatestRuns(memberIds)) {
            latestRunByDeviceId.put(run.deviceId(), run);
        }
        List<ClusterInventoryMerger.MergedContext> contexts = ClusterInventoryMerger.merge(memberIds, latestRunByDeviceId);
        return new ClusterInventoryOutcome.Found(clusterMemberRef, members, contexts);
    }
}
