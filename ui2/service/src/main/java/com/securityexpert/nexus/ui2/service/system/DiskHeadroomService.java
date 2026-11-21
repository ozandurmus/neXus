package com.securityexpert.nexus.ui2.service.system;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Measures the volumes {@link SystemStatusService} reports on and classifies them against the
 * {@link DiskHeadroomPolicy}: {@code root} (the host root disk as the pod sees it) and {@code artefact_store} (the
 * backup/evidence volume, a host-path volume, when this pod mounts it). The database volume is not reported by the
 * status service (only the database size is), so it is not measured here. A volume that cannot be measured is listed
 * as UNKNOWN and never counts as full; a volume the pod does not mount is not listed at all.
 */
@Service
public class DiskHeadroomService {

    /** The raw space figures of one path; replaceable in tests. */
    @FunctionalInterface
    interface SpaceReader {
        /** {@code {total, free, usable}} in bytes. */
        long[] read(Path path) throws IOException;
    }

    static final String ROOT = "root";
    static final String ARTEFACT_STORE = "artefact_store";

    private final DiskHeadroomPolicy policy;
    private final Map<String, Path> volumes;
    private final SpaceReader reader;

    @Autowired
    public DiskHeadroomService(DiskHeadroomPolicy policy) {
        this(policy, defaultVolumes(), DiskHeadroomService::readFileStore);
    }

    DiskHeadroomService(DiskHeadroomPolicy policy, Map<String, Path> volumes, SpaceReader reader) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.volumes = new LinkedHashMap<>(volumes);
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    public DiskHeadroomPolicy policy() {
        return policy;
    }

    public List<DiskVolumeReading> measure() {
        List<DiskVolumeReading> readings = new ArrayList<>();
        volumes.forEach((name, path) -> {
            if (!Files.isDirectory(path)) {
                return;
            }
            try {
                long[] space = reader.read(path);
                readings.add(DiskVolumeReading.measured(name, space[0], space[1], space[2], policy));
            } catch (IOException | RuntimeException e) {
                readings.add(DiskVolumeReading.unknown(name));
            }
        });
        return readings;
    }

    /** The {@code disk} section of the platform status payload. */
    public Map<String, Object> payload() {
        return payload(policy, measure());
    }

    static Map<String, Object> payload(DiskHeadroomPolicy policy, List<DiskVolumeReading> readings) {
        DiskLevel overall = DiskLevel.OK;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (DiskVolumeReading reading : readings) {
            overall = DiskLevel.worst(overall, reading.level());
            rows.add(reading.toMap());
        }
        Map<String, Object> thresholds = new LinkedHashMap<>();
        thresholds.put("warning_percent", policy.warningPercent());
        thresholds.put("critical_percent", policy.criticalPercent());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("level", overall.token());
        body.put("thresholds", thresholds);
        body.put("volumes", rows);
        return body;
    }

    private static Map<String, Path> defaultVolumes() {
        Map<String, Path> volumes = new LinkedHashMap<>();
        volumes.put(ROOT, Path.of("/"));
        String artefactRoot = OverwatchEnv.getOrDefault("UI2_ARTEFACT_STORE_ROOT", "");
        if (!artefactRoot.isBlank()) {
            volumes.put(ARTEFACT_STORE, Path.of(artefactRoot));
        }
        return volumes;
    }

    private static long[] readFileStore(Path path) throws IOException {
        FileStore store = Files.getFileStore(path);
        return new long[] {store.getTotalSpace(), store.getUnallocatedSpace(), store.getUsableSpace()};
    }
}
