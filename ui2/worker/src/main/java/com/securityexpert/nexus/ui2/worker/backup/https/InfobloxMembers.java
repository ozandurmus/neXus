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
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;

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

    /**
     * One inventory context per member (its host name): interfaces LAN1 (the VIP), MGMT (node mgmt_network_setting), LAN2
     * (lan2_port_setting when enabled) and every additional_ip_list entry; static routes from static_routes
     * (Network settings: address + subnet_mask -> destination, gateway -> next hop). Fields absent on this grid stay empty.
     */
    static List<InventoryContext> contexts(JsonNode members) {
        if (members == null || !members.isArray()) {
            return List.of();
        }
        List<InventoryContext> out = new ArrayList<>();
        for (JsonNode m : members) {
            String host = text(m, "host_name").orElse("");
            if (host.isBlank()) {
                continue;
            }
            List<InventoryInterface> interfaces = new ArrayList<>();
            addInterface(interfaces, "LAN1", m.get("vip_setting"));
            JsonNode node = m.hasNonNull("node_info") && m.get("node_info").isArray() && m.get("node_info").size() > 0
                    ? m.get("node_info").get(0) : null;
            if (node != null && node.hasNonNull("mgmt_network_setting")) {
                addInterface(interfaces, "MGMT", node.get("mgmt_network_setting"));
            }
            JsonNode lan2 = m.get("lan2_port_setting");
            if (m.path("lan2_enabled").asBoolean(false) || (lan2 != null && lan2.path("enabled").asBoolean(false))) {
                addInterface(interfaces, "LAN2", lan2 == null ? null : lan2.get("network_setting"));
            }
            if (m.hasNonNull("additional_ip_list") && m.get("additional_ip_list").isArray()) {
                int n = 0;
                for (JsonNode extra : m.get("additional_ip_list")) {
                    n++;
                    String name = text(extra, "interface").map(v -> v.toUpperCase(Locale.ROOT)).orElse("ADDITIONAL") + "-" + n;
                    addInterface(interfaces, name, extra.get("ipv4_network_setting"));
                }
            }
            List<InventoryRoute> routes = new ArrayList<>();
            if (m.hasNonNull("static_routes") && m.get("static_routes").isArray()) {
                for (JsonNode r : m.get("static_routes")) {
                    Optional<String> dest = cidr(text(r, "address"), text(r, "subnet_mask"));
                    if (dest.isPresent()) {
                        routes.add(new InventoryRoute(UUID.randomUUID().toString(), dest.get(), text(r, "gateway"), Optional.empty(),
                                InventoryRoute.PROTOCOL_STATIC, Optional.empty()));
                    }
                }
            }
            out.add(new InventoryContext(host.trim(), interfaces, routes));
        }
        return List.copyOf(out);
    }

    private static void addInterface(List<InventoryInterface> into, String name, JsonNode network) {
        List<InventoryAddress> addresses = new ArrayList<>();
        if (network != null) {
            cidr(text(network, "address"), text(network, "subnet_mask")).ifPresent(a ->
                    addresses.add(new InventoryAddress(UUID.randomUUID().toString(), a, InventoryAddress.FAMILY_IPV4, InventoryAddress.ROLE_MEMBER)));
        }
        into.add(new InventoryInterface(UUID.randomUUID().toString(), name, Optional.empty(), InventoryInterface.KIND_PHYSICAL,
                network == null ? InventoryInterface.STATE_UNKNOWN : InventoryInterface.STATE_UP, addresses, Optional.empty()));
    }

    /** {@code address} + dotted {@code subnet_mask} to {@code address/len}; an address without a mask is {@code /32}. */
    static Optional<String> cidr(Optional<String> address, Optional<String> mask) {
        if (address.isEmpty() || address.get().isBlank()) {
            return Optional.empty();
        }
        int len = 32;
        if (mask.isPresent() && !mask.get().isBlank()) {
            String[] parts = mask.get().trim().split("\\.");
            if (parts.length != 4) {
                return Optional.empty();
            }
            len = 0;
            for (String part : parts) {
                int octet;
                try {
                    octet = Integer.parseInt(part);
                } catch (NumberFormatException e) {
                    return Optional.empty();
                }
                len += Integer.bitCount(octet & 0xff);
            }
        }
        return Optional.of(address.get().trim() + "/" + len);
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
