package com.securityexpert.nexus.ui2.worker.inventory.lifecycle;

import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.byLabel;
import static com.securityexpert.nexus.ui2.worker.inventory.lifecycle.ParserAsserts.noLeak;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.LicenseItem;

/**
 * Pins the UNKNOWN handling of the Infoblox gridwide and Management Center license parsers: an entry without an expiry,
 * an unrecognised or missing license type, a malformed date and an empty answer never become a VALID or OK state, and an
 * unknown extra field does not change the result. Synthetic values only; the secret-shaped value is built at runtime.
 */
class InfobloxMcLicenseUnknownTest {

    private static final String EXTRA_FIELD = "x_" + "vendor_note";
    private static final String EXTRA_VALUE = "synthetic note for FW-TANGO-04";
    private static final String KEY_FIELD = "k" + "ey";
    private static final String KEY_VALUE = "SYN" + "TH-KEY-" + "0001";

    /** The state of the single item of a one-entry Infoblox answer. */
    private static String infobloxState(String entry) {
        LicenseParse parsed = InfobloxGridwideLicenseParser.parse("[" + entry + "]");
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome(), "one entry must still be collected: " + entry);
        assertEquals(1, parsed.items().size());
        return parsed.items().get(0).state();
    }

    /** The state of the single item of a one-entry Management Center answer. */
    private static String mcState(String component) {
        LicenseParse parsed = ManagementCenterLicenseParser.parse("synthetic-device-01",
                "{\"components\":[" + component + "]}");
        assertEquals(ParseOutcome.COLLECTED, parsed.outcome(), "one component must still be collected: " + component);
        assertEquals(1, parsed.items().size());
        return parsed.items().get(0).state();
    }

    @Test
    void infobloxEntryWithoutAnExpiryIsUnknownNeverValid() {
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":null}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":0}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"0\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiry_date\":true}"));
    }

    @Test
    void infobloxMissingOrBlankTypeIsSkippedNotInventedAsALicense() {
        LicenseParse missing = InfobloxGridwideLicenseParser.parse(
                "[{\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":4102444800}]");
        assertEquals(ParseOutcome.UNPARSED, missing.outcome());
        assertTrue(missing.items().isEmpty());
        assertEquals(1, missing.skipped());

        LicenseParse blank = InfobloxGridwideLicenseParser.parse("[{\"type\":\"   \",\"expiration_status\":\"PERMANENT\"}]");
        assertEquals(ParseOutcome.UNPARSED, blank.outcome());
        assertTrue(blank.items().isEmpty());
    }

    @Test
    void infobloxMalformedExpiryDateIsUnknownNotValid() {
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"soon\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"2027-02-30\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"2027-13-01\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":123456789012}"));
        LicenseItem item = InfobloxGridwideLicenseParser.parse(
                "[{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"2027-02-30\"}]").items().get(0);
        assertNull(item.expiresOn(), "an unparseable date must not be stored as an expiry");
    }

    @Test
    void infobloxUnrecognisedTypeWithoutAnExpiryIsUnknown() {
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"Synthetic Module X\",\"expiration_status\":\"NOT_EXPIRED\"}"));
        assertEquals("UNKNOWN", infobloxState("{\"type\":\"Synthetic Module X\"}"));
    }

    @Test
    void infobloxUnknownExtraFieldDoesNotChangeTheResult() {
        String plain = "{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"2027-02-30\",\"limit\":\"10\"}";
        String extra = "{\"type\":\"DNS\",\"expiration_status\":\"NOT_EXPIRED\",\"expiry_date\":\"2027-02-30\",\"limit\":\"10\","
                + "\"" + EXTRA_FIELD + "\":\"" + EXTRA_VALUE + "\",\"" + KEY_FIELD + "\":\"" + KEY_VALUE + "\"}";
        LicenseItem a = InfobloxGridwideLicenseParser.parse("[" + plain + "]").items().get(0);
        LicenseItem b = InfobloxGridwideLicenseParser.parse("[" + extra + "]").items().get(0);
        assertEquals(a.state(), b.state());
        assertEquals("UNKNOWN", b.state());
        assertEquals(a.label(), b.label());
        assertEquals(a.detail(), b.detail());
        assertEquals(a.expiresOn(), b.expiresOn());
        noLeak(List.of(b), EXTRA_VALUE, KEY_VALUE);
    }

    @Test
    void infobloxNullAndWhitespaceAnswersAreEmptyNotCollected() {
        assertEquals(ParseOutcome.EMPTY, InfobloxGridwideLicenseParser.parse(null).outcome());
        assertEquals(ParseOutcome.EMPTY, InfobloxGridwideLicenseParser.parse("\n\t ").outcome());
        assertTrue(InfobloxGridwideLicenseParser.parse(null).items().isEmpty());
    }

    @Test
    void managementCenterComponentWithoutAnExpiryIsUnknownNeverValid() {
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"validity\":\"Valid\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":null,\"validity\":\"Valid\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"\",\"validity\":\"Valid\"}"));
    }

    @Test
    void managementCenterMalformedDateIsUnknownNeverValid() {
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027-02-30\",\"validity\":\"Valid\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027-13-01\",\"validity\":\"Valid\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"soon\",\"validity\":\"Valid\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027/01/15\",\"validity\":\"Valid\"}"));
        LicenseItem item = ManagementCenterLicenseParser.parse("synthetic-device-01",
                "{\"components\":[{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027-02-30\",\"validity\":\"Valid\"}]}")
                .items().get(0);
        assertNull(item.expiresOn());
    }

    @Test
    void managementCenterUnrecognisedComponentWithoutDateOrValidityIsUnknown() {
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module Z\"}"));
        assertEquals("UNKNOWN", mcState("{\"componentName\":\"Synthetic Module Z\",\"validity\":\"Unknown\"}"));
    }

    @Test
    void managementCenterMissingOrBlankComponentNameIsSkipped() {
        LicenseParse missing = ManagementCenterLicenseParser.parse("synthetic-device-01",
                "{\"components\":[{\"expirationDate\":\"2027-01-15\",\"validity\":\"Valid\"}]}");
        assertEquals(ParseOutcome.UNPARSED, missing.outcome());
        assertTrue(missing.items().isEmpty());
        assertEquals(1, missing.skipped());

        LicenseParse blank = ManagementCenterLicenseParser.parse("synthetic-device-01",
                "{\"components\":[{\"componentName\":\" \",\"expirationDate\":\"2027-01-15\",\"validity\":\"Valid\"}]}");
        assertEquals(ParseOutcome.UNPARSED, blank.outcome());
        assertTrue(blank.items().isEmpty());
    }

    @Test
    void managementCenterUnknownExtraFieldDoesNotChangeTheResult() {
        String plain = "{\"components\":[{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027-02-30\",\"validity\":\"Valid\"}]}";
        String extra = "{\"components\":[{\"componentName\":\"Synthetic Module A\",\"expirationDate\":\"2027-02-30\",\"validity\":\"Valid\","
                + "\"" + EXTRA_FIELD + "\":\"" + EXTRA_VALUE + "\",\"" + KEY_FIELD + "\":\"" + KEY_VALUE + "\"}]}";
        LicenseItem a = ManagementCenterLicenseParser.parse("synthetic-device-01", plain).items().get(0);
        LicenseItem b = ManagementCenterLicenseParser.parse("synthetic-device-01", extra).items().get(0);
        assertEquals(a.state(), b.state());
        assertEquals("UNKNOWN", b.state());
        assertEquals(a.label(), b.label());
        assertEquals(a.detail(), b.detail());
        assertFalse(b.detail() != null && b.detail().contains(EXTRA_VALUE), "an unknown field must not reach the detail");
        noLeak(List.of(b), EXTRA_VALUE, KEY_VALUE);
    }

    @Test
    void managementCenterNullAndWhitespaceAnswersAreEmptyNotCollected() {
        assertEquals(ParseOutcome.EMPTY, ManagementCenterLicenseParser.parse("synthetic-device-01", null).outcome());
        assertEquals(ParseOutcome.EMPTY, ManagementCenterLicenseParser.parse("synthetic-device-01", "\n\t ").outcome());
        assertTrue(ManagementCenterLicenseParser.parse("synthetic-device-01", null).items().isEmpty());
    }

    @Test
    void knownLabelsStayOutOfTheUnknownPath() {
        LicenseParse parsed = ManagementCenterLicenseParser.parse("synthetic-device-01",
                "{\"components\":[{\"componentName\":\"Synthetic Module B\",\"expirationDate\":\"2099-01-01\",\"validity\":\"Valid\"}]}");
        assertEquals("VALID", byLabel(parsed.items(), "Synthetic Module B").state());
    }
}
