package com.securityexpert.nexus.ui2.service.notification;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Projection of the local scanner's atomic summary files: counts plus a bounded HIGH/MEDIUM identity list. No source, matches or secrets. */
final class SecurityScanSummary {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TOOLS = List.of("semgrep", "gitleaks", "trivy", "zap");
    private static final List<String> SEVERITIES = List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> COUNTS = List.of("new", "fixed", "accepted", "existing");

    private SecurityScanSummary() {}

    /** Bounded list size in the mail; the full capped list (200) is on Administration > Platform status. */
    static final int MAIL_FINDING_ROWS = 100;
    static final int MAX_FINDINGS = 200;
    private static final List<String> LISTED = List.of("CRITICAL", "HIGH", "MEDIUM");
    private static final List<String> STATUSES = List.of("new", "existing", "accepted");

    /** One HIGH/CRITICAL/MEDIUM finding: identity and status only, never source, matches, secrets or URLs. */
    record Finding(String tool, String rule, String severity, String location, String status, boolean fixAvailable) {}

    record Failed(String file, String cause) {}

    /** A validated summary file. {@code findings} is empty and {@code findingsTotal} 0 for files written before the list existed. */
    record Parsed(Instant at, boolean passed, int errors, int blocking, Map<String, Map<String, Map<String, Integer>>> counts,
            JsonNode review, List<Finding> findings, int findingsTotal, boolean hasFindings, List<Failed> failed) {}

    static Parsed parse(Path path) throws IOException {
        if (Files.isSymbolicLink(path) || Files.size(path) > 256_000) throw new IOException("invalid security summary");
        JsonNode root = JSON.readTree(path.toFile());
        Set<String> rootKeys = new java.util.HashSet<>(
                Set.of("schema_version", "generated_at", "counts", "scan_errors", "blocking", "passed"));
        for (String optional : List.of("accepted_review", "findings", "findings_total", "failed")) {
            if (root.has(optional)) rootKeys.add(optional);
        }
        if (root.has("findings") != root.has("findings_total")) throw new IOException("invalid security summary fields");
        exactKeys(root, rootKeys);
        if (number(root, "schema_version") != 1 || !root.path("passed").isBoolean()) {
            throw new IOException("invalid security summary schema");
        }
        Instant at;
        try { at = Instant.parse(root.path("generated_at").asText()); }
        catch (RuntimeException e) { throw new IOException("invalid security summary time"); }
        JsonNode tools = root.path("counts");
        exactKeys(tools, Set.copyOf(TOOLS));
        Map<String, Map<String, Map<String, Integer>>> counts = new java.util.LinkedHashMap<>();
        for (String tool : TOOLS) {
            exactKeys(tools.path(tool), Set.copyOf(SEVERITIES));
            Map<String, Map<String, Integer>> bySeverity = new java.util.LinkedHashMap<>();
            for (String severity : SEVERITIES) {
                JsonNode node = tools.path(tool).path(severity);
                exactKeys(node, Set.copyOf(COUNTS));
                Map<String, Integer> values = new java.util.LinkedHashMap<>();
                for (String count : COUNTS) values.put(count, number(node, count));
                bySeverity.put(severity, values);
            }
            counts.put(tool, bySeverity);
        }
        JsonNode review = null;
        if (root.has("accepted_review")) {
            review = root.path("accepted_review");
            validateReview(review);
        }
        List<Finding> findings = new ArrayList<>();
        int total = 0;
        if (root.has("findings")) {
            JsonNode list = root.path("findings");
            total = number(root, "findings_total");
            if (!list.isArray() || list.size() > MAX_FINDINGS || total < list.size()) throw new IOException("invalid security findings");
            for (JsonNode item : list) {
                exactKeys(item, Set.of("tool", "rule", "severity", "location", "status", "fix_available"));
                String tool = item.path("tool").asText(), rule = item.path("rule").asText(), severity = item.path("severity").asText(),
                        location = item.path("location").asText(), status = item.path("status").asText();
                if (!TOOLS.contains(tool) || !LISTED.contains(severity) || !STATUSES.contains(status)
                        || !rule.matches("[A-Za-z0-9._-]{1,80}") || !location.matches("[A-Za-z0-9._+/@: -]{1,160}")
                        || !item.path("fix_available").isBoolean()) {
                    throw new IOException("invalid security finding");
                }
                findings.add(new Finding(tool, rule, severity, location, status, item.path("fix_available").asBoolean()));
            }
        }
        List<Failed> failed = new ArrayList<>();
        if (root.has("failed")) {
            JsonNode list = root.path("failed");
            if (!list.isArray() || list.size() > 50) throw new IOException("invalid security failure list");
            for (JsonNode item : list) {
                exactKeys(item, Set.of("file", "cause"));
                String file = item.path("file").asText(), cause = item.path("cause").asText();
                if (!file.matches("[A-Za-z0-9._-]{1,80}") || !cause.matches("[A-Za-z0-9._ :,()/-]{0,120}")) {
                    throw new IOException("invalid security failure item");
                }
                failed.add(new Failed(file, cause));
            }
        }
        return new Parsed(at, root.path("passed").asBoolean(), number(root, "scan_errors"), number(root, "blocking"), counts,
                review, findings, total, root.has("findings"), failed);
    }

