package com.securityexpert.nexus.ui2.service.eventintake.tls;

/**
 * Pure-Java per-source {@code required_transport} anti-downgrade lock and Cisco ASA
 * {@code logging permit-hostdown} guard
 * ({@code PO_DECISION_RECORD_2026_11_26_POLICY_CHANGE_EVENT_TRIGGER.md} {@code §1 Q2} and
 * {@code §1.1 Q2}).
 *
 * <p>Enforces fail-closed transport policy evaluation in strict order:</p>
 * <ol>
 *   <li>Rejects {@code null} policy, {@code null} observed transport, {@code null} required
 *       transport, or {@link Transport#PLAIN_TCP} as required transport with
 *       {@link Outcome#INVALID_TRANSPORT_CONFIG}.</li>
 *   <li>Unconditionally rejects {@link Transport#PLAIN_TCP} (RFC 6587 Historic) with
 *       {@link Outcome#PLAIN_TCP_PROHIBITED}.</li>
 *   <li>Enforces the hard prohibition on direct Cisco ASA TLS syslog ({@link Transport#TLS_MUTUAL}
 *       or {@link Transport#TLS_SERVER_ONLY}) unless {@code logging permit-hostdown} is verified
 *       present in the stored {@code running-config} ({@link Outcome#ASA_PERMIT_HOSTDOWN_UNVERIFIED}).</li>
 *   <li>Enforces the per-source anti-downgrade lock across the webhook and syslog transport lanes
 *       ({@link Outcome#TRANSPORT_DOWNGRADE_REFUSED} on downgrade or cross-lane mismatch).</li>
 * </ol>
 */
public final class TransportDowngradeGuard {

    /**
     * Closed event-intake transport vocabulary ordered by security grade.
     */
    public enum Transport {
        HTTPS_WEBHOOK,
        TLS_MUTUAL,
        TLS_SERVER_ONLY,
        PLAIN_TCP,
        UDP_LEGACY;

        /**
         * Returns {@code true} for syslog-over-TLS transports ({@link #TLS_MUTUAL} or
         * {@link #TLS_SERVER_ONLY}).
         */
        public boolean isSyslogTls() {
            return this == TLS_MUTUAL || this == TLS_SERVER_ONLY;
        }
    }

    /**
     * Closed evaluation outcome vocabulary with fixed diagnostic reasons.
     */
    public enum Outcome {
        PERMITTED("Observed transport satisfies the enrolled source transport policy."),
        TRANSPORT_DOWNGRADE_REFUSED("Observed transport violates the per-source required_transport anti-downgrade lock."),
        PLAIN_TCP_PROHIBITED("Plain TCP syslog (RFC 6587 Historic) is unconditionally prohibited."),
        ASA_PERMIT_HOSTDOWN_UNVERIFIED(
                "Direct Cisco ASA TLS syslog is prohibited unless logging permit-hostdown is verified in stored running-config."),
        INVALID_TRANSPORT_CONFIG("Source transport policy or observed transport is missing or invalid.");

        private final String reason;

        Outcome(String reason) {
            this.reason = reason;
        }

        /**
         * Returns {@code true} only for {@link #PERMITTED}.
         */
        public boolean isPermitted() {
            return this == PERMITTED;
        }

        /**
         * Returns a fixed closed-vocabulary diagnostic message.
         */
        public String reason() {
            return reason;
        }
    }

    /**
     * Per-source transport policy evaluated by {@link TransportDowngradeGuard}.
     *
     * @param requiredTransport         required transport grade ({@link Transport#HTTPS_WEBHOOK},
     *                                  {@link Transport#TLS_MUTUAL}, {@link Transport#TLS_SERVER_ONLY},
     *                                  or {@link Transport#UDP_LEGACY})
     * @param directCiscoAsa            {@code true} when the emitter is a direct Cisco ASA source
     * @param asaPermitHostdownVerified {@code true} only when {@code logging permit-hostdown} has
     *                                  been verified present in the unit's stored {@code running-config}
     */
    public record SourcePolicy(
            Transport requiredTransport,
            boolean directCiscoAsa,
            boolean asaPermitHostdownVerified) {

        /**
         * Creates a non-ASA source transport policy.
         */
        public static SourcePolicy of(Transport requiredTransport) {
            return new SourcePolicy(requiredTransport, false, false);
        }
    }

    /**
     * Evaluates whether {@code observedTransport} is permitted under {@code policy}.
     */
    public Outcome evaluate(SourcePolicy policy, Transport observedTransport) {
        if (policy == null
                || observedTransport == null
                || policy.requiredTransport() == null
                || policy.requiredTransport() == Transport.PLAIN_TCP) {
            return Outcome.INVALID_TRANSPORT_CONFIG;
        }

        if (observedTransport == Transport.PLAIN_TCP) {
            return Outcome.PLAIN_TCP_PROHIBITED;
        }

        if (policy.directCiscoAsa()
                && observedTransport.isSyslogTls()
                && !policy.asaPermitHostdownVerified()) {
            return Outcome.ASA_PERMIT_HOSTDOWN_UNVERIFIED;
        }

        return switch (policy.requiredTransport()) {
            case HTTPS_WEBHOOK -> observedTransport == Transport.HTTPS_WEBHOOK
                    ? Outcome.PERMITTED
                    : Outcome.TRANSPORT_DOWNGRADE_REFUSED;
            case TLS_MUTUAL -> observedTransport == Transport.TLS_MUTUAL
                    ? Outcome.PERMITTED
                    : Outcome.TRANSPORT_DOWNGRADE_REFUSED;
            case TLS_SERVER_ONLY -> (observedTransport == Transport.TLS_SERVER_ONLY
                    || observedTransport == Transport.TLS_MUTUAL)
                    ? Outcome.PERMITTED
                    : Outcome.TRANSPORT_DOWNGRADE_REFUSED;
            case UDP_LEGACY -> (observedTransport == Transport.UDP_LEGACY
                    || observedTransport == Transport.TLS_SERVER_ONLY
                    || observedTransport == Transport.TLS_MUTUAL)
                    ? Outcome.PERMITTED
                    : Outcome.TRANSPORT_DOWNGRADE_REFUSED;
            case PLAIN_TCP -> Outcome.INVALID_TRANSPORT_CONFIG;
        };
    }
}
