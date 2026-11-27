package com.securityexpert.nexus.ui2.service.eventintake.internal;

import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Closed-schema request payload for {@code POST /internal/event-triggers/submit}
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code Q8};
 * {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.2} Option A).
 *
 * <p>Both {@code scope_id} and {@code event_id} are required opaque canonical 36-character
 * lowercase RFC 4122 UUID strings. No trimming, case-folding, or numeric coercion is performed
 * ({@code AGENTS.md} Identity law).</p>
 */
public record InternalSubmitRequest(
        @JsonProperty("scope_id") String scopeId,
        @JsonProperty("event_id") String eventId) {

    public static final int CANONICAL_UUID_LENGTH = 36;

    private static final Pattern CANONICAL_UUID_PATTERN = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    public InternalSubmitRequest {
        if (!isCanonicalUuid(scopeId)) {
            throw new IllegalArgumentException(
                    "scope_id must be a canonical 36-character lowercase UUID");
        }
        if (!isCanonicalUuid(eventId)) {
            throw new IllegalArgumentException(
                    "event_id must be a canonical 36-character lowercase UUID");
        }
    }

    /**
     * Returns {@code true} iff {@code value} is a non-null canonical 36-character lowercase UUID.
     */
    public static boolean isCanonicalUuid(String value) {
        return value != null
                && value.length() == CANONICAL_UUID_LENGTH
                && CANONICAL_UUID_PATTERN.matcher(value).matches();
    }
}
