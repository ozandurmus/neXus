package com.securityexpert.nexus.ui2.worker.snmp;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Outcome;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;

/**
 * Pure scheduling rules of design section 5 and the option-B scope of section 11: which devices are in scope, which
 * credential reference each uses, when the first poll of a device falls, the next poll after an outcome, and reboot
 * detection from sysUpTime.
 */
public final class SnmpPollPlan {
    private SnmpPollPlan() {}

    /** Three polls in a row without an answer (or refused) and the device is polled every 30 minutes. */
    public static final int MISSES_BEFORE_BACKOFF = 3;
    public static final Duration BACKOFF = Duration.ofMinutes(30);
    /** TimeTicks is a 32-bit counter (hundredths of a second); it wraps after about 497 days. */
    static final long TIMETICKS_WRAP = 1L << 32;

    /** In scope, with the credential reference to use (empty = none named anywhere). */
    public record Decision(Optional<String> credentialReferenceId) {}

    /**
     * Scope: VENDORS mode polls a device whose vendor row is in scope; DEVICES mode polls only devices with a device row
     * in scope. A device row with in_scope = false excludes the device in both modes. Credential: device row, then vendor
     * row, then the fleet default.
     */
    public static Optional<Decision> decide(Configuration c, Device d) {
        Override vendorRow = null;
        Override deviceRow = null;
        for (Override o : c.overrides()) {
            if ("VENDOR".equals(o.scopeKind()) && o.vendor().equals(d.vendor())) vendorRow = o;
            if ("DEVICE".equals(o.scopeKind()) && o.deviceId().equals(d.deviceId())) deviceRow = o;
        }
        if (deviceRow != null && !deviceRow.inScope()) return Optional.empty();
        boolean inScope = "DEVICES".equals(c.settings().scopeMode())
                ? deviceRow != null && deviceRow.inScope()
                : vendorRow != null && vendorRow.inScope();
        if (!inScope) return Optional.empty();
        String credential = deviceRow != null && deviceRow.credentialReferenceId() != null ? deviceRow.credentialReferenceId()
                : vendorRow != null && vendorRow.credentialReferenceId() != null ? vendorRow.credentialReferenceId()
                : c.settings().defaultCredentialReferenceId();
        return Optional.of(new Decision(Optional.ofNullable(credential)));
    }

    /** The first poll of a device: spread over the interval by a stable function of the opaque device id. */
    public static Instant firstPoll(Instant now, String deviceId, int intervalS) {
        return now.plusSeconds(Math.floorMod(deviceId.hashCode(), intervalS));
    }

    /** Misses after this outcome: an answer resets them; no answer or a refusal adds one; no request leaves them. */
    public static int misses(int previous, Outcome outcome) {
        return switch (outcome) {
            case OK, PARTIAL -> 0;
            case SNMP_NO_ANSWER, SNMP_AUTH_REFUSED -> previous + 1;
            default -> previous;
        };
    }

    /** After 3 misses the device waits 30 minutes, or the interval when that is longer: a backoff never polls sooner. */
    public static Instant nextPoll(Instant now, int intervalS, int misses) {
        return misses >= MISSES_BEFORE_BACKOFF ? now.plusSeconds(Math.max(BACKOFF.toSeconds(), intervalS))
                : now.plusSeconds(intervalS);
    }

    /**
     * A reboot between two polls: sysUpTime went down. A wrap of the 32-bit counter also goes down, so a fall from within
     * two elapsed spans of the wrap point is not a reboot.
     */
    public static boolean rebooted(Long previousCs, Long currentCs, Duration elapsed) {
        if (previousCs == null || currentCs == null || currentCs >= previousCs) return false;
        long elapsedCs = Math.max(0, elapsed.toMillis() / 10);
        return previousCs < TIMETICKS_WRAP - 2 * elapsedCs;
    }
}
