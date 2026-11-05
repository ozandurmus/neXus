package com.securityexpert.nexus.ui2.service.report;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.report.JooqReportReadRepository.ComplianceRow;

/**
 * The stored compliance evaluations (V59 {@code compliance_evaluation}) counted the way the Compliance screen counts
 * them ({@code ComplianceService.checkOutcome}): NOT_APPLICABLE is not a check at all; PASS and FAIL are evidence; any
 * other status is "no evidence" and is shown as its own category, never folded into pass or fail. Reports read the
 * stored evaluations only; nothing here asks the compliance service to evaluate.
 *
 * <p>Firewalls only: management servers are never evaluated (as on the Compliance screen), so they are neither covered
 * nor uncovered.</p>
 */
final class ComplianceFacts {

    enum Outcome { PASS, FAIL, NO_EVIDENCE }

    /** The four frameworks, primary first, with their display names (ComplianceService.mapFrameworkName). */
    static final Map<String, String> FRAMEWORK_NAMES = new LinkedHashMap<>();

    static {
        FRAMEWORK_NAMES.put("CIS", "CIS Benchmark");
        FRAMEWORK_NAMES.put("NIST_800_53", "NIST SP 800-53");
        FRAMEWORK_NAMES.put("PCI_DSS", "PCI-DSS v4.0.1");
        FRAMEWORK_NAMES.put("FINANCIAL_BASELINE", "Financial Baseline");
    }

    /** One control on one firewall. {@code frameworks}: framework key to its reference (null when none is given). */
    record Check(String deviceId, String controlId, String title, String severity, Outcome outcome,
            Map<String, String> frameworks) {
    }

    record Counts(long pass, long fail, long noEvidence) {
        long total() {
            return pass + fail + noEvidence;
        }

        Counts plus(Outcome o) {
            return switch (o) {
                case PASS -> new Counts(pass + 1, fail, noEvidence);
                case FAIL -> new Counts(pass, fail + 1, noEvidence);
                case NO_EVIDENCE -> new Counts(pass, fail, noEvidence + 1);
            };
        }

        /** Passing share of evidenced checks: pass / (pass + fail). */
        String evidencedPassPct() {
            return Freshness.pct(pass, pass + fail);
        }

        /** Share of checks with no evidence. */
        String noEvidencePct() {
            return Freshness.pct(noEvidence, total());
        }

        static final Counts ZERO = new Counts(0, 0, 0);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    final List<DeviceSummaryRecord> firewalls;
    final Map<String, DeviceSummaryRecord> firewallById = new LinkedHashMap<>();
    final Map<String, Instant> evaluatedAt = new LinkedHashMap<>();
    final List<Check> checks = new ArrayList<>();
    /** Evaluations that could not be read (unparseable stored JSON): their firewall counts as not covered. */
    int unreadable;

    ComplianceFacts(List<DeviceSummaryRecord> devices, List<ComplianceRow> rows) {
        this.firewalls = devices.stream().filter(d -> !"management_server".equals(d.role())).toList();
        for (DeviceSummaryRecord d : firewalls) firewallById.put(d.deviceId(), d);
        for (ComplianceRow row : rows) {
            if (!firewallById.containsKey(row.deviceId())) continue; // a deleted device or a management server
            JsonNode root;
            try {
                root = JSON.readTree(row.resultJson());
            } catch (Exception e) {
                unreadable++;
                continue;
            }
            if (root == null || root.has("error") || !root.path("items").isArray()) {
                unreadable++;
                continue;
            }
            evaluatedAt.put(row.deviceId(), row.evaluatedAt());
            for (JsonNode item : root.path("items")) {
                Optional<Outcome> outcome = outcome(item.path("displayStatus").asText(null));
                if (outcome.isEmpty()) continue;
                Map<String, String> frameworks = new LinkedHashMap<>();
                for (JsonNode fw : item.path("frameworks")) {
                    String key = fw.path("framework").asText("").toUpperCase(Locale.ROOT);
                    if (!FRAMEWORK_NAMES.containsKey(key) || frameworks.containsKey(key)) continue;
                    String ref = fw.hasNonNull("reference") ? fw.get("reference").asText()
                            : fw.hasNonNull("clauseId") ? fw.get("clauseId").asText() : null;
                    frameworks.put(key, ref);
                }
                checks.add(new Check(row.deviceId(), item.path("controlId").asText("UNKNOWN"),
                        item.hasNonNull("title") ? item.get("title").asText() : item.path("controlId").asText("UNKNOWN"),
                        item.path("severity").asText("UNKNOWN").toUpperCase(Locale.ROOT), outcome.get(), frameworks));
            }
        }
    }

    /** Empty for NOT_APPLICABLE (not a check); PASS / FAIL; anything else is no evidence. */
    static Optional<Outcome> outcome(String displayStatus) {
        if (displayStatus != null && "NOT_APPLICABLE".equalsIgnoreCase(displayStatus)) return Optional.empty();
        if ("PASS".equalsIgnoreCase(displayStatus)) return Optional.of(Outcome.PASS);
        if ("FAIL".equalsIgnoreCase(displayStatus)) return Optional.of(Outcome.FAIL);
        return Optional.of(Outcome.NO_EVIDENCE);
    }

    Counts framework(String key) {
        Counts c = Counts.ZERO;
        for (Check check : checks) {
            if (check.frameworks().containsKey(key)) c = c.plus(check.outcome());
        }
        return c;
    }

    /** Per control family of one framework, sorted by family. */
    Map<String, Counts> families(String key) {
        Map<String, Counts> out = new TreeMap<>();
        for (Check check : checks) {
            if (!check.frameworks().containsKey(key)) continue;
            out.merge(family(key, check.frameworks().get(key)), Counts.ZERO.plus(check.outcome()),
                    (a, b) -> new Counts(a.pass() + b.pass(), a.fail() + b.fail(), a.noEvidence() + b.noEvidence()));
        }
        return out;
    }

    /**
     * A control family from a framework reference: NIST's two-letter family ("AC-2" is AC); otherwise the first
     * section of a dotted reference ("1.2.3" is 1) or the reference without its last dash part ("FB-NET-01" is FB-NET).
     * A missing reference is "Unmapped" -- never guessed.
     */
    static String family(String key, String reference) {
        if (reference == null || reference.isBlank()) return "Unmapped";
        String ref = reference.strip();
        if ("NIST_800_53".equals(key) && ref.matches("^[A-Za-z]{2}-.*")) return ref.substring(0, 2).toUpperCase(Locale.ROOT);
        int dot = ref.indexOf('.');
        if (dot > 0) return ref.substring(0, dot);
        int dash = ref.lastIndexOf('-');
        if (dash > 0) return ref.substring(0, dash);
        return ref;
    }

    int criticalFails() {
        return (int) checks.stream().filter(c -> c.outcome() == Outcome.FAIL && "CRITICAL".equals(c.severity())).count();
    }
}
