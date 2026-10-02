package com.securityexpert.nexus.ui2.worker.policy;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.Instant;

class CheckPointScheduleTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void oneTimeEndNeverAndRecurrenceShapes() throws Exception {
        var once = CheckPointSchedule.parse(json.readTree("{\"start\":{\"iso-8601\":\"2026-10-01T00:00:00Z\"},\"end\":{\"iso-8601\":\"2026-10-31T23:59:00Z\"}}"));
        assertEquals("active", once.status(Instant.parse("2026-10-02T12:00:00Z")));
        assertEquals("expired", once.status(Instant.parse("2026-11-01T00:00:00Z")));
        assertEquals("always", CheckPointSchedule.parse(json.readTree("{\"end-never\":true}")).status(Instant.now()));
        for (String pattern : new String[]{"Daily", "Weekly", "Monthly"}) {
            var fixture = json.createObjectNode().put("end-never", true).put("start-now", true);
            var recurrence = fixture.putObject("recurrence").put("pattern", pattern).put("month", "Any");
            recurrence.putArray("weekdays").add("Fri"); recurrence.putArray("days").add("2-3");
            fixture.putArray("hours-ranges").addObject().put("enabled", true).put("from", "08:00").put("to", "18:00");
            var schedule = CheckPointSchedule.parse(fixture);
            assertEquals("active", schedule.status(Instant.parse("2026-10-02T12:00:00Z")), pattern);
            assertEquals("upcoming", schedule.status(Instant.parse("2026-10-02T20:00:00Z")), pattern);
            if (pattern.equals("Monthly")) {
                recurrence.put("month", "11");
                assertEquals("upcoming", CheckPointSchedule.parse(fixture).status(Instant.parse("2026-10-02T12:00:00Z")));
            }
        }
        var dateTime = json.createObjectNode();
        dateTime.putObject("end").put("date", "31-Oct-2026").put("time", "23:59");
        assertEquals("2026-10-31T23:59", CheckPointSchedule.parse(dateTime).end());
        assertEquals("unknown", CheckPointSchedule.parse(json.readTree("{\"recurrence\":{\"pattern\":\"unsupported\"}}")).status(Instant.now()));
    }
    @Test void timeGroupsAndMetadataMapWithoutNewReads() throws Exception {
        var meta = new com.securityexpert.nexus.ui2.policy.PolicySnapshot.Metadata("policy-1", "manager-1", "MGR-BRAVO-01", "CP", "domain-1", "DOM-TANGO-01", "OBJ-POLICY-01", "2026-10-02T00:00:00Z", "artifact-1", java.util.List.of());
        var page = json.readTree("{\"uid\":\"layer-1\",\"name\":\"OBJ-LAYER-01\",\"objects-dictionary\":[{\"uid\":\"t1\",\"type\":\"time\",\"name\":\"OBJ-TIME-01\",\"end-never\":true},{\"uid\":\"g1\",\"type\":\"time-group\",\"name\":\"OBJ-GROUP-01\",\"members\":[\"t1\"]}],\"rulebase\":[{\"type\":\"access-rule\",\"uid\":\"r1\",\"time\":[\"g1\"],\"meta-info\":{\"last-modify-time\":{\"iso-8601\":\"2026-10-01T00:00:00Z\"},\"last-modifier\":\"Synthetic editor\"}}]}");
        var snapshot = new CheckPointPolicyMapper().map(meta, java.util.List.of(page));
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.type().equals("time-group") && o.members().size() == 1));
        assertTrue(snapshot.objects().values().stream().anyMatch(o -> o.schedule() != null));
        assertTrue(snapshot.sections().get(0).rules().get(0).extras().containsKey("last-modified"));
        var fallback = (com.fasterxml.jackson.databind.node.ObjectNode) page.path("rulebase").get(0);
        fallback.remove("uid"); fallback.put("name", "OBJ-RULE-01");
        var named = new CheckPointPolicyMapper().map(meta, java.util.List.of(page)).sections().get(0).rules().get(0);
        assertEquals("", named.uuid()); assertEquals("OBJ-RULE-01", named.name());
    }
}
