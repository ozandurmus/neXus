package com.securityexpert.nexus.ui2.service.eventtrigger;

import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure read-model projector for the policy-change event-trigger operator and {@code aiview}
 * ({@code role:replay_viewer}) views ({@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md}
 * §7.2; {@code AGENTS.md} mandatory AIView masking and UNKNOWN/fail-closed laws).
 *
 * <p>Decouples transport/pipeline liveness from policy silence:
 * <ul>
 *   <li>Per-vendor fleet coverage counts units in {@code CONNECTED} or {@code HEARTBEAT_OK}
 *       out of total enrolled/tracked units for that vendor.</li>
 *   <li>{@code NO_CHANGE_EVENTS_N_DAYS} ({@code N = 14}) is strictly informational (never an
 *       alarm when liveness is {@code CONNECTED} or {@code HEARTBEAT_OK}), always worded
 *       {@code "No policy-install events received for N days"} (never {@code "no changes"}), and
 *       never fabricated when {@code lastReceivedAt} is missing or the unit is
 *       {@code NOT_ENROLLED}.</li>
 *   <li>When {@code masked == true}, unit names pass through {@link TopologyNamePseudonymizer}:
 *       {@code DOM-…} (never {@code DMN-}) for {@link UnitKind#DOMAIN} and
 *       {@link UnitKind#DEVICE_GROUP}, {@code CLS-…} for {@link UnitKind#CLUSTER}, and
 *       {@code FW-…} for {@link UnitKind#DEVICE}; placeholders and IP literals resolve to
 *       {@link TopologyNamePseudonymizer#GAP} across all unit kinds without registering them in
 *       the pseudonymizer text dictionary. Zero raw hostnames, domain/cluster names, session
 *       names, or peer IP addresses are emitted.</li>
 * </ul>
 */
public final class EventTriggerOperatorProjection {

    /** Informational silence threshold in days (§7.2). */
    public static final int NO_CHANGE_EVENTS_THRESHOLD_DAYS = 14;

    /** Informational code emitted when no policy-install events were received in >= 14 days. */
    public static final String NO_CHANGE_EVENTS_CODE = "NO_CHANGE_EVENTS_N_DAYS";

    private static final String UNKNOWN_LIVENESS = "UNKNOWN";
    private static final String UNKNOWN_VENDOR = "UNKNOWN";

    private static final Set<String> HEALTHY_LIVE_STATES = Set.of("CONNECTED", "HEARTBEAT_OK");

    private static final Set<String> KNOWN_LIVENESS_STATES = Set.of(
            "CONNECTED",
            "HEARTBEAT_OK",
            "DISCONNECTED",
            "HEARTBEAT_STALE",
            "PASSIVE_NO_HEARTBEAT",
            "DEGRADED",
            "NOT_ENROLLED");

    private EventTriggerOperatorProjection() {
    }

    /** Policy/configuration collection unit kind. */
    public enum UnitKind {
        DOMAIN,
        DEVICE_GROUP,
        DEVICE,
        CLUSTER
    }

    /** Live pending/defer status for an active dirty {@code event_trigger_scope}. */
    public enum PendingDeferState {
        NONE,
        DEBOUNCING,
        DEFERRED_SOURCE_BUSY,
        DEFERRED_BACKUP_WINDOW,
        DEFERRED_WINDOW_GUARD
    }

    /** Input activity snapshot for one collection unit. */
    public record UnitActivity(
            UnitKind unitKind,
            String rawUnitName,
            String vendor,
            String livenessState,
            Instant lastDeviceEventAt,
            Instant lastReceivedAt,
            Instant lastPolicyCollectedAt,
            PendingDeferState pendingDeferState,
            Instant triggerDueAt) {

        public UnitActivity {
            Objects.requireNonNull(unitKind, "unitKind");
        }
    }

    /** Projected operator / {@code aiview} row for one collection unit. */
    public record ProjectedUnitRow(
            UnitKind unitKind,
            String displayName,
            String vendor,
            String livenessState,
            boolean livenessHealthy,
            Instant lastDeviceEventAt,
            Instant lastReceivedAt,
            Instant lastPolicyCollectedAt,
            PendingDeferState pendingDeferState,
            String pendingStatusText,
            Instant triggerDueAt,
            boolean noChangeEventsFlag,
            String noChangeEventsCode,
            String noChangeEventsMessage,
            boolean noChangeEventsAlarm) {
    }

    /** Per-vendor live event coverage summary: {@code healthyLiveUnits / totalUnits}. */
    public record VendorFleetCoverage(
            String vendor,
            int healthyLiveUnits,
            int totalUnits) {

        public VendorFleetCoverage {
            Objects.requireNonNull(vendor, "vendor");
            if (healthyLiveUnits < 0 || totalUnits < 0 || healthyLiveUnits > totalUnits) {
                throw new IllegalArgumentException(
                        "Invalid fleet coverage counts for " + vendor + ": " + healthyLiveUnits + "/" + totalUnits);
            }
        }
    }

    /** Complete projected read model for the operator or {@code aiview} screen. */
    public record Projection(
            boolean masked,
            Instant projectedAt,
            List<ProjectedUnitRow> units,
            Map<String, VendorFleetCoverage> fleetCoverageByVendor) {

        public Projection {
            Objects.requireNonNull(projectedAt, "projectedAt");
            units = units == null ? List.of() : List.copyOf(units);
            fleetCoverageByVendor = fleetCoverageByVendor == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(fleetCoverageByVendor));
        }
    }

    /**
     * Projects unit change-event activity records into an immutable read model for either the
     * operator view ({@code masked == false}) or the {@code aiview} ({@code role:replay_viewer})
     * view ({@code masked == true}).
     *
     * <p>Fails closed if {@code masked == true} and {@code pseudonymizer == null}.</p>
     */
    public static Projection project(
            List<UnitActivity> activities,
            Instant now,
            boolean masked,
            TopologyNamePseudonymizer pseudonymizer) {
        Objects.requireNonNull(now, "now");
        if (masked) {
            Objects.requireNonNull(pseudonymizer, "pseudonymizer is required when masked is true");
        }
        if (activities == null || activities.isEmpty()) {
            return new Projection(masked, now, List.of(), Map.of());
        }

        List<ProjectedUnitRow> projectedRows = new ArrayList<>(activities.size());
        Map<String, int[]> vendorCounts = new LinkedHashMap<>();

        for (UnitActivity activity : activities) {
            Objects.requireNonNull(activity, "activity");
            String vendor = normalizeVendor(activity.vendor());
            String liveness = normalizeLiveness(activity.livenessState());
            boolean livenessHealthy = HEALTHY_LIVE_STATES.contains(liveness);

            String displayName = projectUnitName(activity.unitKind(), activity.rawUnitName(), masked, pseudonymizer);

            PendingDeferState pendingState = activity.pendingDeferState() == null
                    ? PendingDeferState.NONE
                    : activity.pendingDeferState();
            Instant triggerDueAt = pendingState == PendingDeferState.NONE ? null : activity.triggerDueAt();
            String pendingStatusText = pendingStatusWording(pendingState, triggerDueAt);

            SilenceStatus silence = evaluateSilence(liveness, activity.lastReceivedAt(), now);

            projectedRows.add(new ProjectedUnitRow(
                    activity.unitKind(),
                    displayName,
                    vendor,
                    liveness,
                    livenessHealthy,
                    activity.lastDeviceEventAt(),
                    activity.lastReceivedAt(),
                    activity.lastPolicyCollectedAt(),
                    pendingState,
                    pendingStatusText,
                    triggerDueAt,
                    silence.flagged(),
                    silence.code(),
                    silence.message(),
                    false));

            int[] counts = vendorCounts.computeIfAbsent(vendor, ignored -> new int[2]);
            counts[1] += 1;
            if (livenessHealthy) {
                counts[0] += 1;
            }
        }

        Map<String, VendorFleetCoverage> coverage = new LinkedHashMap<>(vendorCounts.size());
        vendorCounts.forEach((vendor, counts) ->
                coverage.put(vendor, new VendorFleetCoverage(vendor, counts[0], counts[1])));

        return new Projection(masked, now, projectedRows, coverage);
    }

    private static String projectUnitName(
            UnitKind kind,
            String rawUnitName,
            boolean masked,
            TopologyNamePseudonymizer pseudonymizer) {
        if (rawUnitName == null || rawUnitName.isBlank()) {
            return TopologyNamePseudonymizer.GAP;
        }
        String trimmed = rawUnitName.trim();
        if (!masked) {
            return trimmed;
        }
        if (TopologyNamePseudonymizer.isNotAHostname(trimmed)) {
            return TopologyNamePseudonymizer.GAP;
        }
        return switch (kind) {
            case DOMAIN, DEVICE_GROUP -> {
                String domainPseudonym = pseudonymizer.maskDomainName(trimmed);
                if (domainPseudonym == null
                        || !domainPseudonym.startsWith("DOM-")
                        || domainPseudonym.startsWith("DMN-")) {
                    throw new IllegalStateException("Expected DOM- pseudonym prefix for domain/device-group unit");
                }
                yield domainPseudonym;
            }
            case CLUSTER -> {
                String clusterPseudonym = pseudonymizer.maskClusterName(trimmed);
                if (clusterPseudonym == null || !clusterPseudonym.startsWith("CLS-")) {
                    throw new IllegalStateException("Expected CLS- pseudonym prefix for cluster unit");
                }
                yield clusterPseudonym;
            }
            case DEVICE -> {
                String devicePseudonym = pseudonymizer.maskDeviceName(trimmed, null);
                if (devicePseudonym == null || devicePseudonym.isBlank()) {
                    yield TopologyNamePseudonymizer.GAP;
                }
                if (!devicePseudonym.startsWith("FW-") && !TopologyNamePseudonymizer.isGap(devicePseudonym)) {
                    throw new IllegalStateException("Expected FW- pseudonym or gap marker for device unit");
                }
                yield devicePseudonym;
            }
        };
    }

    private static String normalizeVendor(String vendor) {
        if (vendor == null || vendor.isBlank()) {
            return UNKNOWN_VENDOR;
        }
        return vendor.trim();
    }

    private static String normalizeLiveness(String livenessState) {
        if (livenessState == null || livenessState.isBlank()) {
            return UNKNOWN_LIVENESS;
        }
        String trimmed = livenessState.trim();
        return KNOWN_LIVENESS_STATES.contains(trimmed) ? trimmed : UNKNOWN_LIVENESS;
    }

    private static String pendingStatusWording(PendingDeferState state, Instant triggerDueAt) {
        return switch (state) {
            case NONE -> null;
            case DEBOUNCING -> triggerDueAt == null
                    ? "Change detected — collection debouncing"
                    : "Change detected — collection debouncing (due " + triggerDueAt + ")";
            case DEFERRED_SOURCE_BUSY -> "Deferred: management source busy (DEFERRED_SOURCE_BUSY)";
            case DEFERRED_BACKUP_WINDOW -> "Held for 01:45–03:00 backup window (DEFERRED_BACKUP_WINDOW)";
            case DEFERRED_WINDOW_GUARD -> "Held for scheduled collection window guard (DEFERRED_WINDOW_GUARD)";
        };
    }

    private record SilenceStatus(boolean flagged, String code, String message) {
    }

    private static SilenceStatus evaluateSilence(String liveness, Instant lastReceivedAt, Instant now) {
        if ("NOT_ENROLLED".equals(liveness) || lastReceivedAt == null) {
            return new SilenceStatus(false, null, null);
        }
        long elapsedDays = ChronoUnit.DAYS.between(lastReceivedAt, now);
        if (elapsedDays >= NO_CHANGE_EVENTS_THRESHOLD_DAYS) {
            return new SilenceStatus(
                    true,
                    NO_CHANGE_EVENTS_CODE,
                    "No policy-install events received for " + elapsedDays + " days");
        }
        return new SilenceStatus(false, null, null);
    }
}
