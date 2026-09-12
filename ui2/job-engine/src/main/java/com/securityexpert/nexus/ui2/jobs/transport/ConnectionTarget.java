package com.securityexpert.nexus.ui2.jobs.transport;

/**
 * Where {@code connect} dials. {@code hostRef}/{@code portRef} are opaque
 * endpoint-resolved values (never a raw address held here beyond what the
 * caller already resolved from {@code endpoints.address_ref}, C1 §7) --
 * this record carries what {@code ssh_exec} needs to open a socket, not a
 * device identity.
 */
public record ConnectionTarget(String endpointId, String host, int port) {
}
