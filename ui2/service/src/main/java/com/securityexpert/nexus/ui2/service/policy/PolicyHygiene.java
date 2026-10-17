package com.securityexpert.nexus.ui2.service.policy;

import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.*;
import static com.securityexpert.nexus.ui2.service.policy.PolicyNormalization.*;
import java.time.*;
import java.util.*;

/** Pure stored-evidence analysis over proven, ordered evaluation scopes. */
public final class PolicyHygiene {
    private PolicyHygiene() {}
    public static final Set<String> CLASSES = Set.of("shadowed", "conflict", "disabled", "unused", "expired", "broad", "unknown");
    public static final Set<String> SEVERITIES = Set.of("HIGH", "MEDIUM", "LOW");
    public record Finding(String findingClass, String severity, String evidence) {}
    public record Assessment(String shadowStatus, String shadowReason, String coveringRuleId,
            List<Finding> findings, Map<String, Object> permissiveness, String timeStatus, boolean expiring,
            String counterWindow, String hitSource, String hitsCollectedAt) {}
    public record Result(Map<String, Assessment> rules, boolean budgetReached) {}
    public static Result analyze(PolicySnapshot snapshot, int days, Instant now, int workLimit) {
        if (days < 1 || days > 36500 || workLimit < 0) throw new IllegalArgumentException("Invalid hygiene limits");
        Budget budget = new Budget(workLimit);
        var resolver = new Resolver(snapshot.objects(), budget);
        Map<String, Assessment> results = new LinkedHashMap<>();
        var scope = new EvaluationScope(snapshot.metadata());
        List<Match> earlier = new ArrayList<>();
        boolean earlierUnknown = false;
        for (Section section : snapshot.sections()) {
            if (PolicySnapshot.panNonSecurity(snapshot.metadata(), section)) {
                // Palo Alto NAT and default-rule sections are stored, not analysed: no shadowing, permissiveness or
                // hit-based finding is invented for them, and they do not disturb the security rule ordering.
                for (Rule rule : section.rules()) {
                    List<Finding> findings = Boolean.FALSE.equals(rule.enabled()) ? List.of(finding("disabled", "LOW", "Rule is disabled.")) : List.of();
                    results.put(rule.id(), new Assessment("UNKNOWN", "unsupported rulebase", null, findings,
                        Map.of("level", "Unknown", "reasons", List.of(), "score", 0), "unknown", false,
                        "Counter window unknown.", "UNKNOWN", null));
                }
                continue;
            }
            if (!scope.advance(section)) {
                earlier.clear();
                earlierUnknown = false;
            }
            for (Rule rule : section.rules()) {
                List<Finding> findings = new ArrayList<>();
                String status = "UNKNOWN", reason = "analysis budget", covering = null;
                Map<String, Object> broad = Map.of("level", "Unknown", "reasons", List.of("Analysis budget reached"), "score", 0);
                String timeStatus = "unknown"; boolean expiring = false;
                if (Boolean.FALSE.equals(rule.enabled())) findings.add(finding("disabled", "LOW", "Rule is disabled."));
                else if (rule.enabled() == null) findings.add(unknown("Enabled state is unknown."));
                try {
                    budget.spend();
                    Match match = normalize(snapshot, section, rule, resolver);
                    broad = PolicyRuleMetrics.permissiveness(rule, snapshot.objects(), budget.left);
                    var time = PolicyRuleMetrics.time(rule, snapshot.objects(), now, snapshot.metadata().vendor(), budget.left);
                    budget.spend();
                    timeStatus = time.status(); expiring = time.expiring();
                    if (timeStatus.equals("expired")) findings.add(finding("expired", "LOW", "Stored schedule has expired."));
                    else if (expiring) findings.add(finding("expired", "LOW", "Stored schedule expires within 14 days."));
                    else if (timeStatus.equals("unknown")) findings.add(unknown("Schedule evidence is incomplete."));
                    if (broad.get("level").equals("Unknown")) findings.add(unknown("Permissiveness cannot be established from resolved selectors."));
                    else if (!broad.get("level").equals("Low")) {
                        var reasons = (List<?>) broad.get("reasons");
                        boolean anyAccept = accepting(rule.action()) && reasons.containsAll(List.of("Any source", "Any destination", "Any service"))
                            && (reasons.contains("Any application") || snapshot.metadata().vendor().equals("CP") && rule.application().refs().isEmpty());
                        findings.add(finding("broad", anyAccept ? "HIGH" : "MEDIUM", anyAccept
                            ? "Accept rule matches any source, destination, service and application."
                            : "Broad selectors are present; see permissiveness reasons."));
                    }
                    reason = match.problem();
                    if (scope.unproven) reason = "earlier sections not analysed";
                    if (reason == null) {
                        status = "NOT_SHADOWED";
                        if (earlierUnknown) reason = "earlier rule has unsupported constraints";
                        for (Match prior : earlier) {
                            budget.spend();
                            if (disjoint(prior, match, snapshot.objects())) continue;
                            if (!compatible(prior, match)) { reason = "zone or install-on coverage is unproven"; continue; }
                            boolean covers = true;
                            for (int i = 0; i < 4 && covers; i++) covers = contains(prior.selectors().get(i), match.selectors().get(i), budget);
                            if (!covers) continue;
                            covering = prior.rule().id();
                            status = prior.rule().action().equals(rule.action()) ? "REDUNDANT" : "CONFLICT";
                            reason = null;
                            findings.add(finding(status.equals("CONFLICT") ? "conflict" : "shadowed", status.equals("CONFLICT") ? "HIGH" : "MEDIUM",
                                status.equals("CONFLICT") ? "Earlier enabled rule fully covers this match with a different action."
                                    : "Earlier enabled rule fully covers this match with the same action."));
                            break;
                        }
                        if (covering == null && reason != null) status = "UNKNOWN";
                    }
                    if (!Boolean.FALSE.equals(rule.enabled())) {
                        if (match.problem() == null) earlier.add(match);
                        else earlierUnknown = true;
                    }
                } catch (Unknown limit) { status = "UNKNOWN"; reason = limit.getMessage(); }
                if (status.equals("NOT_SHADOWED") && !snapshot.failures().isEmpty()) {
                    status = "UNKNOWN"; reason = "snapshot collection incomplete";
                }
                if (status.equals("UNKNOWN")) findings.add(unknown("Shadowing: " + reason + "."));
                String window = unused(snapshot, rule, days, findings);
                var hits = rule.hitCounts();
                results.put(rule.id(), new Assessment(status, reason, covering, List.copyOf(findings), broad, timeStatus, expiring,
                    window, hits == null ? "UNKNOWN" : hits.source(), hits == null ? null : hits.collectedAt()));
            }
        }
        return new Result(Collections.unmodifiableMap(results), budget.left[0] < 0);
    }
    private static Finding finding(String cls, String severity, String evidence) { return new Finding(cls, severity, evidence); }
    private static Finding unknown(String evidence) { return finding("unknown", "LOW", evidence); }
    private static boolean accepting(String action) { return Set.of("Accept", "allow").contains(action); }
    private static String unused(PolicySnapshot snapshot, Rule rule, int days, List<Finding> findings) {
        var h = rule.hitCounts(); String window = "Counter window unknown; first hit is not a counter start.";
        if (h == null || h.hits() == null || h.hits() < 0 || parse(h.collectedAt()) == null) {
            findings.add(unknown("Hit counts were not collected or are invalid.")); return window;
        }
        if (h.firewalls().stream().anyMatch(f -> f.hits() == null) || ("device".equals(h.source()) && snapshot.metadata().targets().stream()
                .anyMatch(t -> h.firewalls().stream().noneMatch(f -> f.deviceId().equals(t.deviceId()) && Objects.equals(f.context(), t.context()))))) {
            findings.add(unknown("Hit counts are missing for a target.")); return window;
        }
        Instant at = parse(h.collectedAt()), last = parse(h.lastHit());
        if (h.lastHit() != null && (last == null || last.isAfter(at))) {
            findings.add(unknown("Last-hit timestamp is invalid.")); return window;
        }
        Instant newestCreation = h.firewalls().stream().map(f -> parse(f.createdAt())).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        if (newestCreation != null) {
            long age = Duration.between(newestCreation, at).toDays();
            // Creation is only an upper bound: a later counter reset may shorten it further.
            window = "Counter window unknown; bounded above by rule age: " + Math.max(0, age) + " days.";
            if (h.hits() == 0 && age < days) { findings.add(unknown("Zero-hit counter window is shorter than the threshold.")); return window; }
        }
        if (h.hits() == 0 && last != null) findings.add(unknown("Zero hits contradict a recorded last hit."));
        else if (h.hits() == 0) findings.add(finding("unused", "LOW", "Zero collected hits; counter reset/window is unknown."));
        else if (last == null) findings.add(unknown("Last-hit time was not collected."));
        else if (!last.isAfter(at.minus(Duration.ofDays(days)))) findings.add(finding("unused", "LOW", "Last hit predates the threshold at counter collection time."));
        return window;
    }
    private static Instant parse(String value) { try { return Instant.parse(value); } catch (RuntimeException invalid) { return null; } }


}
