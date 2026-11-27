package com.securityexpert.nexus.ui2.service.eventintake.webhook;

import java.util.Objects;

/**
 * Immutable outcome of verifying an inbound {@code RELAY_HTTPS} webhook envelope.
 *
 * <p>Guarantees that {@link #errorMessage()} and {@link #toString()} never contain secret key bytes,
 * raw request bodies, raw signature hex, or raw untrusted header/payload values.</p>
 */
public final class WebhookVerificationResult {

    /**
     * Closed verification outcome vocabulary with its mapped HTTP response status code.
     */
    public enum Status {
        ACCEPTED(200),
        INVALID_HEADERS(400),
        INVALID_PAYLOAD(400),
        UNKNOWN_KEY(401),
        TIMESTAMP_OUT_OF_WINDOW(401),
        INVALID_SIGNATURE(401),
        REPLAYED_NONCE(409),
        OVERSIZE(413),
        NOT_EVALUABLE(422),
        REPLAY_STORE_UNAVAILABLE(503);

        private final int httpStatus;

        Status(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    private final Status status;
    private final String sourceId;
    private final String matchedKid;
    private final WebhookPayload payload;
    private final String errorMessage;

    private WebhookVerificationResult(
            Status status,
            String sourceId,
            String matchedKid,
            WebhookPayload payload,
            String errorMessage) {
        this.status = Objects.requireNonNull(status, "status");
        this.sourceId = sourceId;
        this.matchedKid = matchedKid;
        this.payload = payload;
        this.errorMessage = errorMessage;
    }

    /**
     * Creates an {@link Status#ACCEPTED} result carrying the validated {@link WebhookPayload}.
     */
    public static WebhookVerificationResult accepted(String sourceId, String matchedKid, WebhookPayload payload) {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(matchedKid, "matchedKid");
        Objects.requireNonNull(payload, "payload");
        return new WebhookVerificationResult(Status.ACCEPTED, sourceId, matchedKid, payload, null);
    }

    /**
     * Creates a fail-closed rejection result with {@code payload == null} and a fixed safe diagnostic message.
     */
    public static WebhookVerificationResult rejected(
            Status status,
            String sourceId,
            String matchedKid,
            String errorMessage) {
        Objects.requireNonNull(status, "status");
        if (status == Status.ACCEPTED) {
            throw new IllegalArgumentException("Use accepted(...) for Status.ACCEPTED");
        }
        if (errorMessage == null || errorMessage.isBlank()) {
            throw new IllegalArgumentException("errorMessage must not be null or blank");
        }
        return new WebhookVerificationResult(status, sourceId, matchedKid, null, errorMessage);
    }

    public Status status() {
        return status;
    }

    public int httpStatus() {
        return status.httpStatus();
    }

    public boolean isAccepted() {
        return status == Status.ACCEPTED;
    }

    public String sourceId() {
        return sourceId;
    }

    public String matchedKid() {
        return matchedKid;
    }

    /**
     * Returns the parsed closed-schema payload when {@link #isAccepted()} is {@code true}, or {@code null}
     * on every rejected or {@link Status#NOT_EVALUABLE} outcome.
     */
    public WebhookPayload payload() {
        return payload;
    }

    /**
     * Returns a fixed safe rejection reason when {@link #isAccepted()} is {@code false}, or {@code null}
     * when accepted. Never contains secrets, raw body bytes, or raw signature hex.
     */
    public String errorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "WebhookVerificationResult[status=" + status
                + ", httpStatus=" + status.httpStatus()
                + ", sourceId=" + sourceId
                + ", matchedKid=" + matchedKid
                + ", hasPayload=" + (payload != null)
                + ", errorMessage=" + errorMessage
                + "]";
    }
}
