package com.securityexpert.nexus.ui2.service.policy;

import static org.junit.jupiter.api.Assertions.*;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import com.securityexpert.nexus.ui2.policy.PolicySchedule;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

class PolicyRuleQueryTest {
    private final Instant now = Instant.parse("2026-10-02T12:00:00Z");
    private final Cell empty = new Cell(List.of(), false);
    private Map<String, PolicyObject> objects() {
        return Map.of("group", new PolicyObject("group", "OBJ-GROUP-01", "group", List.of("network", "range"), List.of(), "RESOLVED"),
                "network", new PolicyObject("network", "OBJ-ADDRESS-01", "address", List.of(), List.of("subnet4: 192.0.2.0", "mask-length4: 25"), "RESOLVED"),
                "range", new PolicyObject("range", "OBJ-ADDRESS-02", "address", List.of(), List.of("ip-address-first: 192.0.2.128", "ip-address-last: 192.0.2.255"), "RESOLVED"),
                "service", new PolicyObject("service", "OBJ-SERVICE-01", "service", List.of(), List.of("protocol: tcp", "port: 443"), "RESOLVED"),
                "any", new PolicyObject("any", "ANY", "any", List.of(), List.of(), "RESOLVED"));
    }
    private Rule rule(Cell source) {
        return new Rule("r1", "uuid-1", 1, "OBJ-RULE-01", true, source, new Cell(List.of("any"), false),
                new Cell(List.of("service"), false), empty, "Accept", "Log", "Synthetic comment", Map.of("from", List.of("OBJ-ZONE-01"), "source-user", List.of("OBJ-USER-01")));
    }
    private boolean match(String query, Rule rule) { return PolicyRuleQuery.compile(query, objects(), now).test(rule); }
    @Test void precedenceParenthesesAllFieldsAndGroupRangeContainment() {
        var r = rule(new Cell(List.of("group"), false));
        assertTrue(match("source.ip='192.0.2.10' AND destination.ip='192.0.2.0/24' AND service='tcp/443' AND action='accept'", r));
        assertTrue(match("source.ip='192.0.2.0/24' AND (enabled=true OR action='drop') AND NOT name='other'", r));
        assertTrue(match("name='OBJ-RULE-01' AND comment='Synthetic comment' AND user='OBJ-USER-01' AND zone.from='OBJ-ZONE-01' AND time='any' AND expired=false", r));
        assertTrue(match("name='other' OR action='accept' AND enabled=true", r));
        assertFalse(match("(name='other' OR action='accept') AND enabled=false", r));
        assertFalse(match("source.ip='198.51.100.1'", r));
        assertFalse(match("source.ip='192.0.2.0/23'", r));
    }
    @Test void unresolvedNegationAndPartialOverlapNeverInventMatches() {
        assertFalse(match("NOT source.ip='192.0.2.1'", rule(new Cell(List.of("missing"), false))));
        assertFalse(match("source.ip='192.0.2.1'", rule(new Cell(List.of("group"), true))));
        assertTrue(match("source.ip='198.51.100.1'", rule(new Cell(List.of("group"), true))));
        assertFalse(match("source.ip='192.0.2.0/23'", rule(new Cell(List.of("group"), true))));
        assertFalse(match("NOT application='any'", rule(empty)));
    }
    @Test void invalidQueriesAreRejectedWithoutExecutingAnything() {
        for (String q : List.of("source.ip='192.0.2.999'", "source.ip='192.0.2.0/33'", "device='anything'", "name='unterminated", "enabled='maybe'", "time='sometimes'", "(name='x'", "name='x' OR", "name='x';"))
            assertThrows(IllegalArgumentException.class, () -> PolicyRuleQuery.compile(q, objects(), now), q);
    }
    @Test void scheduleFilteringAndExplainablePermissiveness() {
        var schedule = new PolicySchedule("one-time", null, "2026-10-01T23:59", List.of(), "UTC", false);
        Map<String, PolicyObject> objects = new HashMap<>(objects());
        objects.put("time", new PolicyObject("time", "OBJ-TIME-01", "time", List.of(), List.of(), "RESOLVED", schedule));
        var base = rule(new Cell(List.of("any"), false));
        var timed = new Rule(base.id(), base.uuid(), 1, base.name(), true, base.source(), base.destination(), base.service(), base.application(), "Accept", "Log", "", Map.of("time", List.of("time")));
        assertTrue(PolicyRuleQuery.compile("expired=true AND time='bound'", objects, now).test(timed));
        assertEquals("High", PolicyRuleMetrics.permissiveness(base, objects).get("level"));
        assertTrue(((List<?>) PolicyRuleMetrics.permissiveness(base, objects).get("reasons")).contains("Any source"));
        assertEquals("Unknown", PolicyRuleMetrics.permissiveness(rule(empty), objects).get("level"));
    }
    @Test void broadServiceRangesAndLargeCidrsHaveReasonsAndResolveServiceQueries() {
        Map<String, PolicyObject> objects = new HashMap<>(objects());
        objects.put("service", new PolicyObject("service", "OBJ-SERVICE-01", "service", List.of(),
                List.of("protocol: tcp", "port: 1-2048"), "RESOLVED"));
        objects.put("network", new PolicyObject("network", "OBJ-ADDRESS-01", "address", List.of(),
                List.of("subnet4: 192.0.2.0", "mask-length4: 16"), "RESOLVED"));
        var r = rule(new Cell(List.of("network"), false));
        assertTrue(PolicyRuleQuery.compile("service='tcp/443'", objects, now).test(r));
        var reasons = (List<?>) PolicyRuleMetrics.permissiveness(r, objects).get("reasons");
        assertTrue(reasons.contains("Large CIDR in source")); assertTrue(reasons.contains("Broad service range"));
    }

}
