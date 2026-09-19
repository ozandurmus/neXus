package com.securityexpert.nexus.ui2.service.compliance;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.device.DeviceRecord;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.service.device.configuration.ConfigurationQueryService;

/**
 * Service layer coordinating with the {@code ui2-compliance} microservice (Port 8085).
 * Strictly complies with DIR-2: zero imports of worker classes.
 */
public final class ComplianceService {

    private final String complianceServiceUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ConfigurationQueryService configurationQueryService;
    private final DeviceRepository deviceRepository;

    // Cache of device evaluation results to enable instant UI responsiveness
    private final Map<String, CachedEvaluation> evaluationCache = new ConcurrentHashMap<>();

    private record CachedEvaluation(Instant timestamp, String configHash, Map<String, Object> result) {
    }

    public ComplianceService(ConfigurationQueryService configurationQueryService,
                             DeviceRepository deviceRepository) {
        this(configurationQueryService, deviceRepository, resolveUrl());
    }

    public ComplianceService(ConfigurationQueryService configurationQueryService,
                             DeviceRepository deviceRepository,
                             String complianceServiceUrl) {
        this.configurationQueryService = configurationQueryService;
        this.deviceRepository = deviceRepository;
        this.complianceServiceUrl = complianceServiceUrl != null && !complianceServiceUrl.isBlank()
                ? complianceServiceUrl.trim().replaceAll("/+$", "")
                : "http://127.0.0.1:8085";
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .setSerializationInclusion(JsonInclude.Include.ALWAYS);
    }

    private static String resolveUrl() {
        String env = System.getenv("UI2_COMPLIANCE_SERVICE_URL");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "http://ui2-compliance.ui2.svc.cluster.local:8085";
    }

