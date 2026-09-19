package com.securityexpert.nexus.ui2.worker.configuration.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.securityexpert.nexus.ui2.persistence.device.configuration.ConfigurationIndexEntry;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigFormat;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseContext;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigParseResult;
import com.securityexpert.nexus.ui2.worker.configuration.core.ConfigVendor;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigParser;
import com.securityexpert.nexus.ui2.worker.configuration.cp.CheckPointGaiaConfigProcessor;

/**
 * Client for delegating configuration parsing to the {@code ui2-configuration}
 * microservice over internal HTTP streaming, with transparent local fallback.
 */
public final class ConfigurationServiceClient {

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final CheckPointGaiaConfigParser localCpParser;

    public ConfigurationServiceClient(String baseUrl) {
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim().replaceAll("/+$", "") : null;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        this.mapper = new ObjectMapper();
        this.localCpParser = new CheckPointGaiaConfigParser();
    }

    public static ConfigurationServiceClient fromEnvironment() {
        return new ConfigurationServiceClient(System.getenv("UI2_CONFIG_SERVICE_URL"));
    }

    public CheckPointGaiaConfigProcessor.Processed parseCheckPoint(String deviceId, String rawConfig) {
        if (baseUrl != null) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/v1/parse"))
                        .timeout(Duration.ofSeconds(30))
                        .header("X-Nexus-Vendor", "check_point")
                        .header("X-Nexus-Format", "gaia_clish")
                        .header("X-Nexus-Device-Id", deviceId != null ? deviceId : "unknown")
                        .POST(HttpRequest.BodyPublishers.ofString(rawConfig, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonNode json = mapper.readTree(response.body());
                    String hash = json.hasNonNull("canonical_hash") ? json.get("canonical_hash").asText() : "";
                    int withheld = json.has("withheld_line_count") ? json.get("withheld_line_count").asInt() : 0;
                    String sanitized = json.has("sanitized_text") ? json.get("sanitized_text").asText() : "";
                    List<ConfigurationIndexEntry> index = new ArrayList<>();
                    if (json.has("index") && json.get("index").isArray()) {
                        for (JsonNode entryNode : json.get("index")) {
                            index.add(new ConfigurationIndexEntry(
                                    entryNode.get("context").asText(),
                                    entryNode.get("section").asText(),
                                    entryNode.hasNonNull("source") ? Optional.of(entryNode.get("source").asText()) : Optional.empty(),
                                    entryNode.get("entry_count").asInt()
                            ));
                        }
                    }
                    return new CheckPointGaiaConfigProcessor.Processed(hash, withheld, sanitized, index);
                }
            } catch (Exception ignored) {
                // Microservice unreachable or timed out; fall through to local parser
            }
        }

        ConfigParseContext context = new ConfigParseContext(
                deviceId != null ? deviceId : "unknown",
                ConfigVendor.CHECK_POINT,
                ConfigFormat.GAIA_CLISH,
                "physical",
                Optional.empty()
        );
        ConfigParseResult res = localCpParser.parseString(context, rawConfig);
        return new CheckPointGaiaConfigProcessor.Processed(
                res.canonicalHash().orElse(""),
                res.withheldLineCount(),
                res.sanitizedText(),
                res.index()
        );
    }
}
