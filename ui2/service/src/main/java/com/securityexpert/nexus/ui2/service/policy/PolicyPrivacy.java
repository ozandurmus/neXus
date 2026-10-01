package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;

/** Fail-closed policy projection: unfamiliar fields and free-form extensions never pass through AIView. */
public final class PolicyPrivacy {
    private PolicyPrivacy() {}
    private static final Set<String> OPAQUE = Set.of("id", "sourceId", "containerId", "deviceId", "artefactRef", "parentRuleId");
    private static final Set<String> ENUMS = Set.of("CP", "PAN", "any", "address", "group", "service", "service-group", "address-group",
            "application-group", "tag", "unresolved", "UNRESOLVED", "UNSUPPORTED", "RESOLVED", "DYNAMIC", "CYCLE", "LIMIT", "UNKNOWN",
            "MATCH", "MISMATCH", "IN_SYNC", "OUT_OF_SYNC", "PENDING", "Accept", "Drop", "Reject", "allow", "deny", "drop", "reject", "reset-client", "reset-server", "reset-both", "Apply Layer", "Log", "None", "Alert");
    public static Object mask(Object value, String key, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (key.equals("extras")) return Map.of("withheld", List.of("Withheld in AIView"));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String field = String.valueOf(k);
                if (field.equals("name") && "any".equals(map.get("type"))) out.put(field, "ANY");
                else if (field.equals("name") && map.containsKey("rules") && Set.of("Pre rules", "Post rules", "Inline layer unavailable").contains(v)) out.put(field, v);
                else if (field.equals("name") && map.containsKey("deviceId")) out.put(field, names.maskDeviceName((String) v, null));
                else out.put(field, mask(v, field, names, ips));
            });
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> mask(item, key, names, ips)).toList();
        if (!(value instanceof String text)) return "Withheld in AIView";
        if (text.isEmpty()) return text;
        if (OPAQUE.contains(key) || key.equals("refs") || key.equals("members") || key.equals("collectedAt")) return text;
        if (key.equals("comment")) return "Withheld in AIView";
        if (key.equals("sourceName")) return names.maskDeviceName(text, null);
        if (key.equals("containerName")) return names.maskDomainName(text);
        if (key.equals("source")) return Set.of("Shared", "CP access layer").contains(text) ? text : names.maskDomainName(text);
        if (key.equals("type") || key.equals("status") || key.equals("vendor") || key.equals("action") || key.equals("syncStatus"))
            return ENUMS.contains(text) ? text : "UNKNOWN";
        if (key.equals("log")) return ENUMS.contains(text) || text.matches("start=(yes|no|UNKNOWN), end=(yes|no|UNKNOWN)") ? text : "UNKNOWN";
        if (key.equals("values") && (text.matches("(?:port|icmp-type|icmp-code|protocol/(?:tcp|udp)/(?:port|source-port)): [0-9,<>*:/ -]+")
                || text.matches("protocol: (tcp|udp|icmp)") || text.matches("mask-length[46]: [0-9]{1,3}") || text.matches("color: color[0-9]+"))) return text;
        if (key.equals("values") && text.matches("(?:(?:ipv4-address|subnet4|ip-netmask|ip-range|ip-address-first|ip-address-last): )?[0-9./ -]+"))
            return ips.maskText(text);
        return names.maskPolicyName(key, text);
    }
}
