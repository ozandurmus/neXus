package com.securityexpert.nexus.ui2.service.eventtrigger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Pure, fail-closed evaluator for the 3-level DB-backed {@code event_trigger_settings} runtime
 * kill-switch (global, per-vendor, per-{@code event_source}).
 *
 * <p>Controls both {@link Checkpoint#NEW_SUBMISSION} (enqueuing new event-triggered collection
 * jobs) and {@link Checkpoint#UNCLAIMED_EXEMPTION_CLAIM} (claiming already-queued
 * {@code jobs.trigger_window_exemption} rows) immediately without pod restart
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} §1.1 Q6 safeguard #4).</p>
 *
 * <p>Precedence (strictly fail-closed):
 * <ol>
 *   <li>Unavailable/incomplete settings, missing checkpoint, or missing/blank vendor or
 *       {@code event_source} ID &rarr; {@link Decision#KILL_SWITCH_SETTINGS_UNAVAILABLE}</li>
 *   <li>Global switch disabled &rarr; {@link Decision#KILL_SWITCH_GLOBAL_OFF}</li>
 *   <li>Vendor switch disabled or missing &rarr; {@link Decision#KILL_SWITCH_VENDOR_OFF}</li>
 *   <li>Event source switch disabled or missing &rarr; {@link Decision#KILL_SWITCH_SOURCE_OFF}</li>
 *   <li>All three levels explicitly enabled &rarr; {@link Decision#PERMITTED}</li>
 * </ol>
 */
public final class EventTriggerKillSwitch {

    private static final Logger LOG = Logger.getLogger(EventTriggerKillSwitch.class.getName());

    private EventTriggerKillSwitch() {
    }

    /** Execution checkpoint guarded by the runtime kill-switch. */
    public enum Checkpoint {
        NEW_SUBMISSION,
        UNCLAIMED_EXEMPTION_CLAIM
    }

    /** Closed evaluation outcome for a kill-switch check. */
    public enum Decision {
        PERMITTED,
        KILL_SWITCH_GLOBAL_OFF,
        KILL_SWITCH_VENDOR_OFF,
        KILL_SWITCH_SOURCE_OFF,
        KILL_SWITCH_SETTINGS_UNAVAILABLE;

        /** True only when all three kill-switch levels are explicitly enabled. */
        public boolean isPermitted() {
            return this == PERMITTED;
        }
    }

    /**
     * Immutable point-in-time snapshot of {@code event_trigger_settings}.
     *
     * <p>Keys are treated as opaque identifiers (no case folding or normalization). A {@code null}
     * map marks the snapshot incomplete/unreadable and evaluates fail-closed to
     * {@link Decision#KILL_SWITCH_SETTINGS_UNAVAILABLE}.</p>
     */
    public record SettingsSnapshot(
            boolean globalEnabled,
            Map<String, Boolean> vendorEnabled,
            Map<String, Boolean> eventSourceEnabled) {

        public SettingsSnapshot {
            vendorEnabled = copyUnmodifiableOrNull(vendorEnabled);
            eventSourceEnabled = copyUnmodifiableOrNull(eventSourceEnabled);
        }

        boolean isReadable() {
            return vendorEnabled != null && eventSourceEnabled != null;
        }

        private static Map<String, Boolean> copyUnmodifiableOrNull(Map<String, Boolean> source) {
            if (source == null) {
                return null;
            }
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }

    /**
     * Evaluates the 3-level kill-switch against a pre-loaded {@link SettingsSnapshot}.
     */
    public static Decision evaluate(
            SettingsSnapshot settings,
            Checkpoint checkpoint,
            String vendor,
            String eventSourceId) {
        if (settings == null || !settings.isReadable()) {
            LOG.warning("[EVENT_TRIGGER_KILL_SWITCH] checkpoint=" + checkpoint
                    + ": settings snapshot unavailable or incomplete; failing closed");
            return Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE;
        }
        if (checkpoint == null || vendor == null || vendor.isBlank()
                || eventSourceId == null || eventSourceId.isBlank()) {
            LOG.warning("[EVENT_TRIGGER_KILL_SWITCH] checkpoint=" + checkpoint
                    + ": missing or blank evaluation input (checkpoint/vendor/eventSourceId); failing closed");
            return Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE;
        }
        if (!settings.globalEnabled()) {
            return Decision.KILL_SWITCH_GLOBAL_OFF;
        }
        if (!Boolean.TRUE.equals(settings.vendorEnabled().get(vendor))) {
            return Decision.KILL_SWITCH_VENDOR_OFF;
        }
        if (!Boolean.TRUE.equals(settings.eventSourceEnabled().get(eventSourceId))) {
            return Decision.KILL_SWITCH_SOURCE_OFF;
        }
        return Decision.PERMITTED;
    }

    /**
     * Evaluates the 3-level kill-switch by loading a fresh snapshot from {@code settingsSupplier};
     * any {@code null} supplier, {@code null} snapshot, or runtime read failure is logged and fails
     * closed to {@link Decision#KILL_SWITCH_SETTINGS_UNAVAILABLE}.
     */
    public static Decision evaluate(
            Supplier<SettingsSnapshot> settingsSupplier,
            Checkpoint checkpoint,
            String vendor,
            String eventSourceId) {
        if (settingsSupplier == null) {
            LOG.warning("[EVENT_TRIGGER_KILL_SWITCH] checkpoint=" + checkpoint
                    + ": settings supplier is null; failing closed");
            return Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE;
        }
        SettingsSnapshot snapshot;
        try {
            snapshot = settingsSupplier.get();
        } catch (RuntimeException unreadable) {
            LOG.log(
                    Level.WARNING,
                    "[EVENT_TRIGGER_KILL_SWITCH] checkpoint=" + checkpoint
                            + ": settings load failed (" + unreadable.getClass().getSimpleName()
                            + "); failing closed",
                    unreadable);
            return Decision.KILL_SWITCH_SETTINGS_UNAVAILABLE;
        }
        return evaluate(snapshot, checkpoint, vendor, eventSourceId);
    }

    /** Convenience checkpoint wrapper for new event-triggered job submission. */
    public static Decision evaluateNewSubmission(
            SettingsSnapshot settings,
            String vendor,
            String eventSourceId) {
        return evaluate(settings, Checkpoint.NEW_SUBMISSION, vendor, eventSourceId);
    }

    /** Convenience checkpoint wrapper for claiming unclaimed {@code trigger_window_exemption} jobs. */
    public static Decision evaluateUnclaimedExemptionClaim(
            SettingsSnapshot settings,
            String vendor,
            String eventSourceId) {
        return evaluate(settings, Checkpoint.UNCLAIMED_EXEMPTION_CLAIM, vendor, eventSourceId);
    }
}
