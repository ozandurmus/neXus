package com.securityexpert.nexus.ui2.persistence.artefact;

import java.util.Objects;

/**
 * An opaque artefact identity (AGENTS.md identity law: never cast to
 * integer, never parsed for meaning). {@link #value()} is the only thing a
 * caller may do with one besides equality/storage -- C7 section 3.2's own
 * pattern ("{@code artefact_id}... opaque, sha256-of-ciphertext identity as
 * value not DB mechanism").
 */
public record ArtefactRef(String value) {

    public ArtefactRef {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("artefact ref must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
