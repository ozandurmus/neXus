package com.securityexpert.nexus.ui2.platform;

import java.util.Objects;
import java.util.UUID;

/**
 * An opaque identifier shared by every UI 2.0 module. Values are never
 * parsed for embedded meaning; the wrapping type exists so that no module
 * accidentally exchanges the wrong kind of identifier.
 */
public final class OpaqueId {

    private final String value;

    private OpaqueId(String value) {
        this.value = Objects.requireNonNull(value, "value");
    }

    public static OpaqueId random() {
        return new OpaqueId(UUID.randomUUID().toString());
    }

    public static OpaqueId of(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OpaqueId value must not be blank");
        }
        return new OpaqueId(value);
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof OpaqueId opaqueId && value.equals(opaqueId.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
