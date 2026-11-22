package com.securityexpert.nexus.ui2.worker.discovery.pan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.discovery.pan.RawDeviceInput;
import com.securityexpert.nexus.ui2.discovery.pan.RawVirtualSystemInput;

/**
 * Direct pins for {@link PanoramaResponseParser}: UNKNOWN / absent handling
 * and identity sourcing. Synthetic XML only (FW-TANGO-04, 192.0.2.x); every
 * serial is an obviously fake literal. A missing field must never become a
 * guessed value, a display name must never become an identity key, and a
 * virtual system must never inherit a sibling's serial.
 */
class PanoramaResponseParserTest {

    private static String keyResponse(String keyElement) {
        return "<response status=\"success\"><result>" + keyElement + "</result></response>";
    }

    private static String devicesResponse(String entries) {
        return "<response status=\"success\"><result><devices>" + entries + "</devices></result></response>";
    }

    @Test
    void extractKeyIsEmptyWhenTheKeyElementIsAbsent() {
        assertEquals(Optional.empty(), PanoramaResponseParser.extractKey(keyResponse("<status>ok</status>")));
    }

    @Test
    void extractKeyReturnsTheKeyTextWhenPresent() {
        String key = "SYNTHETIC-KEY-0001";
        assertEquals(Optional.of(key), PanoramaResponseParser.extractKey(
                "<response status=\"success\"><result><key>" + key + "</key></result></response>"));
    }

    @Test
    void entryWithoutSerialOrNameCarriesAbsentIdentityAndIsNotInvented() {
        String body = devicesResponse("<entry><hostname>FW-TANGO-04</hostname>"
                + "<ip-address>192.0.2.10</ip-address></entry>");

        List<RawDeviceInput> devices = PanoramaResponseParser.extractDevices(body);

        assertEquals(1, devices.size());
        RawDeviceInput device = devices.get(0);
        assertFalse(device.stableIdentifier().isPresent(), "missing serial must stay absent: " + device);
        assertEquals("FW-TANGO-04", device.displayName());
    }

    @Test
    void displayNameIsNeverUsedAsTheStableIdentifier() {
        String body = devicesResponse("<entry><hostname>FW-JULIET-06</hostname></entry>");

        RawDeviceInput device = PanoramaResponseParser.extractDevices(body).get(0);

        assertFalse(device.stableIdentifier().isPresent());
        assertFalse("FW-JULIET-06".equals(device.stableIdentifier().value().orElse(null)));
    }

    @Test
    void deviceSerialIsCarriedVerbatimWithoutNumericNormalisation() {
        String body = devicesResponse("<entry name=\"SYN-0007\"><serial>0042SYN</serial></entry>");

        RawDeviceInput device = PanoramaResponseParser.extractDevices(body).get(0);

        assertEquals(Optional.of("0042SYN"), device.stableIdentifier().value());
    }

    @Test
    void twoVirtualSystemsYieldOneInputEachWithItsOwnSerial() {
        String body = devicesResponse("<entry name=\"SYN-A\"><serial>SYN-DEVICE-A</serial>"
                + "<vsys>"
                + "<entry name=\"vsys1\"><uniqid>SYN-VS-ONE</uniqid><display-name>tango-vs1</display-name></entry>"
                + "<entry name=\"vsys2\"><uniqid>SYN-VS-TWO</uniqid><display-name>tango-vs2</display-name></entry>"
                + "</vsys></entry>");

        List<RawVirtualSystemInput> vs = PanoramaResponseParser.extractDevices(body).get(0).virtualSystems();

        assertEquals(2, vs.size());
        assertEquals(Optional.of("SYN-VS-ONE"), vs.get(0).stableIdentifier().value());
        assertEquals(Optional.of("SYN-VS-TWO"), vs.get(1).stableIdentifier().value());
        assertEquals("tango-vs1", vs.get(0).displayName());
        assertEquals("tango-vs2", vs.get(1).displayName());
    }

    @Test
    void virtualSystemWithoutSerialIsNotJoinedToASiblingSerial() {
        String body = devicesResponse("<entry name=\"SYN-B\"><serial>SYN-DEVICE-B</serial>"
                + "<vsys>"
                + "<entry name=\"vsys1\"><uniqid>SYN-VS-ONE</uniqid></entry>"
                + "<entry><display-name>tango-vs-nameless</display-name></entry>"
                + "</vsys></entry>");

        List<RawVirtualSystemInput> vs = PanoramaResponseParser.extractDevices(body).get(0).virtualSystems();

        assertEquals(2, vs.size());
        assertEquals(Optional.of("SYN-VS-ONE"), vs.get(0).stableIdentifier().value());
        assertFalse(vs.get(1).stableIdentifier().isPresent(),
                "a VS without a serial must not take a sibling's serial: " + vs.get(1));
        assertFalse("SYN-VS-ONE".equals(vs.get(1).stableIdentifier().value().orElse(null)));
    }

    @Test
    void virtualSystemNameAttributeIsPresentationNotIdentity() {
        String body = devicesResponse("<entry name=\"SYN-D\"><serial>SYN-DEVICE-D</serial>"
                + "<vsys><entry name=\"vsys3\"><display-name>tango-vs3</display-name></entry></vsys></entry>");

        RawVirtualSystemInput vs = PanoramaResponseParser.extractDevices(body).get(0).virtualSystems().get(0);

        assertFalse(vs.stableIdentifier().isPresent(),
                "the VS name attribute is a presentation name, not a serial: " + vs);
        assertEquals("tango-vs3", vs.displayName());
    }

    @Test
    void virtualSystemDisplayNameIsNotUsedAsTheIdentifier() {
        String body = devicesResponse("<entry name=\"SYN-C\"><serial>SYN-DEVICE-C</serial>"
                + "<vsys><entry><display-name>tango-vs-only-name</display-name></entry></vsys></entry>");

        RawVirtualSystemInput vs = PanoramaResponseParser.extractDevices(body).get(0).virtualSystems().get(0);

        assertFalse(vs.stableIdentifier().isPresent(), "display name must not become identity: " + vs);
        assertTrue("tango-vs-only-name".equals(vs.displayName()));
    }
}
