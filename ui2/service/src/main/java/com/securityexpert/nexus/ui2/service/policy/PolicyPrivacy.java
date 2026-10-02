package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;

/** Fail-closed policy projection: unfamiliar fields and free-form extensions never pass through AIView. */
public final class PolicyPrivacy {
    private PolicyPrivacy() {}
    private static final Set<String> OPAQUE = Set.of("id", "sourceId", "containerId", "deviceId", "artefactRef", "parentRuleId", "layerRef", "jobId");
    private static final Set<String> ENUMS = Set.of("CP", "PAN", "static", "hide", "any", "address", "group", "service", "service-group", "address-group",
            "LOCAL_FIREWALL", "MANAGEMENT", "application-group", "tag", "unresolved", "UNRESOLVED", "UNSUPPORTED", "RESOLVED", "DYNAMIC", "CYCLE", "LIMIT", "UNKNOWN",
            "MATCH", "MISMATCH", "IN_SYNC", "OUT_OF_SYNC", "PENDING", "Accept", "Drop", "Reject", "allow", "deny", "drop", "reject", "reset-client", "reset-server", "reset-both", "Apply Layer", "Log", "None", "Alert");
    public static Object mask(Object value, String key, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (key.equals("extras")) return Map.of("withheld", List.of("Withheld in AIView"));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String field = String.valueOf(k);
                if (field.equals("name") && "any".equals(map.get("type"))) out.put(field, "ANY");
                else if (field.equals("name") && map.containsKey("rules") && Set.of("Pre rules", "Post rules", "Local rules", "Inline layer unavailable").contains(v)) out.put(field, v);
                else if (field.equals("name") && map.containsKey("deviceId")) out.put(field, names.maskDeviceName((String) v, null));
                else out.put(field, mask(v, field, names, ips));
            });
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> mask(item, key, names, ips)).toList();
        if (!(value instanceof String text)) return "Withheld in AIView";
        if (text.isEmpty()) return text;
        if (OPAQUE.contains(key) || key.equals("refs") || key.equals("members") || key.equals("collectedAt")) return text;
        if (key.equals("state")) return Set.of("REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING", "COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN", "RECONCILED").contains(text) ? text : "UNKNOWN";
        if (key.equals("reason")) return safeReason(text);
        if (key.equals("comment")) return "Withheld in AIView";
        if (key.equals("sourceName")) return names.maskDeviceName(text, null);
        if (key.equals("containerName")) return names.maskDomainName(text);
        if (key.equals("source")) return Set.of("Shared", "CP access layer", "CP NAT rulebase").contains(text) ? text : names.maskDomainName(text);
        if (key.equals("type") || key.equals("status") || key.equals("vendor") || key.equals("action") || key.equals("syncStatus") || key.equals("policyKind"))
            return ENUMS.contains(text) ? text : "UNKNOWN";
        if (key.equals("log")) return ENUMS.contains(text) || text.matches("start=(yes|no|UNKNOWN), end=(yes|no|UNKNOWN)") ? text : "UNKNOWN";
        if (key.equals("values") && (text.matches("(?:port|icmp-type|icmp-code|protocol/(?:tcp|udp)/(?:port|source-port)): [0-9,<>*:/ -]+")
                || text.matches("protocol: (tcp|udp|icmp)") || text.matches("mask-length[46]: [0-9]{1,3}") || text.matches("color: color[0-9]+"))) return text;
        if (key.equals("values") && text.matches("(?:(?:ipv4-address|subnet4|ip-netmask|ip-range|ip-address-first|ip-address-last): )?[0-9./ -]+"))
            return ips.maskText(text);
        return names.maskPolicyName(key, text);
    }
    private static String safeReason(String text) {
        if (text.equals("INLINE_LAYER_NAME_MISSING")) return text;
        int delimiter = text.lastIndexOf(": ");
        if (delimiter < 0) return "COLLECTION_FAILED";
        String code = text.substring(delimiter + 2);
        if (!code.matches("(?:HTTP_[0-9]{3}|API_ERROR_[0-9]{1,6}|EXIT_[0-9]+|FAILED_[A-Za-z]+|TIMEOUT|SIZE_LIMIT|JOB_DEADLINE|LEASE_LOST|POLICY_GATE_UNAVAILABLE|PANORAMA_TARGET_NOT_FOUND|PANORAMA_COLLECTOR_NOT_WIRED|POLICY_REQUEST_NOT_FOUND|DISCOVERY_RUN_NOT_FOUND|POLICY_SOURCE_NOT_ELIGIBLE|CREDENTIAL_UNRESOLVABLE|CREDENTIAL_UNUSABLE|TRANSPORT_NOT_REGISTERED|TLS_TARGET_UNRESOLVABLE|TRANSPORT_FAILED|INTERRUPTED|XML_PARSE_OR_SIZE_FAILED|API_RESPONSE_ERROR|INVALID_OR_INCOMPLETE_RESPONSE|ChannelFailed|TimedOut|HostKeyRejected|AuthenticationFailed)"))
            return "COLLECTION_FAILED";
        var target = java.util.regex.Pattern.compile("target=([a-f0-9-]{36}|(?:source|manager|mds|pan|run|layer)-[0-9]+)(?=: )").matcher(text);
        if (!target.find()) return "COLLECTION_FAILED: " + code;
        String step = "preflight";
        for (String label : List.of("show-domains", "show-packages", "show-access-rulebase", "show-nat-rulebase", "show devicegroups", "show dg-hierarchy", "shared", "authentication", "credential resolution", "PANORAMA_TARGET", "connect", "access"))
            if (text.startsWith(label) || text.contains(" " + label + " ")) { step = label; break; }
        if (text.startsWith("device group ")) {
            var group = java.util.regex.Pattern.compile("^device group ([a-f0-9-]{36}) ").matcher(text);
            step = group.find() ? "DG " + group.group(1) : "DG";
        }
        return (text.startsWith("PARTIAL_SNAPSHOT ") ? "PARTIAL_SNAPSHOT " : "") + step + " target=" + target.group(1) + ": " + code;
    }

}
