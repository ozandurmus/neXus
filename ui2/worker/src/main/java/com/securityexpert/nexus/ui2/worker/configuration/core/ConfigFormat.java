package com.securityexpert.nexus.ui2.worker.configuration.core;

public enum ConfigFormat {
    GAIA_CLISH("gaia_clish"),
    PAN_OS_XML("pan_os_xml");

    private final String wireValue;

    ConfigFormat(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static ConfigFormat fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("ConfigFormat cannot be null");
        }
        for (ConfigFormat f : values()) {
            if (f.wireValue.equalsIgnoreCase(value.trim()) || f.name().equalsIgnoreCase(value.trim())) {
                return f;
            }
        }
        throw new IllegalArgumentException("Unknown ConfigFormat: " + value);
    }
}
