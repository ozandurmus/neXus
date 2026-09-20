package com.securityexpert.nexus.ui2.jobs.failover.authz;

/**
 * Result of evaluating a 4-eyes authorization request.
 */
public sealed interface FourEyesAuthorizationResult {

    record Authorized(FailoverLeaseToken leaseToken) implements FourEyesAuthorizationResult {}

    record Refused(String code, String reason) implements FourEyesAuthorizationResult {}
}
