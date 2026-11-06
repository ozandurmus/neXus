package com.securityexpert.nexus.ui2.worker.inventory.cp;

import java.util.Locale;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The management server's own HA role from {@code mgmt_cli -r true -f json show-ha-status} (gate
 * {@code cp_identity_mds_ha_status}, V194, PO approval 2026-11-07). Management API reply: {@code servers[]} with
 * {@code ip-address} and {@code ha-state} ({@code active} | {@code standby}). The local server is the one whose
 * {@code ip-address} equals the device's own connection address exactly; names and addresses are compared in memory
 * and never kept. No match, several matches, an unknown state or an unexpected shape is UNKNOWN (empty role).
 */
public final class CheckPointMdsHaStatusParser {
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Role {@code ACTIVE} / {@code STANDBY} or empty, the number of servers listed, and an outcome token for the log. */
    public record HaStatus(Optional<String> role, int servers, String outcome) {}

    private CheckPointMdsHaStatusParser() {}

    public static HaStatus parse(String output, String localAddress) {
        if (output == null || output.isBlank()) return new HaStatus(Optional.empty(), 0, "EMPTY");
        int start = output.indexOf('{');
        int end = output.lastIndexOf('}');
        if (start < 0 || end < start) return new HaStatus(Optional.empty(), 0, "NOT_JSON");
        JsonNode root;
        try {
            root = JSON.readTree(output.substring(start, end + 1));
        } catch (java.io.IOException | RuntimeException invalid) {
            return new HaStatus(Optional.empty(), 0, "NOT_JSON");
        }
        if (root.has("code") && !root.has("servers")) return new HaStatus(Optional.empty(), 0, "VENDOR_ERROR");
        JsonNode servers = root.path("servers");
        if (!servers.isArray()) return new HaStatus(Optional.empty(), 0, "NO_SERVERS");
        if (localAddress == null || localAddress.isBlank()) return new HaStatus(Optional.empty(), servers.size(), "NO_LOCAL_ADDRESS");
        JsonNode local = null;
        int matches = 0;
        for (JsonNode server : servers) {
            if (localAddress.equals(server.path("ip-address").asText(null))) {
                local = server;
                matches++;
            }
        }
        if (matches == 0) return new HaStatus(Optional.empty(), servers.size(), "NO_MATCH");
        if (matches > 1) return new HaStatus(Optional.empty(), servers.size(), "AMBIGUOUS");
        String state = local.path("ha-state").asText("").toLowerCase(Locale.ROOT);
        return switch (state) {
            case "active" -> new HaStatus(Optional.of("ACTIVE"), servers.size(), "OK");
            case "standby" -> new HaStatus(Optional.of("STANDBY"), servers.size(), "OK");
            default -> new HaStatus(Optional.empty(), servers.size(), "UNKNOWN_STATE");
        };
    }
}
