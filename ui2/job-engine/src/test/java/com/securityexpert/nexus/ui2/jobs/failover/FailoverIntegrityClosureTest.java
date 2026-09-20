package com.securityexpert.nexus.ui2.jobs.failover;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverCommandResult;
import com.securityexpert.nexus.ui2.jobs.failover.execution.FailoverCommandResult.DeliveryCertainty;
import com.securityexpert.nexus.ui2.jobs.failover.schedule.BaselineSnapshotSummary;

/**
 * Closure tests for two of the P0 defects both external final reviews left open on the
 * Failover Engine: incoherent success/delivery pairs (CF-P0.16) and a baseline whose
 * content is never checked against the digest that was signed (CF-P0.2).
 */
class FailoverIntegrityClosureTest {

    private static BaselineSnapshotSummary baselineWithDigest(String activeMemberId, String digest) {
        return BaselineSnapshotSummary.of(
            "cls-ref-1", "check_point", "HIGH_AVAILABILITY",
            activeMemberId, "dev-cp-2", "R81.20", "policy-hash-1", 7L,
            digest, Instant.parse("2026-09-20T10:00:00Z"));
    }

    private static BaselineSnapshotSummary sealedBaseline(String activeMemberId) {
        BaselineSnapshotSummary draft = baselineWithDigest(activeMemberId, "placeholder");
        return baselineWithDigest(activeMemberId, draft.computeCanonicalDigest());
    }

    @Test
    @DisplayName("a success claim cannot be paired with an uncertain or negative delivery outcome")
    void successRequiresConfirmedDelivery() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> new FailoverCommandResult(
                true, 0, "cmd", Instant.now(), null, DeliveryCertainty.DELIVERY_UNKNOWN),
                "a command cannot both have worked and possibly never have arrived"),
            () -> assertThrows(IllegalArgumentException.class, () -> new FailoverCommandResult(
                true, 0, "cmd", Instant.now(), null, DeliveryCertainty.DEFINITELY_NOT_SUBMITTED)),
            () -> assertThrows(IllegalArgumentException.class, () -> new FailoverCommandResult(
                true, 0, "cmd", Instant.now(), null, DeliveryCertainty.DEFINITELY_REJECTED)));
    }

    @Test
    @DisplayName("a failure cannot claim the device confirmed submission")
    void failureCannotClaimConfirmedDelivery() {
        assertThrows(IllegalArgumentException.class, () -> new FailoverCommandResult(
            false, 1, "cmd", Instant.now(), "transport reset", DeliveryCertainty.SUBMITTED_SUCCESS),
            "an unsuccessful result carrying SUBMITTED_SUCCESS reads downstream as an affirmative rejection");
    }

    @Test
    @DisplayName("the coherent pairs remain constructible")
    void coherentPairsStillWork() {
        assertTrue(FailoverCommandResult.success("clusterXL_admin up").successful());
        assertFalse(FailoverCommandResult
            .failure(1, "clusterXL_admin up", "timeout", DeliveryCertainty.DELIVERY_UNKNOWN)
            .successful());
    }

    @Test
    @DisplayName("a baseline re-derives to its own recorded digest")
    void sealedBaselineVerifies() {
        assertTrue(sealedBaseline("dev-cp-1").digestMatchesContent());
    }

    @Test
    @DisplayName("substituting the active member while keeping the signed digest is detected")
    void substitutedBaselineIsDetected() {
        BaselineSnapshotSummary sealed = sealedBaseline("dev-cp-1");

        // Exactly the attack the review describes: the stored baseline is rewritten to
        // point at the other member, and the digest the envelope signature covers is left
        // untouched. The signature still verifies; only re-deriving the digest catches it.
        BaselineSnapshotSummary substituted = baselineWithDigest("dev-cp-2", sealed.assessmentDigest());

        assertFalse(substituted.digestMatchesContent(),
            "a baseline whose content no longer produces its recorded digest must not be trusted");
    }
}
