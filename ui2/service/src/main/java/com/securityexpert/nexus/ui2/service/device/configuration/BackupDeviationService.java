package com.securityexpert.nexus.ui2.service.device.configuration;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ChangeState;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationEntry;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationDeviationSummary;
import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationRunHead;
import com.securityexpert.nexus.ui2.persistence.device.configuration.DeviceConfigurationRepository;
import com.securityexpert.nexus.ui2.service.device.configuration.MajorDeviationRules.Category;

/**
 * {@code GET /api/v2/backups/deviations}: the Backups screen's "Active major deviations", read from the
 * configuration runs the Configuration plane already stores (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md).
 * Read-only over stored rows; no device command, no artefact decrypt, no configuration value.
 *
 * <p>Each primary run in the window is one comparison with the newest earlier run of its read kind: a valid one
 * when unchanged, or changed with a COMPUTED summary. A device is compared when it has a valid comparison in the
 * window and its vendor is categorised; it is MAJOR when any of them touched a major section.</p>
 */
public final class BackupDeviationService {

    public static final Duration WINDOW = Duration.ofDays(7);

    /** A device's class over the window. */
    public enum DeviceClass { MAJOR, MINOR, NONE }

    /** Why a device that has configuration runs was not compared. */
    public enum NotComparedReason {
        NO_READ_IN_WINDOW, FIRST_READ_ONLY, NOT_COMPUTABLE, VENDOR_NOT_CATEGORISED;

        public String wire() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    /** One (device, category) row: the newest change time in the window and how many comparisons hit it. */
    public record MajorDeviation(String deviceId, Optional<String> hostname, Optional<String> clusterMemberRef,
            String vendor, Category category, Instant changedAt, int count, List<String> sections) {
    }

    /** One compared device's class and the major categories it hit. */
    public record DeviceDeviation(String deviceId, String vendor, DeviceClass deviceClass, Instant lastComparedAt,
            List<Category> categories) {
    }

    public record Result(Instant evaluatedAt, Duration window, int devicesKnown, List<DeviceDeviation> compared,
            Map<NotComparedReason, Integer> notCompared, List<MajorDeviation> activeMajor) {

        public long count(DeviceClass deviceClass) {
            return compared.stream().filter(d -> d.deviceClass() == deviceClass).count();
        }
    }

    private final DeviceConfigurationRepository repository;
    private final Supplier<List<DeviceSummaryRecord>> devices;
    private final Clock clock;

    public BackupDeviationService(DeviceConfigurationRepository repository, Supplier<List<DeviceSummaryRecord>> devices,
            Clock clock) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result evaluate() {
        Instant now = clock.instant();
        Instant since = now.minus(WINDOW);
        Map<String, String> known = repository.findDevicesWithPrimaryRun();
        Map<String, List<ConfigurationRunHead>> runsByDevice = new LinkedHashMap<>();
        for (ConfigurationRunHead run : repository.findPrimaryRunHeadsSince(since)) {
            runsByDevice.computeIfAbsent(run.deviceId(), k -> new ArrayList<>()).add(run);
        }
        Map<String, DeviceSummaryRecord> inventory = new LinkedHashMap<>();
        for (DeviceSummaryRecord device : devices.get()) {
            inventory.put(device.deviceId(), device);
        }

        List<DeviceDeviation> compared = new ArrayList<>();
        Map<NotComparedReason, Integer> notCompared = new EnumMap<>(NotComparedReason.class);
        for (NotComparedReason reason : NotComparedReason.values()) {
            notCompared.put(reason, 0);
        }
        List<MajorDeviation> activeMajor = new ArrayList<>();

        for (Map.Entry<String, String> device : known.entrySet()) {
            String deviceId = device.getKey();
            List<ConfigurationRunHead> runs = runsByDevice.getOrDefault(deviceId, List.of());
            String vendor = runs.isEmpty() ? device.getValue() : runs.get(runs.size() - 1).vendor();
            if (runs.isEmpty()) {
                notCompared.merge(NotComparedReason.NO_READ_IN_WINDOW, 1, Integer::sum);
                continue;
            }
            if (!MajorDeviationRules.categorises(vendor)) {
                notCompared.merge(NotComparedReason.VENDOR_NOT_CATEGORISED, 1, Integer::sum);
                continue;
            }
            List<ConfigurationRunHead> valid = runs.stream().filter(BackupDeviationService::isValidComparison).toList();
            if (valid.isEmpty()) {
                boolean anyNotComputable = runs.stream().anyMatch(r -> ChangeState.CHANGED.equals(r.changeState()));
                notCompared.merge(anyNotComputable ? NotComparedReason.NOT_COMPUTABLE : NotComparedReason.FIRST_READ_ONLY,
                        1, Integer::sum);
                continue;
            }

            Map<Category, Instant> latestByCategory = new EnumMap<>(Category.class);
            Map<Category, Integer> countByCategory = new EnumMap<>(Category.class);
            Map<Category, Set<String>> sectionsByCategory = new EnumMap<>(Category.class);
            boolean anyChange = false;
            for (ConfigurationRunHead run : valid) {
                if (!ChangeState.CHANGED.equals(run.changeState())) {
                    continue;
                }
                anyChange = true;
                Set<Category> hit = new LinkedHashSet<>();
                for (ConfigurationDeviationEntry entry : run.deviationSummary().orElseThrow().entries()) {
                    Optional<Category> category = MajorDeviationRules.categoryOf(run.vendor(), entry.context(), entry.section());
                    if (category.isPresent()) {
                        hit.add(category.get());
                        sectionsByCategory.computeIfAbsent(category.get(), k -> new LinkedHashSet<>()).add(entry.section());
                    }
                }
                for (Category category : hit) {
                    countByCategory.merge(category, 1, Integer::sum);
                    latestByCategory.merge(category, run.collectedAt(), (a, b) -> a.isAfter(b) ? a : b);
                }
            }
            DeviceClass deviceClass = !latestByCategory.isEmpty() ? DeviceClass.MAJOR
                    : anyChange ? DeviceClass.MINOR : DeviceClass.NONE;
            compared.add(new DeviceDeviation(deviceId, vendor, deviceClass, valid.get(valid.size() - 1).collectedAt(),
                    List.copyOf(latestByCategory.keySet())));
            DeviceSummaryRecord summary = inventory.get(deviceId);
            for (Map.Entry<Category, Instant> hit : latestByCategory.entrySet()) {
                activeMajor.add(new MajorDeviation(deviceId,
                        summary == null ? Optional.empty() : summary.observedHostname(),
                        summary == null ? Optional.empty() : summary.clusterMemberRef(),
                        vendor, hit.getKey(), hit.getValue(), countByCategory.get(hit.getKey()),
                        List.copyOf(sectionsByCategory.get(hit.getKey()))));
            }
        }
        activeMajor.sort(Comparator.comparing(MajorDeviation::changedAt).reversed()
                .thenComparing(MajorDeviation::deviceId).thenComparing(MajorDeviation::category));
        return new Result(now, WINDOW, known.size(), List.copyOf(compared), Map.copyOf(notCompared),
                List.copyOf(activeMajor));
    }

    /** Unchanged, or changed with a summary computed from the predecessor's index. First runs compare nothing. */
    static boolean isValidComparison(ConfigurationRunHead run) {
        if (ChangeState.UNCHANGED.equals(run.changeState())) {
            return true;
        }
        return ChangeState.CHANGED.equals(run.changeState()) && run.deviationSummary()
                .map(s -> s.status() == ConfigurationDeviationSummary.Status.COMPUTED).orElse(false);
    }
}