    public Map<String, Object> getOverview() {
        List<ConfigurationQueryService.DeviceListEntry> devices = configurationQueryService.listDevices();
        List<ConfigurationQueryService.DeviceListEntry> evaluated = devices.stream()
                .filter(d -> d.latestRun().isPresent() && "check_point".equalsIgnoreCase(d.vendor()))
                .toList();

        int totalDevices = devices.size();
        int evaluatedCount = evaluated.size();

        if (evaluatedCount == 0) {
            return emptyOverview(totalDevices);
        }

        double totalAssured = 0.0;
        double totalCoverage = 0.0;
        double totalObserved = 0.0;
        int criticalDeficiencies = 0;
        int dataGaps = 0;

        // Framework accumulators: [total, pass, fail, unavail]
        Map<String, int[]> frameworkStats = new LinkedHashMap<>();
        frameworkStats.put("CIS Gaia v1.1.0", new int[]{0, 0, 0, 0});
        frameworkStats.put("PCI-DSS v4.0.1", new int[]{0, 0, 0, 0});
        frameworkStats.put("NIST SP 800-53", new int[]{0, 0, 0, 0});
        frameworkStats.put("Financial Baseline", new int[]{0, 0, 0, 0});

        for (var dev : evaluated) {
            Map<String, Object> eval = evaluateDevice(dev.deviceId());
            if (eval == null || eval.containsKey("error")) {
                continue;
            }

            totalAssured += getDouble(eval, "assuredCompliance", 0.0);
            totalCoverage += getDouble(eval, "evidenceCoverage", 0.0);
            totalObserved += getDouble(eval, "observedCompliance", 0.0);
            dataGaps += getInt(eval, "dataUnavailableCount", 0);

            List<Map<String, Object>> items = getItems(eval);
            for (Map<String, Object> item : items) {
                String status = String.valueOf(item.get("displayStatus"));
                String severity = String.valueOf(item.get("severity"));

                if ("FAIL".equalsIgnoreCase(status) && "CRITICAL".equalsIgnoreCase(severity)) {
                    criticalDeficiencies++;
                }

                List<Map<String, Object>> frameworks = getFrameworks(item);
                for (Map<String, Object> fw : frameworks) {
                    String name = mapFrameworkName(String.valueOf(fw.get("framework")));
                    int[] stats = frameworkStats.get(name);
                    if (stats != null) {
                        stats[0]++; // total
                        if ("PASS".equalsIgnoreCase(status)) stats[1]++;
                        else if ("FAIL".equalsIgnoreCase(status)) stats[2]++;
                        else if ("DATA_UNAVAILABLE".equalsIgnoreCase(status)) stats[3]++;
                    }
                }
            }
        }

        double avgAssured = Math.round((totalAssured / evaluatedCount) * 10.0) / 10.0;
        double avgCoverage = Math.round((totalCoverage / evaluatedCount) * 10.0) / 10.0;
        double avgObserved = Math.round((totalObserved / evaluatedCount) * 10.0) / 10.0;

        List<Map<String, Object>> frameworkCards = new ArrayList<>();
        for (var entry : frameworkStats.entrySet()) {
            int[] s = entry.getValue();
            int total = s[0];
            int pass = s[1];
            int fail = s[2];
            int unavail = s[3];
            double score = total > 0 ? Math.round((pass * 100.0 / total) * 10.0) / 10.0 : 0.0;

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("framework", entry.getKey());
            card.put("score_pct", score);
            card.put("total_controls", total);
            card.put("pass_count", pass);
            card.put("fail_count", fail);
            card.put("data_unavailable_count", unavail);
            frameworkCards.add(card);
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total_firewalls", totalDevices);
        overview.put("evaluated_firewalls", evaluatedCount);
        overview.put("assured_compliance_pct", avgAssured);
        overview.put("evidence_coverage_pct", avgCoverage);
        overview.put("observed_compliance_pct", avgObserved);
        overview.put("critical_deficiencies", criticalDeficiencies);
        overview.put("data_gaps", dataGaps);
        overview.put("frameworks", frameworkCards);

        return overview;
    }

    public List<Map<String, Object>> getControls() {
        List<Map<String, Object>> catalog = fetchCatalog();
        List<ConfigurationQueryService.DeviceListEntry> devices = configurationQueryService.listDevices();
        List<ConfigurationQueryService.DeviceListEntry> evaluated = devices.stream()
                .filter(d -> d.latestRun().isPresent() && "check_point".equalsIgnoreCase(d.vendor()))
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();

        for (Map<String, Object> c : catalog) {
            String controlId = String.valueOf(c.get("id"));
            String title = String.valueOf(c.get("title"));
            String desc = String.valueOf(c.get("description"));
            String severity = String.valueOf(c.get("severity"));
            String category = String.valueOf(c.get("category"));
            Object frameworks = c.get("frameworks");

            int targetDevices = evaluated.size();
            int passCount = 0;
            int failCount = 0;
            int unavailCount = 0;
            List<String> affectedDevices = new ArrayList<>();
            String missingReason = null;

            for (var dev : evaluated) {
                Map<String, Object> eval = evaluateDevice(dev.deviceId());
                if (eval == null) continue;

                List<Map<String, Object>> items = getItems(eval);
                for (Map<String, Object> item : items) {
                    if (controlId.equals(item.get("controlId"))) {
                        String st = String.valueOf(item.get("displayStatus"));
                        if ("PASS".equalsIgnoreCase(st)) {
                            passCount++;
                        } else if ("FAIL".equalsIgnoreCase(st)) {
                            failCount++;
                            dev.hostname().ifPresent(affectedDevices::add);
                        } else if ("DATA_UNAVAILABLE".equalsIgnoreCase(st)) {
                            unavailCount++;
                            if (missingReason == null && item.get("message") != null) {
                                missingReason = String.valueOf(item.get("message"));
                            }
                        }
                        break;
                    }
                }
            }

            double compliancePct = targetDevices > 0 ? Math.round((passCount * 100.0 / targetDevices) * 10.0) / 10.0 : 0.0;
            String status = unavailCount > 0 && passCount == 0 && failCount == 0 ? "DATA_UNAVAILABLE" :
                    (failCount > 0 ? "FAIL" : "PASS");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("control_id", controlId);
            row.put("title", title);
            row.put("description", desc);
            row.put("severity", severity);
            row.put("category", category);
            row.put("frameworks", frameworks);
            row.put("status", status);
            row.put("compliance_pct", compliancePct);
            row.put("target_device_count", targetDevices);
            row.put("pass_count", passCount);
            row.put("fail_count", failCount);
            row.put("data_unavailable_count", unavailCount);
            row.put("missing_reason", missingReason);
            row.put("affected_devices", affectedDevices);

            result.add(row);
        }

        return result;
    }

    public Map<String, Object> getDeviceCompliance(String deviceId) {
        Optional<DeviceRecord> device = deviceRepository.find(deviceId);
        if (device.isEmpty()) {
            return Map.of("error", "NOT_FOUND", "message", "Device not found");
        }

        Map<String, Object> eval = evaluateDevice(deviceId);
        if (eval == null) {
            return Map.of("error", "EVALUATION_FAILED", "message", "Could not evaluate compliance for device");
        }

        Map<String, Object> response = new LinkedHashMap<>(eval);
        response.put("device_id", deviceId);
        String hostname = deviceRepository.findConfirmFacts(deviceId)
                .flatMap(com.securityexpert.nexus.ui2.persistence.device.DeviceConfirmFacts::observedHostname)
                .orElse(deviceId);
        response.put("hostname", hostname);
        response.put("vendor", device.get().vendorHint());
        return response;
    }

    public Map<String, Object> evaluateDevice(String deviceId) {
        Optional<String> sanitizedConfig = configurationQueryService.sanitizedText(deviceId);
        String configText = sanitizedConfig.orElse("");

        CachedEvaluation cached = evaluationCache.get(deviceId);
        if (cached != null && cached.configHash().equals(String.valueOf(configText.hashCode()))) {
            if (Duration.between(cached.timestamp(), Instant.now()).toMinutes() < 10) {
                return cached.result();
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(complianceServiceUrl + "/api/v1/compliance/evaluate"))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-Nexus-Device-Id", deviceId)
                    .header("X-Nexus-Vendor", "check_point")
                    .POST(HttpRequest.BodyPublishers.ofString(configText, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                Map<String, Object> parsed = mapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {});
                evaluationCache.put(deviceId, new CachedEvaluation(Instant.now(), String.valueOf(configText.hashCode()), parsed));
                return parsed;
            }
        } catch (Exception ignored) {
        }

        // Return empty evaluation if microservice unreachable and no config
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("deviceId", deviceId);
        fallback.put("vendor", "check_point");
        fallback.put("totalAssigned", 24);
        fallback.put("passCount", 0);
        fallback.put("failCount", 0);
        fallback.put("dataUnavailableCount", 24);
        fallback.put("observedCompliance", 0.0);
        fallback.put("evidenceCoverage", 0.0);
        fallback.put("assuredCompliance", 0.0);
        fallback.put("items", List.of());
        return fallback;
    }

    private List<Map<String, Object>> fetchCatalog() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(complianceServiceUrl + "/api/v1/compliance/catalog"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonNode root = mapper.readTree(response.body());
                if (root.has("controls")) {
                    return mapper.readValue(root.get("controls").toString(),
                            new TypeReference<List<Map<String, Object>>>() {});
                }
            }
        } catch (Exception ignored) {
        }
        return List.of();
    }

    private static String mapFrameworkName(String raw) {
        if (raw == null) return "Financial Baseline";
        return switch (raw.toUpperCase(Locale.ROOT)) {
            case "CIS" -> "CIS Gaia v1.1.0";
            case "PCI_DSS" -> "PCI-DSS v4.0.1";
            case "NIST_800_53" -> "NIST SP 800-53";
            default -> "Financial Baseline";
        };
    }

    private static Map<String, Object> emptyOverview(int totalDevices) {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("total_firewalls", totalDevices);
        overview.put("evaluated_firewalls", 0);
        overview.put("assured_compliance_pct", 0.0);
        overview.put("evidence_coverage_pct", 0.0);
        overview.put("observed_compliance_pct", 0.0);
        overview.put("critical_deficiencies", 0);
        overview.put("data_gaps", 0);
        overview.put("frameworks", List.of(
                Map.of("framework", "CIS Gaia v1.1.0", "score_pct", 0.0, "total_controls", 24, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 24),
                Map.of("framework", "PCI-DSS v4.0.1", "score_pct", 0.0, "total_controls", 16, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 16),
                Map.of("framework", "NIST SP 800-53", "score_pct", 0.0, "total_controls", 20, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 20),
                Map.of("framework", "Financial Baseline", "score_pct", 0.0, "total_controls", 10, "pass_count", 0, "fail_count", 0, "data_unavailable_count", 10)
        ));
        return overview;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getItems(Map<String, Object> map) {
        Object items = map.get("items");
        return items instanceof List ? (List<Map<String, Object>>) items : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> getFrameworks(Map<String, Object> item) {
        Object fw = item.get("frameworks");
        return fw instanceof List ? (List<Map<String, Object>>) fw : List.of();
    }

    private static double getDouble(Map<String, Object> map, String key, double defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.doubleValue();
        return defaultVal;
    }

    private static int getInt(Map<String, Object> map, String key, int defaultVal) {
        Object val = map.get(key);
        if (val instanceof Number n) return n.intValue();
        return defaultVal;
    }
}