    static NotificationMailRouter.Batch collect(Path directory, Instant from) throws IOException {
        List<String> lines = new ArrayList<>();
        Instant newest = from;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (!path.getFileName().toString().endsWith(".json")) continue;
                Parsed parsed = parse(path);
                Instant at = parsed.at();
                if (!at.isAfter(from)) continue;
                StringBuilder line = new StringBuilder(at + "  security scan");
                line.append(" errors=").append(parsed.errors());
                line.append(" blocking=").append(parsed.blocking());
                for (var tool : parsed.counts().entrySet()) {
                    for (var severity : tool.getValue().entrySet()) {
                        Map<String, Integer> values = severity.getValue();
                        if (values.values().stream().allMatch(n -> n == 0)) continue;
                        line.append("; ").append(tool.getKey()).append('/').append(severity.getKey());
                        for (var count : values.entrySet()) line.append(' ').append(count.getKey()).append('=').append(count.getValue());
                    }
                }
                if (!parsed.failed().isEmpty()) {
                    line.append("; scan failures");
                    for (Failed f : parsed.failed()) line.append(' ').append(f.file()).append(" (").append(f.cause()).append(')');
                }
                if (parsed.review() != null) appendReview(line, parsed.review());
                appendFindings(line, parsed);
                lines.add(line.toString());
                if (at.isAfter(newest)) newest = at;
            }
        }
        return new NotificationMailRouter.Batch(lines, 0, newest, Map.of());
    }

    /** HIGH/CRITICAL/MEDIUM list, worst and newest first (the scanner already sorts); extra rows stay in the console. */
    private static void appendFindings(StringBuilder line, Parsed parsed) {
        if (!parsed.hasFindings()) return;
        line.append("\n  High and medium findings: ").append(parsed.findingsTotal());
        List<Finding> shown = parsed.findings().subList(0, Math.min(MAIL_FINDING_ROWS, parsed.findings().size()));
        for (Finding f : shown) {
            line.append("\n  ").append(f.severity()).append(' ').append(f.status()).append("  ").append(f.tool()).append(' ')
                    .append(f.rule()).append("  ").append(f.location());
            if (f.fixAvailable()) line.append("  fix available");
        }
        if (parsed.findingsTotal() > shown.size()) {
            line.append("\n  and ").append(parsed.findingsTotal() - shown.size())
                    .append(" more: see Administration > Platform status > Security scans");
        }
    }

    /** Accepted-findings review: counts plus CVE ids and package names only; every field is shape-checked. */
    private static void validateReview(JsonNode review) throws IOException {
        exactKeys(review, Set.of("fix_available", "due_soon", "past_review", "not_reported", "attention", "items"));
        if (!review.path("attention").isBoolean() || !review.path("items").isArray() || review.path("items").size() > 50) {
            throw new IOException("invalid security review");
        }
        for (String count : List.of("fix_available", "due_soon", "past_review", "not_reported")) number(review, count);
        for (JsonNode item : review.path("items")) {
            exactKeys(item, Set.of("id", "package", "status"));
            String id = item.path("id").asText(), pkg = item.path("package").asText(), status = item.path("status").asText();
            if (!id.matches("[A-Za-z0-9._-]{1,40}") || !pkg.matches("[A-Za-z0-9._+/@-]{0,80}")
                    || !List.of("fix_available", "due_soon", "past_review").contains(status)) {
                throw new IOException("invalid security review item");
            }
        }
    }

    private static void appendReview(StringBuilder line, JsonNode review) throws IOException {
        line.append("; accepted-review attention=").append(review.path("attention").asBoolean());
        for (String count : List.of("fix_available", "due_soon", "past_review", "not_reported")) {
            line.append(' ').append(count).append('=').append(number(review, count));
        }
        for (JsonNode item : review.path("items")) {
            line.append("; ").append(item.path("status").asText()).append(' ').append(item.path("id").asText());
            String pkg = item.path("package").asText();
            if (!pkg.isEmpty()) line.append(' ').append(pkg);
        }
    }

    private static int number(JsonNode node, String key) throws IOException {
        JsonNode value = node.path(key);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new IOException("invalid security summary count");
        }
        return value.intValue();
    }

    private static void exactKeys(JsonNode node, Set<String> expected) throws IOException {
        Set<String> actual = new java.util.HashSet<>();
        if (node == null || !node.isObject()) throw new IOException("invalid security summary object");
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw new IOException("invalid security summary fields");
    }
}
