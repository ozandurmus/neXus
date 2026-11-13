package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Legacy (IP-level) mode: the legacy view, the upper-bound match, and the flags that mark its verdicts IP-level. */
class PolicyHygieneLegacyModeTest {
    static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    static Map<String, PolicyObject> objects() {
        Map<String, PolicyObject> o = new HashMap<>();
        o.put("any", new PolicyObject("any", "Any", "any", List.of(), List.of(), "RESOLVED"));
        o.put("net", new PolicyObject("net", "NET-ALPHA", "network", List.of(), List.of("subnet4: 192.0.2.0", "mask-length4: 24"), "RESOLVED"));
        o.put("host", new PolicyObject("host", "HOST-ALPHA", "host", List.of(), List.of("ipv4-address: 192.0.2.5"), "RESOLVED"));
        o.put("other", new PolicyObject("other", "HOST-BRAVO", "host", List.of(), List.of("ipv4-address: 198.51.100.7"), "RESOLVED"));
        o.put("https", new PolicyObject("https", "SVC-HTTPS", "service-tcp", List.of(), List.of("protocol: tcp", "port: 443"), "RESOLVED"));
        o.put("hours", new PolicyObject("hours", "TIME-WORK", "time", List.of(), List.of(), "RESOLVED"));
        o.put("role", new PolicyObject("role", "ROLE-STAFF", "access-role", List.of(), List.of("networks: net"), "DYNAMIC"));
        o.put("dyn", new PolicyObject("dyn", "DYN-ALPHA", "dynamic-object", List.of(), List.of(), "DYNAMIC"));
        return o;
    }
    static Rule rule(String id, int number, String source, String destination, String time, String action) {
        return new Rule(id, id, number, "RULE-" + id, true, new Cell(List.of(source), false), new Cell(List.of(destination), false),
            new Cell(List.of("https"), false), new Cell(List.of(), false), action, "Log", "",
            Map.of("install-on", List.of("any"), "time", List.of(time), "layer-name", List.of("LAYER-1")));
    }
    static PolicySnapshot cp(Rule... rules) {
        return new PolicySnapshot(new Metadata("policy-1", "source-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "POL-ALPHA-01",
            "2026-11-13T00:00:00Z", "a1", List.of()), List.of(new Section("sec-1", "Section A", "CP access layer", null, List.of(rules))), objects());
    }

    @Test void upperBoundDropsNarrowingConstraintsButNeverStandsInForASelector() {
        var snapshot = cp(rule("r1", 1, "host", "any", "hours", "Accept"), rule("r2", 2, "role", "any", "any", "Accept"));
        var section = snapshot.sections().get(0);
        var resolver = PolicyNormalization.Resolver.shadowing(snapshot, new PolicyNormalization.Budget(100_000));
        assertEquals("time or content constraint", PolicyNormalization.normalize(snapshot, section, section.rules().get(0), resolver).problem());
        assertNull(PolicyNormalization.upperBound(snapshot, section, section.rules().get(0), resolver).problem());
        // An access role is no address: the upper-bound match does not use its network bound.
        assertNotNull(PolicyNormalization.upperBound(snapshot, section, section.rules().get(1), resolver).problem());
        assertNotNull(PolicyNormalization.partial(snapshot, section, section.rules().get(1), resolver), "the sound mode still bounds it");
    }

    @Test void legacyViewResolvesIdentityAndDynamicCellsAndFlagsThem() {
        var snapshot = cp(rule("r1", 1, "role", "any", "any", "Accept"), rule("r2", 2, "host", "dyn", "any", "Accept"),
            rule("r3", 3, "host", "any", "hours", "Accept"), rule("r4", 4, "host", "any", "any", "Accept"));
        var view = PolicyLegacyView.of(snapshot);
        assertEquals("group", view.objects().get("role").type());
        assertEquals(List.of("net"), view.objects().get("role").members());
        assertEquals("any", view.objects().get("dyn").type());
        assertEquals(Set.of("r1", "r2", "r3"), PolicyLegacyView.flagged(snapshot, view));
        assertSame(snapshot.objects().get("host"), view.objects().get("host"));
    }

    @Test void legacyCoversAtIpLevelWhereTheSoundModeStaysUnknown() {
        // r1 is limited to working hours; r2 has no schedule. In legacy r1 covers r2 at IP level (flagged); the sound mode
        // cannot prove r2 shadowed nor clean.
        var snapshot = cp(rule("r1", 1, "net", "any", "hours", "Accept"), rule("r2", 2, "host", "any", "any", "Drop"),
            rule("r3", 3, "other", "any", "any", "Accept"));
        var sound = PolicyHygiene.analyze(snapshot, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("UNKNOWN", sound.get("r2").shadowStatus());
        assertEquals("NOT_SHADOWED", sound.get("r3").shadowStatus());
        var view = PolicyLegacyView.of(snapshot);
        var legacy = PolicyHygiene.analyzeLegacy(view, 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("CONFLICT", legacy.get("r2").shadowStatus());
        assertEquals("r1", legacy.get("r2").coveringRuleId());
        assertTrue(PolicyLegacyView.flagged(snapshot, view).contains("r1"), "the cover rests on a dropped constraint: IP-level");
        assertEquals("NOT_SHADOWED", legacy.get("r3").shadowStatus());
    }

    @Test void legacyKeepsCollectionFailuresFailClosed() {
        var base = cp(rule("r1", 1, "host", "any", "any", "Accept"), rule("r2", 2, "other", "any", "any", "Accept"));
        var incomplete = new PolicySnapshot(base.metadata(), base.sections(), base.objects(), List.of(new CollectionFailure("layer-2", "COLLECTION_PENDING")));
        var legacy = PolicyHygiene.analyzeLegacy(PolicyLegacyView.of(incomplete), 90, NOW, PolicyHygiene.TOTAL_WORK).rules();
        assertEquals("UNKNOWN", legacy.get("r2").shadowStatus());
        assertEquals("snapshot collection incomplete", legacy.get("r2").shadowReason());
    }
}
