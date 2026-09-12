package com.securityexpert.nexus.ui2.discovery.cp;

import java.util.Objects;
import java.util.Optional;

/**
 * One of the two address fields contract §5.1 resolves over: present with a
 * value (possibly empty — CR-3's measured-present-but-empty case), or
 * absent entirely. Equality between two {@link Address} values is decided
 * locally, after trimming surrounding whitespace and after nothing else
 * (§5.1 "How equality is decided"; {@code AGENTS.md} identity law): no
 * numeric casting, no zero-stripping, no truncation or padding, no case or
 * punctuation normalization, and no equivalence rule invented to make two
 * values match.
 */
public final class Address {

    private static final Address ABSENT = new Address(Optional.empty());

    private final Optional<String> value;

    private Address(Optional<String> value) {
        this.value = value;
    }

    public static Address of(String value) {
        return new Address(Optional.of(Objects.requireNonNull(value, "value")));
    }

    public static Address absent() {
        return ABSENT;
    }

    public boolean isPresent() {
        return value.isPresent();
    }

    public Optional<String> value() {
        return value;
    }

    /**
     * §5.1 equality rule. Two absent addresses are not compared as equal
     * here: HR-1/HR-2 apply only where the management-address field is
     * present, and callers hold that precondition.
     */
    public boolean equalsTrimmed(Address other) {
        return value.isPresent() && other.value.isPresent()
                && value.get().trim().equals(other.value.get().trim());
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Address a && value.equals(a.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value.map(v -> "Address[" + v + "]").orElse("Address[absent]");
    }
}
