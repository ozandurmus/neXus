package com.securityexpert.nexus.ui2.worker.configuration.core;

public enum ConfigVendor {
    CHECK_POINT("check_point"),
    PALO_ALTO("palo_alto");

    private final String wireValue;

    ConfigVendor(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ConfigVendor fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ConfigVendor cannot be null");
        }
        for (ConfigVendor v : values()) {
            if (v.wireValue.equalsIgnoreCase(value.trim()) || v.name().equalsIgnoreCase(value.trim())) {
                return v;
            }
        }
        throw new IllegalArgumentException("Unknown ConfigVendor: " + value);
    }
}
