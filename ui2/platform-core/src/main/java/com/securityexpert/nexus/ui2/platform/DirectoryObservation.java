package com.securityexpert.nexus.ui2.platform;

import java.util.Set;
import java.util.Objects;

/** In-flight authenticated evidence; never a browser or audit value. */
public record DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences,
        java.time.Instant resolvedAt, java.time.Instant validUntil, Publication publication) {
    public DirectoryObservation(String profileId, String principalReference, Set<String> groupReferences, Publication publication) {
        this(profileId, principalReference, groupReferences, java.time.Instant.now(), java.time.Instant.now().plusSeconds(300), publication);
    }

    public DirectoryObservation {
        if (profileId == null || profileId.isBlank() || principalReference == null || principalReference.isBlank()) {
            throw new IllegalArgumentException("directory_identity_not_proven");
        }
        if (resolvedAt == null || validUntil == null || !resolvedAt.isBefore(validUntil)) {
            throw new IllegalArgumentException("directory_observation_invalid");
        }
        groupReferences = Set.copyOf(groupReferences);
        Objects.requireNonNull(publication);
    }

    /** Serializes publication with trust retirement, closing the in-flight reload race. */
    @FunctionalInterface
    public interface Publication {
        boolean ifCurrent(Runnable write);
    }

    @Override public String toString() { return "DirectoryObservation[redacted]"; }
}
