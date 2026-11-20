package com.securityexpert.nexus.ui2.platform.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;

class JsonLogFormatterTest {

    private static String quoted(String s) {
        StringBuilder b = new StringBuilder();
        JsonLogFormatter.quote(b, s);
        return b.toString();
    }

    private static String line(LogRecord r, boolean raw) {
        return new JsonLogFormatter(raw).format(r);
    }

    @Test
    void escapesQuotesBackslashesAndNewlines() {
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\"", quoted("a\"b\\c\nd\re\tf"));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"\\u0000\\u0001\\b\\f\\u001f\\u007f\"", quoted("\u0000\u0001\b\f\u001f\u007f"));
    }

    @Test
    void escapesUnicodeAsUtf16Units() {
        assertEquals("\"\\u00fc\\u20ac\\ud83d\\ude00\\u2028\"", quoted("\u00fc\u20ac\uD83D\uDE00\u2028"));
    }

    @Test
    void oneLineWithTheRequiredFields() {
        LogRecord r = new LogRecord(Level.WARNING, "hello \"{0}\"\nnext");
        r.setParameters(new Object[] {"x"});
        r.setLoggerName("some.Logger");
        String out = line(r, false);
        assertTrue(out.endsWith("}\n"), out);
        assertEquals(1, out.chars().filter(c -> c == '\n').count(), out);
        assertTrue(out.startsWith("{\"@timestamp\":\""), out);
        assertTrue(out.contains("\"level\":\"WARN\""), out);
        assertTrue(out.contains("\"logger_name\":\"some.Logger\""), out);
        assertTrue(out.contains("\"thread_name\":\""), out);
        assertTrue(out.contains("\"message\":\"hello \\\"x\\\"\\nnext\""), out);
        assertFalse(out.contains("stack_trace"), out);
        assertFalse(out.contains("job_id"), out);
    }

    @Test
    void stackTraceIsEscapedAndMasked() {
        LogRecord r = new LogRecord(Level.SEVERE, "failed to " + LogAddressMaskerTest.v4());
        r.setThrown(new java.io.IOException("connect " + LogAddressMaskerTest.v6() + "\nsecond line"));
        String out = line(r, false);
        assertEquals(1, out.chars().filter(c -> c == '\n').count(), out);
        assertTrue(out.contains("\"level\":\"ERROR\""), out);
        assertTrue(out.contains("\"stack_trace\":\"java.io.IOException: connect <ip>\\nsecond line\\n\\tat "), out);
        assertTrue(out.contains("\"message\":\"failed to <ip>\""), out);
        assertFalse(out.contains(LogAddressMaskerTest.v4()), out);
        assertFalse(out.contains(LogAddressMaskerTest.v6()), out);
    }

    @Test
    void rawEscapeHatchKeepsAddresses() {
        String out = line(new LogRecord(Level.INFO, "peer " + LogAddressMaskerTest.v4()), true);
        assertTrue(out.contains(LogAddressMaskerTest.v4()), out);
    }

    @Test
    void jobIdAppearsOnlyInsideTheScope() {
        LogRecord r = new LogRecord(Level.INFO, "m");
        try (var scope = JobLogContext.open("job-\"7\"")) {
            String formatted = line(r, false);
            assertTrue(formatted.contains("\"correlation_id\":\"job:job-\\\"7\\\"\""), formatted);
            assertTrue(formatted.contains("\"job_id\":\"job-\\\"7\\\"\""), formatted);
        }
        String outside = line(r, false);
        assertFalse(outside.contains("job_id"), outside);
        assertFalse(outside.contains("correlation_id"), outside);
    }

    @Test
    void jobScopeCarriesCorrelationIdJobKindAndRequestCorrelationIdAndClearsAfter() {
        LogRecord r = new LogRecord(Level.INFO, "work");
        try (var scope = JobLogContext.open("42", "cp_inventory_collect", "req_12345678")) {
            String formatted = line(r, false);
            assertTrue(formatted.contains("\"correlation_id\":\"job:42\""), formatted);
            assertTrue(formatted.contains("\"job_id\":\"42\""), formatted);
            assertTrue(formatted.contains("\"job_kind\":\"cp_inventory_collect\""), formatted);
            assertTrue(formatted.contains("\"request_correlation_id\":\"req_12345678\""), formatted);
        }
        String outside = line(r, false);
        assertFalse(outside.contains("correlation_id"), outside);
        assertFalse(outside.contains("job_id"), outside);
        assertFalse(outside.contains("job_kind"), outside);
        assertFalse(outside.contains("request_correlation_id"), outside);
    }

    @Test
    void configurableFieldSetRenamesAndFiltersFromOnePlace() {
        JsonLogFields custom = new JsonLogFields(
                java.util.Map.of("@timestamp", "ts", "correlation_id", "ark_corr_id"),
                java.util.Set.of("@timestamp", "level", "message", "correlation_id"),
                java.util.Set.of("thread_name"));
        LogRecord r = new LogRecord(Level.INFO, "hello");
        r.setLoggerName("a.b.C");
        try (var scope = JobLogContext.openRequest("valid-req-1234")) {
            String out = new JsonLogFormatter(false, custom).format(r);
            assertTrue(out.contains("\"ts\":\""), out);
            assertTrue(out.contains("\"ark_corr_id\":\"valid-req-1234\""), out);
            assertFalse(out.contains("\"logger_name\""), out);
            assertFalse(out.contains("\"thread_name\""), out);
        }
    }

    @Test
    void requestIdValidationAcceptsWellFormedAndRejectsInvalid() {
        assertTrue(JsonLogFields.isValidRequestId("abcd1234"));
        assertTrue(JsonLogFields.isValidRequestId("Req_2026-11.17-ABCDEF0123456789"));
        assertTrue(JsonLogFields.isValidRequestId("a".repeat(64)));
        assertFalse(JsonLogFields.isValidRequestId(null));
        assertFalse(JsonLogFields.isValidRequestId(""));
        assertFalse(JsonLogFields.isValidRequestId("short77"));
        assertFalse(JsonLogFields.isValidRequestId("a".repeat(65)));
        assertFalse(JsonLogFields.isValidRequestId("bad space 123"));
        assertFalse(JsonLogFields.isValidRequestId("bad:colon:123"));
        assertEquals("valid_id-1234", JsonLogFields.resolveRequestId("valid_id-1234"));
        String generated = JsonLogFields.resolveRequestId("bad id!");
        assertTrue(JsonLogFields.isValidRequestId(generated), generated);
        assertEquals(36, generated.length());
    }

    @Test
    void badMessagePatternDoesNotThrow() {
        LogRecord r = new LogRecord(Level.INFO, "oops {0,bogus}");
        r.setParameters(new Object[] {"x"});
        assertTrue(line(r, false).contains("oops {0,bogus}"));
    }

    @Test
    void levelMapping() {
        assertEquals("TRACE", JsonLogFormatter.levelName(Level.FINEST));
        assertEquals("DEBUG", JsonLogFormatter.levelName(Level.FINE));
        assertEquals("DEBUG", JsonLogFormatter.levelName(Level.CONFIG));
        assertEquals("INFO", JsonLogFormatter.levelName(Level.INFO));
        assertEquals("WARN", JsonLogFormatter.levelName(Level.WARNING));
        assertEquals("ERROR", JsonLogFormatter.levelName(Level.SEVERE));
    }

    @Test
    void installUsesJsonOnlyWhenAsked() {
        Logger text = Logger.getAnonymousLogger();
        text.setUseParentHandlers(false);
        text.addHandler(new java.util.logging.ConsoleHandler());
        MaskingFormatter.install(text);
        assertTrue(text.getHandlers()[0].getFormatter() instanceof MaskingFormatter);

        Logger json = Logger.getAnonymousLogger();
        json.setUseParentHandlers(false);
        json.addHandler(new java.util.logging.ConsoleHandler());
        MaskingFormatter.install(json, true);
        MaskingFormatter.install(json, true);
        assertTrue(json.getHandlers()[0].getFormatter() instanceof JsonLogFormatter);
    }

    @Test
    void extractsBracketedEventCodeIntoEventFieldWhenPresent() {
        assertEquals("PAN_CONFIG_SYNC", JsonLogFields.extractEventCode("[PAN_CONFIG_SYNC] peer 192.0.2.4"));
        assertEquals("NOTIFY_SYSLOG_FAILED", JsonLogFields.extractEventCode("[NOTIFY_SYSLOG_FAILED]: timeout"));
        assertEquals("BACKUP", JsonLogFields.extractEventCode("[BACKUP]"));
        org.junit.jupiter.api.Assertions.assertNull(JsonLogFields.extractEventCode("plain message [PAN_CONFIG_SYNC]"));
        org.junit.jupiter.api.Assertions.assertNull(JsonLogFields.extractEventCode("[lowercase] not an event"));
        org.junit.jupiter.api.Assertions.assertNull(JsonLogFields.extractEventCode("[REDACTED_IP] masked placeholder"));

        LogRecord withEvent = new LogRecord(Level.WARNING, "[PAN_CONFIG_SYNC] peer " + LogAddressMaskerTest.v4());
        String jsonWithEvent = line(withEvent, false);
        assertTrue(jsonWithEvent.contains("\"event\":\"PAN_CONFIG_SYNC\""), jsonWithEvent);
        assertTrue(jsonWithEvent.contains("\"message\":\"[PAN_CONFIG_SYNC] peer <ip>\""), jsonWithEvent);
        assertFalse(jsonWithEvent.contains(LogAddressMaskerTest.v4()), jsonWithEvent);

        LogRecord withoutEvent = new LogRecord(Level.INFO, "ordinary log line");
        String jsonWithoutEvent = line(withoutEvent, false);
        assertFalse(jsonWithoutEvent.contains("\"event\""), jsonWithoutEvent);
    }

    @Test
    void rendersOneLogEventInBothFormatsAndKeepsMaskedValueMasked() {
        LogRecord record = new LogRecord(
                Level.WARNING, "[NOTIFY_SYSLOG_FAILED] target " + LogAddressMaskerTest.v4() + " unreachable");
        record.setLoggerName("nexus.platform.test");
        record.setThrown(new java.io.IOException("connect " + LogAddressMaskerTest.v6() + "\nretry aborted"));

        String textOut = new MaskingFormatter(new java.util.logging.SimpleFormatter(), false).format(record);
        assertFalse(textOut.trim().startsWith("{"), textOut);
        assertTrue(textOut.contains("[NOTIFY_SYSLOG_FAILED] target <ip> unreachable"), textOut);
        assertTrue(textOut.contains("IOException: connect <ip>"), textOut);
        assertFalse(textOut.contains(LogAddressMaskerTest.v4()), textOut);
        assertFalse(textOut.contains(LogAddressMaskerTest.v6()), textOut);

        String jsonOut = new JsonLogFormatter(false).format(record);
        assertEquals(1, jsonOut.chars().filter(c -> c == '\n').count(), jsonOut);
        assertTrue(jsonOut.startsWith("{\"@timestamp\":\""), jsonOut);
        assertTrue(jsonOut.contains("\"level\":\"WARN\""), jsonOut);
        assertTrue(jsonOut.contains("\"logger_name\":\"nexus.platform.test\""), jsonOut);
        assertTrue(jsonOut.contains("\"thread_name\":\""), jsonOut);
        assertTrue(jsonOut.contains("\"message\":\"[NOTIFY_SYSLOG_FAILED] target <ip> unreachable\""), jsonOut);
        assertTrue(jsonOut.contains("\"event\":\"NOTIFY_SYSLOG_FAILED\""), jsonOut);
        assertTrue(jsonOut.contains("\"stack_trace\":\"java.io.IOException: connect <ip>\\nretry aborted"), jsonOut);
        assertFalse(jsonOut.contains(LogAddressMaskerTest.v4()), jsonOut);
        assertFalse(jsonOut.contains(LogAddressMaskerTest.v6()), jsonOut);
    }
}
