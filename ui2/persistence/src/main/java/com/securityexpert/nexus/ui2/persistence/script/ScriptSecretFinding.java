package com.securityexpert.nexus.ui2.persistence.script;

import java.util.Objects;

/**
 * One privacy-gate secret-pattern match on an uploaded script version (PO decision 2026-11-18: line number and
 * pattern class only, never the matched value).
 */
public record ScriptSecretFinding(int line, String patternClass) {

    public ScriptSecretFinding {
        if (line < 1) {
            throw new IllegalArgumentException("line must be >= 1, got: " + line);
        }
        Objects.requireNonNull(patternClass, "patternClass");
        if (patternClass.isBlank()) {
            throw new IllegalArgumentException("patternClass must not be blank");
        }
    }
}
