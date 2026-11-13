package com.securityexpert.nexus.ui2.platform;

import java.util.Set;
import java.util.Objects;

/**
 * In-flight authenticated evidence; never a browser or audit value. {@code displayName} is the one exception: the
 * directory account name (sAMAccountName) a sign-in shows in the app bar. It is display only -- never an identity,
 * authorization or audit key -- and is null when the observation (a revalidation) did not read it.
 */
public record DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences,
        String displayName, java.time.Instant resolvedAt, java.time.Instant validUntil, Publication publication) {
    /** Longest display name kept (an sAMAccountName is at most 20 characters; the bound only limits garbage). */
    public static final int MAX_DISPLAY_NAME = 64;

    public DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences, Publication publication) {
        this(profileId, principalReference, groupReferences, null, publication);
    }

    public DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences,
            String displayName, Publication publication) {
        this(profileId, principalReference, groupReferences, displayName, java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(300), publication);
    }

    public DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences,
            java.time.Instant resolvedAt, java.time.Instant validUntil, Publication publication) {
        this(profileId, principalReference, groupReferences, null, resolvedAt, validUntil, publication);
    }

    /** The name as shown: trimmed, control characters dropped, bounded; null when nothing printable is left. */
    public static String cleanDisplayName(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.length() > MAX_DISPLAY_NAME) cleaned = cleaned.substring(0, MAX_DISPLAY_NAME);
        return cleaned.isEmpty() ? null : cleaned;
    }

    public DirectoryObservation {
        if (profileId == null || profileId.isBlank() || principalReference == null || principalReference.isBlank()) {
            throw new IllegalArgumentException("directory_identity_not_proven");
        }
        if (resolvedAt == null || validUntil == null || !resolvedAt.isBefore(validUntil)) {
            throw new IllegalArgumentException("directory_observation_invalid");
        }
        groupReferences = Set.copyOf(groupReferences);
        displayName = cleanDisplayName(displayName);
        Objects.requireNonNull(publication);
    }

    /** Serializes publication with trust retirement, closing the in-flight reload race. */
    @FunctionalInterface
    public interface Publication {
        boolean ifCurrent(Runnable write);
    }

    @Override public String toString() { return "DirectoryObservation[redacted]"; }
}
