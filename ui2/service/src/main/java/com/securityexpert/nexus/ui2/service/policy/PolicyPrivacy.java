package com.securityexpert.nexus.ui2.service.policy;

import java.util.*;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.privacy.SubnetPreservingIpMasker;

/** Fail-closed policy projection: unfamiliar fields and free-form extensions never pass through AIView. */
public final class PolicyPrivacy {
    private PolicyPrivacy() {}
    private static final Set<String> OPAQUE = Set.of("id", "sourceId", "containerId", "deviceId", "artefactRef", "parentRuleId", "layerRef", "jobId", "ruleId", "policyId", "uid");
    private static final Set<String> ENUMS = Set.of("CP", "PAN", "static", "hide", "any", "address", "group", "service", "service-group", "address-group",
            "simple-gateway", "simple-cluster", "cluster", "gateway", "checkpoint-host", "vsx-cluster", "vsx-gateway", "vsx-cluster-member", "CpmiGatewayCluster", "CpmiVsClusterNetobj", "CpmiVsxClusterNetobj", "CpmiVsxNetobj", "CpmiVsxClusterMember",
            "COLLECTION_FAILED", "host", "network", "address-range", "group-with-exclusion", "service-tcp", "service-udp", "service-icmp", "service-icmp6", "service-other", "service-sctp", "service-dce-rpc", "service-rpc", "access-role", "dynamic-object", "dns-domain", "security-zone",
            "times", "time-groups", "gateways-and-servers", "hosts", "networks", "groups", "groups-with-exclusion", "address-ranges", "services-tcp", "services-udp", "services-icmp", "services-icmp6", "services-other", "services-sctp", "services-dce-rpc", "services-rpc", "service-groups", "access-roles", "dynamic-objects", "dns-domains", "security-zones", "unused-objects",
            "LOCAL_FIREWALL", "MANAGEMENT", "application-group", "tag", "time", "time-group", "schedule", "unresolved", "UNRESOLVED", "UNSUPPORTED", "RESOLVED", "DYNAMIC", "CYCLE", "LIMIT", "UNKNOWN",
            "MATCH", "MISMATCH", "IN_SYNC", "OUT_OF_SYNC", "PENDING", "Accept", "Drop", "Reject", "allow", "deny", "drop", "reject", "reset-client", "reset-server", "reset-both", "Apply Layer", "Log", "None", "Alert");
    public static Object mask(Object value, String key, TopologyNamePseudonymizer names, SubnetPreservingIpMasker ips) {
        if (value == null || value instanceof Boolean || value instanceof Number) return value;
        if (key.equals("changes") && value instanceof List<?> changes) return changes.stream().map(change -> {
            if (!(change instanceof Map<?, ?> row)) return Map.of();
            String field = String.valueOf(row.get("field"));
            if (!Set.of("name", "number", "enabled", "source", "destination", "service", "application", "action", "log", "comment", "extras", "container", "section", "parentRuleId").contains(field)) return Map.of();
            Map<String, Object> out = new LinkedHashMap<>(); out.put("field", field);
            out.put("before", mask(row.get("before"), field.equals("name") ? "rule" : field, names, ips));
            out.put("after", mask(row.get("after"), field.equals("name") ? "rule" : field, names, ips));
            return out;
        }).toList();
        if (key.equals("extras") && value instanceof Map<?, ?> extras) {
            Map<String, Object> out = new LinkedHashMap<>();
            extras.forEach((k, v) -> {
                String field = String.valueOf(k);
                if (Set.of("time", "schedule", "install-on", "vpn", "content", "inline-layer").contains(field)) out.put(field, mask(v, "refs", names, ips));
                else if (field.equals("last-modified")) out.put(field, mask(v, "changedOn", names, ips));
                else if (Set.of("from", "to", "source-user", "category", "tag", "last-modifier", "layer-name", "target-selectors", "log-setting", "profile-setting/group", "profile-setting/profiles/virus", "profile-setting/profiles/spyware", "profile-setting/profiles/vulnerability", "profile-setting/profiles/url-filtering", "profile-setting/profiles/file-blocking", "profile-setting/profiles/wildfire-analysis").contains(field))
                    out.put(field, mask(v, field, names, ips));
                else if (field.equals("rule-type")) out.put(field, mask(v, "ruleType", names, ips));
                else out.put(field, List.of("Withheld in AIView"));
            });
            return out;
        }
        if (key.equals("permissiveness") && value instanceof Map<?, ?> metric) {
            String level = String.valueOf(metric.get("level"));
            List<?> reasons = metric.get("reasons") instanceof List<?> list ? list : List.of();
            return Map.of("level", Set.of("Low", "Medium", "High", "Unknown").contains(level) ? level : "Unknown",
                    "reasons", reasons.stream().filter(r -> r instanceof String s && (s.matches("(?:Any |Large CIDR in |Negated )(source|destination|service|application)(?: needs analysis)?")
                            || s.equals("Broad service range") || s.equals("No broad selectors in resolved objects") || s.equals("Incomplete objects; assessment requires analysis"))).toList());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String field = String.valueOf(k);
                if (field.equals("name") && "any".equals(map.get("type"))) out.put(field, "ANY");
                else if (field.equals("name") && map.containsKey("rules") && Set.of("Pre rules", "Post rules", "Local rules", "Inline layer unavailable").contains(v)) out.put(field, mask(v, "refs", names, ips));
                else if (field.equals("name") && map.containsKey("deviceId")) out.put(field, names.maskDeviceName((String) v, null));
                else if (field.equals("name") && v instanceof String text) out.put(field, names.maskPolicyName(
                    map.containsKey("sourceId") ? "policy" : map.containsKey("number") ? "rule"
                        : map.containsKey("type") ? objectNameType(String.valueOf(map.get("type"))) : "name", text));
                else out.put(field, mask(v, field, names, ips));
            });
            return out;
        }
        if (value instanceof List<?> list) return list.stream().map(item -> mask(item, key, names, ips)).toList();
        if (!(value instanceof String text)) return "Withheld in AIView";
        if (text.isEmpty()) return text;
        if (OPAQUE.contains(key) || key.equals("refs") || key.equals("members") || key.equals("collectedAt")) return text;
        if (key.equals("state")) return Set.of("REQUESTED", "CLAIMED", "EXECUTING", "RECONCILING", "COMPLETED", "FAILED", "REJECTED", "CANCELLED", "OUTCOME_UNKNOWN", "RECONCILED").contains(text) ? text : "UNKNOWN";
        if (key.equals("outcome")) return Set.of("COMPLETED", "PARTIAL", "FAILED", "UNKNOWN").contains(text) ? text : "UNKNOWN";
        if (key.equals("unitFailureCodes")) return failureCode(text);
        if (key.equals("reason")) return text.equals("COLLECTION_SKIPPED") ? text : safeReason(text);
        if (Set.of("firstHit", "lastHit", "createdAt", "modifiedAt", "startedAt", "lastActivityAt").contains(key)) {
            try { return java.time.Instant.parse(text).toString(); } catch (RuntimeException invalid) { return null; }
        }
        if (Set.of("start", "end", "changedOn").contains(key)) {
            try {
                if (text.matches("[0-9]{2}:[0-9]{2}(?::[0-9]{2})?")) return java.time.LocalTime.parse(text).toString();
                return com.securityexpert.nexus.ui2.policy.PolicySchedule.date(text);
            } catch (RuntimeException invalid) { return null; }
        }
        if (key.equals("timezone")) {
            try { return java.time.ZoneId.of(text).getId(); } catch (RuntimeException invalid) { return "UTC"; }
        }
        if (key.equals("kind")) return Set.of("one-time", "recurring", "unknown").contains(text) ? text : "unknown";
        if (key.equals("timeStatus")) return Set.of("always", "active", "expired", "upcoming", "unknown").contains(text) ? text : "unknown";
        if (key.equals("changeType")) return Set.of("added", "removed", "modified").contains(text) ? text : "UNKNOWN";
        if (key.equals("ruleType")) return Set.of("universal", "interzone", "intrazone").contains(text) ? text : "UNKNOWN";
        if (Set.of("from", "to", "source-user", "category").contains(key) && text.equals("any")) return text;
        if (key.equals("changedBy")) return names.maskPolicyName("last-modifier", text);
        if (key.equals("policyName")) return names.maskPolicyName("policy", text);
        if (key.equals("comment")) return "Withheld in AIView";
        if (key.equals("sourceName")) return names.maskDeviceName(text, null);
        if (key.equals("containerName")) return names.maskDomainName(text);
        if (key.equals("level")) return Set.of("zero", "low", "medium", "high").contains(text) ? text : "UNKNOWN";
        if (key.equals("source")) return Set.of("device", "mds", "Shared", "CP access layer", "CP NAT rulebase").contains(text) ? text : names.maskDomainName(text);
        if (key.equals("type") || key.equals("status") || key.equals("vendor") || key.equals("action") || key.equals("syncStatus") || key.equals("policyKind"))
            return ENUMS.contains(text) ? text : "UNKNOWN";
        if (key.equals("log")) return ENUMS.contains(text) || text.matches("start=(yes|no|UNKNOWN), end=(yes|no|UNKNOWN)") ? text : "UNKNOWN";
        if (key.equals("values") && (text.matches("(?:port|source-port|icmp-type|icmp-code|protocol/(?:tcp|udp)/(?:port|source-port)): [0-9,<>*:/ -]+")
                || text.matches("protocol: (tcp|udp|icmp|icmp6|sctp|other|dce-rpc|rpc|[0-9]{1,3})") || text.matches("mask-length[46]: [0-9]{1,3}") || text.matches("color: color[0-9]+"))) return text;
        if (key.equals("values") && text.matches("(?:(?:ipv4-address|ipv4-address-first|ipv4-address-last|subnet4|subnet-mask|ip-netmask|ip-range|ip-address-first|ip-address-last): )?[0-9./ -]+"))
            return ips.maskText(text);
        return names.maskPolicyName(key, text);
    }
    private static String objectNameType(String type) {
        return switch (type) {
            case "host", "network", "address-range" -> "address";
            case "group-with-exclusion" -> "group";
            case "service-tcp", "service-udp", "service-icmp", "service-icmp6", "service-other", "service-sctp", "service-dce-rpc", "service-rpc" -> "service";
            default -> type;
        };
    }

    public static String failureCode(String text) {
        String code = text.contains(": ") ? text.substring(text.lastIndexOf(": ") + 2) : text;
        if (Set.of("INLINE_LAYER_NAME_MISSING", "COLLECTION_PENDING").contains(code)) return code;
        // Reuse the reason allowlist; unrecognized text remains withheld.
        String safe = safeReason("policy: " + code);
        return safe.startsWith("COLLECTION_FAILED: ") ? safe.substring("COLLECTION_FAILED: ".length())
            : "COLLECTION_FAILED";
    }

    private static String safeReason(String text) {
        if (Set.of("INLINE_LAYER_NAME_MISSING", "COLLECTION_PENDING").contains(text)) return text;
        int delimiter = text.lastIndexOf(": ");
        if (delimiter < 0) return "COLLECTION_FAILED";
        String code = text.substring(delimiter + 2);
        if (!code.matches("(?:HTTP_[0-9]{3}|API_ERROR_[0-9]{1,6}|EXIT_[0-9]+|FAILED_[A-Za-z]+|TIMEOUT|STREAMING_TIMEOUT|API_SESSION_PRESSURE|SIZE_LIMIT|JOB_DEADLINE|LEASE_LOST|POLICY_GATE_UNAVAILABLE|PANORAMA_TARGET_NOT_FOUND|PANORAMA_COLLECTOR_NOT_WIRED|POLICY_REQUEST_NOT_FOUND|DISCOVERY_RUN_NOT_FOUND|POLICY_SOURCE_NOT_ELIGIBLE|CREDENTIAL_UNRESOLVABLE|CREDENTIAL_UNUSABLE|TRANSPORT_NOT_REGISTERED|TLS_TARGET_UNRESOLVABLE|TRANSPORT_FAILED|INTERRUPTED|XML_PARSE_OR_SIZE_FAILED|API_RESPONSE_ERROR|INVALID_OR_INCOMPLETE_RESPONSE|ChannelFailed|TimedOut|HostKeyRejected|AuthenticationFailed)"))
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
