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
        assertEquals("unknown", once.status(Instant.parse("2026-11-01T00:00:00Z"))); // inside the 26 h timezone buffer
        assertEquals("expired", once.status(Instant.parse("2026-11-02T12:00:00Z")));
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
    @Test void ruleModificationTimeIsAUtcInstantTheChangeReportCanUse() throws Exception {
        // CP's documented shape: posix milliseconds plus minute-precision ISO with a colon-less offset.
        assertEquals("2026-10-09T18:51:37.761Z", CheckPointPolicyMapper.modifiedTime(json.readTree(
            "{\"posix\":1791571897761,\"iso-8601\":\"2026-10-09T21:51+0300\"}")));
        assertEquals("2026-10-09T18:51:00Z", CheckPointPolicyMapper.modifiedTime(json.readTree("{\"iso-8601\":\"2026-10-09T21:51+0300\"}")));
        assertEquals("2026-10-01T00:00:00Z", CheckPointPolicyMapper.modifiedTime(json.readTree("{\"iso-8601\":\"2026-10-01T00:00:00Z\"}")));
        assertEquals("2026-10-09T18:51:37.761Z", CheckPointPolicyMapper.modifiedTime(json.readTree("{\"posix\":1791571897761}")));
        // Disagreeing representations (e.g. a seconds-based posix) prove nothing: the time stays UNKNOWN.
        assertNull(CheckPointPolicyMapper.modifiedTime(json.readTree("{\"posix\":1791571897,\"iso-8601\":\"2026-10-09T21:51+0300\"}")));
        // Zone-less text is kept as a local date, never promoted to an instant.
        assertEquals("2026-10-09T21:51", CheckPointPolicyMapper.modifiedTime(json.readTree("{\"iso-8601\":\"2026-10-09T21:51\"}")));
        assertNull(CheckPointPolicyMapper.modifiedTime(json.readTree("{\"iso-8601\":\"not a time\"}")));
        assertNull(CheckPointPolicyMapper.modifiedTime(json.missingNode()));
        assertNull(CheckPointPolicyMapper.modifiedTime(json.readTree("null")));
    }
    @Test void dateShapesAndTimezoneBuffer() throws Exception {
        // d-MMM-uuuu with a single-digit day and hour; colon-less ISO offset (wall-clock kept, offset ignored).
        var shape = CheckPointSchedule.parse(json.readTree("{\"start\":{\"date\":\"5-Oct-2026\",\"time\":\"9:05\"},\"end\":{\"iso-8601\":\"2026-10-09T21:51+0300\"}}"));
        assertEquals("2026-10-05T09:05", shape.start()); assertEquals("2026-10-09T21:51", shape.end());
        assertEquals("unknown", CheckPointSchedule.parse(json.readTree("{\"end\":{\"date\":\"31-Feb-2026\",\"time\":\"10:00\"},\"start-now\":true}")).kind());
        // Expiry is PROVEN only past the buffer when the timezone is not known: 2026-10-09T21:51 + 26 h = 2026-10-10T23:51Z.
        assertEquals("unknown", shape.status(Instant.parse("2026-10-10T23:50:00Z")));
        assertEquals("expired", shape.status(Instant.parse("2026-10-10T23:52:00Z")));
        assertEquals("active", shape.status(Instant.parse("2026-10-08T12:00:00Z")));
        assertEquals("upcoming", shape.status(Instant.parse("2026-10-01T12:00:00Z")));
        // A known timezone needs no buffer.
        var known = new com.securityexpert.nexus.ui2.policy.PolicySchedule("one-time", null, "2026-10-09T21:51", java.util.List.of(), "UTC", true);
        assertEquals("expired", known.status(Instant.parse("2026-10-09T21:52:00Z")));
    }
    @Test void oneTimeScheduleWithHoursRangesIsDecidedOnlyWhereSound() throws Exception {
        var node = json.readTree("{\"start\":{\"iso-8601\":\"2026-10-01T00:00:00\"},\"end\":{\"iso-8601\":\"2026-10-05T00:00:00\"},"
            + "\"hours-ranges\":[{\"enabled\":true,\"from\":\"08:00\",\"to\":\"18:00\"},{\"enabled\":false,\"from\":\"00:00\",\"to\":\"23:59\"}]}");
        var schedule = CheckPointSchedule.parse(node);
        assertEquals("expired", schedule.status(Instant.parse("2026-10-20T12:00:00Z")));
        assertEquals("active", schedule.status(Instant.parse("2026-10-02T12:00:00Z")));
        assertEquals("upcoming", schedule.status(Instant.parse("2026-10-02T20:00:00Z"))); // outside the hours: not "expired"
        assertEquals("upcoming", schedule.status(Instant.parse("2026-09-20T12:00:00Z")));
        // An unparseable hour keeps the whole schedule unknown.
        assertEquals("unknown", CheckPointSchedule.parse(json.readTree("{\"start-now\":true,\"end\":{\"iso-8601\":\"2026-10-05T00:00:00\"},\"hours-ranges\":[{\"enabled\":true,\"from\":\"x\",\"to\":\"18:00\"}]}")).kind());
    }
}
