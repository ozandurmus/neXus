package com.securityexpert.nexus.ui2.service.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneSet;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;

/**
 * The zones a Palo Alto rule set can see: the zone names of every target (firewall, virtual system) it is pushed to,
 * kept as one union because a list of zone names holds a lone {@code any} exactly when it names every zone of every
 * target (PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md, DECIDED 2026-11-15). Known only when all of that is known:
 * <ul>
 * <li>the snapshot has at least one target, and each target is an inventoried firewall with a stored zone set for its
 * virtual system context (a Panorama target without a context, an unmatched member or a firewall neXus does not
 * inventory has none);</li>
 * <li>every stored zone set was read at most {@link #MAX_AGE} before the analysis;</li>
 * <li>every zone set holds at least one zone (an empty set is not a universe a rule can be judged against).</li>
 * </ul>
 * Empty zones (no interface) are zones. Anything else is {@link #UNKNOWN} and every answer that needs it stays
 * undecided, exactly as before the zone sets were stored.
 */
final class ZoneUniverse {
    /** The longest a stored zone read may be before the analysis for it to count (PO 2026-11-15). */
    static final Duration MAX_AGE = Duration.ofHours(24);
    static final ZoneUniverse UNKNOWN = new ZoneUniverse(null);

    private final Set<String> every;

    private ZoneUniverse(Set<String> every) { this.every = every; }

    /** Known when every target has a known, non-empty zone set; a null or empty entry (a target without one) makes it unknown. */
    static ZoneUniverse of(Collection<Set<String>> targetZoneSets) {
        if (targetZoneSets == null || targetZoneSets.isEmpty()) return UNKNOWN;
        Set<String> union = new HashSet<>();
        for (Set<String> zones : targetZoneSets) {
            if (zones == null || zones.isEmpty() || zones.contains("any")) return UNKNOWN;
            union.addAll(zones);
        }
        return new ZoneUniverse(Set.copyOf(union));
    }

    /**
     * The universe of a Palo Alto snapshot from the stored zone sets (key: device id, then vsys entry name, as Panorama
     * names the target's context). Another vendor, or any target without a fresh stored set, is {@link #UNKNOWN}.
     */
    static ZoneUniverse of(PolicySnapshot snapshot, Map<String, Map<String, PanVsysZoneSet>> stored, Instant now) {
        if (snapshot == null || stored == null || !"PAN".equals(snapshot.metadata().vendor())) return UNKNOWN;
        Set<Set<String>> sets = new HashSet<>();
        boolean any = false;
        for (PolicySnapshot.Target target : snapshot.metadata().targets()) {
            any = true;
            if (target.context() == null || target.context().isEmpty()) return UNKNOWN;
            PanVsysZoneSet row = stored.getOrDefault(target.deviceId(), Map.of()).get(target.context());
            if (row == null || row.readAt() == null || row.readAt().isBefore(now.minus(MAX_AGE))) return UNKNOWN;
            sets.add(row.zones());
        }
        return any ? of(sets) : UNKNOWN;
    }

    boolean known() { return every != null; }

    /** Every zone of every target; null when unknown. */
    Set<String> zones() { return every; }

    /** Whether the zone list names every zone of every target: TRUE or FALSE, and null when the universe is unknown. */
    Boolean holdsEvery(Set<String> list) { return every == null ? null : list.containsAll(every); }
}
