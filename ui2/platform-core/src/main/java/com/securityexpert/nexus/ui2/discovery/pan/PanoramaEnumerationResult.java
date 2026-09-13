package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.List;

/**
 * T-1/T-2/T-7: the outcome of one {@link PanoramaEnumeration#run}. Exactly
 * one of three shapes -- never a partial candidate list alongside a
 * failure, never a raw exception escaping to the caller, and no raw text
 * field anywhere (T-7). Mirrors cp's {@code ManagementPlaneEnumerationResult}.
 */
public sealed interface PanoramaEnumerationResult {

    /** T-5/SB-16: credential or trust-rule resolution failed before any request was ever sent (AC-3, AC-5). */
    record Refused(String reason) implements PanoramaEnumerationResult {
    }

    /** T-1/T-7: the run completed; {@code keyDisposalOutcome} names what happened to the in-memory key. */
    record Completed(
            List<RawDeviceInput> candidates,
            int requestCount,
            KeyDisposalOutcome keyDisposalOutcome) implements PanoramaEnumerationResult {
    }

    /** T-1: key generation did not succeed, or the enumeration call failed. */
    record Failed(
            String reason,
            int requestCount,
            KeyDisposalOutcome keyDisposalOutcome) implements PanoramaEnumerationResult {
    }
}
