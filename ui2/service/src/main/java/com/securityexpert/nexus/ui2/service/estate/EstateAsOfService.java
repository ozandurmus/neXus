package com.securityexpert.nexus.ui2.service.estate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.platform.DeviceEnrollmentState;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.report.Freshness;

/**
 * Read-only historical snapshot of the Estate at a requested instant {@code at}
 * ({@code GET /api/v2/estate/as-of?at=<ISO>}; {@code docs/design/ESTATE_REWIND_AS_OF_DRAFT_2026_11_20.md}).
 *
 * <p>Evaluates stored evidence only (zero network-device commands, zero job admissions, zero writes) for the three
 * lenses whose stored tables carry exact timestamps at or before {@code at}:</p>
 * <ol>
 *   <li><b>Failover readiness</b> ({@code failover_run} {@code READINESS} runs with {@code finished_at <= at});</li>
 *   <li><b>Backup presence and age</b> ({@code backup_artefact} {@code created_at <= at} not yet removed in
 *       {@code artefact_retention_ledger} at {@code occurred_at <= at}, plus {@code backup_deviation_record}
 *       {@code size_anomaly} rows open at {@code at});</li>
 *   <li><b>Compliance pass/fail counts</b> ({@code compliance_evaluation} joined to the newest primary
 *       {@code device_configuration_run} with {@code collected_at <= at}, reporting {@code UNKNOWN} wherever history
 *       was not yet collected or was overwritten by a post-{@code at} configuration change).</li>
 * </ol>
 */
@Service
public final class EstateAsOfService {

    public static final int STATEMENT_TIMEOUT_MS = 15_000;
    public static final Duration BACKUP_ATTENTION_STALE_AFTER = Duration.ofDays(7);

