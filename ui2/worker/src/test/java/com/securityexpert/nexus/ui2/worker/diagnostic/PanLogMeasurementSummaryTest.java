package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Synthetic text only: FW-TANGO-04 style names, 192.0.2.x addresses, secret-shaped values built at runtime. */
class PanLogMeasurementSummaryTest {
    private static final String SUCCESS_OPEN = "<response status=\"success\"><result>";
    private static final String SUCCESS_CLOSE = "</result></response>";

    private static String entry(String time, String subtype, String severity, String eventid, String cmd) {
        StringBuilder sb = new StringBuilder("<entry>");
        if (time != null) sb.append("<receive_time>").append(time).append("</receive_time>");
        if (subtype != null) sb.append("<subtype>").append(subtype).append("</subtype>");
        if (severity != null) sb.append("<severity>").append(severity).append("</severity>");
        if (eventid != null) sb.append("<eventid>").append(eventid).append("</eventid>");
        if (cmd != null) sb.append("<cmd>").append(cmd).append("</cmd>");
        return sb.append("</entry>").toString();
    }

    private static String logAnswer(String countAttribute, String... entries) {
        StringBuilder sb = new StringBuilder(SUCCESS_OPEN).append("<log><logs");
        if (countAttribute != null) sb.append(" count=\"").append(countAttribute).append('"');
        sb.append('>');
        for (String e : entries) sb.append(e);
        return sb.append("</logs></log>").append(SUCCESS_CLOSE).toString();
    }

    private static Map<String, Object> summarize(String source, String body) {
        Optional<Map<String, Object>> summary = PanLogMeasurementSummary.summarize(source, body, 1500L, 2);
        assertTrue(summary.isPresent());
        return summary.get();
    }

    @Test
    void successIsTrueOnlyForAStatusSuccessResponse() {
        assertTrue(PanLogMeasurementSummary.success(SUCCESS_OPEN + SUCCESS_CLOSE));
        assertTrue(PanLogMeasurementSummary.success("<response status='success'><result/></response>"));
        assertFalse(PanLogMeasurementSummary.success("<response status=\"error\"><msg>denied</msg></response>"));
        assertFalse(PanLogMeasurementSummary.success(""));
        assertFalse(PanLogMeasurementSummary.success(null));
        assertFalse(PanLogMeasurementSummary.success("not a response at all"));
    }

