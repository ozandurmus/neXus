package com.securityexpert.nexus.ui2.worker.confirm;

import java.util.Optional;

/**
 * The facts DA-2 reads from the device on first contact -- hostname,
 * model, software version, HA/cluster role, and a separate Spark revision signal.
 * Every field is independently
 * {@code Optional}: an unparseable read lands the row with {@code UNKNOWN}
 * per field (contract EC-11 gate entry item 8), never a refusal and never
 * an invented value (AGENTS.md UNKNOWN/fail-closed law).
 */
public record ObservedFacts(
        Optional<String> hostname,
        Optional<String> model,
        Optional<String> softwareVersion,
        Optional<String> haRole,
        Optional<String> sparkRevision) {

    public ObservedFacts(Optional<String> hostname, Optional<String> model,
            Optional<String> softwareVersion, Optional<String> haRole) {
        this(hostname, model, softwareVersion, haRole, Optional.empty());
    }

    public static ObservedFacts allUnknown() {
        return new ObservedFacts(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
