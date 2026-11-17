package com.securityexpert.nexus.ui2.worker.failover;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.JooqCpFailoverRepository;

/**
 * "Traffic and ARP (information)": the vendor-neutral half of the readiness check that shows traffic really passes and
 * counts ARP, the way Check Point checks 5 and 8 do (PO 2026-11-17). One reading per run, taken on the active (or
 * enrolled primary) member only; the numbers are compared with the most recent earlier readiness run of the same unit.
 *
 * <p>The check never blocks: a vendor wires it into its check list as an information row, so it can not turn a READY run
 * into NOT_READY or UNKNOWN. Evidence is numbers and booleans only: no address, MAC, interface name or host name.
 */
final class TrafficArpCheck {
    private TrafficArpCheck() {}

    private static final ObjectMapper JSON = new ObjectMapper();
    /** A post-switch number below this fraction of the earlier run's number is flagged (Check Point's traffic tolerance). */
    static final double MIN_RATIO = CpFailoverChecks.TRAFFIC_MIN_RATIO;
    static final String PASS = "PASS", UNKNOWN = "UNKNOWN", NOT_EVALUATED = "NOT_EVALUATED";
    static final String NOT_ACTIVE_MEMBER = "NOT_ACTIVE_MEMBER";

    /** One reading: a null number is one that was not read or did not parse. {@code sessions} is FortiGate's information-only count. */
    record Reading(Long trafficBytesPerSecond, Integer arpEntries, Long sessions, Map<String, Integer> arpByVdom, String trafficScope) {
        Reading {
            // Per-VDOM counts keyed by an ordinal ("1", "2", ...), never a VDOM name; a VDOM that could not be read maps to null.
            arpByVdom = arpByVdom == null ? null : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(arpByVdom));
        }
        Reading(Long trafficBytesPerSecond, Integer arpEntries, Long sessions) { this(trafficBytesPerSecond, arpEntries, sessions, null, null); }
        static final Reading NONE = new Reading(null, null, null);
    }
    /** The earlier run's numbers; {@code arpByVdom} is empty when that run was not multi-VDOM. */
    record Previous(long trafficBytesPerSecond, long arpEntries, Map<String, Long> arpByVdom) {
        Previous(long trafficBytesPerSecond, long arpEntries) { this(trafficBytesPerSecond, arpEntries, Map.of()); }
    }
    /** Status, one reason code and derived evidence. */
    record Outcome(String status, String reason, Map<String, Object> evidence) {
        Outcome { evidence = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evidence)); }
    }

    /** A reading that was not taken at all (a gate that is not approved, no active member): UNKNOWN with the reason. */
    static Outcome notRead(String reason) { return new Outcome(UNKNOWN, reason, Map.of()); }

    /** The standby member's own row: the reading belongs to the active member only. */
    static Outcome notActive() { return new Outcome(NOT_EVALUATED, NOT_ACTIVE_MEMBER, Map.of()); }

    static Outcome evaluate(Reading reading, Optional<Previous> previous) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        Long traffic = reading.trafficBytesPerSecond();
        Integer arp = reading.arpEntries();
        if (traffic != null) evidence.put("trafficBytesPerSecond", traffic);
        if (arp != null) evidence.put("arpEntries", arp);
        if (reading.sessions() != null) evidence.put("averageSessions", reading.sessions());
        // Multi-VDOM FortiGate: the rate is box-wide, the ARP total is the sum over the VDOMs, each VDOM a count by ordinal.
        if (reading.trafficScope() != null) evidence.put("trafficScope", reading.trafficScope());
        if (reading.arpByVdom() != null) {
            evidence.put("arpByVdom", reading.arpByVdom());
            evidence.put("arpVdomCount", reading.arpByVdom().size());
            evidence.put("arpVdomUnknown", reading.arpByVdom().values().stream().filter(java.util.Objects::isNull).count());
        }
        if (previous.isPresent() && (traffic != null || arp != null || reading.arpByVdom() != null)) {
            Previous before = previous.get();
            evidence.put("previousTrafficBytesPerSecond", before.trafficBytesPerSecond());
            evidence.put("previousArpEntries", before.arpEntries());
            if (traffic != null && before.trafficBytesPerSecond() > 0) {
                double ratio = ratio(traffic, before.trafficBytesPerSecond());
                evidence.put("trafficRatio", ratio);
                evidence.put("trafficDropped", ratio < MIN_RATIO);
            }
            if (arp != null && before.arpEntries() > 0) {
                double ratio = ratio(arp, before.arpEntries());
                evidence.put("arpRatio", ratio);
                evidence.put("arpDropped", ratio < MIN_RATIO);
            }
            // Per VDOM, only when both runs saw the same number of VDOMs (ordinals are then the same VDOMs).
            if (reading.arpByVdom() != null && !before.arpByVdom().isEmpty() && before.arpByVdom().size() == reading.arpByVdom().size()) {
                long dropped = 0;
                for (var entry : reading.arpByVdom().entrySet()) {
                    Long earlier = before.arpByVdom().get(entry.getKey());
                    if (entry.getValue() != null && earlier != null && earlier > 0 && (double) entry.getValue() / earlier < MIN_RATIO) dropped++;
                }
                evidence.put("arpDroppedVdoms", dropped);
            }
        }
        if (traffic == null && arp == null) return new Outcome(UNKNOWN, "TRAFFIC_AND_ARP_UNAVAILABLE", evidence);
        if (traffic == null) return new Outcome(UNKNOWN, "TRAFFIC_UNAVAILABLE", evidence);
        if (arp == null) return new Outcome(UNKNOWN, "ARP_UNAVAILABLE", evidence);
        // A zero is not a failure: it may be a quiet window or an empty table. Information only, never PASS.
        if (traffic <= 0 || arp <= 0) return new Outcome(UNKNOWN, traffic <= 0 && arp <= 0 ? "TRAFFIC_AND_ARP_ZERO"
            : traffic <= 0 ? "TRAFFIC_ZERO" : "ARP_ZERO", evidence);
        return new Outcome(PASS, "TRAFFIC_AND_ARP_PRESENT", evidence);
    }

    private static double ratio(double current, double before) {
        return Math.round(current / before * 1000.0) / 1000.0;
    }

    /** The earlier run's numbers, when the repository has them; any stored row that is not two plain numbers is ignored. */
    static Optional<Previous> previous(JooqCpFailoverRepository store, String runId, int checkNo) {
        Optional<JooqCpFailoverRepository.Check> row = store.previousReadinessTraffic(runId, checkNo);
        if (row == null || row.isEmpty()) return Optional.empty();
        try {
            JsonNode derived = JSON.readTree(row.get().derived());
            JsonNode traffic = derived.path("trafficBytesPerSecond"), arp = derived.path("arpEntries");
            if (!traffic.isIntegralNumber() || !arp.isIntegralNumber() || traffic.asLong() < 0 || arp.asLong() < 0) return Optional.empty();
            Map<String, Long> byVdom = new LinkedHashMap<>();
            JsonNode perVdom = derived.path("arpByVdom");
            if (perVdom.isObject()) {
                perVdom.fields().forEachRemaining(e -> { if (e.getValue().isIntegralNumber() && e.getValue().asLong() >= 0) byVdom.put(e.getKey(), e.getValue().asLong()); });
                if (byVdom.size() != perVdom.size()) byVdom.clear(); // an unread VDOM in the earlier run: no per-VDOM comparison
            }
            return Optional.of(new Previous(traffic.asLong(), arp.asLong(), byVdom));
        } catch (java.io.IOException invalid) {
            return Optional.empty();
        }
    }
}
