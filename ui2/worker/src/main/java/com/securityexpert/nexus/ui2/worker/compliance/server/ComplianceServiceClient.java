package com.securityexpert.nexus.ui2.worker.compliance.server;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.worker.compliance.catalog.CheckPointComplianceCatalog;
import com.securityexpert.nexus.ui2.worker.compliance.evaluator.CheckPointGaiaComplianceEvaluator;
import com.securityexpert.nexus.ui2.worker.compliance.model.ComplianceControl;
import com.securityexpert.nexus.ui2.worker.compliance.model.EvaluationResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser;

/**
 * Client for delegating compliance evaluation to the {@code ui2-compliance}
 * microservice over internal HTTP streaming, with transparent local fallback.
 */
public final class ComplianceServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final CheckPointGaiaConfigParser localCpParser;

    public ComplianceServiceClient(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim().replaceAll("/+$", "") : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.localCpParser = new CheckPointGaiaConfigParser();
    }

    public static ComplianceServiceClient fromEnvironment() {
        return new ComplianceServiceClient(System.getenv("UI2_COMPLIANCE_SERVICE_URL"));
    }

    public EvaluationResult evaluateCheckPoint(String deviceId, String rawOrSanitizedConfig) {
        if (baseUrl != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/compliance/evaluate"))
                        .timeout(Duration.ofSeconds(30))
                        .header("X-Nexus-Vendor", "check_point")
                        .header("X-Nexus-Device-Id", deviceId != null ? deviceId : "unknown")
                        .POST(HttpRequest.BodyPublishers.ofString(rawOrSanitizedConfig != null ? rawOrSanitizedConfig : "", StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    return mapper.readValue(response.body(), EvaluationResult.class);
                }
            } catch (Exception ignored) {
                // Microservice unreachable or timed out; fall through to local evaluator
            }
        }

        // Local fallback
        ConfigParseContext context = new ConfigParseContext(
                deviceId != null ? deviceId : "unknown",
                ConfigVendor.CHECK_POINT,
                ConfigFormat.GAIA_CLISH,
                "physical",
                Optional.empty()
        );
        ConfigParseResult res = localCpParser.parse(context,
                new ByteArrayInputStream((rawOrSanitizedConfig != null ? rawOrSanitizedConfig : "").getBytes(StandardCharsets.UTF_8)));

        return CheckPointGaiaComplianceEvaluator.evaluate(
                deviceId,
                "check_point",
                "gaia",
                res.sections(),
                rawOrSanitizedConfig,
                null
        );
    }

    public List<ComplianceControl> getCatalog() {
        if (baseUrl != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/compliance/catalog"))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode root = mapper.readTree(response.body());
                    if (root.has("controls")) {
                        return mapper.readerForListOf(ComplianceControl.class).readValue(root.get("controls"));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return CheckPointComplianceCatalog.getControls();
    }
}
