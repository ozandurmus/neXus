package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.BackupRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.DeviationRow;

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
}
