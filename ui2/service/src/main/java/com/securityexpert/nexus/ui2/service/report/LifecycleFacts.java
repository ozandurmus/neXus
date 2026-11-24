package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import com.securityexpert.nexus.ui2.persistence.device.DevicePlatformFacts;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.lifecycle.DeviceLifecycleRepository.Read;
import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryReadService;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;

/**
 * Stored lifecycle, licensing, advisory, Live Patch and coverage facts for RPT-17
 * ({@code docs/design/REPORTS_RPT17_LIFECYCLE_ADDENDUM_2026_11_24.md}). Derived strictly from
 * {@link LifecycleService.FleetSnapshot} and stored inventory timestamps; never contacts a device.
 */
final class LifecycleFacts {

    record LicenseFinding(DeviceSummaryRecord device, String category, String item, String state, String expiry,
            Long daysRemaining, Instant observedAt) {
    }

    record AdvisoryAffectedRow(DeviceSummaryRecord device, String comparedVersion, String state, long exploited,
            long critical, long high, long criticalHigh, Instant sourceLoadedAt) {
    }

    record AdvisoryUnknownRow(DeviceSummaryRecord device, String storedVersion, String state, String reason) {
    }

    record HotfixRow(DeviceSummaryRecord device, String softwareVersion, String hotfix, String livePatchSummary,
            String state, Instant observedAt) {
    }

    record MissingReadRow(DeviceSummaryRecord device, String state, String detail, Instant lastAttemptAt) {
    }

    record CoveredReadRow(DeviceSummaryRecord device, String state, String kinds, Instant newestReadAt) {
    }

    final List<DeviceSummaryRecord> sortedDevices;
    final List<Instant> lifecycleEvidenceTimes = new ArrayList<>();
    final List<Instant> inventoryEvidenceTimes = new ArrayList<>();
    final List<LicenseFinding> licenseFindings = new ArrayList<>();
    final List<AdvisoryAffectedRow> advisoryAffected = new ArrayList<>();
    final List<AdvisoryUnknownRow> advisoryUnknown = new ArrayList<>();
    final List<HotfixRow> hotfixRows = new ArrayList<>();
    final List<MissingReadRow> missingReads = new ArrayList<>();
    final List<CoveredReadRow> coveredReads = new ArrayList<>();

    int licenseCoveredDevices;
    int expiredItems;
    int expiringWithin30Days;
    int expiringWithin90Days;

    int evaluatedDevices;
    int exploitedDevices;
    int criticalHighDevices;
    Long oldestSourceAgeDays;
    Instant newestSourceLoadedAt;
    Instant oldestSourceLoadedAt;

    int cpTotal;
    int cpCollected;
    int storedHotfixOrPatch;
    int livePatchNotActive;
    int livePatchMissing;

    int noReadDevices;
    int failedReadDevices;

