package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;

/**
 * {@code GET /api/devices/status} parsed in memory (PO 2026-11-24, docs/design/MC_DEVICE_HEALTH_READ_PROPOSAL_2026_11_24.md C1):
 * a closed health state, counts, the check time and at most three scrubbed texts; everything else is discarded, and anything
 * unreadable is UNKNOWN, never OK. Fixtures use masked names and documentation addresses only.
 */
class ManagementCenterDeviceStatusTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UUID_RPT = "a1055376-3448-4a94-82db-d9bd30bbb421";
    private static final String UUID_SG = "b2055376-3448-4a94-82db-d9bd30bbb422";
    private static final String UUID_WSS = "c3055376-3448-4a94-82db-d9bd30bbb423";

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    private static ManagementCenterDeviceStatus.Answer parse(String text) throws Exception {
        return ManagementCenterDeviceStatus.parse(json(text), List.of("MD-BRAVO-02", "192.0.2.15")).orElseThrow();
    }

    /** The proposal's documented StatusData shape, one entry per state. */
    private static final String STATUS = """
            [
              {"uuid":"%s","name":"MD-BRAVO-02","checkDate":"2026-11-24T10:12:23","startDate":"2026-11-24T06:00:00","monitorState":"ERROR",
               "errors":[{"messageId":"/BC/MC/CORE/GENERAL","message":"Unknown error","properties":null}],"warnings":[]},
              {"uuid":"%s","name":"FW-TANGO-04","checkDate":"2026-11-24T10:12:23","monitorState":"OK","errors":[],"warnings":[]},
              {"uuid":"%s","name":"MD-CHARLIE-03","checkDate":"2026-11-24T10:12:23","monitorState":"INACTIVE","errors":[],"warnings":[]}
            ]""".formatted(UUID_RPT, UUID_SG, UUID_WSS);

    @Test
    void readsErrorOkAndInactiveFromMonitorStateJoinedByUuid() throws Exception {
        var answer = parse(STATUS);
        var reporter = ManagementCenterDeviceStatus.healthFor(answer, UUID_RPT);
        assertEquals("ERROR", reporter.state());
        assertEquals(1, reporter.errors());
        assertEquals(0, reporter.warnings());
        assertEquals(Optional.of("2026-11-24T10:12:23"), reporter.checkedAt());
        assertEquals(List.of("Error: Unknown error"), reporter.messages());
        var proxy = ManagementCenterDeviceStatus.healthFor(answer, UUID_SG);
        assertEquals("OK", proxy.state());
        assertEquals(List.of(), proxy.messages());
        assertEquals("INACTIVE", ManagementCenterDeviceStatus.healthFor(answer, UUID_WSS).state());
    }

    @Test
    void warningStateAndCountsComeFromTheAnswer() throws Exception {
        var answer = parse("[{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"WARNING\",\"checkDate\":\"2026-11-24T10:00:00Z\","
                + "\"warnings\":[{\"message\":\"Disk usage high\"},{\"message\":\"Licence near expiry\"}],\"errors\":[]}]");
        var health = ManagementCenterDeviceStatus.healthFor(answer, UUID_SG);
        assertEquals("WARNING", health.state());
        assertEquals(2, health.warnings());
        assertEquals(List.of("Warning: Disk usage high", "Warning: Licence near expiry"), health.messages());
        assertEquals(Optional.of("2026-11-24T10:00:00Z"), health.checkedAt());
    }

    @Test
    void anUnknownOrMissingMonitorStateIsUnknownNeverOk() throws Exception {
        // MONITORED is the documented example value, not a health colour: it is not read as OK.
        var answer = parse("[{\"uuid\":\"" + UUID_RPT + "\",\"monitorState\":\"MONITORED\",\"errors\":[],\"warnings\":[]},"
                + "{\"uuid\":\"" + UUID_SG + "\",\"errors\":[],\"warnings\":[]},"
                + "{\"uuid\":\"" + UUID_WSS + "\",\"monitorState\":42}]");
        assertEquals("UNKNOWN", ManagementCenterDeviceStatus.healthFor(answer, UUID_RPT).state());
        assertEquals("UNKNOWN", ManagementCenterDeviceStatus.healthFor(answer, UUID_SG).state());
        assertEquals("UNKNOWN", ManagementCenterDeviceStatus.healthFor(answer, UUID_WSS).state());
        assertEquals("UNKNOWN", ManagementCenterDeviceStatus.state("  "));
        assertEquals("UNKNOWN", ManagementCenterDeviceStatus.state(null));
        assertEquals("ERROR", ManagementCenterDeviceStatus.state(" error "));
    }

    @Test
    void anOkWithListedErrorsOrWarningsIsRaisedNeverLowered() throws Exception {
        var answer = parse("[{\"uuid\":\"" + UUID_RPT + "\",\"monitorState\":\"OK\",\"errors\":[{\"message\":\"x\"}]},"
                + "{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"OK\",\"warnings\":[{\"message\":\"y\"}]},"
                + "{\"uuid\":\"" + UUID_WSS + "\",\"monitorState\":\"ERROR\",\"errors\":[]}]");
        assertEquals("ERROR", ManagementCenterDeviceStatus.healthFor(answer, UUID_RPT).state());
        assertEquals("WARNING", ManagementCenterDeviceStatus.healthFor(answer, UUID_SG).state());
        assertEquals("ERROR", ManagementCenterDeviceStatus.healthFor(answer, UUID_WSS).state(), "the vendor's ERROR stays");
    }

    @Test
    void aDeviceAbsentFromTheStatusAnswerIsUnknownWithTheClosedReason() throws Exception {
        var answer = parse(STATUS);
        var missing = ManagementCenterDeviceStatus.healthFor(answer, "d4055376-3448-4a94-82db-d9bd30bbb424");
        assertEquals("UNKNOWN", missing.state());
        assertEquals(List.of(ManagementCenterDeviceStatus.NOT_IN_STATUS_ANSWER), missing.messages());
        assertEquals(List.of(ManagementCenterDeviceStatus.NOT_IN_STATUS_ANSWER),
                ManagementCenterDeviceStatus.healthFor(answer, null).messages());
    }

    @Test
    void aUuidListedTwiceIsAmbiguousAndAnInvalidUuidIsDropped() throws Exception {
        var answer = parse("[{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"OK\"},{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"ERROR\"},"
                + "{\"uuid\":\"not a uuid!\",\"monitorState\":\"OK\"},{\"name\":\"no uuid\",\"monitorState\":\"OK\"},\"text\",7]");
        assertEquals(List.of(ManagementCenterDeviceStatus.AMBIGUOUS_STATUS), ManagementCenterDeviceStatus.healthFor(answer, UUID_SG).messages());
        assertEquals(1, answer.byUuid().size());
    }

    @Test
    void anAnswerThatIsNotTheDocumentedArrayIsRejected() throws Exception {
        assertEquals(Optional.empty(), ManagementCenterDeviceStatus.parse(json("{\"devices\":[]}"), List.of()));
        assertEquals(Optional.empty(), ManagementCenterDeviceStatus.parse(json("\"text\""), List.of()));
        assertEquals(Optional.empty(), ManagementCenterDeviceStatus.parse(null, List.of()));
        assertTrue(ManagementCenterDeviceStatus.parse(json("[]"), List.of()).orElseThrow().byUuid().isEmpty());
    }

    @Test
    void messagesAreScrubbedOfAddressesHostnamesIdentifiersAndTheRunsOwnNames() throws Exception {
        var answer = parse("[{\"uuid\":\"" + UUID_RPT + "\",\"monitorState\":\"ERROR\",\"errors\":["
                + "{\"message\":\"Cannot reach 192.0.2.15 port 8082 (fe80::1:2) for MD-BRAVO-02\"},"
                + "{\"message\":\"Connection to reporter01.corp.example failed, owner admin@corp.example, id " + UUID_SG + "\"},"
                + "{\"message\":\"Serial 0123456789ABCDEF0123 rejected\"}],\"warnings\":[{\"message\":\"fourth\"}]}]");
        var health = ManagementCenterDeviceStatus.healthFor(answer, UUID_RPT);
        assertEquals(3, health.errors());
        assertEquals(1, health.warnings());
        assertEquals(3, health.messages().size(), "at most three texts, errors first");
        String all = String.join("\n", health.messages());
        for (String leaked : List.of("192.0.2.15", "fe80", "MD-BRAVO-02", "reporter01", "corp.example", "admin@", UUID_SG, "0123456789ABCDEF0123")) {
            assertFalse(all.contains(leaked), leaked + " leaked: " + all);
        }
        // the run's own host and name are replaced first ([device]); an address the list does not carry is [ip]
        assertTrue(health.messages().get(0).startsWith("Error: Cannot reach [device] port 8082 ([ip]) for [device]"), health.messages().get(0));
        assertEquals(List.of("Error: Cannot reach 203.0.113.9 now"), ManagementCenterDeviceStatus.healthFor(
                parse("[{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"ERROR\",\"errors\":[{\"message\":\"Cannot reach 203.0.113.9 now\"}]}]"), UUID_SG)
                .messages().stream().map(m -> m.replace("[ip]", "203.0.113.9")).toList());
    }

    @Test
    void datesSurviveAndAnOversizeTextIsTruncated() {
        List<Pattern> none = List.of();
        assertEquals("Base license expired 2020-12-30", ManagementCenterDeviceStatus.mask("Base license expired 2020-12-30", none));
        String big = ManagementCenterDeviceStatus.mask("word ".repeat(10_000), none);
        assertEquals(ManagementCenterDeviceStatus.MAX_MESSAGE_CHARS, big.length());
        assertTrue(big.endsWith("..."));
        assertEquals("a b", ManagementCenterDeviceStatus.mask("a\n\t\u0007 b", none), "control characters and runs of blanks collapse");
    }

    @Test
    void anOversizeAnswerKeepsTheCountsButNeverMoreThanThreeTexts() throws Exception {
        StringBuilder errors = new StringBuilder();
        for (int i = 0; i < 500; i++) {
            errors.append(i == 0 ? "" : ",").append("{\"message\":\"").append("e".repeat(2_000)).append("\"}");
        }
        var answer = parse("[{\"uuid\":\"" + UUID_RPT + "\",\"monitorState\":\"ERROR\",\"checkDate\":\"not a date\",\"errors\":[" + errors + "]}]");
        var health = ManagementCenterDeviceStatus.healthFor(answer, UUID_RPT);
        assertEquals(500, health.errors());
        assertEquals(3, health.messages().size());
        assertTrue(health.messages().stream().allMatch(m -> m.length() <= "Error: ".length() + ManagementCenterDeviceStatus.MAX_MESSAGE_CHARS));
        assertEquals(Optional.empty(), health.checkedAt(), "a check time that is not an ISO date-time is never guessed");
    }

    @Test
    void joinsTheDeviceListByUuidAndANullFunctionLeavesMembersWithoutHealth() throws Exception {
        JsonNode devices = json("[{\"uuid\":\"" + UUID_RPT + "\",\"name\":\"MD-BRAVO-02\",\"type\":\"rptr\",\"host\":\"192.0.2.15\"},"
                + "{\"uuid\":\"" + UUID_SG + "\",\"name\":\"FW-TANGO-04\",\"type\":\"sgos6x\"},"
                + "{\"uuid\":\"d4055376-3448-4a94-82db-d9bd30bbb424\",\"name\":\"MD-DELTA-04\",\"type\":\"cp\"},"
                + "{\"name\":\"MD-NOUUID-05\",\"type\":\"cp\"}]");
        var answer = ManagementCenterDeviceStatus.parse(json(STATUS), ManagementCenterDevices.identifyingTexts(devices)).orElseThrow();
        List<GridMember> members = ManagementCenterDevices.parse(devices, uuid -> ManagementCenterDeviceStatus.healthFor(answer, uuid));
        var byName = members.stream().collect(java.util.stream.Collectors.toMap(GridMember::hostName, m -> m.health().orElseThrow().state()));
        assertEquals("ERROR", byName.get("MD-BRAVO-02"));
        assertEquals("OK", byName.get("FW-TANGO-04"));
        assertEquals("UNKNOWN", byName.get("MD-DELTA-04"), "listed by the MC, absent from the status answer");
        assertEquals("UNKNOWN", byName.get("MD-NOUUID-05"));
        assertTrue(ManagementCenterDevices.parse(devices).stream().allMatch(m -> m.health().isEmpty()), "no health read: no health recorded");
    }
}
