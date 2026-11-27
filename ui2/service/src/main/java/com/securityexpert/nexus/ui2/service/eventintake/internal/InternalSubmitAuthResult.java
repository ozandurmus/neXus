package com.securityexpert.nexus.ui2.service.eventintake.internal;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable outcome of authenticating and validating an internal
 * {@code POST /internal/event-triggers/submit} request.
 *
 * <p>Every {@link #reason()} value comes from a fixed closed vocabulary and never contains raw
 * bearer tokens, SHA-256 digests, or raw request bytes ({@code AGENTS.md} Raw-evidence law).</p>
 */
public final class InternalSubmitAuthResult {

    /**
     * Closed verification outcome vocabulary with its mapped HTTP status code and fixed reason.
     */
    public enum Status {
        ACCEPTED_CURRENT(200, "Internal submit bearer token verified against current digest."),
        ACCEPTED_NEXT(200, "Internal submit bearer token verified against staged next digest."),
        VERIFIER_NOT_CONFIGURED(503, "Internal submit token verifier is not configured."),
        OVERSIZE(413, "Internal submit request body exceeds maximum allowed size of 1024 bytes."),
        MISSING_OR_MALFORMED_HEADER(401, "Missing or malformed Authorization Bearer header."),
        TOKEN_MISMATCH(401, "Internal submit bearer token verification failed."),
        NEXT_TOKEN_EXPIRED(401, "Staged next internal submit bearer token has expired."),
        INVALID_PAYLOAD(400, "Internal submit JSON payload failed closed-schema validation.");

        private final int httpStatus;
        private final String defaultReason;

        Status(int httpStatus, String defaultReason) {
            this.httpStatus = httpStatus;
            this.defaultReason = defaultReason;
        }

        public int httpStatus() {
            return httpStatus;
        }

        public String defaultReason() {
            return defaultReason;
        }

        public boolean isAccepted() {
            return this == ACCEPTED_CURRENT || this == ACCEPTED_NEXT;
        }
    }

    private final Status status;
    private final int httpStatus;
    private final Optional<InternalSubmitRequest> request;
    private final String reason;

    private InternalSubmitAuthResult(
            Status status,
            Optional<InternalSubmitRequest> request,
            String reason) {
        this.status = Objects.requireNonNull(status, "status");
        this.httpStatus = status.httpStatus();
        this.request = Objects.requireNonNull(request, "request");
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    /**
     * Creates an {@link Status#ACCEPTED_CURRENT} result carrying the validated request.
     */
    public static InternalSubmitAuthResult acceptedCurrent(InternalSubmitRequest request) {
        Objects.requireNonNull(request, "request");
        return new InternalSubmitAuthResult(
                Status.ACCEPTED_CURRENT,
                Optional.of(request),
                Status.ACCEPTED_CURRENT.defaultReason());
    }

    /**
     * Creates an {@link Status#ACCEPTED_NEXT} result carrying the validated request.
     */
    public static InternalSubmitAuthResult acceptedNext(InternalSubmitRequest request) {
        Objects.requireNonNull(request, "request");
        return new InternalSubmitAuthResult(
                Status.ACCEPTED_NEXT,
                Optional.of(request),
                Status.ACCEPTED_NEXT.defaultReason());
    }

    /**
     * Creates a fail-closed rejection result with an empty request and fixed closed-vocabulary reason.
     */
    public static InternalSubmitAuthResult rejected(Status status) {
        Objects.requireNonNull(status, "status");
        if (status.isAccepted()) {
            throw new IllegalArgumentException(
                    "Use acceptedCurrent(...) or acceptedNext(...) for accepted statuses");
        }
        return new InternalSubmitAuthResult(status, Optional.empty(), status.defaultReason());
    }

    public Status status() {
        return status;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public boolean isAccepted() {
        return status.isAccepted();
    }

    /**
     * Returns the parsed {@link InternalSubmitRequest} when {@link #isAccepted()} is {@code true},
     * or {@link Optional#empty()} on every rejection.
     */
    public Optional<InternalSubmitRequest> request() {
        return request;
    }

    /**
     * Returns a fixed closed-vocabulary diagnostic reason string. Never contains raw tokens,
     * SHA-256 digests, or raw request bytes.
     */
    public String reason() {
        return reason;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof InternalSubmitAuthResult that)) {
            return false;
        }
        return httpStatus == that.httpStatus
                && status == that.status
                && request.equals(that.request)
                && reason.equals(that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, httpStatus, request, reason);
    }

    @Override
    public String toString() {
        return "InternalSubmitAuthResult[status=" + status
                + ", httpStatus=" + httpStatus
                + ", hasRequest=" + request.isPresent()
                + ", reason=" + reason
                + "]";
    }
}
