package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;

/**
 * Production {@link ReportData}: the read-only report queries (read-only transactions with a statement timeout), the
 * device list, and the failover summary the Operations screen shows (database reads only; no job, no device contact).
 */
public final class JooqReportData implements ReportData {

    private final JooqReportReadRepository reads;
    private final DeviceRepository devices;
    private final CpFailoverService failover;

    public JooqReportData(JooqReportReadRepository reads, DeviceRepository devices, CpFailoverService failover) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.failover = failover;
    }

    @Override
    public List<DeviceSummaryRecord> devices() {
        return devices.listAll();
    }

    @Override
    public List<JooqReportReadRepository.ComplianceRow> complianceEvaluations() {
        return reads.complianceEvaluations();
    }

    @Override
    public Map<String, Instant> newestInventory() {
        return reads.newestInventory();
    }

    @Override
    public Map<String, Instant> newestConfiguration() {
        return reads.newestConfiguration();
    }

    @Override
    public List<JooqReportReadRepository.BackupRow> latestBackups(Instant from, Instant to) {
        return reads.latestBackups(from, to);
    }

    @Override
    public List<JooqReportReadRepository.DeviationRow> sizeDeviations(Instant from, Instant to) {
        return reads.sizeDeviations(from, to);
    }

    @Override
    public List<HaUnit> haUnits(String actor) {
        if (failover == null) return List.of();
        return failover.summary(actor).stream().filter(CpFailoverService.Summary::readinessAvailable).map(s -> {
            var r = s.readiness();
            String clusterRef = s.unit().members().get(0).clusterMemberRef().orElse(null);
            return new HaUnit(clusterRef, s.vendor(), s.unit().vsId(), s.unit().vsxChassis(),
                    r == null ? null : r.outcome(), r == null ? null : r.observedAt(),
                    r == null ? null : r.failedCheck(), r == null ? null : r.stopCode());
        }).toList();
    }
}
