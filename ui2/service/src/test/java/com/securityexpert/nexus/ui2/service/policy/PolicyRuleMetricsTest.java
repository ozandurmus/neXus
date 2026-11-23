package com.securityexpert.nexus.ui2.service.policy;

import static com.securityexpert.nexus.ui2.service.policy.PolicyRuleMetrics.*;
import static org.junit.jupiter.api.Assertions.*;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Cell;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.PolicyObject;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot.Rule;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * UNKNOWN handling of PolicyRuleMetrics: a missing, negated, empty or budget-cut input must surface as Unknown,
 * never as Low, Always or zero. Synthetic names only.
 */
class PolicyRuleMetricsTest {
    private static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    private static final Cell EMPTY = new Cell(List.of(), false);

    private static PolicyObject object(String id, String type, String status, List<String> members, String... values) {
        return new PolicyObject(id, "OBJ-" + id.toUpperCase(Locale.ROOT), type, members, List.of(values), status);
    }

    private static Map<String, PolicyObject> base() {
        var objects = new HashMap<String, PolicyObject>();
        objects.put("any", object("any", "any", "RESOLVED", List.of()));
        objects.put("host", object("host", "address", "RESOLVED", List.of(), "ip-netmask: 192.0.2.8/32"));
        objects.put("https", object("https", "service", "RESOLVED", List.of(), "protocol/tcp/port: 443"));
        return objects;
    }

    private static Rule rule(Cell source, Cell destination, Cell service) {
        return new Rule("r1", "uuid-r1", 1, "RULE-ALPHA-01", true, source, destination, service, EMPTY, "accept", "Log", "",
            Map.of());
    }

    private static Rule rule(String source, String destination, String service) {
        return rule(new Cell(List.of(source), false), new Cell(List.of(destination), false), new Cell(List.of(service), false));
    }

    private static PolicySchedule activeSchedule() {
        return new PolicySchedule("one-time", "2020-01-01T00:00", "2030-01-01T00:00", List.of(), "UTC", true);
    }

    private static PolicySchedule expiredSchedule() {
        return new PolicySchedule("one-time", "2020-01-01T00:00", "2020-01-02T00:00", List.of(), "UTC", true);
    }

    private static Rule timedRule(String scheduleId) {
        return new Rule("r1", "uuid-r1", 1, "RULE-ALPHA-01", true, EMPTY, EMPTY, EMPTY, EMPTY, "accept", "Log", "",
            Map.of("time", List.of(scheduleId)));
    }

    private static PolicyObject scheduleObject(String id, PolicySchedule schedule) {
        return new PolicyObject(id, "OBJ-" + id.toUpperCase(Locale.ROOT), "time", List.of(), List.of(), "RESOLVED", schedule);
    }

    @SuppressWarnings("unchecked")
    private static List<String> reasons(Map<String, Object> verdict) { return (List<String>) verdict.get("reasons"); }

    // ---- time(): status must be Unknown when the schedule input is missing or cannot be evaluated ----

    @Test void timeWithoutReferencesIsUnknownForCheckPoint() {
        var time = PolicyRuleMetrics.time(rule("any", "host", "https"), Map.of(), NOW, "CP");
        assertEquals("unknown", time.status());
        assertFalse(time.expiring());
    }

    @Test void timeWithoutReferencesIsAlwaysOutsideCheckPointWhenCollected() {
        var time = PolicyRuleMetrics.time(rule("any", "host", "https"), Map.of(), NOW, "PAN");
        assertEquals("always", time.status());
    }

    @Test void timeWithoutReferencesIsUnknownWhenMarkedUncollected() {
        var uncollected = new Rule("r1", "uuid-r1", 1, "RULE-ALPHA-01", true, EMPTY, EMPTY, EMPTY, EMPTY, "accept", "Log", "",
            Map.of("time-uncollected", List.of("true")));
        assertEquals("unknown", PolicyRuleMetrics.time(uncollected, Map.of(), NOW, "PAN").status());
    }

    @Test void timeWithMissingScheduleObjectIsUnknownNotExpired() {
        var time = PolicyRuleMetrics.time(timedRule("sched-missing"), Map.of(), NOW);
        assertEquals("unknown", time.status());
        assertTrue(time.schedules().isEmpty());
        assertFalse(time.expiring());
    }

    @Test void timeWithUnresolvedScheduleObjectIsUnknown() {
        var objects = Map.of("sched-a", new PolicyObject("sched-a", "OBJ-SCHED-A", "time", List.of(), List.of(), "UNRESOLVED",
            activeSchedule()));
        assertEquals("unknown", PolicyRuleMetrics.time(timedRule("sched-a"), objects, NOW).status());
    }

    @Test void timeWithNullScheduleOnResolvedObjectIsUnknown() {
        var objects = Map.of("sched-a", scheduleObject("sched-a", null));
        assertEquals("unknown", PolicyRuleMetrics.time(timedRule("sched-a"), objects, NOW).status());
    }

