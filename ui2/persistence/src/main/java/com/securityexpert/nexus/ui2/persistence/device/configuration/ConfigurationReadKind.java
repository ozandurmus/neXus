package com.securityexpert.nexus.ui2.persistence.device.configuration;

/**
 * {@code device_configuration_run.read_kind}'s closed vocabulary (migration
 * V16, 14G CG-1/CG-4). Check Point ever writes {@link #SHOW_CONFIGURATION}
 * only; Palo Alto writes all three, one row per kind, sharing one {@code
 * job_id} (AC-2: "active and merged are recorded as their own read
 * kinds").
 */
public final class ConfigurationReadKind {

    public static final String SHOW_CONFIGURATION = "show_configuration";
    public static final String ACTIVE = "active";
    public static final String EFFECTIVE_RUNNING = "effective_running";
    public static final String MERGED = "merged";

    private ConfigurationReadKind() {
    }
}
