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

/** Counts-only projection from the local scanner's atomic summary files. No report contents or paths enter mail. */
final class SecurityScanSummary {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> TOOLS = List.of("semgrep", "gitleaks", "trivy", "zap");
    private static final List<String> SEVERITIES = List.of("UNKNOWN", "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final List<String> COUNTS = List.of("new", "fixed", "accepted", "existing");

    private SecurityScanSummary() {}

    static NotificationMailRouter.Batch collect(Path directory, Instant from) throws IOException {
        List<String> lines = new ArrayList<>();
        Instant newest = from;
        try (var paths = Files.list(directory)) {
            for (Path path : paths.sorted().toList()) {
                if (!path.getFileName().toString().endsWith(".json")) continue;
                if (Files.isSymbolicLink(path) || Files.size(path) > 64_000) throw new IOException("invalid security summary");
                JsonNode root = JSON.readTree(path.toFile());
                exactKeys(root, Set.of("schema_version", "generated_at", "counts", "scan_errors", "blocking", "passed"));
                if (number(root, "schema_version") != 1 || !root.path("passed").isBoolean()) {
                    throw new IOException("invalid security summary schema");
                }
                Instant at;
                try { at = Instant.parse(root.path("generated_at").asText()); }
                catch (RuntimeException e) { throw new IOException("invalid security summary time"); }
                if (!at.isAfter(from)) continue;
                StringBuilder line = new StringBuilder(at + "  security scan");
                line.append(" errors=").append(number(root, "scan_errors"));
                line.append(" blocking=").append(number(root, "blocking"));
                JsonNode tools = root.path("counts");
                exactKeys(tools, Set.copyOf(TOOLS));
                for (String tool : TOOLS) {
                    exactKeys(tools.path(tool), Set.copyOf(SEVERITIES));
                    for (String severity : SEVERITIES) {
                        JsonNode counts = tools.path(tool).path(severity);
                        exactKeys(counts, Set.copyOf(COUNTS));
                        List<Integer> values = new ArrayList<>();
                        for (String count : COUNTS) values.add(number(counts, count));
                        if (values.stream().allMatch(n -> n == 0)) continue;
                        line.append("; ").append(tool).append('/').append(severity);
                        for (int i = 0; i < COUNTS.size(); i++) line.append(' ').append(COUNTS.get(i)).append('=').append(values.get(i));
                    }
                }
                lines.add(line.toString());
                if (at.isAfter(newest)) newest = at;
            }
        }
        return new NotificationMailRouter.Batch(lines, 0, newest, Map.of());
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
