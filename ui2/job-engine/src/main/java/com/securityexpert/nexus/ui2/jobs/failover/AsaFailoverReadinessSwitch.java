package com.securityexpert.nexus.ui2.jobs.failover;

/**
 * Cisco ASA failover readiness switch (docs/design/PO_DECISION_REQUEST_2026_11_13_ASA_FAILOVER_READINESS.md). Read once
 * at process start by the service and by the worker; the value {@code on} enables, anything else or unset is off.
 * Readiness only: no execution path of any kind depends on this switch.
 */
public record AsaFailoverReadinessSwitch(boolean enabled) {
    public static final String ENVIRONMENT_VARIABLE = "UI2_ASA_FAILOVER_READINESS";
    public static final AsaFailoverReadinessSwitch OFF = new AsaFailoverReadinessSwitch(false);

    public static AsaFailoverReadinessSwitch fromEnvironment() {
        return fromValue(System.getenv(ENVIRONMENT_VARIABLE));
    }

    public static AsaFailoverReadinessSwitch fromValue(String value) {
        return new AsaFailoverReadinessSwitch(value != null && "on".equals(value.strip()));
    }
}