    @Test void timeWithPresentActiveScheduleIsActive() {
        var objects = Map.of("sched-a", scheduleObject("sched-a", activeSchedule()));
        assertEquals("active", PolicyRuleMetrics.time(timedRule("sched-a"), objects, NOW).status());
    }

    @Test void timeWithPresentExpiredScheduleIsExpiredNotUnknown() {
        var objects = Map.of("sched-a", scheduleObject("sched-a", expiredSchedule()));
        var time = PolicyRuleMetrics.time(timedRule("sched-a"), objects, NOW);
        assertEquals("expired", time.status());
        assertFalse(time.expiring());
    }

    @Test void timeWithAnyAlternativeMissingCannotBeReportedExpired() {
        var objects = Map.of("sched-a", scheduleObject("sched-a", expiredSchedule()));
        var refs = new Rule("r1", "uuid-r1", 1, "RULE-ALPHA-01", true, EMPTY, EMPTY, EMPTY, EMPTY, "accept", "Log", "",
            Map.of("time", List.of("sched-a", "sched-missing")));
        assertEquals("unknown", PolicyRuleMetrics.time(refs, objects, NOW).status());
    }

    @Test void timeWithExhaustedBudgetIsUnknown() {
        var objects = Map.of("sched-a", scheduleObject("sched-a", activeSchedule()));
        var time = PolicyRuleMetrics.time(timedRule("sched-a"), objects, NOW, "", new int[]{0});
        assertEquals("unknown", time.status());
    }

    @Test void timeWithAnyObjectIsAlwaysAndNotUnknown() {
        var objects = Map.of("any", new PolicyObject("any", "OBJ-ANY", "any", List.of(), List.of(), "RESOLVED"));
        assertEquals("always", PolicyRuleMetrics.time(timedRule("any"), objects, NOW).status());
    }

    // ---- permissiveness(): level must be Unknown, not Low, when any selector is unassessable ----

    @Test void missingObjectMakesPermissivenessUnknownWithCause() {
        var verdict = permissiveness(rule("ghost", "host", "https"), base());
        assertEquals("Unknown", verdict.get("level"));
        assertTrue(reasons(verdict).contains(NOT_COLLECTED));
    }

    @Test void emptySelectorMakesPermissivenessUnknownWithCause() {
        var verdict = permissiveness(rule(new Cell(List.of(), false), new Cell(List.of("host"), false),
            new Cell(List.of("https"), false)), base());
        assertEquals("Unknown", verdict.get("level"));
        assertTrue(reasons(verdict).contains(EMPTY_SELECTOR));
    }

    @Test void negatedSelectorMakesPermissivenessUnknown() {
        var verdict = permissiveness(rule(new Cell(List.of("any"), true), new Cell(List.of("host"), false),
            new Cell(List.of("https"), false)), base());
        assertEquals("Unknown", verdict.get("level"));
        assertTrue(reasons(verdict).contains("Negated source needs analysis"));
    }

    @Test void exhaustedBudgetMakesPermissivenessUnknownWithBudgetCause() {
        var verdict = permissiveness(rule("any", "host", "https"), base(), new int[]{0}, "");
        assertEquals("Unknown", verdict.get("level"));
        assertTrue(reasons(verdict).contains(BUDGET));
    }

    @Test void unknownStaysUnknownEvenWhenKnownPartsScoreHigh() {
        // Any source scores 3 points, but the unassessable destination keeps the verdict Unknown.
        var verdict = permissiveness(rule("any", "ghost", "https"), base());
        assertEquals("Unknown", verdict.get("level"));
        assertEquals(3, verdict.get("score"));
    }

    @Test void presentAnySourceScoresThreeAsMedium() {
        var verdict = permissiveness(rule("any", "host", "https"), base());
        assertEquals("Medium", verdict.get("level"));
        assertEquals(3, verdict.get("score"));
        assertTrue(reasons(verdict).contains("Any source"));
    }

    @Test void presentTwoAnySelectorsScoreSixAsHigh() {
        var verdict = permissiveness(rule("any", "any", "https"), base());
        assertEquals("High", verdict.get("level"));
        assertEquals(6, verdict.get("score"));
    }

    @Test void presentSpecificRuleIsLowWithZeroScoreAndNoBroadMarker() {
        var verdict = permissiveness(rule("host", "host", "https"), base());
        assertEquals("Low", verdict.get("level"));
        assertEquals(0, verdict.get("score"));
        assertEquals(List.of(NO_BROAD), reasons(verdict));
    }

    @Test void presentLargeCidrScoresOneAsMedium() {
        var objects = base();
        objects.put("wide", object("wide", "address", "RESOLVED", List.of(), "ip-netmask: 198.51.100.0/16"));
        var verdict = permissiveness(rule("wide", "host", "https"), objects);
        assertEquals("Medium", verdict.get("level"));
        assertEquals(1, verdict.get("score"));
        assertTrue(reasons(verdict).contains("Large CIDR in source"));
    }

    @Test void presentZeroCountIsNotUnknown() {
        var verdict = permissiveness(rule("host", "host", "https"), base());
        assertNotEquals("Unknown", verdict.get("level"));
        assertFalse(reasons(verdict).contains(BUDGET));
    }
}
