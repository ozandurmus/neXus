package com.securityexpert.nexus.ui2.service.audit;

import java.util.Map;

/** Contract §4.2: the metadata plus the §3.2 field projection and, for an {@code UPDATE} row, its per-key comparison. */
public record AuditEntryDetail(
        AuditEntrySummary summary,
        Map<String, AuditFieldProjection> beforeFields,
        Map<String, AuditFieldProjection> afterFields,
        Map<String, AuditChangeState> changeStates) {
}
