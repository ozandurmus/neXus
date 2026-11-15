package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.PanVsysZoneSet;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Target;

/**
 * PAN_VSYS_ZONE_UNIVERSE_2026_11_15.md (DECIDED): the truth table of {@code zoneHolds} against the zones of the rule's
 * targets, and how those zones are assembled from the stored sets (missing, stale, empty, unmatched, another vendor).
 * Synthetic zone and device names only.
 */
class ZoneUniverseTest {
    static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");
    static final Set<String> ANY = Set.of("any");

    static ZoneUniverse universe(String... zones) { return ZoneUniverse.of(List.of(Set.of(zones))); }
    static Set<String> set(String... zones) { return Set.of(zones); }

    // ---- zoneHolds truth table ----

    @Test void aLoneAnyInnerIsDecidedOnlyWithAKnownUniverse() {
        var known = universe("ZONE-A", "ZONE-B", "ZONE-C");
        // TRUE: the list names every zone (and may name zones the targets do not have).
        assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C"), ANY, known));
        assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C", "ZONE-X"), ANY, known));
        // FALSE: a zone of the targets is missing from the list.
        assertEquals(Boolean.FALSE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B"), ANY, known));
        assertEquals(Boolean.FALSE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-X"), ANY, known));
        // UNKNOWN: no universe.
        assertNull(PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C"), ANY, ZoneUniverse.UNKNOWN));
        assertNull(PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C"), ANY));
    }
    @Test void everythingElseIsExactlyAsBeforeWhateverTheUniverse() {
        for (var universe : List.of(ZoneUniverse.UNKNOWN, universe("ZONE-A", "ZONE-B"))) {
            // an earlier any and equal sets hold
            assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(ANY, set("ZONE-A"), universe));
            assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(ANY, ANY, universe));
            assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A"), set("ZONE-A"), universe));
            // names against names
            assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B"), set("ZONE-A"), universe));
            assertEquals(Boolean.FALSE, PolicyNormalization.zoneHolds(set("ZONE-A"), set("ZONE-A", "ZONE-B"), universe));
            // any mixed with names, or an empty set, is never interpreted
            assertNull(PolicyNormalization.zoneHolds(set("any", "ZONE-A"), set("ZONE-A"), universe));
            assertNull(PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B"), set("any", "ZONE-A"), universe));
            assertNull(PolicyNormalization.zoneHolds(Set.of(), set("ZONE-A"), universe));
            assertNull(PolicyNormalization.zoneHolds(set("ZONE-A"), Set.of(), universe));
            assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("any", "ZONE-A"), set("any", "ZONE-A"), universe), "equal sets always hold");
        }
    }
    @Test void coverAndCannotCoverFollowTheTruthTable() {
        var known = universe("ZONE-A", "ZONE-B");
        var both = set("ZONE-A", "ZONE-B");
        assertTrue(PolicyNormalization.zonesCover(both, both, ANY, ANY, false, known));
        assertFalse(PolicyNormalization.zonesCannotCover(both, both, ANY, ANY, known));
        var one = set("ZONE-A");
        assertFalse(PolicyNormalization.zonesCover(one, both, ANY, ANY, false, known));
        assertTrue(PolicyNormalization.zonesCannotCover(one, both, ANY, ANY, known), "a zone of the targets is not in the earlier list");
        // Undecided: neither covers nor provably cannot.
        assertFalse(PolicyNormalization.zonesCover(both, both, ANY, ANY, false));
        assertFalse(PolicyNormalization.zonesCannotCover(both, both, ANY, ANY));
        // An intrazone later rule is held by its from side on both lists.
        assertTrue(PolicyNormalization.zonesCover(both, both, ANY, ANY, true, known));
    }

    // ---- assembling the universe ----

    static PanVsysZoneSet row(String device, String vsys, Instant readAt, String... zones) {
        return new PanVsysZoneSet(device, vsys, Set.of(zones), readAt);
    }
    static Map<String, Map<String, PanVsysZoneSet>> stored(PanVsysZoneSet... rows) {
        Map<String, Map<String, PanVsysZoneSet>> out = new HashMap<>();
        for (var r : rows) out.computeIfAbsent(r.deviceId(), k -> new HashMap<>()).put(r.vsys(), r);
        return out;
    }
    static PolicySnapshot snapshot(String vendor, Target... targets) {
        return new PolicySnapshot(new Metadata("p1", "s1", "MGR-BRAVO-01", vendor, "c1", "DOM-TANGO-01", "POL-ALPHA-01", NOW.toString(), "a1",
            List.of(targets)), List.of(), Map.of());
    }
    static Target target(String device, String context) { return new Target(device, "FW-TANGO-01", context, "IN_SYNC"); }
    static final Instant FRESH = NOW.minus(Duration.ofHours(1));

    @Test void everyTargetsZonesJoinIntoOneUniverse() {
        var u = ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1"), target("d1", "vsys2"), target("d2", "vsys1")),
            stored(row("d1", "vsys1", FRESH, "ZONE-A", "ZONE-B"), row("d1", "vsys2", FRESH, "ZONE-C"), row("d2", "vsys1", FRESH, "ZONE-A", "ZONE-D")), NOW);
        assertTrue(u.known());
        assertEquals(Set.of("ZONE-A", "ZONE-B", "ZONE-C", "ZONE-D"), u.zones());
        // A list holding every target's zones holds a lone any; one that misses a zone of any single target does not.
        assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C", "ZONE-D"), ANY, u));
        assertEquals(Boolean.FALSE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-B", "ZONE-C"), ANY, u), "only d2 has ZONE-D");
    }
    @Test void emptyZonesCountAsZones() {
        // A zone with no interface is a zone: it is in the stored set, so the universe holds it.
        var u = ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1")), stored(row("d1", "vsys1", FRESH, "ZONE-A", "ZONE-NO-INTERFACE")), NOW);
        assertEquals(Boolean.FALSE, PolicyNormalization.zoneHolds(set("ZONE-A"), ANY, u));
        assertEquals(Boolean.TRUE, PolicyNormalization.zoneHolds(set("ZONE-A", "ZONE-NO-INTERFACE"), ANY, u));
    }
    @Test void aTargetWithoutAKnownZoneSetMakesTheUniverseUnknown() {
        var rows = stored(row("d1", "vsys1", FRESH, "ZONE-A"));
        // missing virtual system, missing device, a Panorama target with no context, an unmatched member, a firewall neXus does not inventory
        for (var missing : List.of(target("d1", "vsys2"), target("d9", "vsys1"), target("d1", ""), target("ref-unmatched-member", "vsys1")))
            assertFalse(ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1"), missing), rows, NOW).known(), missing.toString());
        assertTrue(ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1")), rows, NOW).known());
        // no targets at all (a device group with only child groups)
        assertFalse(ZoneUniverse.of(snapshot("PAN"), rows, NOW).known());
        // UNKNOWN beats a FALSE the other targets would give: one gap means nothing is decided.
        var u = ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1"), target("d9", "vsys1")), rows, NOW);
        assertNull(PolicyNormalization.zoneHolds(set("ZONE-X"), ANY, u));
    }
    @Test void aZoneReadOlderThanTwentyFourHoursIsUnknown() {
        var snapshot = snapshot("PAN", target("d1", "vsys1"));
        assertTrue(ZoneUniverse.of(snapshot, stored(row("d1", "vsys1", NOW.minus(Duration.ofHours(24)), "ZONE-A")), NOW).known(),
            "exactly 24 hours is not older than 24 hours");
        assertFalse(ZoneUniverse.of(snapshot, stored(row("d1", "vsys1", NOW.minus(Duration.ofHours(24)).minusSeconds(1), "ZONE-A")), NOW).known());
        assertFalse(ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1"), target("d2", "vsys1")),
            stored(row("d1", "vsys1", FRESH, "ZONE-A"), row("d2", "vsys1", NOW.minus(Duration.ofDays(3)), "ZONE-A")), NOW).known(),
            "one stale target is enough");
    }
    @Test void aVirtualSystemWithNoZoneIsNotAUniverseARuleCanBeJudgedAgainst() {
        assertFalse(ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1")), stored(row("d1", "vsys1", FRESH)), NOW).known());
        assertFalse(ZoneUniverse.of(List.of(Set.of("ZONE-A"), Set.of())).known());
        assertFalse(ZoneUniverse.of(List.of()).known());
        assertFalse(ZoneUniverse.of((Collection<Set<String>>) null).known());
        assertFalse(ZoneUniverse.of(List.of(Set.of("any", "ZONE-A"))).known(), "a zone literally named any is never interpreted");
    }
    @Test void otherVendorsHaveNoUniverse() {
        for (String vendor : List.of("CP", "ASA", "FGT"))
            assertFalse(ZoneUniverse.of(snapshot(vendor, target("d1", "vsys1")), stored(row("d1", "vsys1", FRESH, "ZONE-A")), NOW).known(), vendor);
        assertFalse(ZoneUniverse.of(null, Map.of(), NOW).known());
        assertFalse(ZoneUniverse.of(snapshot("PAN", target("d1", "vsys1")), null, NOW).known());
    }
}