    @Test
    void enqueuedJobIdIsTheDigitsOfAJobElementOfASuccessfulAnswer() {
        assertEquals(Optional.of("12345"), PanLogMeasurementSummary.enqueuedJobId(
                SUCCESS_OPEN + "<job>12345</job>" + SUCCESS_CLOSE));
        assertEquals(Optional.of("7"), PanLogMeasurementSummary.enqueuedJobId(
                "<response status=\"success\"><result><job> 7 </job></result></response>"));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.enqueuedJobId(SUCCESS_OPEN + "<msg>queued</msg>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.enqueuedJobId(SUCCESS_OPEN + "<job>abc</job>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.enqueuedJobId(SUCCESS_OPEN + "<job>12a</job>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.enqueuedJobId(
                "<response status=\"error\"><result><job>12345</job></result></response>"));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.enqueuedJobId(""));
    }

    @Test
    void jobStatusIsTheUpperCaseStatusTokenOfTheJobElementOrEmpty() {
        assertEquals(Optional.of("FIN"), PanLogMeasurementSummary.jobStatus(
                SUCCESS_OPEN + "<job><status>FIN</status></job>" + SUCCESS_CLOSE));
        assertEquals(Optional.of("PEND"), PanLogMeasurementSummary.jobStatus(
                SUCCESS_OPEN + "<job><status> PEND </status></job>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.jobStatus(SUCCESS_OPEN + "<job>9</job>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.jobStatus(
                SUCCESS_OPEN + "<job><status>fin</status></job>" + SUCCESS_CLOSE));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.jobStatus(
                "<response status=\"error\"><job><status>FIN</status></job></response>"));
        assertEquals(Optional.empty(), PanLogMeasurementSummary.jobStatus(null));
    }

    @Test
    void summarizeRefusesEveryBodyThatIsNotALogAnswer() {
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", "", 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", "plain text", 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", "<response status=\"error\"><msg>denied</msg></response>", 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", SUCCESS_OPEN + "<job>1</job>" + SUCCESS_CLOSE, 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", "<response status=\"success\"><result><log/></result></response>", 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", "<!DOCTYPE response [<!ENTITY x SYSTEM \"file:///synthetic\">]>"
                + "<response status=\"success\"><result><log><logs>&x;</logs></log></result></response>", 0L, 0).isEmpty());
        assertTrue(PanLogMeasurementSummary.summarize("pan_system", null, 0L, 0).isEmpty());
    }

    @Test
    void aSystemAnswerSummarisesCountsFieldNamesAndSpan() {
        String body = logAnswer("3",
                entry("2026/11/20 10:00:00", "general", "informational", "ev-link-up", null),
                entry("2026/11/20 10:00:10", "general", "critical", "ev-link-down", null),
                entry("2026/11/20 10:00:20", "system event", "Informational", "ev-link-up", null));
        Map<String, Object> s = summarize("pan_system", body);

        assertEquals("MEASURED", s.get("status"));
        assertEquals(3, s.get("entries"));
        assertEquals(3, s.get("reportedCount"));
        assertEquals(false, s.get("capped"));
        assertEquals(20L, s.get("spanSeconds"));
        assertEquals(12960.0, s.get("entriesPerDayEstimate"));
        assertEquals((long) body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length, s.get("resultBytes"));
        assertEquals(1500L, s.get("enqueueToFinMs"));
        assertEquals(2, s.get("polls"));
        assertEquals(List.of("eventid", "receive_time", "severity", "subtype"), s.get("fieldNames"));
        assertEquals(0, s.get("unrecognisedFieldNames"));
        assertEquals("yyyy/MM/dd HH:mm:ss", s.get("receiveTimeFormat"));
        assertEquals(0, s.get("receiveTimeUnparsed"));
        assertEquals(Map.of("general", 2, "OTHER", 1), s.get("subtypeCounts"));
        assertEquals(Map.of("informational", 1, "critical", 1, "OTHER", 1), s.get("severityCounts"));
        assertEquals(Map.of("ev-link-down", 1, "ev-link-up", 2), s.get("eventidTopCounts"));
        assertEquals(2, s.get("eventidDistinct"));
        assertFalse(s.containsKey("cmdCounts"));
    }

    @Test
    void aFieldNameOutsideTheClosedPatternIsCountedNotStored() {
        String body = logAnswer("1", "<entry><receive_time>2026/11/20 10:00:00</receive_time><bad.name>x</bad.name></entry>");
        Map<String, Object> s = summarize("pan_system", body);
        assertEquals(List.of("receive_time"), s.get("fieldNames"));
        assertEquals(1, s.get("unrecognisedFieldNames"));
    }

    @Test
    void valuesOutsideTheEnumPatternBecomeOtherAndNeverLeaveTheSummary() {
        String secret = "tok" + "-" + "Q".repeat(20) + "-" + "9".repeat(4);
        String tooLong = "e".repeat(49);
        String body = logAnswer("3",
                entry("2026/11/20 10:00:00", "password " + secret, "urgent", "ev.with.dots", null),
                entry("2026/11/20 10:00:01", "general", null, tooLong, null),
                entry("2026/11/20 10:00:02", "general", "low", "ev-ok", null));
        Map<String, Object> s = summarize("pan_system", body);

        assertEquals(Map.of("general", 2, "OTHER", 1), s.get("subtypeCounts"));
        assertEquals(Map.of("OTHER", 2, "low", 1), s.get("severityCounts"));
        assertEquals(Map.of("OTHER", 2, "ev-ok", 1), s.get("eventidTopCounts"));
        assertFalse(s.toString().contains(secret), "a raw value must never be copied into the summary");
        assertFalse(s.toString().contains(tooLong));
        assertFalse(s.toString().contains("ev.with.dots"));
    }

    @Test
    void eventidTopIsCappedAtTheDeclaredLimitWhileTheDistinctCountIsNot() {
        List<String> entries = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String id = String.format("ev-%02d", i);
            for (int n = 0; n < i; n++) entries.add(entry("2026/11/20 10:00:00", "general", "low", id, null));
        }
        Map<String, Object> s = summarize("pan_system", logAnswer("325", entries.toArray(new String[0])));

        @SuppressWarnings("unchecked")
        Map<String, Integer> top = (Map<String, Integer>) s.get("eventidTopCounts");
        assertEquals(PanLogMeasurementSummary.EVENTID_TOP, top.size());
        assertEquals(20, PanLogMeasurementSummary.EVENTID_TOP);
        assertEquals("ev-25", top.keySet().iterator().next());
        assertEquals(Integer.valueOf(25), top.get("ev-25"));
        assertFalse(top.containsKey("ev-05"), "the 21st-largest bucket must be dropped");
        assertEquals(25, s.get("eventidDistinct"));
    }

    @Test
    void aConfigAnswerCountsCommandsInsteadOfSystemBuckets() {
        String body = logAnswer("2",
                entry("2026/11/20 10:00:00", null, null, null, "set"),
                entry("2026/11/20 10:00:01", null, null, null, "set"));
        Map<String, Object> s = summarize("pan_config", body);
        assertEquals(Map.of("set", 2), s.get("cmdCounts"));
        assertFalse(s.containsKey("subtypeCounts"));
        assertFalse(s.containsKey("eventidTopCounts"));
    }

    @Test
    void theCappedFlagTurnsOnAtTheRequestedEntryCount() {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 99; i++) rows.add(entry("2026/11/20 10:00:00", "general", "low", "ev-a", null));
        assertEquals(false, summarize("pan_system", logAnswer("99", rows.toArray(new String[0]))).get("capped"));
        rows.add(entry("2026/11/20 10:00:00", "general", "low", "ev-a", null));
        Map<String, Object> s = summarize("pan_system", logAnswer("100", rows.toArray(new String[0])));
        assertEquals(true, s.get("capped"));
        assertEquals(100, s.get("requestedEntries"));
        assertEquals(100, s.get("entries"));
    }

    @Test
    void receiveTimeFormatIsMixedWhenShapesDisagreeAndUnparsedTimesVoidTheSpan() {
        String mixed = logAnswer("2",
                entry("2026/11/20 10:00:00", "general", "low", "ev-a", null),
                entry("2026-11-20 10:00:05", "general", "low", "ev-a", null));
        Map<String, Object> m = summarize("pan_system", mixed);
        assertEquals("MIXED", m.get("receiveTimeFormat"));
        assertEquals(1, m.get("receiveTimeUnparsed"));
        assertNull(m.get("spanSeconds"));
        assertNull(m.get("entriesPerDayEstimate"));

        Map<String, Object> none = summarize("pan_system", logAnswer("1", entry(null, "general", "low", "ev-a", null)));
        assertNull(none.get("receiveTimeFormat"));
        assertNull(none.get("spanSeconds"));
    }

    @Test
    void theReportedCountIsNullUnlessItIsDigitsOnly() {
        assertNull(summarize("pan_system", logAnswer("abc")).get("reportedCount"));
        assertNull(summarize("pan_system", logAnswer(null)).get("reportedCount"));
        assertEquals(0, summarize("pan_system", logAnswer("0")).get("reportedCount"));
    }

    @Test
    void unknownCarriesTheStatusAndTheReason() {
        Map<String, Object> u = PanLogMeasurementSummary.unknown("COLLECTION_FAILED");
        assertEquals("UNKNOWN", u.get("status"));
        assertEquals("COLLECTION_FAILED", u.get("reason"));
        assertEquals(2, u.size());
    }
}
