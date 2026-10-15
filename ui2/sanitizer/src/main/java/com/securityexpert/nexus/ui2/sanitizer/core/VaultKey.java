package com.securityexpert.nexus.ui2.sanitizer.core;

import java.util.Objects;

/** One token in its canonical lookup form. */
public record VaultKey(VaultKind kind, String canonical) {
    public VaultKey {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(canonical, "canonical");
    }

    @Override
    public String toString() {
        return "VaultKey[" + kind + "]";
    }
}
