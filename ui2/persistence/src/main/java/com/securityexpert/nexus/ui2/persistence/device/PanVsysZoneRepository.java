package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Port over {@code pan_vsys_zone_set} (V229): the zone names of each virtual system of a Palo Alto firewall, as its
 * effective-running configuration holds them, with the time of that read. The policy analysis joins them to decide a
 * rule whose zone is a lone {@code any} (docs/design/PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md, DECIDED).
 */
public interface PanVsysZoneRepository {

    /**
     * Makes the device's stored sets equal to {@code sets}, all read at {@code readAt}, by difference: a virtual system
     * not in {@code sets} is removed (it is not in that configuration any more), a changed one is replaced, an unchanged
     * one with the same read time is not touched. A read older than the stored one is ignored (never moves it back).
     *
     * @return the number of rows inserted, updated or deleted
     */
    int replace(String deviceId, Instant readAt, List<PanVsysZoneSet> sets, String actorFingerprint, String actionId);

    /** The stored sets of these devices: device id, then vsys entry name. A device without any is absent. */
    Map<String, Map<String, PanVsysZoneSet>> findForDevices(Collection<String> deviceIds);

    PanVsysZoneRepository NONE = new PanVsysZoneRepository() {
        @Override
        public int replace(String deviceId, Instant readAt, List<PanVsysZoneSet> sets, String actorFingerprint, String actionId) {
            return 0;
        }

        @Override
        public Map<String, Map<String, PanVsysZoneSet>> findForDevices(Collection<String> deviceIds) {
            return Map.of();
        }
    };
}
