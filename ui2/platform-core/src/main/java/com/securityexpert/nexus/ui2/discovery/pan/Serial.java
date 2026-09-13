package com.securityexpert.nexus.ui2.discovery.pan;

import java.util.Objects;
import java.util.Optional;

/**
 * ID-1: the stable identifier (and the peer-serial reference of HA-0, which
 * is the same kind of value) is opaque — never cast, never trimmed of
 * leading zeroes, never normalized, never parsed for meaning. Equality
 * between two {@link Serial} values is decided locally, after trimming
 * surrounding whitespace and after nothing else (check 11; {@code
 * AGENTS.md} identity law).
 */
public final class Serial {

    private static final Serial ABSENT = new Serial(Optional.empty());

    private final Optional<String> value;

    private Serial(Optional<String> value) {
        this.value = value;
    }

    public static Serial of(String value) {
        return new Serial(Optional.of(Objects.requireNonNull(value, "value")));
    }

    public static Serial ofOptional(Optional<String> value) {
        return value.map(Serial::of).orElse(ABSENT);
    }

    public static Serial absent() {
        return ABSENT;
    }

    public boolean isPresent() {
        return value.isPresent();
    }

    public Optional<String> value() {
        return value;
    }

    /** ID-1/check 11: surrounding-whitespace trim only, and nothing else. */
    public boolean equalsTrimmed(Serial other) {
        return value.isPresent() && other.value.isPresent()
                && value.get().trim().equals(other.value.get().trim());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Serial s && value.equals(s.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.map(v -> "Serial[" + v + "]").orElse("Serial[absent]");
    }
}
