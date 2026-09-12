package com.securityexpert.nexus.ui2.persistence.gates;

import java.util.List;

/** A {@code gate_registry} row, plain-typed (DIR-7/DIR-6: no capability-registry type reaches persistence). */
public record GateRowData(
        String gateId,
        String vendor,
        String platformRoleScope,
        String shellContext,
        String transportKind,
        String canonicalCommandKey,
        String actionClass,
        String signOffState,
        int timeoutS,
        String retryRule,
        String maxFrequency,
        String sessionReuseRule,
        String unsupportedBehaviorRef,
        String secretOutputRisk,
        List<String> safeTelemetryFields,
        String sourceDocumentPointer) {
}
