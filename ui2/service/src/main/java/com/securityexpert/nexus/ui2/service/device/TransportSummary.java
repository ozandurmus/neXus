package com.securityexpert.nexus.ui2.service.device;

import java.util.List;
import java.util.Objects;

/**
 * The device workspace's transport summary (contract §4.2) -- the
 * replacement for {@code endpoints.address_ref}, which this type carries
 * NO component for, anywhere, at any name. That is a structural decision,
 * not an omission a caller must remember to make: there is no field to
 * populate with the address even by mistake.
 *
 * <p>The schema permits zero or several {@code endpoints} rows per device
 * (contract §4.2, {@code UNKNOWN-5}); {@link #transports()} is therefore a
 * list rather than an optional single value, and this type asserts nothing
 * about ordering, primacy or a "default" endpoint -- {@code UNKNOWN-5}
 * forbids inferring one from render order.
 *
 * @param presence  {@code PRESENT} when {@link #transports()} is non-empty,
 *                  {@code MISSING} when it is empty (a real outcome, not an
 *                  error -- never an empty string that could be mistaken
 *                  for an empty address)
 * @param transports one entry per {@code endpoints} row for the device, in
 *                    {@code endpoint_id} order (an arbitrary, stable tie
 *                    -breaker -- not a primacy claim)
 */
public record TransportSummary(Presence presence, List<Entry> transports) {

    /** Contract §4.2's closed relationship vocabulary. */
    public enum Presence {
        PRESENT,
        MISSING
    }

    /** Contract §4.2: "Copy states that the management address is held by the service and not displayed." */
    public static final String ADDRESS_COPY =
            "The management address is held by the service and is not displayed.";

    /**
     * One {@code endpoints} row, transport identity only.
     *
     * @param endpointId    the opaque {@code endpoints.endpoint_id}
     * @param transportKind {@code endpoints.transport_kind}
     */
    public record Entry(String endpointId, String transportKind) {
        public Entry {
            Objects.requireNonNull(endpointId, "endpointId");
            Objects.requireNonNull(transportKind, "transportKind");
        }
    }

    public TransportSummary {
        transports = List.copyOf(transports);
        Presence expected = transports.isEmpty() ? Presence.MISSING : Presence.PRESENT;
        if (presence != expected) {
            throw new IllegalArgumentException("presence must agree with whether any endpoint row exists");
        }
    }

    public static TransportSummary of(List<Entry> transports) {
        return new TransportSummary(transports.isEmpty() ? Presence.MISSING : Presence.PRESENT, transports);
    }
}
