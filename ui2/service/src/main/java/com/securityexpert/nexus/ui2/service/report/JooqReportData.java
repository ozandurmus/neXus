package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository;
import com.securityexpert.nexus.ui2.service.failover.CpFailoverService;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;

/**
 * Production {@link ReportData}: the read-only report queries (read-only transactions with a statement timeout), the
 * device list, the failover and lifecycle summaries the screens show, and the stored zone matrix read models (database
 * reads only; no job, no device contact).
 */
public final class JooqReportData implements ReportData {

    private final JooqReportReadRepository reads;
    private final DeviceRepository devices;
    private final CpFailoverService failover;
    private final LifecycleService lifecycle;
    private final ZoneMatrixEvidenceReportBuilder.Source zoneMatrix;

    public JooqReportData(JooqReportReadRepository reads, DeviceRepository devices, CpFailoverService failover) {
        this(reads, devices, failover, null);
    }

    public JooqReportData(JooqReportReadRepository reads, DeviceRepository devices, CpFailoverService failover,
            LifecycleService lifecycle) {
        this(reads, devices, failover, lifecycle, null);
    }

    public JooqReportData(JooqReportReadRepository reads, DeviceRepository devices, CpFailoverService failover,
            LifecycleService lifecycle, ZoneMatrixEvidenceReportBuilder.Source zoneMatrix) {
        this.reads = Objects.requireNonNull(reads, "reads");
        this.devices = Objects.requireNonNull(devices, "devices");
        this.failover = failover;
        this.lifecycle = lifecycle;
        this.zoneMatrix = zoneMatrix;
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

    @Override
    public LifecycleService.FleetSnapshot lifecycleSnapshot(LocalDate today, Instant now) {
        if (lifecycle == null) {
            // An absent source is not a "no read recorded" fact: fail the run instead of fabricating NO READ.
            throw new IllegalStateException("Lifecycle service is unavailable; lifecycle evidence cannot be read");
        }
        return lifecycle.fleetSnapshot(today, now);
    }

    @Override
    public ZoneMatrixEvidenceReportBuilder.Source zoneMatrixEvidence() {
        if (zoneMatrix == null) {
            // An absent source is not an empty matrix: fail the run instead of printing "no findings".
            throw new IllegalStateException("Zone matrix service is unavailable; zone matrix evidence cannot be read");
        }
        return zoneMatrix;
    }
}
