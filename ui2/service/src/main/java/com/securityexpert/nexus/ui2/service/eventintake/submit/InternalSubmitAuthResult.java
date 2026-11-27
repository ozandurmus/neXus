package com.securityexpert.nexus.ui2.service.eventintake.submit;

import java.util.Objects;

/**
 * Immutable discriminated outcome of authenticating and parsing an internal trigger submission
 * ({@code POST /internal/event-triggers/submit}).
 *
 * <p>Carries only a closed {@link Status}, optional {@link InternalSubmitRequest} and {@link MatchedSlot}
 * when accepted, and a fixed safe {@link #reasonCode()}. Never retains or emits the raw
 * {@code Authorization} header, raw bearer token, computed SHA-256 digest, or raw JSON body.</p>
 */
public final class InternalSubmitAuthResult {

    /**
     * Closed outcome vocabulary with its mapped HTTP response status code.
     */
    public enum Status {
        ACCEPTED(200),
        UNAUTHORIZED(401),
        PAYLOAD_TOO_LARGE(413),
        INVALID_PAYLOAD(400);

        private final int httpStatus;

        Status(int httpStatus) {
            this.httpStatus = httpStatus;
        }

        public int httpStatus() {
            return httpStatus;
        }
    }

    /**
     * Token digest slot that authenticated the request.
     */
    public enum MatchedSlot {
        CURRENT,
        NEXT
    }

    private final Status status;
    private final InternalSubmitRequest request;
    private final MatchedSlot matchedSlot;
    private final String reasonCode;

    private InternalSubmitAuthResult(
            Status status,
            InternalSubmitRequest request,
            MatchedSlot matchedSlot,
            String reasonCode) {
        this.status = Objects.requireNonNull(status, "status");
        this.request = request;
        this.matchedSlot = matchedSlot;
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    /**
     * Creates an {@link Status#ACCEPTED} result carrying the validated request and matched token slot.
     */
    public static InternalSubmitAuthResult accepted(InternalSubmitRequest request, MatchedSlot matchedSlot) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(matchedSlot, "matchedSlot");
        return new InternalSubmitAuthResult(Status.ACCEPTED, request, matchedSlot, "ACCEPTED");
    }

    /**
     * Creates a fail-closed rejection result with {@code request == null}, {@code matchedSlot == null},
     * and a safe closed {@code reasonCode}.
     */
    public static InternalSubmitAuthResult rejected(Status status, String reasonCode) {
        Objects.requireNonNull(status, "status");
        if (status == Status.ACCEPTED) {
            throw new IllegalArgumentException("Use accepted(...) for Status.ACCEPTED");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be null or blank");
        }
        return new InternalSubmitAuthResult(status, null, null, reasonCode);
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

    public InternalSubmitRequest request() {
        return request;
    }

    public MatchedSlot matchedSlot() {
        return matchedSlot;
    }

    public String reasonCode() {
        return reasonCode;
    }

    @Override
    public String toString() {
        return "InternalSubmitAuthResult[status=" + status
                + ", httpStatus=" + status.httpStatus()
                + ", matchedSlot=" + matchedSlot
                + ", hasRequest=" + (request != null)
                + ", reasonCode=" + reasonCode
                + "]";
    }
}
