package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * V246 {@code device_install_event}: the base-install and Jumbo-install dates a Check Point gateway's own install log
 * (/opt/CPInstLog) gave at an inventory run. The device prints its own local clock with no offset, so the time is a
 * {@link LocalDateTime} and is never converted. Rows are only ever added or re-observed, never deleted.
 */
public interface DeviceInstallEventRepository {

    enum Kind { BASE_INSTALL, JUMBO_INSTALL }

    /** One install as read from the log (what the worker writes). */
    record Observed(Kind kind, String productVersion, Optional<Integer> jumboTake, LocalDateTime installedAtLocal,
            String fileName) {
    }

    /** One stored install; {@code hostname} is filled only by the fleet read. */
    record Event(String deviceId, Optional<String> hostname, Kind kind, String productVersion, Optional<Integer> jumboTake,
            LocalDateTime installedAtLocal, String fileName, Instant firstObservedAt) {
    }

    /** Upserts each event (a file seen again only moves last_observed_at and may fill a missing take). Returns the count. */
    int record(String deviceId, List<Observed> events);

    /** The device's events, newest install first. */
    List<Event> forDevice(String deviceId);

    /** Events whose local install time is at or after {@code sinceLocal}, newest first, at most {@code limit}. */
    List<Event> installedSince(LocalDateTime sinceLocal, int limit);

    DeviceInstallEventRepository NONE = new DeviceInstallEventRepository() {
        @Override
        public int record(String deviceId, List<Observed> events) {
            return 0;
        }

        @Override
        public List<Event> forDevice(String deviceId) {
            return List.of();
        }

        @Override
        public List<Event> installedSince(LocalDateTime sinceLocal, int limit) {
            return List.of();
        }
    };
}
