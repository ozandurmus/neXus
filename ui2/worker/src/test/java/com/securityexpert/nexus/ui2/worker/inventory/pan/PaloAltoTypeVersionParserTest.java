package com.securityexpert.nexus.ui2.worker.inventory.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.transport.XmlApiSpec;

/** The type=version answer (PO 2026-10-21): documented fields only, a missing field is UNKNOWN, never a guess. Synthetic values. */
class PaloAltoTypeVersionParserTest {

    // Built at runtime: no literal here looks like a real serial or a secret.
    private static final String SERIAL = "TESTSER" + "0001";
    private static final String MODEL = "PA-" + "TESTMODEL";
    private static final String VERSION = "9." + "9." + "9-test";

    private static String body(String serial, String model, String version, String multiVsys) {
        return "<response status=\"success\"><result>"
                + (version == null ? "" : "<sw-version>" + version + "</sw-version>")
                + (multiVsys == null ? "" : "<multi-vsys>" + multiVsys + "</multi-vsys>")
                + (model == null ? "" : "<model>" + model + "</model>")
                + (serial == null ? "" : "<serial>" + serial + "</serial>") + "</result></response>";
    }

    @Test
    void theRequestIsTheDocumentedFormRequestWithTheKeyInTheHeaderOnly() {
        XmlApiSpec spec = PaloAltoTypeVersionParser.request(Map.of("X-PAN-KEY", "synthetic-key"));
        assertEquals("version", spec.type(), "the form field type=version");
        assertTrue(spec.formParams().isEmpty(), "no cmd, no key, no other field in the body");
        assertEquals(Map.of("X-PAN-KEY", "synthetic-key"), spec.headers());
        assertEquals("POST", spec.httpMethod());
        assertEquals("direct_firewall", spec.targetScope());
    }

    @Test
    void aCompleteAnswerMapsEveryDocumentedField() {
        var info = PaloAltoTypeVersionParser.parse(body(SERIAL, MODEL, VERSION, "off"));
        assertTrue(info.envelopeOk());
        assertEquals(Optional.of(SERIAL), info.serial());
        assertEquals(Optional.of(MODEL), info.model());
        assertEquals(Optional.of(VERSION), info.swVersion());
        assertEquals(Optional.of("off"), info.multiVsys());
        assertTrue(info.hasSerial());
        assertEquals(Optional.of("on"), PaloAltoTypeVersionParser.parse(body(SERIAL, MODEL, VERSION, "ON")).multiVsys());
    }

    @Test
    void whitespaceAroundAValueIsStrippedAndNothingElseIsChanged() {
        var info = PaloAltoTypeVersionParser.parse(
                "<response status=\"success\"><result>\n  <serial>  " + SERIAL + " \n</serial><model> " + MODEL + "</model></result></response>");
        assertEquals(Optional.of(SERIAL), info.serial(), "the serial exactly as received after the whitespace strip");
        assertEquals(Optional.of(MODEL), info.model());
    }

    @Test
    void aMissingFieldIsUnknownAndTheOthersAreKept() {
        var noVersion = PaloAltoTypeVersionParser.parse(body(SERIAL, MODEL, null, "off"));
        assertTrue(noVersion.envelopeOk());
        assertTrue(noVersion.swVersion().isEmpty());
        assertEquals(Optional.of(SERIAL), noVersion.serial());
        var noModel = PaloAltoTypeVersionParser.parse(body(SERIAL, null, VERSION, null));
        assertTrue(noModel.model().isEmpty() && noModel.multiVsys().isEmpty());
        assertEquals(Optional.of(VERSION), noModel.swVersion());
        var noSerial = PaloAltoTypeVersionParser.parse(body(null, MODEL, VERSION, "off"));
        assertTrue(noSerial.envelopeOk() && !noSerial.hasSerial(), "an answer with no serial has none, never a default");
        assertEquals(Optional.of(MODEL), noSerial.model());
        // A blank value is a missing value.
        assertTrue(PaloAltoTypeVersionParser.parse(body("  ", MODEL, VERSION, "off")).serial().isEmpty());
    }