    LifecycleFacts(List<DeviceSummaryRecord> devices, LifecycleService.FleetSnapshot snapshot,
            Map<String, Instant> newestInventory, Instant now) {
        LifecycleService.FleetSnapshot safeSnapshot = snapshot == null ? LifecycleService.FleetSnapshot.EMPTY : snapshot;
        Map<String, Instant> safeInventory = newestInventory == null ? Map.of() : newestInventory;
        this.sortedDevices = devices.stream().sorted(Comparator.comparing(BackupFacts::sortName)).toList();

        Map<String, Map<String, Object>> rowById = new LinkedHashMap<>();
        for (Map<String, Object> row : safeSnapshot.devices()) {
            String id = str(row.get("device_id"));
            if (id != null) rowById.put(id, row);
        }

        for (DeviceSummaryRecord d : sortedDevices) {
            Map<String, Object> row = rowById.get(d.deviceId());
            DevicePlatformFacts facts = safeSnapshot.platformFacts().get(d.deviceId());
            List<Read> reads = safeSnapshot.readsByDevice().getOrDefault(d.deviceId(), List.of());

            collectInventoryTime(d, facts, safeInventory);
            collectLicensing(d, row, safeInventory);
            collectAdvisories(d, row, facts);
            collectHotfixAndLivePatch(d, row, reads, facts, safeInventory);
            collectCoverage(d, row, reads, safeInventory, now);
        }

        licenseFindings.sort(Comparator.comparingInt((LicenseFinding f) -> licenseRank(f.state()))
                .thenComparing(LicenseFinding::daysRemaining, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(f -> BackupFacts.sortName(f.device()))
                .thenComparing(LicenseFinding::item));

        advisoryAffected.sort(Comparator.comparing((AdvisoryAffectedRow r) -> r.exploited() <= 0)
                .thenComparing(Comparator.comparingLong(AdvisoryAffectedRow::exploited).reversed())
                .thenComparing(Comparator.comparingLong(AdvisoryAffectedRow::criticalHigh).reversed())
                .thenComparing(r -> BackupFacts.sortName(r.device())));
    }

    private void collectInventoryTime(DeviceSummaryRecord d, DevicePlatformFacts facts, Map<String, Instant> newestInventory) {
        Instant inv = newestInventory.get(d.deviceId());
        Instant pf = facts == null ? null : facts.observedAt().orElse(null);
        Instant best = inv == null ? pf : pf == null || !pf.isAfter(inv) ? inv : pf;
        inventoryEvidenceTimes.add(best);
    }

    private void collectLicensing(DeviceSummaryRecord d, Map<String, Object> row, Map<String, Instant> newestInventory) {
        String licenseStatus = row == null ? "NOT_COLLECTED" : str(row.get("license_status"));
        String contractStatus = row == null ? "NOT_COLLECTED" : str(row.get("support_contract_status"));
        if ("COLLECTED".equals(licenseStatus) || "COLLECTED".equals(contractStatus)) {
            licenseCoveredDevices++;
        }
        List<Map<String, Object>> licenses = listOfMaps(row == null ? null : row.get("licenses"));
        boolean hasValidSupportContract = licenses.stream().anyMatch(l -> "SUPPORT_CONTRACT".equals(l.get("category"))
                && ("DATED".equals(l.get("status")) || "PERMANENT".equals(l.get("status"))));

        for (Map<String, Object> l : licenses) {
            String rawCategory = str(l.get("category"));
            String kind = str(l.get("kind"));
            String category = rawCategory != null ? rawCategory
                    : "subscription".equalsIgnoreCase(kind) ? "SUBSCRIPTION" : "LICENSE";
            String status = str(l.get("status"));
            Long days = longVal(l.get("days_remaining"));
            String expiry = str(l.get("expiry"));
            Instant observedAt = parseInstant(str(l.get("observed_at")));
            if (observedAt == null && "infoblox".equals(d.vendorHint())) {
                observedAt = newestInventory.get(d.deviceId());
            }

            if ("SUPPORT_CONTRACT".equals(category) && ("EXPIRED".equals(status) || (days != null && days < 0))
                    && hasValidSupportContract) {
                continue;
            }

            String itemState;
            if ("EXPIRED".equals(status) || "EVAL_EXPIRED".equals(status) || (days != null && days < 0)) {
                itemState = "EXPIRED";
                expiredItems++;
            } else if ("DATED".equals(status) && days != null) {
                if (days <= 30) {
                    itemState = "WITHIN 30 DAYS";
                    expiringWithin30Days++;
                    expiringWithin90Days++;
                } else if (days <= 90) {
                    itemState = "WITHIN 90 DAYS";
                    expiringWithin90Days++;
                } else {
                    continue;
                }
            } else if ("UNREGISTERED".equals(status) || "OUT_OF_COMPLIANCE".equals(status)) {
                itemState = status;
            } else if ("PERMANENT".equals(status)) {
                continue;
            } else {
                // UNKNOWN, null, DATED without days, or any unrecognised status: never silently dropped.
                itemState = "UNKNOWN";
            }

            String name = str(l.get("name"));
            if (name == null || name.isBlank()) name = str(l.get("label"));
            licenseFindings.add(new LicenseFinding(d, categoryLabel(category), blankToUnknown(name), itemState, expiry,
                    days, observedAt));
        }
    }

    private void collectAdvisories(DeviceSummaryRecord d, Map<String, Object> row, DevicePlatformFacts facts) {
        String rowVersion = row == null ? null : str(row.get("software_version"));
        String storedVersion = rowVersion != null ? rowVersion : d.observedSoftwareVersion().orElse(null);
        String hotfix = facts == null ? null : facts.hotfixLevel().orElse(null);
        String comparedVersion = AdvisoryReadService.comparedVersion(d.vendorHint(), storedVersion, hotfix);
        Map<String, Object> vuln = mapVal(row == null ? null : row.get("vulnerabilities"));

        if (vuln == null) {
            advisoryUnknown.add(new AdvisoryUnknownRow(d, storedVersion, "UNKNOWN", "ADVISORY_NOT_AVAILABLE"));
            return;
        }

        Map<String, Object> compared = mapVal(vuln.get("compared"));
        if (compared != null) {
            Long ageDays = longVal(compared.get("age_days"));
            if (ageDays != null && (oldestSourceAgeDays == null || ageDays > oldestSourceAgeDays)) {
                oldestSourceAgeDays = ageDays;
            }
            Instant loadedAt = parseInstant(str(compared.get("loaded_at")));
            if (loadedAt != null) {
                if (newestSourceLoadedAt == null || loadedAt.isAfter(newestSourceLoadedAt)) newestSourceLoadedAt = loadedAt;
                if (oldestSourceLoadedAt == null || loadedAt.isBefore(oldestSourceLoadedAt)) oldestSourceLoadedAt = loadedAt;
            }
        }

        String state = blankToUnknown(str(vuln.get("state")));
        if (!"EVALUATED".equals(state)) {
            advisoryUnknown.add(new AdvisoryUnknownRow(d, storedVersion, state, str(vuln.get("reason"))));
            return;
        }

        evaluatedDevices++;
        long exploited = longOrZero(vuln.get("affected_exploited"));
        long critHigh = longOrZero(vuln.get("affected_critical_high"));
        long crit = longOrZero(vuln.get("affected_critical"));
        long high = longOrZero(vuln.get("affected_high"));
        if (exploited > 0) exploitedDevices++;
        if (critHigh > 0) criticalHighDevices++;
        if (exploited > 0 || critHigh > 0) {
            Instant loadedAt = compared == null ? null : parseInstant(str(compared.get("loaded_at")));
            advisoryAffected.add(new AdvisoryAffectedRow(d, comparedVersion,
                    exploited > 0 ? "EXPLOITED" : "CRITICAL_HIGH", exploited, crit, high, critHigh, loadedAt));
        }
    }

    private void collectHotfixAndLivePatch(DeviceSummaryRecord d, Map<String, Object> row, List<Read> reads,
            DevicePlatformFacts facts, Map<String, Instant> newestInventory) {
        String rowVersion = row == null ? null : str(row.get("software_version"));
        String version = rowVersion != null ? rowVersion : d.observedSoftwareVersion().orElse(null);

        if ("check_point".equals(d.vendorHint())) {
            cpTotal++;
            Map<String, Object> lp = mapVal(row == null ? null : row.get("live_patch"));
            if (lp == null) {
                lp = LifecycleProjection.livePatch("CHECKPOINT",
                        new LifecycleProjection.Stored(reads, List.of(), List.of(), facts));
            }
            String jumbo = str(lp.get("jumbo"));
            String take = str(lp.get("package_take"));
            String readOutcome = str(lp.get("read_outcome"));
            long armed = longOrZero(lp.get("armed"));
            long ready = longOrZero(lp.get("ready"));
            boolean missing = Boolean.TRUE.equals(lp.get("missing"));
            Instant observedAt = parseInstant(str(lp.get("observed_at")));
            if (observedAt == null && facts != null) observedAt = facts.observedAt().orElse(null);

            boolean collected = "COLLECTED".equals(readOutcome);
            if (collected) cpCollected++;
            if (jumbo != null || take != null || collected) storedHotfixOrPatch++;
            if (ready > 0) livePatchNotActive++;
            if (missing) livePatchMissing++;

            String summary;
            String patchState;
            if (missing) {
                summary = "No Live Patch package";
                patchState = "MISSING";
            } else if (collected) {
                summary = "take " + blankToUnknown(take) + " · " + armed + " armed"
                        + (ready > 0 ? " · " + ready + " not active" : "");
                patchState = ready > 0 ? "NOT ACTIVE" : "COLLECTED";
            } else if (readOutcome != null) {
                summary = (take != null ? "take " + take + " · " : "") + "read " + readOutcome;
                patchState = "UNKNOWN";
            } else {
                summary = take != null ? "take " + take + " · patches not read" : "Not collected";
                patchState = jumbo != null || take != null ? "UNKNOWN" : "NOT_COLLECTED";
            }
            hotfixRows.add(new HotfixRow(d, version, jumbo, summary, patchState, observedAt));
            return;
        }

        String hotfix = facts == null ? null : facts.hotfixLevel().filter(s -> !s.isBlank()).orElse(null);
        if (hotfix != null) {
            storedHotfixOrPatch++;
            Instant observedAt = facts.observedAt().orElse(newestInventory.get(d.deviceId()));
            hotfixRows.add(new HotfixRow(d, version, hotfix, "NOT_APPLICABLE", "STORED", observedAt));
        }
    }

    private void collectCoverage(DeviceSummaryRecord d, Map<String, Object> row, List<Read> reads,
            Map<String, Instant> newestInventory, Instant now) {
        List<Read> collected = reads.stream()
                .filter(r -> "COLLECTED".equals(r.outcome()) && r.observedAt() != null).toList();
        boolean infobloxSummary = "infoblox".equals(d.vendorHint()) && row != null
                && "COLLECTED".equals(row.get("license_status")) && collected.isEmpty()
                && newestInventory.get(d.deviceId()) != null;

        if (!collected.isEmpty() || infobloxSummary) {
            Instant newestAt = !collected.isEmpty()
                    ? collected.stream().map(Read::observedAt).max(Comparator.naturalOrder()).orElseThrow()
                    : newestInventory.get(d.deviceId());
            lifecycleEvidenceTimes.add(newestAt);
            String kinds = !collected.isEmpty()
                    ? collected.stream().map(Read::readKind).distinct().collect(Collectors.joining(", "))
                    : "infoblox_grid_summary";
            String freshness = Freshness.stale(newestAt, now) ? "STALE" : "CURRENT";
            coveredReads.add(new CoveredReadRow(d, freshness, kinds, newestAt));
            return;
        }

        lifecycleEvidenceTimes.add(null);
        if (reads.isEmpty()) {
            noReadDevices++;
            missingReads.add(new MissingReadRow(d, "NO READ", "No lifecycle read recorded", null));
        } else {
            failedReadDevices++;
            Instant lastAttempt = reads.stream().map(Read::observedAt).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            String detail = reads.stream().map(r -> r.readKind() + ": " + blankToUnknown(r.outcome())
                    + (r.reason() != null && !r.reason().isBlank() ? " (" + r.reason() + ")" : ""))
                    .collect(Collectors.joining("; "));
            missingReads.add(new MissingReadRow(d, "UNKNOWN", detail, lastAttempt));
        }
    }

    static String categoryLabel(String category) {
        return switch (category == null ? "" : category.toUpperCase(Locale.ROOT)) {
            case "LICENSE" -> "License";
            case "SUBSCRIPTION" -> "Subscription";
            case "SUPPORT_CONTRACT" -> "Support contract";
            case "SMART_LICENSING" -> "Smart Licensing";
            default -> "UNKNOWN";
        };
    }

    private static int licenseRank(String state) {
        return switch (state) {
            case "EXPIRED" -> 0;
            case "UNREGISTERED", "OUT_OF_COMPLIANCE" -> 1;
            case "WITHIN 30 DAYS" -> 2;
            case "WITHIN 90 DAYS" -> 3;
            default -> 4;
        };
    }

    static String blankToUnknown(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static String str(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private static Long longVal(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static long longOrZero(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapVal(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) out.add((Map<String, Object>) m);
        }
        return out;
    }
}
