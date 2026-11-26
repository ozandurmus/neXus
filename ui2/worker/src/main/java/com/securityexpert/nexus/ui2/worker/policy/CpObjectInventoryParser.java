package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref;
import java.util.*;

/** Explicit field projection; credentials, topology and vendor blobs are never retained (gateway addresses: D-1 only). */
final class CpObjectInventoryParser {
    /** Stage 2 of the VSX VIP prefix decision (2026-11-06): only these management objects keep interface addresses. */
    static final Set<String> VS_TYPES = Set.of("CpmiVsClusterNetobj", "CpmiVsNetobj");
    private CpObjectInventoryParser() {}
    /** Address + mask length only; an interface without a valid pair is dropped and the object can never bind. */
    static CpObjectInventory.VsAddresses vsAddresses(JsonNode node) {
        List<CpObjectInventory.VsInterface> kept = new ArrayList<>();
        boolean complete = node.path("interfaces").isArray() && node.path("interfaces").size() > 0;
        for (JsonNode iface : node.path("interfaces")) {
            JsonNode address = iface.path("ipv4-address");
            JsonNode mask = iface.path("ipv4-mask-length");
            if (address.isTextual() && validIpv4(address.textValue()) && mask.isIntegralNumber() && mask.canConvertToInt()
                    && mask.intValue() >= 1 && mask.intValue() <= 32) {
                var entry = new CpObjectInventory.VsInterface(address.textValue(), mask.intValue());
                if (kept.contains(entry)) continue;
                if (kept.stream().anyMatch(k -> k.address().equals(entry.address()))) complete = false;
                kept.add(entry);
            } else complete = false;
        }
        return new CpObjectInventory.VsAddresses(kept, complete);
    }
    /**
     * PO decision D-1 (2026-11-14, A0 amendment section G1): the management-side IPv4 addresses of a gateway-class object,
     * from the {@code show-gateways-and-servers details-level full} reply already read (parse-scope extension, no new
     * command): the main {@code ipv4-address}, every {@code interfaces[].ipv4-address} and, when the object lists
     * {@code cluster-members} (Cluster Management API reply shape), each member's {@code ip-address}/{@code ipv4-address}
     * and its {@code interfaces[].ipv4-address}. Masks, names, topology and anything else are not kept.
     * <p>Complete only when the object has a non-empty interface list, every address field present is a valid IPv4
     * address, every interface carries an IPv4 or an IPv6 address, a classic cluster ({@link #CLUSTER_TYPES}) lists its
     * members, and every listed member carries an address or interfaces read the same way. Anything else: not complete,
     * so the object never bounds a rule (fail closed). IPv6 addresses are not kept: the engine bounds every gateway's IPv6
     * side as all of IPv6.
     */
    static CpObjectInventory.GatewayAddresses gatewayAddresses(JsonNode node, String type) {
        Set<String> kept = new LinkedHashSet<>();
        JsonNode interfaces = objectList(node.path("interfaces"));
        boolean complete = address(node.path("ipv4-address"), kept) && interfaces(interfaces, kept, true);
        JsonNode rawMembers = node.path("cluster-members");
        JsonNode members = objectList(rawMembers);
        if (CLUSTER_TYPES.contains(type) && !(members != null && members.size() > 0)) complete = false;
        if (!rawMembers.isMissingNode() && !rawMembers.isNull()) {
            if (members == null) complete = false;
            else for (JsonNode member : members) {
                if (!member.isObject()) { complete = false; continue; }
                boolean ip = address(member.path("ip-address"), kept) & address(member.path("ipv4-address"), kept);
                boolean any = hasAddressText(member.path("ip-address")) || hasAddressText(member.path("ipv4-address"));
                JsonNode rawMemberIfaces = member.path("interfaces");
                JsonNode memberIfaces = objectList(rawMemberIfaces);
                boolean hasMemberIfacesField = !rawMemberIfaces.isMissingNode() && !rawMemberIfaces.isNull();
                boolean listed = memberIfaces != null && memberIfaces.size() > 0;
                if (!ip || (hasMemberIfacesField && memberIfaces == null) || (!any && !listed)
                        || (listed && !interfaces(memberIfaces, kept, true))) complete = false;
            }
        }
        if (kept.isEmpty()) complete = false;
        return new CpObjectInventory.GatewayAddresses(new ArrayList<>(kept), complete);
    }
    /** Classic ClusterXL cluster objects, which must list their members for a complete address set. */
    static final Set<String> CLUSTER_TYPES = Set.of("simple-cluster", "CpmiGatewayCluster");
    /**
     * Unwraps either a direct JSON array or an un-truncated Check Point sub-list wrapper {@code {"objects":[...],"total":N}}.
     * If {@code total} disagrees with {@code objects.size()} (or {@code from}/{@code to} appear without {@code total}), returns null so D-1 fails closed.
     */
    private static JsonNode objectList(JsonNode node) {
        if (node.isArray()) return node;
        if (!node.isObject() || !node.path("objects").isArray()) return null;
        JsonNode objects = node.path("objects"), total = node.path("total");
        if (!total.isMissingNode() && !total.isNull())
            return total.isIntegralNumber() && total.canConvertToInt() && total.intValue() == objects.size() ? objects : null;
        return node.has("from") || node.has("to") ? null : objects;
    }
    /** A missing or empty-string field is absent; a non-empty present value must be a valid IPv4 address text. */
    private static boolean address(JsonNode value, Set<String> kept) {
        if (value.isMissingNode() || value.isNull() || (value.isTextual() && value.textValue().isEmpty())) return true;
        if (!value.isTextual() || !validIpv4(value.textValue())) return false;
        kept.add(value.textValue());
        return true;
    }
    private static boolean hasAddressText(JsonNode value) {
        return value.isTextual() && !value.textValue().isEmpty();
    }
    private static boolean validIpv6Text(JsonNode value) {
        return value.isTextual() && !value.textValue().isBlank() && value.textValue().indexOf(':') >= 0;
    }
    private static boolean interfaces(JsonNode list, Set<String> kept, boolean required) {
        if (list == null || !list.isArray() || list.size() == 0) return !required;
        boolean complete = true;
        for (JsonNode iface : list) {
            if (!iface.isObject()) { complete = false; continue; }
            boolean v4 = hasAddressText(iface.path("ipv4-address")), v6 = validIpv6Text(iface.path("ipv6-address"));
            if (!address(iface.path("ipv4-address"), kept) || !v4 && !v6) complete = false;
        }
        return complete;
    }
    private static boolean validIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(c -> c >= '0' && c <= '9')) return false;
            if (part.length() > 1 && part.startsWith("0") || Integer.parseInt(part) > 255) return false;
        }
        return true;
    }
    static List<CpObjectInventory.Item> parse(String source, String domain, List<JsonNode> nodes) {
        return nodes.stream().map(node -> {
            String uid = CheckPointPolicyCollector.required(node, "uid");
            String type = node.path("type").asText("unknown");
            List<String> values = new ArrayList<>();
            for (String field : List.of("ipv4-address", "ipv6-address", "subnet4", "subnet6", "subnet-mask", "mask-length4",
                    "mask-length6", "ipv4-address-first", "ipv4-address-last", "ipv6-address-first", "ipv6-address-last",
                    "ip-address-first", "ip-address-last", "port", "source-port", "protocol", "icmp-type", "icmp-code")) {
                JsonNode value = node.path(field);
                if (value.isTextual() || value.isNumber()) values.add(field + ": " + value.asText());
            }
            if (type.equals("time")) {
                for (String field : List.of("start-now", "end-never"))
                    if (node.path(field).isBoolean()) values.add(field + ": " + node.path(field).booleanValue());
                JsonNode recurrence = node.path("recurrence");
                for (String field : List.of("pattern", "month", "interval"))
                    if (recurrence.path(field).isValueNode()) values.add("recurrence/" + field + ": " + recurrence.path(field).asText());
                for (String field : List.of("weekdays", "days")) for (JsonNode value : recurrence.path(field))
                    if (value.isValueNode()) values.add("recurrence/" + field + ": " + value.asText());
                for (JsonNode range : node.path("hours-ranges")) for (String field : List.of("enabled", "from", "to"))
                    if (range.path(field).isValueNode()) values.add("hours-range/" + field + ": " + range.path(field).asText());
            }
            // Keep exclusion operands labelled: include/exclude is not an ordinary union group.
            for (String field : List.of("include", "except")) {
                JsonNode operand = node.path(field);
                String id = operand.isMissingNode() || operand.isNull() ? "" : uid(operand);
                if (!id.isEmpty()) values.add(field + ": " + id);
            }
            List<CpObjectInventory.Installation> installed = new ArrayList<>();
            var policy = node.path("policy");
            for (String field : List.of("access-policy-name", "threat-policy-name"))
                if (policy.path(field).isTextual()) {
                    JsonNode state = policy.path(field.replace("-name", "-installed"));
                    installed.add(new CpObjectInventory.Installation(policy.path(field).textValue(),
                        state.isBoolean() ? state.booleanValue() : null));
                }
            return new CpObjectInventory.Item(ref(source, domain, "object", uid), uid, node.path("name").asText(""),
                type, uids(node.path("members")), values, type.equals("time") ? CheckPointSchedule.parse(node) : null, installed,
                VS_TYPES.contains(type) ? vsAddresses(node) : null,
                com.securityexpert.nexus.ui2.policy.CpObjectTypes.GATEWAY_TYPES.contains(type) ? gatewayAddresses(node, type) : null);
        }).toList();
    }
    private static String uid(JsonNode node) {
        if (node.isObject()) return CheckPointPolicyCollector.required(node, "uid");
        if (!node.isTextual() || node.textValue().isBlank()) throw CheckPointPolicyCollector.failure();
        return node.textValue();
    }
    private static List<String> uids(JsonNode members) {
        List<String> ids = new ArrayList<>();
        for (JsonNode member : members) {
            String uid = uid(member);
            if (uid.isBlank()) throw CheckPointPolicyCollector.failure();
            ids.add(uid);
        }
        return ids;
    }
}
