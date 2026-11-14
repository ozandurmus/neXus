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
            assertTrue(line(r, false).contains("\"job_id\":\"job-\\\"7\\\"\""));
        }
        assertFalse(line(r, false).contains("job_id"));
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
}