    private static final String FGT_SINGLE_PREFIX = "fgt-single|";
    private static final Set<String> FAILOVER_VENDORS = Set.of("check_point", "palo_alto", "cisco_asa", "fortinet");
    private static final Set<String> COMPLIANCE_VENDORS = Set.of("check_point", "palo_alto", "cisco_asa", "fortinet");
    private static final Pattern VSID_IN_LABEL = Pattern.compile("\\(VSID\\s+([0-9]{1,10})\\)|^VSID\\s+([0-9]{1,10})$");
    private static final Pattern SAFE_CODE_TOKEN = Pattern.compile("^[A-Z0-9_]{1,64}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public static final class InvalidMomentException extends RuntimeException {
        private final String code;

        public InvalidMomentException(String code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public String code() {
            return code;
        }
    }

    public record DeviceMeta(String deviceId, Instant createdAt, boolean disabled) {
    }

    public record ReadinessRow(
            String runId,
            String vendor,
            String clusterRef,
            String vsId,
            String state,
            String outcome,
            Instant finishedAt,
            String failedCheck,
            String message
    ) {
    }

    public record BackupRow(
            String deviceId,
            String artefactId,
            Instant createdAt,
            long plaintextBytes,
            String backupType,
            String deviationState,
            boolean stillStoredNow
    ) {
    }

    public record ConfigRunRow(
            String deviceId,
            String runId,
            String canonicalHash,
            Instant collectedAt
    ) {
    }

    public record ComplianceRow(
            String deviceId,
            String canonicalHash,
            String rulesFingerprint,
            Instant evaluatedAt,
            String resultJson
    ) {
    }

    public record EvidenceSnapshot(
            Map<String, DeviceMeta> deviceMeta,
            List<ReadinessRow> readinessRuns,
            Map<String, BackupRow> backupsByDevice,
            Set<String> openSizeAnomalyDevices,
            Map<String, ConfigRunRow> configsByDevice,
            Map<String, ComplianceRow> complianceByDevice
    ) {
    }

    interface Store {
        EvidenceSnapshot load(Instant at);
    }

    private record ComplianceCounts(int pass, int fail, int noEvidence, int criticalFail) {
    }

    private final Store store;
    private final Supplier<List<DeviceSummaryRecord>> deviceListSupplier;
    private final TopologyNamePseudonymizer pseudonymizer;
    private final Supplier<Instant> clock;

    @Autowired
    public EstateAsOfService(
            TransactionBoundary transactionBoundary,
            DeviceRepository deviceRepository,
            TopologyNamePseudonymizer pseudonymizer
    ) {
        this(
                new JooqStore(Objects.requireNonNull(transactionBoundary, "transactionBoundary")),
                Objects.requireNonNull(deviceRepository, "deviceRepository")::listAll,
                Objects.requireNonNull(pseudonymizer, "pseudonymizer"),
                Instant::now
        );
    }

    EstateAsOfService(
            Store store,
            Supplier<List<DeviceSummaryRecord>> deviceListSupplier,
            TopologyNamePseudonymizer pseudonymizer,
            Supplier<Instant> clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.deviceListSupplier = Objects.requireNonNull(deviceListSupplier, "deviceListSupplier");
        this.pseudonymizer = Objects.requireNonNull(pseudonymizer, "pseudonymizer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Parses a mandatory ISO-8601 instant or offset timestamp (e.g. {@code 2026-11-12T02:00:00Z} or
     * {@code 2026-11-12T05:00:00+03:00}). Rejects missing, blank, or timezone-less strings.
     */
    public static Instant parseAt(String rawAt) {
        if (rawAt == null || rawAt.isBlank()) {
            throw new InvalidMomentException("TIME_INVALID", "Query parameter 'at' is required and must be an ISO-8601 timestamp with offset.");
        }
        String trimmed = rawAt.strip();
        try {
            return Instant.parse(trimmed);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(trimmed).toInstant();
            } catch (RuntimeException invalid) {
                throw new InvalidMomentException("TIME_INVALID", "Query parameter 'at' must be an ISO-8601 timestamp with offset (for example 2026-11-12T02:00:00Z).");
            }
        }
    }

    public EstateAsOfResponse asOf(Instant at, boolean masked) {
        Objects.requireNonNull(at, "at");
        Instant now = clock.get();
        List<Map<String, Object>> warnings = new ArrayList<>();
        if (at.isAfter(now)) {
            Map<String, Object> futureWarning = new LinkedHashMap<>();
            futureWarning.put("code", "AT_IN_FUTURE");
            futureWarning.put("message", "Requested 'at' is after server generated_at; answered from stored evidence without refusal.");
            warnings.add(futureWarning);
        }

        List<DeviceSummaryRecord> allDevices = deviceListSupplier.get();
        EvidenceSnapshot snap = store.load(at);

        // Active enrolled devices match OverviewService's Estate population (ENROLLED and not disabled).
        List<DeviceSummaryRecord> active = allDevices.stream()
                .filter(d -> d.enrollmentState() == DeviceEnrollmentState.ENROLLED)
                .filter(d -> {
                    DeviceMeta meta = snap.deviceMeta().get(d.deviceId());
                    return meta == null || !meta.disabled();
                })
                .sorted(Comparator.comparing(DeviceSummaryRecord::deviceId))
                .toList();

        // Group readiness runs by (vendor, clusterRef) -> vsKey ("" for base/chassis, else vsId)
        Map<String, Map<String, ReadinessRow>> readinessByCluster = new LinkedHashMap<>();
        for (ReadinessRow row : snap.readinessRuns()) {
            String key = row.vendor() + "\u0000" + row.clusterRef();
            String vsKey = row.vsId() == null ? "" : row.vsId();
            readinessByCluster.computeIfAbsent(key, k -> new LinkedHashMap<>()).putIfAbsent(vsKey, row);
        }

        // Collect cluster member sets so VSX sub-unit expectations are shared across both members of a cluster.
        Map<String, List<DeviceSummaryRecord>> membersByClusterRef = new LinkedHashMap<>();
        for (DeviceSummaryRecord d : active) {
            effectiveClusterRef(d, readinessByCluster).ifPresent(ref ->
                    membersByClusterRef.computeIfAbsent(ref, k -> new ArrayList<>()).add(d));
        }

        int enrolledAtMomentCount = 0;
        int enrolledAfterMomentCount = 0;

        // Failover summary accumulators
        int foReady = 0, foStale = 0, foNotReady = 0, foUnknown = 0, foNotApplicable = 0;
        Instant foNewest = null, foOldest = null;

        // Backup summary accumulators
        int bkPresent = 0, bkStale = 0, bkAnomaly = 0, bkMissing = 0, bkUnknown = 0, bkNotApplicable = 0;
        Instant bkNewest = null, bkOldest = null;

        // Compliance summary accumulators
        int cmpEvidenced = 0, cmpAllPass = 0, cmpFailing = 0, cmpCriticalFailing = 0, cmpNoEvidenceOnly = 0;
        int cmpUnknown = 0, cmpNotApplicable = 0;
        int matchingHashBasisUnits = 0;
        long cmpPassChecks = 0, cmpFailChecks = 0, cmpNoEvidenceChecks = 0, cmpCriticalFailChecks = 0;
        Instant cmpNewest = null, cmpOldest = null;

        List<Map<String, Object>> unitRows = new ArrayList<>(active.size());

        for (DeviceSummaryRecord d : active) {
            DeviceMeta meta = snap.deviceMeta().get(d.deviceId());
            boolean enrolledAtMoment = meta == null || meta.createdAt() == null || !meta.createdAt().isAfter(at);
            if (enrolledAtMoment) {
                enrolledAtMomentCount++;
            } else {
                enrolledAfterMomentCount++;
            }

            Optional<String> effectiveCluster = effectiveClusterRef(d, readinessByCluster);
            String rawClusterForDisplay = d.clusterMemberRef().filter(s -> !s.isBlank()).orElse(null);

            Map<String, Object> failover = buildFailoverFigure(
                    d, enrolledAtMoment, effectiveCluster.orElse(null), membersByClusterRef, readinessByCluster, at);
            String foVerdict = String.valueOf(failover.get("verdict"));
            switch (foVerdict) {
                case "READY" -> foReady++;
                case "STALE" -> foStale++;
                case "NOT_READY" -> foNotReady++;
                case "NOT_APPLICABLE" -> foNotApplicable++;
                default -> foUnknown++;
            }
            Instant foAt = parseIsoOrNull((String) failover.get("evidence_at"));
            if (foAt != null) {
                foNewest = maxInstant(foNewest, foAt);
                foOldest = minInstant(foOldest, foAt);
            }

            Map<String, Object> backup = buildBackupFigure(
                    d, enrolledAtMoment, snap.backupsByDevice().get(d.deviceId()),
                    snap.openSizeAnomalyDevices().contains(d.deviceId()), at);
            String bkState = String.valueOf(backup.get("state"));
            if ("PRESENT".equals(bkState)) {
                bkPresent++;
                if (Boolean.TRUE.equals(backup.get("stale"))) {
                    bkStale++;
                }
                if (Boolean.TRUE.equals(backup.get("open_size_anomaly"))) {
                    bkAnomaly++;
                }
            } else if ("MISSING".equals(bkState)) {
                bkMissing++;
            } else if ("NOT_APPLICABLE".equals(bkState)) {
                bkNotApplicable++;
            } else {
                bkUnknown++;
            }
            Instant bkAt = parseIsoOrNull((String) backup.get("evidence_at"));
            if (bkAt != null) {
                bkNewest = maxInstant(bkNewest, bkAt);
                bkOldest = minInstant(bkOldest, bkAt);
            }

            Map<String, Object> compliance = buildComplianceFigure(
                    d, enrolledAtMoment, snap.configsByDevice().get(d.deviceId()),
                    snap.complianceByDevice().get(d.deviceId()), at);
            String cmpState = String.valueOf(compliance.get("state"));
            if ("EVIDENCED".equals(cmpState)) {
                cmpEvidenced++;
                int pass = (Integer) compliance.get("pass_count");
                int fail = (Integer) compliance.get("fail_count");
                int noEv = (Integer) compliance.get("no_evidence_count");
                int critFail = (Integer) compliance.get("critical_fail_count");
                cmpPassChecks += pass;
                cmpFailChecks += fail;
                cmpNoEvidenceChecks += noEv;
                cmpCriticalFailChecks += critFail;
                if (fail > 0) {
                    cmpFailing++;
                    if (critFail > 0) {
                        cmpCriticalFailing++;
                    }
                } else if (pass > 0) {
                    cmpAllPass++;
                } else {
                    cmpNoEvidenceOnly++;
                }
                if ("MATCHING_CONFIG_HASH".equals(compliance.get("basis"))) {
                    matchingHashBasisUnits++;
                }
            } else if ("NOT_APPLICABLE".equals(cmpState)) {
                cmpNotApplicable++;
            } else {
                cmpUnknown++;
            }
            Instant cmpAt = parseIsoOrNull((String) compliance.get("evidence_at"));
            if (cmpAt != null) {
                cmpNewest = maxInstant(cmpNewest, cmpAt);
                cmpOldest = minInstant(cmpOldest, cmpAt);
            }

            Map<String, Object> unit = new LinkedHashMap<>();
            unit.put("device_id", d.deviceId());
            unit.put("hostname", projectHostname(d, rawClusterForDisplay, masked));
            unit.put("vendor", d.vendorHint());
            unit.put("role", d.role());
            unit.put("model", d.observedModel().orElse(null));
            unit.put("cluster_id", effectiveCluster.map(EstateAsOfService::opaqueClusterId).orElse(null));
            unit.put("cluster_member_ref", projectClusterRef(rawClusterForDisplay, masked));
            unit.put("enrolled_at_moment", enrolledAtMoment);
            unit.put("failover", failover);
            unit.put("backup", backup);
            unit.put("compliance", compliance);
            unitRows.add(unit);
        }

        if (matchingHashBasisUnits > 0) {
            Map<String, Object> hashNote = new LinkedHashMap<>();
            hashNote.put("code", "COMPLIANCE_MATCHED_BY_CANONICAL_HASH");
            hashNote.put("units", matchingHashBasisUnits);
            hashNote.put("message", "compliance_evaluation stores the latest row per device (6 h warmup); units whose primary configuration canonical_hash at 'at' equals the stored evaluation hash are answered with basis=MATCHING_CONFIG_HASH.");
            warnings.add(hashNote);
        }

        Map<String, Object> failoverSummary = new LinkedHashMap<>();
        failoverSummary.put("ready", foReady);
        failoverSummary.put("stale", foStale);
        failoverSummary.put("not_ready", foNotReady);
        failoverSummary.put("unknown", foUnknown);
        failoverSummary.put("not_applicable", foNotApplicable);
        failoverSummary.put("newest_evidence_at", foNewest == null ? null : foNewest.toString());
        failoverSummary.put("oldest_evidence_at", foOldest == null ? null : foOldest.toString());

        Map<String, Object> backupSummary = new LinkedHashMap<>();
        backupSummary.put("present", bkPresent);
        backupSummary.put("stale", bkStale);
        backupSummary.put("open_size_anomaly", bkAnomaly);
        backupSummary.put("missing", bkMissing);
        backupSummary.put("unknown", bkUnknown);
        backupSummary.put("not_applicable", bkNotApplicable);
        backupSummary.put("newest_evidence_at", bkNewest == null ? null : bkNewest.toString());
        backupSummary.put("oldest_evidence_at", bkOldest == null ? null : bkOldest.toString());

        Map<String, Object> complianceSummary = new LinkedHashMap<>();
        complianceSummary.put("evidenced_units", cmpEvidenced);
        complianceSummary.put("all_passing_units", cmpAllPass);
        complianceSummary.put("failing_units", cmpFailing);
        complianceSummary.put("critical_failing_units", cmpCriticalFailing);
        complianceSummary.put("no_evidence_only_units", cmpNoEvidenceOnly);
        complianceSummary.put("unknown_units", cmpUnknown);
        complianceSummary.put("not_applicable_units", cmpNotApplicable);
        complianceSummary.put("pass_checks", cmpPassChecks);
        complianceSummary.put("fail_checks", cmpFailChecks);
        complianceSummary.put("no_evidence_checks", cmpNoEvidenceChecks);
        complianceSummary.put("critical_fail_checks", cmpCriticalFailChecks);
        complianceSummary.put("newest_evidence_at", cmpNewest == null ? null : cmpNewest.toString());
        complianceSummary.put("oldest_evidence_at", cmpOldest == null ? null : cmpOldest.toString());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total_units", active.size());
        summary.put("enrolled_at_moment", enrolledAtMomentCount);
        summary.put("enrolled_after_moment", enrolledAfterMomentCount);
        summary.put("failover", failoverSummary);
        summary.put("backup", backupSummary);
        summary.put("compliance", complianceSummary);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("as_of", at.toString());
        body.put("generated_at", now.toString());
        body.put("masked", masked);
        body.put("warnings", List.copyOf(warnings));
        body.put("summary", summary);
        body.put("units", List.copyOf(unitRows));
        return new EstateAsOfResponse(masked, body);
    }

    private String projectHostname(DeviceSummaryRecord d, String rawClusterRef, boolean masked) {
        String rawHostname = d.observedHostname().filter(s -> !s.isBlank()).orElse(null);
        if (!masked) {
            return rawHostname;
        }
        if (rawHostname != null) {
            String candidate = pseudonymizer.maskDeviceName(
                    rawHostname, rawClusterRef, d.deviceId(), d.managementIp().orElse(null));
            if (candidate != null && !TopologyNamePseudonymizer.isGap(candidate)) {
                return candidate;
            }
        }
        return pseudonymizer.maskUnknownHost(d.deviceId());
    }

    private String projectClusterRef(String rawClusterRef, boolean masked) {
        if (rawClusterRef == null || rawClusterRef.isBlank()) {
            return null;
        }
        return masked ? pseudonymizer.maskClusterName(rawClusterRef) : rawClusterRef;
    }

    private static Optional<String> effectiveClusterRef(
            DeviceSummaryRecord d,
            Map<String, Map<String, ReadinessRow>> readinessByCluster
    ) {
        if (d.clusterMemberRef().isPresent() && !d.clusterMemberRef().get().isBlank()) {
            return Optional.of(d.clusterMemberRef().get());
        }
        String singleFgtRef = FGT_SINGLE_PREFIX + d.deviceId();
        if ("fortinet".equals(d.vendorHint()) && "gateway".equals(d.role())
                && (d.haPeerUnconfirmed() || readinessByCluster.containsKey("fortinet\u0000" + singleFgtRef))) {
            return Optional.of(singleFgtRef);
        }
        return Optional.empty();
    }

    private static Map<String, Object> buildFailoverFigure(
            DeviceSummaryRecord d,
            boolean enrolledAtMoment,
            String clusterRef,
            Map<String, List<DeviceSummaryRecord>> membersByClusterRef,
            Map<String, Map<String, ReadinessRow>> readinessByCluster,
            Instant at
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enrolledAtMoment) {
            return fillFailoverUnknown(out, "UNKNOWN", "UNKNOWN", "ENROLLED_AFTER_MOMENT", 0, 0, 0, 0, null, null);
        }
        if (clusterRef == null) {
            out.put("state", "NOT_APPLICABLE");
            out.put("outcome", "NOT_APPLICABLE");
            out.put("verdict", "NOT_APPLICABLE");
            out.put("stale", false);
            out.put("failed_check", null);
            out.put("reason", "NOT_HA_MEMBER");
            out.put("ready_sub_units", 0);
            out.put("stale_sub_units", 0);
            out.put("not_ready_sub_units", 0);
            out.put("unknown_sub_units", 0);
            out.put("evidence_at", null);
            out.put("age_seconds", null);
            return out;
        }
        String vendor = d.vendorHint();
        if (vendor == null || !FAILOVER_VENDORS.contains(vendor)) {
            return fillFailoverUnknown(out, "UNKNOWN", "UNKNOWN", "VENDOR_READINESS_UNSUPPORTED", 0, 0, 0, 1, null, null);
        }

        Map<String, ReadinessRow> byVs = readinessByCluster.getOrDefault(vendor + "\u0000" + clusterRef, Map.of());
        Set<String> expectedVsKeys = new LinkedHashSet<>();
        expectedVsKeys.add(""); // base cluster or VSX chassis step
        if ("check_point".equals(vendor)) {
            for (DeviceSummaryRecord member : membersByClusterRef.getOrDefault(clusterRef, List.of(d))) {
                member.virtualSystems().ifPresent(vsList -> {
                    for (String part : vsList.split(",\\s*")) {
                        Matcher m = VSID_IN_LABEL.matcher(part.strip());
                        if (m.find()) {
                            String id = m.group(1) != null ? m.group(1) : m.group(2);
                            if (id != null && !id.isBlank()) {
                                expectedVsKeys.add(id);
                            }
                        }
                    }
                });
            }
        }
        expectedVsKeys.addAll(byVs.keySet());

        if (byVs.isEmpty()) {
            return fillFailoverUnknown(out, "UNKNOWN", "UNKNOWN", "NO_READINESS_BEFORE_AT",
                    0, 0, 0, expectedVsKeys.size(), null, null);
        }

        int readyN = 0, staleN = 0, notReadyN = 0, unknownN = 0;
        String firstFailedCheck = null;
        String firstUnknownReason = null;
        Instant conservativeEvidenceAt = null;
        Instant newestNotReadyAt = null;

        for (String vsKey : expectedVsKeys) {
            ReadinessRow row = byVs.get(vsKey);
            if (row == null || row.finishedAt() == null) {
                unknownN++;
                if (firstUnknownReason == null) {
                    firstUnknownReason = "SUB_UNIT_READINESS_MISSING";
                }
                continue;
            }
            conservativeEvidenceAt = minInstant(conservativeEvidenceAt, row.finishedAt());
            boolean rowStale = staleAt(row.finishedAt(), at);
            if ("NOT_READY".equals(row.outcome())) {
                notReadyN++;
                newestNotReadyAt = maxInstant(newestNotReadyAt, row.finishedAt());
                if (firstFailedCheck == null && row.failedCheck() != null && !row.failedCheck().isBlank()) {
                    firstFailedCheck = row.failedCheck();
                }
                if (firstUnknownReason == null) {
                    firstUnknownReason = safeStopCode(row.message());
                }
            } else if ("READY".equals(row.outcome())) {
                if (rowStale) {
                    staleN++;
                } else {
                    readyN++;
                }
            } else {
                unknownN++;
                if (firstUnknownReason == null) {
                    String code = safeStopCode(row.message());
                    firstUnknownReason = code != null ? code : "READINESS_OUTCOME_UNKNOWN";
                }
            }
        }

        String outcome;
        String verdict;
        String state;
        String reason = null;
        Instant reportedEvidenceAt = conservativeEvidenceAt;
        if (notReadyN > 0) {
            state = "EVIDENCED";
            outcome = "NOT_READY";
            verdict = "NOT_READY";
            reportedEvidenceAt = newestNotReadyAt != null ? newestNotReadyAt : conservativeEvidenceAt;
            reason = firstUnknownReason;
        } else if (unknownN > 0) {
            state = "UNKNOWN";
            outcome = "UNKNOWN";
            verdict = "UNKNOWN";
            reason = firstUnknownReason != null ? firstUnknownReason : "READINESS_OUTCOME_UNKNOWN";
        } else if (staleN > 0) {
            state = "EVIDENCED";
            outcome = "READY";
            verdict = "STALE";
        } else {
            state = "EVIDENCED";
            outcome = "READY";
            verdict = "READY";
        }

        boolean stale = reportedEvidenceAt != null && staleAt(reportedEvidenceAt, at);
        out.put("state", state);
        out.put("outcome", outcome);
        out.put("verdict", verdict);
        out.put("stale", stale);
        out.put("failed_check", firstFailedCheck);
        out.put("reason", reason);
        out.put("ready_sub_units", readyN);
        out.put("stale_sub_units", staleN);
        out.put("not_ready_sub_units", notReadyN);
        out.put("unknown_sub_units", unknownN);
        out.put("evidence_at", reportedEvidenceAt == null ? null : reportedEvidenceAt.toString());
        out.put("age_seconds", ageSeconds(reportedEvidenceAt, at));
        return out;
    }

