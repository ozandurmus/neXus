package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref;
import java.util.*;

/** Explicit field projection; credentials, topology and vendor blobs are never retained. */
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
                VS_TYPES.contains(type) ? vsAddresses(node) : null);
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
