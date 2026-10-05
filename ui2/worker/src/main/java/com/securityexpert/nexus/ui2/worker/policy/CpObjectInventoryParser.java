package com.securityexpert.nexus.ui2.worker.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.securityexpert.nexus.ui2.policy.CpObjectInventory;
import static com.securityexpert.nexus.ui2.policy.PolicySnapshot.ref;
import java.util.*;

/** Explicit field projection; credentials, topology and vendor blobs are never retained. */
final class CpObjectInventoryParser {
    private CpObjectInventoryParser() {}
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
                type, uids(node.path("members")), values, type.equals("time") ? CheckPointSchedule.parse(node) : null, installed);
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