    @Test
    void multiVsysIsOnlyEverOnOrOff() {
        for (String odd : new String[] {"maybe", "1", "yes", ""}) {
            assertTrue(PaloAltoTypeVersionParser.parse(body(SERIAL, MODEL, VERSION, odd)).multiVsys().isEmpty(), odd);
        }
    }

    @Test
    void anythingButAWellFormedSuccessEnvelopeIsUnusable() {
        for (String unusable : new String[] {
                null, "", "   ", "not xml at all", "<response status=\"error\" code=\"13\"><msg>denied</msg></response>",
                "<response status=\"success\"/>", "<response status=\"success\"><msg>no result</msg></response>",
                "<response><result><serial>" + SERIAL + "</serial></result></response>",
                "<other status=\"success\"><result><serial>" + SERIAL + "</serial></result></other>",
                "<response status=\"success\"><result><serial>" + SERIAL + "</serial>",
                "<!DOCTYPE response [<!ENTITY x \"y\">]><response status=\"success\"><result><serial>" + SERIAL
                        + "</serial></result></response>"}) {
            var info = PaloAltoTypeVersionParser.parse(unusable);
            assertFalse(info.envelopeOk(), String.valueOf(unusable));
            assertEquals(PaloAltoTypeVersionParser.Info.UNUSABLE, info, String.valueOf(unusable));
        }
    }

    @Test
    void aRepeatedFieldIsAmbiguousSoItIsMissing() {
        var info = PaloAltoTypeVersionParser.parse("<response status=\"success\"><result><serial>" + SERIAL
                + "</serial><serial>" + SERIAL + "</serial><model>" + MODEL + "</model></result></response>");
        assertTrue(info.envelopeOk() && info.serial().isEmpty());
        assertEquals(Optional.of(MODEL), info.model());
    }

    @Test
    void anOversizeOrControlCharacterValueIsDroppedNotTruncated() {
        assertTrue(PaloAltoTypeVersionParser.parse(body("S".repeat(PaloAltoTypeVersionParser.MAX_VALUE_CHARS + 1), MODEL, VERSION, "off"))
                .serial().isEmpty());
        assertEquals(Optional.of("S".repeat(PaloAltoTypeVersionParser.MAX_VALUE_CHARS)),
                PaloAltoTypeVersionParser.parse(body("S".repeat(PaloAltoTypeVersionParser.MAX_VALUE_CHARS), MODEL, VERSION, "off")).serial());
        assertTrue(PaloAltoTypeVersionParser.parse(body("AB\u0007CD", MODEL, VERSION, "off")).serial().isEmpty());
        String huge = "<response status=\"success\"><result><serial>" + SERIAL + "</serial><pad>"
                + "x".repeat(PaloAltoTypeVersionParser.MAX_BODY_CHARS) + "</pad></result></response>";
        assertEquals(PaloAltoTypeVersionParser.Info.UNUSABLE, PaloAltoTypeVersionParser.parse(huge),
                "a body over the output bound is not parsed at all");
    }

    @Test
    void onlyTheDocumentedFieldsAreMappedNothingElseIsInvented() {
        // The response carries no hostname, family, uptime or content version: extra tags are ignored, not mapped.
        var info = PaloAltoTypeVersionParser.parse("<response status=\"success\"><result><serial>" + SERIAL
                + "</serial><hostname>fw-synthetic</hostname><family>200</family><uptime>1 day</uptime>"
                + "<app-version>1111-2222</app-version></result></response>");
        assertEquals(Optional.of(SERIAL), info.serial());
        assertTrue(info.model().isEmpty() && info.swVersion().isEmpty() && info.multiVsys().isEmpty());
        assertEquals(5, PaloAltoTypeVersionParser.Info.class.getRecordComponents().length,
                "envelope flag, serial, model, sw-version, multi-vsys: no field beyond the documented response");
    }
}
