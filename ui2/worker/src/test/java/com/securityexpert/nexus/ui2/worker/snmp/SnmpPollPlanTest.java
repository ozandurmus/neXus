package com.securityexpert.nexus.ui2.worker.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Outcome;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;

class SnmpPollPlanTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final Device CP = new Device("dev-cp-1", "check_point", "192.0.2.10", 0, null, null);
    private static final Device PAN = new Device("dev-pan-1", "palo_alto", "192.0.2.20", 0, null, null);

    private static Configuration config(String mode, String fleetCredential, Override... overrides) {
        return new Configuration(new Settings(true, 300, fleetCredential, mode, 35, 400), List.of(overrides), null, null);
    }

    @Test
    void vendorsModeUsesVendorRowsAndTheCredentialPrecedenceIsDeviceVendorFleet() {
        var c = config("VENDORS", "cred-fleet", Override.vendor("check_point", true, "cred-cp"),
                Override.vendor("palo_alto", true, null), Override.device("dev-pan-1", true, "cred-dev"));
        assertEquals(Optional.of("cred-cp"), SnmpPollPlan.decide(c, CP).orElseThrow().credentialReferenceId());
        assertEquals(Optional.of("cred-dev"), SnmpPollPlan.decide(c, PAN).orElseThrow().credentialReferenceId());
        var plain = config("VENDORS", "cred-fleet", Override.vendor("palo_alto", true, null));
        assertEquals(Optional.of("cred-fleet"), SnmpPollPlan.decide(plain, PAN).orElseThrow().credentialReferenceId());
        assertTrue(SnmpPollPlan.decide(plain, CP).isEmpty(), "no vendor row = not in scope");
        var none = config("VENDORS", null, Override.vendor("palo_alto", true, null));
        assertEquals(Optional.empty(), SnmpPollPlan.decide(none, PAN).orElseThrow().credentialReferenceId());
    }

    @Test
    void aVendorOutOfScopeOrAnExcludedDeviceIsNotPolled() {
        var c = config("VENDORS", "cred", Override.vendor("check_point", false, null), Override.vendor("palo_alto", true, null),
                Override.device("dev-pan-1", false, null));
        assertTrue(SnmpPollPlan.decide(c, CP).isEmpty());
        assertTrue(SnmpPollPlan.decide(c, PAN).isEmpty());
    }

    @Test
    void devicesModePollsOnlySelectedDevices() {
        var c = config("DEVICES", "cred", Override.vendor("check_point", true, null), Override.device("dev-pan-1", true, null));
        assertTrue(SnmpPollPlan.decide(c, CP).isEmpty(), "vendor rows do not select devices in DEVICES mode");
        assertTrue(SnmpPollPlan.decide(c, PAN).isPresent());
    }

    @Test
    void firstPollIsSpreadInsideTheIntervalAndStable() {
        for (String id : List.of("a", "dev-cp-1", "dev-pan-1", "ffffffff-0000")) {
            Instant first = SnmpPollPlan.firstPoll(NOW, id, 300);
            assertFalse(first.isBefore(NOW));
            assertTrue(first.isBefore(NOW.plusSeconds(300)));
            assertEquals(first, SnmpPollPlan.firstPoll(NOW, id, 300));
        }
    }

    @Test
    void threeMissesBackOffToThirtyMinutesAndAnAnswerResets() {
        int misses = 0;
        misses = SnmpPollPlan.misses(misses, Outcome.SNMP_NO_ANSWER);
        assertEquals(NOW.plusSeconds(300), SnmpPollPlan.nextPoll(NOW, 300, misses));
        misses = SnmpPollPlan.misses(misses, Outcome.SNMP_AUTH_REFUSED);
        assertEquals(NOW.plusSeconds(300), SnmpPollPlan.nextPoll(NOW, 300, misses));
        misses = SnmpPollPlan.misses(misses, Outcome.SNMP_NO_ANSWER);
        assertEquals(3, misses);
        assertEquals(NOW.plus(Duration.ofMinutes(30)), SnmpPollPlan.nextPoll(NOW, 300, misses));
        assertEquals(3, SnmpPollPlan.misses(misses, Outcome.SNMP_NO_CREDENTIAL), "no request sent: unchanged");
        assertEquals(0, SnmpPollPlan.misses(misses, Outcome.PARTIAL));
        assertEquals(0, SnmpPollPlan.misses(misses, Outcome.OK));
        // An interval longer than the backoff: the backoff never polls sooner than the interval.
        assertEquals(NOW.plusSeconds(3600), SnmpPollPlan.nextPoll(NOW, 3600, 3));
        assertEquals(NOW.plusSeconds(1800), SnmpPollPlan.nextPoll(NOW, 1800, 5));
    }

    @Test
    void rebootIsALowerUptimeButNotACounterWrap() {
        Duration span = Duration.ofMinutes(30);
        assertTrue(SnmpPollPlan.rebooted(5_000_000L, 12_000L, span));
        assertFalse(SnmpPollPlan.rebooted(5_000_000L, 5_030_000L, span));
        assertFalse(SnmpPollPlan.rebooted(null, 12_000L, span));
        assertFalse(SnmpPollPlan.rebooted(5_000_000L, null, span));
        long nearWrap = (1L << 32) - 100_000L;
        assertFalse(SnmpPollPlan.rebooted(nearWrap, 30_000L, span), "32-bit TimeTicks wrap");
    }
}
