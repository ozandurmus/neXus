package com.securityexpert.nexus.ui2.worker.backup.https;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;

/**
 * The Infoblox {@code member} list (WAPI 2.13.7, measured 2026-09-25 on the production grid) into {@link GridMember}
 * facts. Node health comes from {@code node_info[0].service_status}: {@code NODE_STATUS}, {@code REPLICATION} (status
 * text), {@code DISK_USAGE}, {@code MEMORY}, {@code DB_OBJECT} ("16% - ..."), {@code CPU_USAGE} ("CPU Usage: 8%").
 * Free-text descriptions are never kept (they carry addresses). The member whose VIP is the dialled address is the
 * Grid Master; nothing else is inferred.
 */
final class InfobloxMembers {

    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})\\s*%");

    private InfobloxMembers() {
    }

    static List<GridMember> parse(JsonNode members, String dialledHost) {
        if (members == null || !members.isArray()) {
            return List.of();
        }
        List<GridMember> out = new ArrayList<>();
        for (JsonNode m : members) {
            String host = text(m, "host_name").orElse("");
            if (host.isBlank()) {
                continue;
            }
            Optional<String> vip = m.hasNonNull("vip_setting") ? text(m.get("vip_setting"), "address") : Optional.empty();
            boolean gridMaster = vip.isPresent() && dialledHost != null && vip.get().equalsIgnoreCase(dialledHost.trim());
            JsonNode node = m.hasNonNull("node_info") && m.get("node_info").isArray() && m.get("node_info").size() > 0
                    ? m.get("node_info").get(0) : null;
            Optional<String> nodeStatus = Optional.empty();
            Optional<String> replication = Optional.empty();
            Optional<Integer> disk = Optional.empty();
            Optional<Integer> memory = Optional.empty();
            Optional<Integer> cpu = Optional.empty();
            Optional<Integer> db = Optional.empty();
            if (node != null && node.hasNonNull("service_status") && node.get("service_status").isArray()) {
                for (JsonNode sv : node.get("service_status")) {
                    String service = text(sv, "service").orElse("");
                    String status = text(sv, "status").orElse("");
                    String description = text(sv, "description").orElse("");
                    switch (service) {
                        case "NODE_STATUS" -> nodeStatus = Optional.of(status.isBlank() ? description : status);
                        case "REPLICATION" -> replication = Optional.of(description.isBlank() ? status : description);
                        case "DISK_USAGE" -> disk = percent(description);
                        case "MEMORY" -> memory = percent(description);
                        case "CPU_USAGE" -> cpu = percent(description);
                        case "DB_OBJECT" -> db = percent(description);
                        default -> { }
                    }
                }
            }
            List<GridMember.ServiceStatus> services = new ArrayList<>();
            if (m.hasNonNull("service_status") && m.get("service_status").isArray()) {
                for (JsonNode sv : m.get("service_status")) {
                    Optional<String> service = text(sv, "service");
                    Optional<String> status = text(sv, "status");
                    if (service.isPresent() && status.isPresent()) {
                        services.add(new GridMember.ServiceStatus(service.get().toUpperCase(Locale.ROOT), status.get().toUpperCase(Locale.ROOT)));
                    }
                }
            }
            out.add(new GridMember(UUID.randomUUID().toString(), host.trim(), vip, text(m, "platform"),
                    node == null ? Optional.empty() : text(node, "hwtype").filter(t -> !t.isBlank()),
                    node == null ? Optional.empty() : text(node, "hypervisor").filter(t -> !t.isBlank()),
                    gridMaster, m.path("master_candidate").asBoolean(false), m.path("enable_ha").asBoolean(false),
                    node == null ? Optional.empty() : text(node, "ha_status"), nodeStatus, replication, disk, memory, cpu, db, services));
        }
        out.sort(Comparator.comparing(GridMember::gridMaster).reversed()
                .thenComparing(Comparator.comparing(GridMember::masterCandidate).reversed())
                .thenComparing(GridMember::hostName, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(out);
    }

    private static Optional<String> text(JsonNode n, String field) {
        return n != null && n.hasNonNull(field) && n.get(field).isTextual() ? Optional.of(n.get(field).asText()) : Optional.empty();
    }

    private static Optional<Integer> percent(String description) {
        if (description == null) {
            return Optional.empty();
        }
        Matcher m = PERCENT.matcher(description);
        if (!m.find()) {
            return Optional.empty();
        }
        int v = Integer.parseInt(m.group(1));
        return v <= 100 ? Optional.of(v) : Optional.empty();
    }
}
