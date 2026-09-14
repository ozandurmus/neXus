package com.securityexpert.nexus.ui2.worker.backup;

import java.util.Optional;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;

/**
 * {@code cp_gateway_backup}'s own device-contact outcome (14H BK-1..BK-8).
 * Mirrors {@code worker.configuration.ConfigurationResult}'s shape, but the
 * submit-then-poll-then-fetch-then-verify-then-delete state machine (BK-5)
 * needs more terminal shapes than a single {@code Completed}/failure split:
 * a digest mismatch and a failed cleanup are each their own distinct,
 * honestly-named outcome, never folded into a generic failure string.
 */
public sealed interface BackupResult {

    /** The archive is fetched, verified, stored and deleted from the device -- the full happy path. */
    record Completed(ArtefactStore.ArtefactMetadata artefact, String archiveName, Optional<String> observedSoftwareVersion,
            Optional<String> observedHostname) implements BackupResult {
    }

    /**
     * BK-3: the digest computed on the device did not match the digest of
     * the bytes actually received. The device-side copy is left in place
     * (never deleted) and the run says which side differed.
     */
    record DigestMismatch(String deviceDigest, String receivedDigest) implements BackupResult {
    }

    /**
     * The archive was fetched, verified and stored, but the device-side
     * delete failed even after its one retry (BK-7). The manifest row is
     * still recorded (the backup itself succeeded) -- only the device-side
     * cleanup failed -- but the run's own outcome is a failure and the
     * endpoint is marked ineligible for a further backup until cleared.
     */
    record CleanupFailed(ArtefactStore.ArtefactMetadata artefact, String archiveName, String reason) implements BackupResult {
    }

    /** BK-5: the poll never reached a terminal state before the run's own deadline. Nothing is deleted. */
    record OutcomeUnknown(String reason) implements BackupResult {
    }

    /** BK-11: the distinct backup credential could not be resolved -- refused before any device contact. */
    record CredentialUnresolvable(String reason) implements BackupResult {
    }

    record ConnectFailed(String reason) implements BackupResult {
    }

    /** BK-6: the free-space heuristic precondition failed -- refused before the submit. */
    record InsufficientFreeSpace(String reason) implements BackupResult {
    }

    /** The submit's own output did not contain a recognizable archive name -- refused before any poll. */
    record SubmitOutputUnparseable(String reason) implements BackupResult {
    }

    /** BK-8: a vendor refusal (snapshot in progress, an open management client) reported as the failure reason, never retried. */
    record SubmitRefused(String reason) implements BackupResult {
    }

    record ArtefactStoreFailed(String reason) implements BackupResult {
    }
}
