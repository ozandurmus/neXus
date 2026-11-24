package com.securityexpert.nexus.ui2.worker.supportbundle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link SupportBundleTextRedactor#redact(byte[])} and {@link SupportBundleTextRedactor#lines()}. Secret-shaped
 * values are assembled at runtime; all hosts and addresses are synthetic.
 */
class SupportBundleTextRedactorTest {

    private static final String SECRET = "[SECRET REDACTED]";
    private static final String VALUE = "syn" + "thetic" + "-" + "pass" + "-9";

    private final SupportBundleTextRedactor redactor = new SupportBundleTextRedactor();

    private String redact(String text) {
        return new String(redactor.redact(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    void aCleanLinePassesUnchanged() {
        assertEquals("interface eth1 up 192.0.2.10", redact("interface eth1 up 192.0.2.10"));
    }

    @Test
    void aSecretLineIsReplacedWhole() {
        assertEquals(SECRET, redact("password " + VALUE));
    }

    @Test
    void emptyInputGivesEmptyOutputAndNullIsAFailedRedaction() {
        assertEquals("", redact(""));
        assertThrows(SupportBundleTextRedactor.RedactionFailed.class, () -> redactor.redact(null));
    }

    @Test
    void aLineAlreadyCarryingTheMarkerIsNotDoubled() {
        assertEquals(SECRET, redact("password <redacted>"));
    }

    @Test
    void lineFormPinsTheSameBehaviourPerLine() {
        SupportBundleTextRedactor.LineRedactor lines = redactor.lines();
        assertEquals("hostname FW-TANGO-04", lines.line("hostname FW-TANGO-04"));
        assertEquals(SECRET, lines.line("token " + VALUE));
        assertEquals("", lines.line(""));
        assertEquals(SECRET, redactor.lines().line("password <redacted>"));
        assertThrows(SupportBundleTextRedactor.RedactionFailed.class, () -> redactor.lines().line(null));
    }
}
