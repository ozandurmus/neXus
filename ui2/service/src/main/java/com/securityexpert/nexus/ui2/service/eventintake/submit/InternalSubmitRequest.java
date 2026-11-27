package com.securityexpert.nexus.ui2.service.eventintake.submit;

import java.util.Objects;
import java.util.UUID;

/**
 * Closed-schema internal submit request representing {@code {"scope_id":"<uuid>","event_id":"<uuid>"}}
 * sent from {@code ui2-event-intake} to {@code ui2-service} on {@code POST /internal/event-triggers/submit}
 * ({@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.2} Option A).
 */
public record InternalSubmitRequest(UUID scopeId, UUID eventId) {

    public InternalSubmitRequest {
        Objects.requireNonNull(scopeId, "scopeId");
        Objects.requireNonNull(eventId, "eventId");
    }
}
