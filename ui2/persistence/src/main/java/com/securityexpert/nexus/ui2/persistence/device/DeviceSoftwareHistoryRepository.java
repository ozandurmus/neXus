package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * V243 {@code device_software_version_history}: when a device's software version (and Check Point hotfix / Jumbo
 * level) changed, from neXus's own observations. Writing happens inside the observation writers
 * ({@link JooqDeviceRepository}, {@link JooqDevicePlatformFactsRepository}); this port only reads.
 */
public interface DeviceSoftwareHistoryRepository {

    /**
     * One stretch of one device. The change that opened it happened after {@code previousLastSeenAt} (the last time the
     * previous version was still seen) and no later than {@code firstSeenAt}; both previous fields are empty for the
     * first stretch known.
     */
    record Stretch(String deviceId, Optional<String> hostname, Optional<String> vendor, String softwareVersion,
            Optional<String> hotfixLevel, Instant firstSeenAt, Instant lastSeenAt,
            Optional<String> previousSoftwareVersion, Optional<String> previousHotfixLevel,
            Optional<Instant> previousLastSeenAt, String source) {
        public boolean isChange() {
            return previousSoftwareVersion.isPresent() || previousHotfixLevel.isPresent() || previousLastSeenAt.isPresent();
        }
    }

    /** The device's stretches, newest first. */
    List<Stretch> forDevice(String deviceId);

    /** Stretches that opened as a change at or after {@code since}, newest first, at most {@code limit}. */
    List<Stretch> changesSince(Instant since, int limit);

    DeviceSoftwareHistoryRepository NONE = new DeviceSoftwareHistoryRepository() {
        @Override
        public List<Stretch> forDevice(String deviceId) {
            return List.of();
        }

        @Override
        public List<Stretch> changesSince(Instant since, int limit) {
            return List.of();
        }
    };
}