    private static Map<String, Object> fillFailoverUnknown(
            Map<String, Object> out,
            String state,
            String outcome,
            String reason,
            int ready,
            int stale,
            int notReady,
            int unknown,
            Instant evidenceAt,
            Instant at
    ) {
        out.put("state", state);
        out.put("outcome", outcome);
        out.put("verdict", outcome);
        out.put("stale", false);
        out.put("failed_check", null);
        out.put("reason", reason);
        out.put("ready_sub_units", ready);
        out.put("stale_sub_units", stale);
        out.put("not_ready_sub_units", notReady);
        out.put("unknown_sub_units", unknown);
        out.put("evidence_at", evidenceAt == null ? null : evidenceAt.toString());
        out.put("age_seconds", evidenceAt == null || at == null ? null : ageSeconds(evidenceAt, at));
        return out;
    }

    private static Map<String, Object> buildBackupFigure(
            DeviceSummaryRecord d,
            boolean enrolledAtMoment,
            BackupRow backup,
            boolean openSizeAnomaly,
            Instant at
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enrolledAtMoment) {
            out.put("state", "UNKNOWN");
            out.put("verdict", "UNKNOWN");
            out.put("backup_target", d.backupTarget());
            out.put("present", null);
            out.put("stale", null);
            out.put("older_than_7d", null);
            out.put("open_size_anomaly", null);
            out.put("still_stored_now", null);
            out.put("plaintext_bytes", null);
            out.put("backup_type", null);
            out.put("reason", "ENROLLED_AFTER_MOMENT");
            out.put("evidence_at", null);
            out.put("age_seconds", null);
            return out;
        }
        if (!d.backupTarget() && backup == null) {
            out.put("state", "NOT_APPLICABLE");
            out.put("verdict", "NOT_APPLICABLE");
            out.put("backup_target", false);
            out.put("present", false);
            out.put("stale", false);
            out.put("older_than_7d", false);
            out.put("open_size_anomaly", false);
            out.put("still_stored_now", false);
            out.put("plaintext_bytes", null);
            out.put("backup_type", null);
            out.put("reason", "NOT_BACKUP_TARGET");
            out.put("evidence_at", null);
            out.put("age_seconds", null);
            return out;
        }
        if (backup == null) {
            out.put("state", "MISSING");
            out.put("verdict", "MISSING");
            out.put("backup_target", d.backupTarget());
            out.put("present", false);
            out.put("stale", false);
            out.put("older_than_7d", false);
            out.put("open_size_anomaly", openSizeAnomaly);
            out.put("still_stored_now", false);
            out.put("plaintext_bytes", null);
            out.put("backup_type", null);
            out.put("reason", "NO_ARCHIVE_AT_MOMENT");
            out.put("evidence_at", null);
            out.put("age_seconds", null);
            return out;
        }
        boolean stale = staleAt(backup.createdAt(), at);
        boolean olderThan7d = backup.createdAt().isBefore(at)
                && Duration.between(backup.createdAt(), at).compareTo(BACKUP_ATTENTION_STALE_AFTER) > 0;
        String verdict = openSizeAnomaly ? "ANOMALY" : stale ? "STALE" : "OK";
        out.put("state", "PRESENT");
        out.put("verdict", verdict);
        out.put("backup_target", d.backupTarget());
        out.put("present", true);
        out.put("stale", stale);
        out.put("older_than_7d", olderThan7d);
        out.put("open_size_anomaly", openSizeAnomaly);
        out.put("still_stored_now", backup.stillStoredNow());
        out.put("plaintext_bytes", backup.plaintextBytes());
        out.put("backup_type", backup.backupType());
        out.put("reason", null);
        out.put("evidence_at", backup.createdAt().toString());
        out.put("age_seconds", ageSeconds(backup.createdAt(), at));
        return out;
    }

    private static Map<String, Object> buildComplianceFigure(
            DeviceSummaryRecord d,
            boolean enrolledAtMoment,
            ConfigRunRow configAtMoment,
            ComplianceRow eval,
            Instant at
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!enrolledAtMoment) {
            return fillComplianceNonEvidenced(out, "UNKNOWN", "ENROLLED_AFTER_MOMENT");
        }
        boolean complianceTarget = !"management_server".equals(d.role())
                && d.vendorHint() != null
                && COMPLIANCE_VENDORS.contains(d.vendorHint().toLowerCase(Locale.ROOT));
        if (!complianceTarget) {
            return fillComplianceNonEvidenced(out, "NOT_APPLICABLE", "NOT_COMPLIANCE_TARGET");
        }
        if (eval == null) {
            return fillComplianceNonEvidenced(out, "UNKNOWN", configAtMoment == null ? "NO_EVIDENCE_BEFORE_AT" : "NOT_EVALUATED");
        }

        boolean evaluatedByMoment = eval.evaluatedAt() != null && !eval.evaluatedAt().isAfter(at);
        String basis;
        Instant evidenceAt;

        if (evaluatedByMoment) {
            if (configAtMoment != null
                    && eval.canonicalHash() != null
                    && configAtMoment.canonicalHash() != null
                    && !eval.canonicalHash().equals(configAtMoment.canonicalHash())) {
                return fillComplianceNonEvidenced(out, "UNKNOWN", "EVALUATION_NOT_MATCHING_CONFIG_AT_MOMENT");
            }
            basis = "EVALUATED_AT_OR_BEFORE";
            evidenceAt = configAtMoment != null ? configAtMoment.collectedAt() : eval.evaluatedAt();
        } else {
            if (configAtMoment == null) {
                return fillComplianceNonEvidenced(out, "UNKNOWN", "NO_EVIDENCE_BEFORE_AT");
            }
            if (eval.canonicalHash() == null || !eval.canonicalHash().equals(configAtMoment.canonicalHash())) {
                return fillComplianceNonEvidenced(out, "UNKNOWN", "EVALUATION_OVERWRITTEN");
            }
            basis = "MATCHING_CONFIG_HASH";
            evidenceAt = configAtMoment.collectedAt();
        }

        ComplianceCounts counts = parseComplianceResult(eval.resultJson());
        if (counts == null) {
            return fillComplianceNonEvidenced(out, "UNKNOWN", "EVALUATION_UNREADABLE");
        }

        String verdict = counts.fail() > 0 ? "FAIL" : counts.pass() > 0 ? "PASS" : "NO_EVIDENCE";
        out.put("state", "EVIDENCED");
        out.put("verdict", verdict);
        out.put("basis", basis);
        out.put("pass_count", counts.pass());
        out.put("fail_count", counts.fail());
        out.put("no_evidence_count", counts.noEvidence());
        out.put("critical_fail_count", counts.criticalFail());
        out.put("evaluated_at", eval.evaluatedAt() == null ? null : eval.evaluatedAt().toString());
        out.put("reason", null);
        out.put("evidence_at", evidenceAt == null ? null : evidenceAt.toString());
        out.put("age_seconds", ageSeconds(evidenceAt, at));
        return out;
    }

    private static Map<String, Object> fillComplianceNonEvidenced(Map<String, Object> out, String state, String reason) {
        out.put("state", state);
        out.put("verdict", state);
        out.put("basis", null);
        out.put("pass_count", null);
        out.put("fail_count", null);
        out.put("no_evidence_count", null);
        out.put("critical_fail_count", null);
        out.put("evaluated_at", null);
        out.put("reason", reason);
        out.put("evidence_at", null);
        out.put("age_seconds", null);
        return out;
    }

    private static ComplianceCounts parseComplianceResult(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(json);
        } catch (Exception unreadable) {
            return null;
        }
        if (root == null || root.has("error") || !root.path("items").isArray()) {
            return null;
        }
        int pass = 0, fail = 0, noEvidence = 0, criticalFail = 0;
        for (JsonNode item : root.path("items")) {
            String st = item.path("displayStatus").asText("");
            if ("NOT_APPLICABLE".equalsIgnoreCase(st)) {
                continue;
            }
            if ("PASS".equalsIgnoreCase(st)) {
                pass++;
            } else if ("FAIL".equalsIgnoreCase(st)) {
                fail++;
                if ("CRITICAL".equalsIgnoreCase(item.path("severity").asText(""))) {
                    criticalFail++;
                }
            } else {
                noEvidence++;
            }
        }
        return new ComplianceCounts(pass, fail, noEvidence, criticalFail);
    }

    private static String safeStopCode(String rawMessage) {
        if (rawMessage == null) {
            return null;
        }
        String trimmed = rawMessage.strip();
        if (trimmed.isEmpty() || "READY".equals(trimmed)) {
            return null;
        }
        return SAFE_CODE_TOKEN.matcher(trimmed).matches() ? trimmed : null;
    }

    private static boolean staleAt(Instant evidenceAt, Instant at) {
        return evidenceAt != null && evidenceAt.isBefore(at)
                && Duration.between(evidenceAt, at).compareTo(Freshness.STALE_AFTER) > 0;
    }

    private static Long ageSeconds(Instant evidenceAt, Instant at) {
        if (evidenceAt == null || at == null) {
            return null;
        }
        return Math.max(0L, at.getEpochSecond() - evidenceAt.getEpochSecond());
    }

    private static String opaqueClusterId(String clusterRef) {
        return UUID.nameUUIDFromBytes(clusterRef.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static Instant parseIsoOrNull(String value) {
        return value == null ? null : Instant.parse(value);
    }

    private static Instant maxInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isAfter(a) ? b : a;
    }

    private static Instant minInstant(Instant a, Instant b) {
        if (a == null) return b;
        if (b == null) return a;
        return b.isBefore(a) ? b : a;
    }

    static Instant toInstant(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof Instant instant) {
            return instant;
        }
        if (raw instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (raw instanceof Timestamp ts) {
            return ts.toInstant();
        }
        return Instant.parse(raw.toString());
    }

    static final class JooqStore implements Store {

        private final TransactionBoundary tx;

        JooqStore(TransactionBoundary tx) {
            this.tx = Objects.requireNonNull(tx, "tx");
        }

        @Override
        public EvidenceSnapshot load(Instant at) {
            OffsetDateTime cutoff = OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
            return tx.inTransaction(dsl -> {
                dsl.execute("set transaction read only");
                dsl.execute("set local statement_timeout = " + STATEMENT_TIMEOUT_MS);
                return new EvidenceSnapshot(
                        loadDeviceMeta(dsl),
                        loadReadiness(dsl, cutoff),
                        loadBackups(dsl, cutoff),
                        loadOpenSizeAnomalies(dsl, cutoff),
                        loadConfigurations(dsl, cutoff),
                        loadCompliance(dsl)
                );
            });
        }

        private static Map<String, DeviceMeta> loadDeviceMeta(DSLContext dsl) {
            Map<String, DeviceMeta> out = new HashMap<>();
            for (Record r : dsl.fetch("select device_id, created_at, disabled from devices")) {
                String id = r.get("device_id", String.class);
                out.put(id, new DeviceMeta(
                        id,
                        toInstant(r.get("created_at")),
                        Boolean.TRUE.equals(r.get("disabled", Boolean.class))
                ));
            }
            return out;
        }

        private static List<ReadinessRow> loadReadiness(DSLContext dsl, OffsetDateTime cutoff) {
            return dsl.fetch("""
                    select distinct on (vendor, cluster_ref, coalesce(vs_id, ''))
                        run_id, vendor, cluster_ref, vs_id, state, outcome, finished_at, failed_check, message
                    from failover_run
                    where run_kind = 'READINESS'
                      and state in ('DONE', 'STOPPED')
                      and finished_at <= {0}
                    order by vendor, cluster_ref, coalesce(vs_id, ''), finished_at desc, run_id desc
                    """, cutoff)
                    .map(r -> new ReadinessRow(
                            r.get("run_id", String.class),
                            r.get("vendor", String.class),
                            r.get("cluster_ref", String.class),
                            r.get("vs_id", String.class),
                            r.get("state", String.class),
                            r.get("outcome", String.class),
                            toInstant(r.get("finished_at")),
                            r.get("failed_check", String.class),
                            r.get("message", String.class)
                    ));
        }

        private static Map<String, BackupRow> loadBackups(DSLContext dsl, OffsetDateTime cutoff) {
            Map<String, BackupRow> out = new LinkedHashMap<>();
            dsl.fetch("""
                    select distinct on (a.device_id)
                        a.device_id, a.artefact_id, a.created_at, a.plaintext_bytes,
                        coalesce(a.backup_type, 'standard') as backup_type, a.deviation_state,
                        not exists (
                            select 1 from artefact_retention_ledger l_now
                            where l_now.artefact_id = a.artefact_id and l_now.event = 'removed'
                        ) as still_stored_now
                    from backup_artefact a
                    where a.artefact_class = 'backup'
                      and a.created_at <= {0}
                      and not exists (
                          select 1 from artefact_retention_ledger l
                          where l.artefact_id = a.artefact_id
                            and l.event = 'removed'
                            and l.occurred_at <= {0}
                      )
                    order by a.device_id, a.created_at desc, a.artefact_id desc
                    """, cutoff)
                    .forEach(r -> {
                        String deviceId = r.get("device_id", String.class);
                        out.put(deviceId, new BackupRow(
                                deviceId,
                                r.get("artefact_id", String.class),
                                toInstant(r.get("created_at")),
                                r.get("plaintext_bytes", Long.class),
                                r.get("backup_type", String.class),
                                r.get("deviation_state", String.class),
                                Boolean.TRUE.equals(r.get("still_stored_now", Boolean.class))
                        ));
                    });
            return out;
        }

        private static Set<String> loadOpenSizeAnomalies(DSLContext dsl, OffsetDateTime cutoff) {
            Set<String> out = new LinkedHashSet<>();
            dsl.fetch("""
                    select distinct device_id
                    from backup_deviation_record
                    where deviation_class = 'size_anomaly'
                      and detected_at <= {0}
                      and (resolved_at is null or resolved_at > {0})
                    """, cutoff)
                    .forEach(r -> out.add(r.get("device_id", String.class)));
            return out;
        }

        private static Map<String, ConfigRunRow> loadConfigurations(DSLContext dsl, OffsetDateTime cutoff) {
            Map<String, ConfigRunRow> out = new LinkedHashMap<>();
            dsl.fetch("""
                    select distinct on (device_id)
                        device_id, run_id, canonical_hash, collected_at
                    from device_configuration_run
                    where is_primary and collected_at <= {0}
                    order by device_id, collected_at desc, run_id desc
                    """, cutoff)
                    .forEach(r -> {
                        String deviceId = r.get("device_id", String.class);
                        out.put(deviceId, new ConfigRunRow(
                                deviceId,
                                r.get("run_id", String.class),
                                r.get("canonical_hash", String.class),
                                toInstant(r.get("collected_at"))
                        ));
                    });
            return out;
        }

        private static Map<String, ComplianceRow> loadCompliance(DSLContext dsl) {
            Map<String, ComplianceRow> out = new LinkedHashMap<>();
            dsl.fetch("""
                    select device_id, canonical_hash, rules_fingerprint, evaluated_at, result::text as result
                    from compliance_evaluation
                    """)
                    .forEach(r -> {
                        String deviceId = r.get("device_id", String.class);
                        out.put(deviceId, new ComplianceRow(
                                deviceId,
                                r.get("canonical_hash", String.class),
                                r.get("rules_fingerprint", String.class),
                                toInstant(r.get("evaluated_at")),
                                r.get("result", String.class)
                        ));
                    });
            return out;
        }
    }
}
