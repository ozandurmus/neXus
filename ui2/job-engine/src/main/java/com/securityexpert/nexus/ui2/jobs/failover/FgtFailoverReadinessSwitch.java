package com.securityexpert.nexus.ui2.jobs.failover;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

/**
 * FortiGate (FGCP) failover readiness switch (docs/design/PO_DECISION_REQUEST_2026_11_17_FGT_FAILOVER_READINESS.md).
 * Read once at process start by the service and by the worker; the value {@code on} enables, anything else or unset is
 * off. Readiness only: no execution path of any kind depends on this switch.
 */
public record FgtFailoverReadinessSwitch(boolean enabled) {
    public static final String ENVIRONMENT_VARIABLE = "UI2_FGT_FAILOVER_READINESS";
    public static final FgtFailoverReadinessSwitch OFF = new FgtFailoverReadinessSwitch(false);

    public static FgtFailoverReadinessSwitch fromEnvironment() {
        return fromValue(OverwatchEnv.get(ENVIRONMENT_VARIABLE));
    }

    public static FgtFailoverReadinessSwitch fromValue(String value) {
        return new FgtFailoverReadinessSwitch(value != null && "on".equals(value.strip()));
    }
}
