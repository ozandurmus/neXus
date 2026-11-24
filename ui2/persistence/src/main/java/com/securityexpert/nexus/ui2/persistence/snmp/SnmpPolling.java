package com.securityexpert.nexus.ui2.persistence.snmp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Value types of SNMP device metrics (V268; docs/design/SNMP_DEVICE_METRICS_DESIGN_2026_11_24.md, FROZEN). No type here
 * carries a secret, an address or a raw device answer.
 */
public final class SnmpPolling {
    private SnmpPolling() {}

    /** The pilot vendors (PO decision 4). V268 CHECKs the same set on snmp_polling_override.vendor. */
    public static final List<String> PILOT_VENDORS = List.of("check_point", "palo_alto");
    public static final Set<String> SCOPE_MODES = Set.of("VENDORS", "DEVICES");
    public static final int MIN_INTERVAL_S = 60;
    public static final int MAX_INTERVAL_S = 3600;

    /** The single settings row. {@code enabled} is the fleet switch. */
    public record Settings(boolean enabled, int intervalS, String defaultCredentialReferenceId, String scopeMode,
            int rawRetentionDays, int hourlyRetentionDays) {
        public static final Settings DEFAULTS = new Settings(false, 300, null, "VENDORS", 35, 400);

        public Settings {
            if (intervalS < MIN_INTERVAL_S || intervalS > MAX_INTERVAL_S) throw new IllegalArgumentException("interval_s");
            if (!SCOPE_MODES.contains(scopeMode)) throw new IllegalArgumentException("scope_mode");
            if (rawRetentionDays < 1 || hourlyRetentionDays < 1) throw new IllegalArgumentException("retention");
        }
    }

    /**
     * One override. VENDOR: {@code vendor} set, {@code inScope} = the vendor is polled in VENDORS mode. DEVICE:
     * {@code deviceId} set, {@code inScope} true selects the device (DEVICES mode), false excludes it (both modes).
     * {@code credentialReferenceId} null = inherit.
     */
    public record Override(String scopeKind, String vendor, String deviceId, boolean inScope,
            String credentialReferenceId) {
        public Override {
            if ("VENDOR".equals(scopeKind)) {
                if (!PILOT_VENDORS.contains(vendor) || deviceId != null) throw new IllegalArgumentException("vendor override");
            } else if ("DEVICE".equals(scopeKind)) {
                if (deviceId == null || deviceId.isBlank() || vendor != null) throw new IllegalArgumentException("device override");
            } else {
                throw new IllegalArgumentException("scope_kind");
            }
        }

        public String key() {
            return "VENDOR".equals(scopeKind) ? "vendor:" + vendor : "device:" + deviceId;
        }

        public static Override vendor(String vendor, boolean inScope, String credentialReferenceId) {
            return new Override("VENDOR", vendor, null, inScope, credentialReferenceId);
        }

        public static Override device(String deviceId, boolean inScope, String credentialReferenceId) {
            return new Override("DEVICE", null, deviceId, inScope, credentialReferenceId);
        }
    }

    /** Settings and overrides as stored, with the last change. */
    public record Configuration(Settings settings, List<Override> overrides, Instant updatedAt, String updatedBy) {
        public Configuration {
            Objects.requireNonNull(settings, "settings");
            overrides = List.copyOf(overrides);
        }
    }

    /** An eligible pilot device (enrolled, not disabled, gateway) with its first endpoint and its poll state. */
    public record Device(String deviceId, String vendor, String addressRef, int consecutiveMisses, Instant nextPollAt,
            Long lastSysUptimeCs) {
    }

    /** Why a device in scope is not polled, or how a poll ended. */
    public enum Outcome {
        OK, PARTIAL, SNMP_NO_ANSWER, SNMP_AUTH_REFUSED,
        /** No credential named for the device, or the named one cannot be resolved. */
        SNMP_NO_CREDENTIAL,
        /** The credential is not SNMPv3 authPriv with SHA-2 authentication and AES privacy (design section 2). */
        SNMP_CREDENTIAL_UNSUITABLE,
        SNMP_NO_ADDRESS,
        /** A gate row of the vendor's reads is missing or not a signed-off read: nothing is sent. */
        SNMP_GATE_MISSING;

        /** Only an answered poll or a poll that did not answer writes a sample row (V268 CHECK). */
        public boolean writesSample() {
            return this == OK || this == PARTIAL || this == SNMP_NO_ANSWER || this == SNMP_AUTH_REFUSED;
        }
    }

    /** The parsed facts of one poll. A null value did not answer (UNKNOWN), never 0. */
    public record Metrics(Long sysUptimeCs, BigDecimal cpuPct, BigDecimal cpuDataplanePct, BigDecimal memPct, Long sessions) {
        public static final Metrics NONE = new Metrics(null, null, null, null, null);

        public boolean complete() {
            return sysUptimeCs != null && cpuPct != null && memPct != null && sessions != null;
        }

        public boolean any() {
            return sysUptimeCs != null || cpuPct != null || cpuDataplanePct != null || memPct != null || sessions != null;
        }
    }

    /** What one poll writes: the sample (when {@link Outcome#writesSample()}) and the next state. */
    public record PollResult(String deviceId, Instant sampledAt, Outcome outcome, Metrics metrics, boolean rebooted,
            int consecutiveMisses, Instant nextPollAt) {
        public PollResult {
            Objects.requireNonNull(outcome, "outcome");
            metrics = outcome == Outcome.OK || outcome == Outcome.PARTIAL ? metrics : Metrics.NONE;
        }
    }

    /**
     * One calendar day of one device's samples (day in the zone the caller names). p95 is nearest-rank over the answered
     * values; a day with no answered value has null figures.
     */
    public record DailyFigures(java.time.LocalDate day, int samples, int answered, BigDecimal cpuP95, BigDecimal cpuMax,
            BigDecimal cpuDataplaneP95, BigDecimal memP95) {
    }

    /** Device health figures over a window, from answered samples only. */
    public record HealthFigures(String deviceId, int samples, int answered, BigDecimal cpuP95, BigDecimal cpuDataplaneP95,
            BigDecimal memP95, Long sessionsMax, Instant newestSampleAt, Optional<String> lastOutcome) {
    }
}
