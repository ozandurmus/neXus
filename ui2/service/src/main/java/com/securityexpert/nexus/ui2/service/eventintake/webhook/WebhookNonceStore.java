package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import java.time.Instant;

/**
 * Durable replay-protection boundary for authenticated HTTPS webhook envelopes
 * ({@code event_intake_nonce} in {@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md}
 * {@code Q1} Option {@code 1B} and {@code POLICY_CHANGE_EVENT_TRIGGER_DESIGN_2026_11_26.md} {@code §4.5}).
 *
 * <p>Implementations must fail closed: if the backing store cannot durably record the nonce
 * (for example, database error or connection timeout), they must return {@link RecordOutcome#UNAVAILABLE}
 * or throw a {@link RuntimeException}; {@link WebhookEnvelopeVerifier} rejects both with HTTP {@code 503}
 * and never admits an unrecorded nonce.</p>
 */
@FunctionalInterface
public interface WebhookNonceStore {

    /**
     * Outcome of attempting an atomic {@code INSERT ... ON CONFLICT DO NOTHING} for a webhook nonce.
     */
    enum RecordOutcome {
        /** Nonce was absent and has been durably recorded until {@code expiresAt}. */
        RECORDED,
        /** Nonce was already recorded for this source; the request is a replay. */
        REPLAYED,
        /** Backing store could not confirm durable persistence; caller must fail closed (HTTP 503). */
        UNAVAILABLE
    }

    /**
     * Atomically records {@code (sourceId, nonce)} with expiry {@code expiresAt} if not already present.
     *
     * @param sourceId  enrolled opaque event-source identifier
     * @param nonce     validated request nonce from {@code X-Signal-Nonce}
     * @param expiresAt replay-retention horizon (typically {@code now + 600 s})
     * @return {@link RecordOutcome#RECORDED} if newly inserted, {@link RecordOutcome#REPLAYED} if already seen,
     *         or {@link RecordOutcome#UNAVAILABLE} when persistence cannot be confirmed
     */
    RecordOutcome recordIfAbsent(String sourceId, String nonce, Instant expiresAt);
}
