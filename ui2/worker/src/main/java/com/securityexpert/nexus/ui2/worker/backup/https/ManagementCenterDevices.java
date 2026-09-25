package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;

/**
 * A Symantec Management Center's {@code GET /api/devices} answer (measured 2026-09-25: applianceId, build,
 * deploymentStatus, description, hardwareSerialNumber, host, lastChanged, lastChangedBy, managementStatus, model,
 * name, osVersion, platform, serialNumber, type, uuid; types cp, rptr, sgos6x) as the managed-device facts shown
 * under the MC -- carried in the member-fact shape the Devices screen already renders (V72): platform = the MC's
 * type, hardware type = model, node status = management status, services = type and deployment status.
 */
final class ManagementCenterDevices {

    private ManagementCenterDevices() {
    }

    static List<GridMember> parse(JsonNode list) {
        List<GridMember> out = new ArrayList<>();
        for (JsonNode d : items(list)) {
            Optional<String> name = text(d, "name");
            if (name.isEmpty()) {
                continue;
            }
            Optional<String> type = text(d, "type");
            Optional<String> version = text(d, "osVersion").map(v -> text(d, "build").map(b -> v + " (build " + b + ")").orElse(v));
            List<GridMember.ServiceStatus> services = new ArrayList<>();
            type.ifPresent(t -> services.add(new GridMember.ServiceStatus("TYPE_" + t.toUpperCase(java.util.Locale.ROOT), "WORKING")));
            text(d, "deploymentStatus").ifPresent(v -> services.add(new GridMember.ServiceStatus("DEPLOYMENT", v.toUpperCase(java.util.Locale.ROOT))));
            // Slots: platform = the device type, hardware type = model, "hypervisor" = the OS version (the table shows
            // "platform · hypervisor" under the model) -- the member-fact shape is reused, not stretched with new columns.
            out.add(new GridMember(UUID.randomUUID().toString(), name.get(), text(d, "host"), type.map(ManagementCenterDevices::typeLabel),
                    text(d, "model").or(() -> text(d, "platform")), version, false, false, false, Optional.empty(),
                    text(d, "managementStatus").map(v -> v.toUpperCase(java.util.Locale.ROOT)), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), services));
        }
        out.sort(Comparator.comparing(GridMember::hostName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    static Map<String, Integer> typeCounts(JsonNode list) {
        Map<String, Integer> counts = new TreeMap<>();
        for (JsonNode d : items(list)) {
            counts.merge(text(d, "type").orElse("(none)"), 1, Integer::sum);
        }
        return counts;
    }

    /** The MC's type codes as the screen names them (measured: sgos6x, rptr, cp). */
    static String typeLabel(String type) {
        return switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "sgos6x", "sgos" -> "ProxySG";
            case "rptr" -> "Reporter";
            case "cp" -> "Cloud (WSS)";
            default -> type;
        };
    }

    private static Iterable<JsonNode> items(JsonNode list) {
        if (list == null) {
            return List.of();
        }
        return list.isArray() ? list : list.path("devices").isArray() ? list.path("devices")
                : list.path("results").isArray() ? list.path("results") : List.of();
    }

    private static Optional<String> text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && n.get(field).isValueNode() && !n.get(field).asText().isBlank()
                ? Optional.of(n.get(field).asText().trim()) : Optional.empty();
    }
}
