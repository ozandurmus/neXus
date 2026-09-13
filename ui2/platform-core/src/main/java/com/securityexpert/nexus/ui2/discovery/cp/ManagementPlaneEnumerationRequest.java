package com.securityexpert.nexus.ui2.discovery.cp;

import java.time.Duration;
import java.util.Optional;

/**
 * T-5/SB-12/SB-16: what a run needs and nothing secret -- an opaque
 * {@code credentialRef} and {@code trustRuleRef} (the existing {@code
 * ConnectSpec} shape), the management server's own address, the port the
 * caller resolved for reaching it (CS-6b: never a literal in the adapter),
 * and the CS-3 sampling interval between the two connection-table
 * observations. {@code configuredChannelPort} is CS-6b's fallback source for
 * the channel port when no established row in the sample supplies one.
 */
public record ManagementPlaneEnumerationRequest(
        String managementHost,
        int managementPort,
        String credentialRef,
        String trustRuleRef,
        Duration channelObservationInterval,
        Optional<Integer> configuredChannelPort) {
}
