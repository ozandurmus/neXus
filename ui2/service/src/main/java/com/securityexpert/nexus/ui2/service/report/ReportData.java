package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;

/**
 * What the R1 report builders read: stored evidence only (contract "Data sources": the neXus database and the existing
 * artefact store, no device command, no new collection). Unit tests supply synthetic implementations.
 */
public interface ReportData {

    /** One failover unit's latest stored readiness (a plain cluster, a VSX chassis or one of its virtual systems). */
    record HaUnit(String clusterRef, String vendor, String vsId, boolean vsxChassis, String outcome, Instant observedAt,
            String failedCheck, String stopCode) {
        public boolean base() {
            return vsId == null;
        }
    }

    List<DeviceSummaryRecord> devices();

    List<ComplianceRow> complianceEvaluations();

    Map<String, Instant> newestInventory();

    Map<String, Instant> newestConfiguration();

    List<BackupRow> latestBackups(Instant from, Instant to);

    List<DeviationRow> sizeDeviations(Instant from, Instant to);

    /** Failover units as the Operations screen lists them, read under the requesting actor's own reader check. */
    List<HaUnit> haUnits(String actor);

    /** Stored lifecycle, licensing, Live Patch and advisory snapshot as the Lifecycle screen computes it (RPT-17). */
    default LifecycleService.FleetSnapshot lifecycleSnapshot(LocalDate today, Instant now) {
        return LifecycleService.FleetSnapshot.EMPTY;
    }

    /**
     * The stored zone matrix read models for ZM-01. Without a zone matrix source the build fails closed: an absent
     * source is never printed as "no findings".
     */
    default ZoneMatrixEvidenceReportBuilder.Source zoneMatrixEvidence() {
        throw new IllegalStateException("Zone matrix evidence is unavailable; the evidence report cannot be built");
    }
}
