package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;

/**
 * HA readiness as the Operations screen shows it ({@code haReadinessItems}): every enrolled cluster (devices sharing a
 * cluster reference) counts once, with its base unit's latest readiness; a cluster whose vendor the failover feature
 * does not support is "not supported"; a cluster without a readiness record is UNKNOWN.
 *
 * <p>Reports R1 contract: stale readiness is flagged as such, never as ready. A READY result older than the staleness
 * threshold is the verdict {@link #STALE}, counted apart from READY; a stale NOT_READY stays NOT_READY with its age
 * shown.</p>
 */
final class HaFacts {

    static final String READY = "READY";
    static final String NOT_READY = "NOT_READY";
    static final String UNKNOWN = "UNKNOWN";
    static final String STALE = "STALE";
    static final String UNSUPPORTED = "NOT_SUPPORTED";

    record Cluster(String clusterRef, String vendor, List<DeviceSummaryRecord> members, ReportData.HaUnit base,
            List<ReportData.HaUnit> units, String verdict) {
    }

    final List<Cluster> clusters = new ArrayList<>();
    final List<ReportData.HaUnit> units;

    HaFacts(List<DeviceSummaryRecord> devices, List<ReportData.HaUnit> haUnits, Instant now) {
        this.units = List.copyOf(haUnits);
        Map<String, List<DeviceSummaryRecord>> byRef = new TreeMap<>();
        for (DeviceSummaryRecord d : devices) {
            d.clusterMemberRef().filter(r -> !r.isBlank()).ifPresent(ref -> byRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(d));
        }
        Map<String, List<ReportData.HaUnit>> unitsByRef = new LinkedHashMap<>();
        for (ReportData.HaUnit u : haUnits) {
            if (u.clusterRef() != null) unitsByRef.computeIfAbsent(u.clusterRef(), k -> new ArrayList<>()).add(u);
        }
        for (var e : byRef.entrySet()) {
            List<DeviceSummaryRecord> members = e.getValue().stream().sorted(Comparator.comparing(DeviceSummaryRecord::deviceId)).toList();
            String vendor = members.get(0).vendorHint();
            List<ReportData.HaUnit> clusterUnits = unitsByRef.getOrDefault(e.getKey(), List.of());
            ReportData.HaUnit base = clusterUnits.stream().filter(ReportData.HaUnit::base).findFirst().orElse(null);
            String verdict = !"check_point".equals(vendor) && !"palo_alto".equals(vendor) ? UNSUPPORTED
                    : verdict(base == null ? null : base.outcome(), base == null ? null : base.observedAt(), now);
            clusters.add(new Cluster(e.getKey(), vendor, members, base, clusterUnits, verdict));
        }
    }

    /** The report verdict of one readiness record: READY only when fresh; READY but old is STALE. */
    static String verdict(String outcome, Instant observedAt, Instant now) {
        if (outcome == null || observedAt == null) return UNKNOWN;
        if (READY.equals(outcome)) return Freshness.stale(observedAt, now) ? STALE : READY;
        if (NOT_READY.equals(outcome)) return NOT_READY;
        return UNKNOWN;
    }

    long count(String verdict) {
        return clusters.stream().filter(c -> verdict.equals(c.verdict())).count();
    }

    List<Instant> observedTimes() {
        return clusters.stream().filter(c -> !UNSUPPORTED.equals(c.verdict()))
                .map(c -> c.base() == null ? null : c.base().observedAt()).toList();
    }

    long supported() {
        return clusters.size() - count(UNSUPPORTED);
    }
}
