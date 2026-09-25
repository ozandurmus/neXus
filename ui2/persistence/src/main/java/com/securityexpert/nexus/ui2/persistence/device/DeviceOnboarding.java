package com.securityexpert.nexus.ui2.persistence.device;

import java.time.Instant;
import java.util.Optional;

/**
 * V77: one device's onboarding flow (docs/design/DEVICE_ONBOARDING_FLOW_CONTRACT.md) -- identity, then inventory,
 * then configuration, each its own job. {@code skipped} names the steps this device's vendor/role has no read for,
 * with their refusal code ("configuration:VENDOR_UNSUPPORTED"), comma separated.
 */
public record DeviceOnboarding(String deviceId, String source, String state, String step, Optional<String> stepJobId,
        Optional<String> reason, String skipped, Instant startedAt, Instant updatedAt, Optional<Instant> completedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String COMPLETED = "COMPLETED";
    public static final String STOPPED = "STOPPED";

    public static final String IDENTITY = "identity";
    public static final String INVENTORY = "inventory";
    public static final String CONFIGURATION = "configuration";

    /** 1-based position of {@code step} in the flow, for "Onboarding · n/3". */
    public int stepNumber() {
        return switch (step) {
            case IDENTITY -> 1;
            case INVENTORY -> 2;
            default -> 3;
        };
    }
}
