package com.securityexpert.nexus.ui2.jobs.failover;

/** Process configuration shared by service and worker; changes require a restart. */
public record FailoverMutationSwitch(boolean enabled) {
    public static final String ENVIRONMENT_VARIABLE = "NEXUS_FAILOVER_MUTATION_ENABLED";
    public static final String DISABLED = "FAILOVER_MUTATION_DISABLED";
    public static final String GENERIC_DISABLED = "GENERIC_FAILOVER_EXECUTION_DISABLED";

    public static FailoverMutationSwitch fromEnvironment() {
        return fromValue(System.getenv(ENVIRONMENT_VARIABLE));
    }

    public static FailoverMutationSwitch fromValue(String value) {
        return new FailoverMutationSwitch("true".equalsIgnoreCase(value));
    }

    /** Generic manual and scheduled execution remain disabled even when mutation is enabled. */
    public static void refuseGenericExecution() {
        throw new SecurityException(GENERIC_DISABLED);
    }
}
