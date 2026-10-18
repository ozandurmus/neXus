package com.securityexpert.nexus.ui2.service.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.DeviceIdentityCheck;

/** The device view model's `identity_check` (PO decision 2026-10-18): a state word, its basis and a time, nothing else. */
class DeviceIdentityCheckBodyTest {

    @Test
    void aMatchedPinnedComparisonIsReportedVerifiedWithItsTransportAndTime() {
        Map<String, Object> body = DeviceRegistrationController.toIdentityCheckBody(new DeviceIdentityCheck("device-fixture",
                Optional.of("HTTPS_CERTIFICATE"), Optional.of("MATCH"), Optional.of(Instant.parse("2026-10-18T06:13:03Z")), false));
        assertEquals("VERIFIED", body.get("state"));
        assertEquals("HTTPS_CERTIFICATE", body.get("basis"));
        assertEquals(false, body.get("first_use"));
        assertEquals("2026-10-18T06:13:03Z", body.get("checked_at"));
    }

    @Test
    void aFailedComparisonAndAnOpenFirstContactConflictAreBothMismatches() {
        assertEquals("MISMATCH", DeviceRegistrationController.toIdentityCheckBody(new DeviceIdentityCheck("device-fixture",
                Optional.of("SSH_HOST_KEY"), Optional.of("MISMATCH"), Optional.of(Instant.parse("2026-10-18T06:13:03Z")), false)).get("state"));
        Map<String, Object> conflict = DeviceRegistrationController.toIdentityCheckBody(new DeviceIdentityCheck("device-fixture",
                Optional.empty(), Optional.empty(), Optional.empty(), true));
        assertEquals("MISMATCH", conflict.get("state"));
        assertEquals("RECORDED_IDENTITY", conflict.get("basis"));
    }

    @Test
    void aDeviceNeverComparedIsNotVerifiedAndFirstUseIsSaidSo() {
        Map<String, Object> never = DeviceRegistrationController.toIdentityCheckBody(DeviceIdentityCheck.none("device-fixture"));
        assertEquals("NOT_VERIFIED", never.get("state"));
        assertEquals("NONE", never.get("basis"));
        assertNull(never.get("checked_at"));
        Map<String, Object> firstUse = DeviceRegistrationController.toIdentityCheckBody(new DeviceIdentityCheck("device-fixture",
                Optional.of("SSH_HOST_KEY"), Optional.of("FIRST_USE"), Optional.of(Instant.parse("2026-10-18T06:13:03Z")), false));
        assertEquals("NOT_VERIFIED", firstUse.get("state"));
        assertEquals(true, firstUse.get("first_use"));
    }

    @Test
    void theBodyCarriesNoDeviceIdentifierOrFingerprint() {
        Map<String, Object> body = DeviceRegistrationController.toIdentityCheckBody(DeviceIdentityCheck.none("device-fixture"));
        assertFalse(body.toString().contains("device-fixture"));
        assertEquals(java.util.Set.of("state", "basis", "first_use", "checked_at"), body.keySet());
    }
}
