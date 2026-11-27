package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Closed-schema payload for the {@code RELAY_HTTPS} structured webhook envelope
 * ({@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.5}).
 *
 * <p>Identifiers ({@code opaqueEmitterKey}, {@code opaqueTargetId}) are preserved verbatim as opaque
 * tokens — never case-folded, stripped, cast to integers, or joined against presentation hostnames
 * ({@code AGENTS.md} Identity law; {@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md}
 * {@code §1.1}).</p>
 */
public record WebhookPayload(
        @JsonProperty("signal_id") String signalId,
        @JsonProperty("message_type") MessageType messageType,
        @JsonProperty("vendor") Vendor vendor,
        @JsonProperty("event_class") EventClass eventClass,
        @JsonProperty("event_outcome") EventOutcome eventOutcome,
        @JsonProperty("opaque_emitter_key") String opaqueEmitterKey,
        @JsonProperty("opaque_target_id") String opaqueTargetId,
        @JsonProperty("device_event_time") String deviceEventTime) {

    /** Top-level webhook message discriminator. */
    public enum MessageType {
        CHANGE_EVENT,
        HEARTBEAT
    }

    /** Closed allowlist of policy/configuration change-event vendors. */
    public enum Vendor {
        CHECK_POINT,
        PALO_ALTO,
        FORTINET,
        CISCO_ASA
    }

    /** Closed allowlist of normalized change-event classes ({@code §4.7}). */
    public enum EventClass {
        POLICY_INSTALLED,
        CONFIG_COMMITTED,
        PANORAMA_PUSHED,
        MANAGER_INSTALLED,
        HEARTBEAT
    }

    /** Closed allowlist of normalized event outcomes ({@code §4.7}, {@code §6.1}). */
    public enum EventOutcome {
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    /**
     * Safe string representation that omits raw opaque target identifiers so sensitive
     * serial/device tokens are never leaked into logs or diagnostics.
     */
    @Override
    public String toString() {
        return "WebhookPayload[signalId=" + signalId
                + ", messageType=" + messageType
                + ", vendor=" + vendor
                + ", eventClass=" + eventClass
                + ", eventOutcome=" + eventOutcome
                + ", opaqueEmitterKeyPresent=" + (opaqueEmitterKey != null && !opaqueEmitterKey.isBlank())
                + ", opaqueTargetIdPresent=" + (opaqueTargetId != null && !opaqueTargetId.isBlank())
                + ", deviceEventTimePresent=" + (deviceEventTime != null && !deviceEventTime.isBlank())
                + "]";
    }
}
