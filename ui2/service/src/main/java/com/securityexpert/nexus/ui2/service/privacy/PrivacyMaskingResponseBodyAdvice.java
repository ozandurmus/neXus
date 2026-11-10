package com.securityexpert.nexus.ui2.service.privacy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobEvent;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Server-side presentation funnel implementing C9 contract §4 and §6.
 * Intercepts outgoing HTTP responses and applies deterministic, relationship-preserving
 * HMAC pseudonymization for sessions holding {@code role:replay_viewer}.
 * Cached and persistent entities in PostgreSQL and background workers remain 100% operational on raw data.
 */
@ControllerAdvice
public class PrivacyMaskingResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private final SubnetPreservingIpMasker ipMasker;
    private final TopologyNamePseudonymizer topologyPseudonymizer;

    public PrivacyMaskingResponseBodyAdvice(SubnetPreservingIpMasker ipMasker,
            TopologyNamePseudonymizer topologyPseudonymizer) {
        this.ipMasker = Objects.requireNonNull(ipMasker, "ipMasker");
        this.topologyPseudonymizer = Objects.requireNonNull(topologyPseudonymizer, "topologyPseudonymizer");
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    public static final String MASKED_HEADER = "X-Nexus-Masked";

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        if (body == null) {
            return null;
        }
        if (!(request instanceof ServletServerHttpRequest servletRequest)) {
            return body;
        }
        HttpServletRequest httpRequest = servletRequest.getServletRequest();
        Boolean isReplayViewer = (Boolean) httpRequest.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE);
        if (body instanceof com.securityexpert.nexus.ui2.service.search.IpSearchResponse search) {
            // The IP search service has explicitly projected names, contexts and addresses after matching.
            // Reapplying subnet/VS pseudonymization would produce different identities from other screens.
            if (Boolean.TRUE.equals(isReplayViewer)) {
                if (!search.masked()) throw new IllegalStateException("IP search projection required");
                if (response != null) response.getHeaders().set(MASKED_HEADER, "true");
            }
            return search.body();
        }
        if (body instanceof com.securityexpert.nexus.ui2.service.changes.ChangeFeedResponse changes) {
            // The change feed masks itself (pseudonyms, USR- tokens, log masking rules); masking a pseudonym again
            // would give a different pseudonym, so the body passes through -- and an unmasked body never reaches aiview.
            if (Boolean.TRUE.equals(isReplayViewer)) {
                if (!changes.masked()) throw new IllegalStateException("change feed projection required");
                if (response != null) response.getHeaders().set(MASKED_HEADER, "true");
            }
            return changes.body();
        }
        if (body instanceof com.securityexpert.nexus.ui2.service.packetanalysis.PacketDeviceEventsResponse deviceEvents) {
            // Packet Analysis > Device events masks itself the same way (device pseudonyms, label pseudonyms, log masking
            // rules on cpwd text); the body passes through and an unmasked body never reaches aiview.
            if (Boolean.TRUE.equals(isReplayViewer)) {
                if (!deviceEvents.masked()) throw new IllegalStateException("device events projection required");
                if (response != null) response.getHeaders().set(MASKED_HEADER, "true");
            }
            return deviceEvents.body();
        }
        if (body instanceof com.securityexpert.nexus.ui2.service.report.ReportResponse report) {
            // Reports R1: the report document was projected once by ReportDocumentMasker before any renderer; the body
            // passes through, and an unmasked body never reaches aiview.
            if (Boolean.TRUE.equals(isReplayViewer)) {
                if (!report.masked()) throw new IllegalStateException("report projection required");
                if (response != null) response.getHeaders().set(MASKED_HEADER, "true");
            }
            return report.body();
        }
        if (body instanceof com.securityexpert.nexus.ui2.service.policy.PolicyResponse policy) {
            if (!Boolean.TRUE.equals(isReplayViewer)) return policy.body();
            if (response != null) response.getHeaders().set(MASKED_HEADER, "true");
            return com.securityexpert.nexus.ui2.service.policy.PolicyPrivacy.mask(
                    policy.body(), "", topologyPseudonymizer, ipMasker);
        }
        if (!Boolean.TRUE.equals(isReplayViewer)) {
            return body;
        }

        // Tells the page this response was masked, so the shell can show the mask chip on every screen without
        // the browser ever deciding anything from a role token (UI review 2026-09-23 §4).
        if (response != null) {
            response.getHeaders().set(MASKED_HEADER, "true");
        }
        preRegisterSubnets(body);
        return maskObject(body, null);
    }

    private void preRegisterSubnets(Object obj) {
        if (obj instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object val = entry.getValue();
                if (("address".equals(key) || "destination".equals(key) || "management_ip".equals(key)
                        || "network".equals(key) || "src".equals(key) || "dst".equals(key)
                        || "client_ip".equals(key) || "server_ip".equals(key)
                        || "pre_nat_src".equals(key) || "pre_nat_dst".equals(key)
                        || "post_nat_src".equals(key) || "post_nat_dst".equals(key)
                        || "translated_src".equals(key) || "translated_dst".equals(key)
                        || "assumed_subnet".equals(key)) && val instanceof String s) {
                    ipMasker.registerSubnet(s);
                } else if (("observed_addresses".equals(key) || "matched_addresses".equals(key)
                        || "interface_addresses".equals(key)) && val instanceof List<?> addrList) {
                    for (Object item : addrList) {
                        if (item instanceof String s) ipMasker.registerSubnet(s);
                    }
                } else if (("hostname".equals(key) || "device".equals(key) || "file".equals(key)
                        || "point_label".equals(key) || "capture_name".equals(key))
                        && val instanceof String s && !s.isBlank()) {
                    topologyPseudonymizer.maskDeviceName(s, null);
                } else if ("name".equals(key) && map.containsKey("inventory") && map.containsKey("checkpoint")
                        && val instanceof String s && !s.isBlank()) {
                    topologyPseudonymizer.maskDeviceName(s, null);
                } else {
                    preRegisterSubnets(val);
                }
            }
        } else if (obj instanceof List<?> list) {
            for (Object item : list) {
                preRegisterSubnets(item);
            }
        }
    }

    /** True when the request's session is the replay viewer (AIView), i.e. when a body must be masked. */
    public static boolean isReplayViewer(HttpServletRequest request) {
        return Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
    }

    /** Deterministic labels for stored search fields outside the device/VS identity vocabulary. */
    public String maskSearchLabel(String namespace, String value) {
        return topologyPseudonymizer.maskPolicyName(namespace, value);
    }

    /**
     * Entrypoint for deep-masking arbitrary objects without mutating the inputs.
     */
    public Object maskObject(Object obj, String parentContextRef) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String text) {
            return topologyPseudonymizer.maskText(ipMasker.maskText(text));
        }
        if (obj instanceof JobEvent jobEvent) {
            String maskedReason = jobEvent.terminalReason() != null
                    ? topologyPseudonymizer.maskText(ipMasker.maskText(jobEvent.terminalReason()))
                    : null;
            return new JobEvent(
                    jobEvent.jobId(),
                    jobEvent.jobType(),
                    jobEvent.targetDeviceId(),
                    maskJobTargetName(jobEvent),
                    jobEvent.state(),
                    jobEvent.outcome(),
                    maskedReason,
                    jobEvent.submittedAt(),
                    jobEvent.finishedAt(),
                    jobEvent.durationMs(),
                    false,
                    jobEvent.linesStored(),
                    jobEvent.targetKind(),
                    null);
        }
        // The job log page (Operations > Jobs) is a record, not a Map: before 2026-09-25 it passed through unmasked and
        // showed real device names to the aiview persona.
        if (obj instanceof com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.JobPage page) {
            List<JobEvent> items = new ArrayList<>(page.items() == null ? 0 : page.items().size());
            if (page.items() != null) {
                for (JobEvent event : page.items()) {
                    items.add((JobEvent) maskObject(event, parentContextRef));
                }
            }
            // withItems keeps target_sortable / search_scope (they tell the masked UI what it may do); a masked page
            // never offers cancellation, as before.
            return page.withItems(items).withCancellationAccess(false);
        }
        if (obj instanceof Map<?, ?> map) {
            return maskMap((Map<?, ?>) map, parentContextRef);
        }
        if (obj instanceof List<?> list) {
            return maskList((List<?>) list, parentContextRef);
        }
        return obj;
    }

    private Map<String, Object> maskMap(Map<?, ?> map, String parentContextRef) {
        Map<String, Object> result = new LinkedHashMap<>();

        // Discover cluster ref in this object if present
        String detectedClusterRef = parentContextRef;
        if (map.get("cluster_member_ref") instanceof String s && !s.isBlank()) {
            detectedClusterRef = s;
        }
        final String clusterRef = detectedClusterRef;
        // A device row's own hostname is masked with its device ID, so two devices with the same name (an ASA
        // failover pair) never share a pseudonym (UI review 2026-10-18, F-1).
        final String rowDeviceId = map.get("device_id") instanceof String id && !id.isBlank() ? id : null;
        // Rows a manager lists (FortiManager FortiGates, Management Center ProxySGs, Infoblox grid members) and a
        // manager's list of them are devices, never virtual systems: MD-, not VS- (P2-3B).
        final boolean managedDeviceRow = map.containsKey("grid_master") && map.containsKey("services");
        final boolean managerListing = managedDeviceRow || isManagerListing(map);

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            Object value = entry.getValue();

            if (value == null) {
                result.put(key, null);
                continue;
            }

            switch (key) {
                // Product facts, never identities (2026-09-25): a four-part version such as "7.4.15.1" read as an address
                // by the IP masker, and a type label equal to a device name ("Reporter") read as that name.
                case "presented_fingerprint", "expected_fingerprint_prefix" -> result.put(key, value);
                case "platform", "hardware_type", "hypervisor", "software_version", "model", "platform_family" -> result.put(key, value);
                // V148: a link-layer address keeps its vendor prefix (OUI) only; a zone name becomes a stable pseudonym.
                case "mac_address" -> result.put(key, value instanceof String mac ? maskMac(mac) : value);
                case "observed_macs" -> {
                    if (value instanceof List<?> macList) {
                        List<Object> maskedMacs = new ArrayList<>(macList.size());
                        for (Object item : macList) {
                            maskedMacs.add(item instanceof String mac ? maskMac(mac) : item);
                        }
                        result.put(key, maskedMacs);
                    } else {
                        result.put(key, value);
                    }
                }
                case "zone", "source_zone", "destination_zone", "ingress_zone", "egress_zone" ->
                    result.put(key, value instanceof String zone ? topologyPseudonymizer.maskPolicyName("zone", zone) : value);
                // V184: an interface's virtual router and a route's table name the same routing instance: one "vrf"
                // pseudonym for both (as the IP search's route_table), so aiview can still relate them and neither
                // field leaks the raw name.
                case "routing_context", "table" ->
                    result.put(key, value instanceof String vrf ? topologyPseudonymizer.maskPolicyName("vrf", vrf) : value);
                case "interface_name", "ingress_interface_name", "egress_interface_name", "vendor_interface" ->
                    result.put(key, value instanceof String iface ? topologyPseudonymizer.maskPolicyName("interface", iface) : value);
                case "policy_name", "rule_name", "section_name" ->
                    result.put(key, value instanceof String r ? topologyPseudonymizer.maskPolicyName("rule", r) : value);
                case "matched_source_objects", "matched_destination_objects", "matched_service_objects" -> {
                    if (value instanceof List<?> objList) {
                        List<Object> maskedObjs = new ArrayList<>(objList.size());
                        for (Object item : objList) {
                            if (item instanceof String s && !"any".equalsIgnoreCase(s)) {
                                maskedObjs.add(topologyPseudonymizer.maskPolicyName("object", s));
                            } else {
                                maskedObjs.add(item);
                            }
                        }
                        result.put(key, maskedObjs);
                    } else {
                        result.put(key, value);
                    }
                }
                case "member_macs", "member_zones" -> {
                    if (value instanceof Map<?, ?> byMember) {
                        Map<String, Object> masked = new LinkedHashMap<>();
                        byMember.forEach((member, v) -> masked.put(String.valueOf(member), v instanceof String text
                                ? ("member_macs".equals(key) ? maskMac(text) : topologyPseudonymizer.maskPolicyName("zone", text))
                                : v));
                        result.put(key, masked);
                    } else {
                        result.put(key, value);
                    }
                }
                case "cluster_member_ref" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskClusterName(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "hostname", "subject_cn", "issuer_cn", "device", "point_label", "capture_name" -> {
                    if (value instanceof String s && "hostname".equals(key) && rowDeviceId != null) {
                        // A name equal to the row's own management address is a display fallback, not a hostname.
                        String ownAddress = map.get("management_ip") instanceof String a ? a
                                : map.get("address") instanceof String a2 ? a2 : null;
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef, rowDeviceId, ownAddress));
                    } else if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef));
                    } else {
                        result.put(key, maskObject(value, clusterRef));
                    }
                }
                case "domain", "qname", "sni" -> {
                    if (value instanceof String s && com.securityexpert.nexus.ui2.packetanalysis.privacy.PayloadMasker
                            .TOKEN.matcher(s).matches()) {
                        // Already a Packet Analysis payload token: masking it again would break "same host" links.
                        result.put(key, s);
                    } else if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDomainName(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "file" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef));
                    } else {
                        result.put(key, value);
                    }
                }
                case "prefilter_excluded", "prefilter_excluded_by_file" -> {
                    if (value instanceof Map<?, ?> byCapture) {
                        Map<String, Object> masked = new LinkedHashMap<>();
                        byCapture.forEach((capName, v) -> masked.put(
                                topologyPseudonymizer.maskDeviceName(String.valueOf(capName), clusterRef),
                                maskObject(v, clusterRef)));
                        result.put(key, masked);
                    } else {
                        result.put(key, maskObject(value, clusterRef));
                    }
                }
                case "folder" -> result.put(key, "[REDACTED_PATH]");
                case "path" -> {
                    if (map.containsKey("inventory") && map.containsKey("checkpoint")) {
                        result.put(key, "[REDACTED_PATH]");
                    } else {
                        result.put(key, maskObject(value, clusterRef));
                    }
                }
                case "prefix", "payload_hex" -> result.put(key, null);
                case "virtual_system", "member" -> {
                    if (value instanceof String s && "virtual_system".equals(key) && managedDeviceRow) {
                        result.put(key, topologyPseudonymizer.maskManagedDeviceName(s));
                    } else if (value instanceof String s && "member".equals(key) && !"grid".equals(s)
                            && (map.containsKey("expiry_date") || map.containsKey("expiration_status")
                                    || map.containsKey("days_remaining"))) {
                        // An Infoblox license row (grid summary, or its Lifecycle projection) names the grid member it
                        // is bound to: the same MD- name as the grid member row, so the two can be matched on screen.
                        result.put(key, topologyPseudonymizer.maskManagedDeviceName(s));
                    } else if (value instanceof String s) {
                        result.put(key, "member".equals(key) && ("grid".equals(s)
                                || map.containsKey("checkNo") && s.matches("Member [12]")) ? s
                                : topologyPseudonymizer.maskVirtualSystem(s, clusterRef));
                    } else {
                        result.put(key, value);
                    }
                }
                case "serial_number" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskSerial(s));
                    } else {
                        result.put(key, value);
                    }
                }
                case "affected_devices", "target_devices" -> {
                    if (value instanceof List<?> devList) {
                        List<String> maskedList = new ArrayList<>();
                        for (Object devItem : devList) {
                            if (devItem instanceof String devStr) {
                                maskedList.add(topologyPseudonymizer.maskDeviceName(devStr, clusterRef));
                            } else {
                                maskedList.add(String.valueOf(devItem));
                            }
                        }
                        result.put(key, maskedList);
                    } else if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskDeviceName(s, clusterRef));
                    } else {
                        result.put(key, value);
                    }
                }
                case "virtual_systems" -> {
                    if (managerListing && value instanceof String s) {
                        result.put(key, Arrays.stream(s.split(",\\s*")).filter(item -> !item.isBlank())
                                .map(topologyPseudonymizer::maskManagedDeviceName).collect(Collectors.joining(", ")));
                    } else if (managerListing && value instanceof List<?> listed) {
                        result.put(key, listed.stream().map(item -> item instanceof String name
                                ? topologyPseudonymizer.maskManagedDeviceName(name) : String.valueOf(item)).toList());
                    } else if (value instanceof String s) {
                        String masked = Arrays.stream(s.split(",\\s*"))
                                .filter(item -> !item.isBlank())
                                .map(vs -> topologyPseudonymizer.maskVirtualSystem(vs, clusterRef))
                                .collect(Collectors.joining(", "));
                        result.put(key, masked);
                    } else if (value instanceof List<?> vsList) {
                        List<String> maskedList = new ArrayList<>();
                        for (Object vsItem : vsList) {
                            if (vsItem instanceof String vsStr) {
                                maskedList.add(topologyPseudonymizer.maskVirtualSystem(vsStr, clusterRef));
                            } else {
                                maskedList.add(String.valueOf(vsItem));
                            }
                        }
                        result.put(key, maskedList);
                    } else {
                        result.put(key, value);
                    }
                }
                case "address", "management_ip", "network", "ip_addresses", "destination", "next_hop",
                        "gateway", "addresses", "secondary_addresses", "alternate_addresses", "ipv6_addresses",
                        "src", "dst", "client_ip", "server_ip", "client_cidrs", "source_cidr", "answers",
                        "observed_addresses", "matched_addresses", "interface_addresses",
                        "pre_nat_src", "pre_nat_dst", "post_nat_src", "post_nat_dst",
                        "translated_src", "translated_dst", "assumed_subnet" ->
                    result.put(key, maskAddressValue(value, clusterRef));
                // Path analysis A3 (PO record 2026-11-03 D-8): an override's free-text note is never shown to aiview. The
                // topology controller already leaves it out of a masked body; this is the second, independent barrier.
                case "override_note" -> result.put(key, null);
                // Path analysis A3: topology ids are keyed hashes under a per-snapshot secret (no address or name
                // inside), and the closed topology vocabularies are product facts, not identities.
                case "snapshot_id", "node_id", "segment_id", "attachment_id", "conflict_id", "override_id",
                        "peer_device_id", "active_member_device_id", "from_node_id", "to_node_id", "node_ids",
                        "member_device_ids", "node_kind", "segment_kind", "conflict_kind", "resolution", "link_kind",
                        "override_action", "reason_code", "override_status", "no_match_reason", "flags" ->
                    result.put(key, value);
                case "terminal_reason", "latest_job_terminal_reason", "peer_follow_reason", "observed_value", "observedValue", "message" -> {
                    if (value instanceof String s) {
                        result.put(key, topologyPseudonymizer.maskText(ipMasker.maskText(s)));
                    } else {
                        result.put(key, value);
                    }
                }
                case "device_id", "unitId", "unit_id", "clusterId", "cluster_id", "runId", "run_id",
                        "approvalId", "approval_id", "jobId", "job_id",
                        "case_id", "point_id", "capture_id", "interface_id",
                        "ingress_interface_id", "egress_interface_id",
                        "policy_id", "rule_id", "section_id", "nat_point_a", "nat_point_b" -> {
                    // Opaque internal UUIDs preserved for seamless frontend routing
                    result.put(key, value);
                }
                case "status", "inventory_status", "policy_status", "action", "proto", "side" ->
                    result.put(key, value);
                // Advisory matching (ADVISORY_MATCHING_CONTRACT.md section 8): an advisory row and a device verdict row hold public
                // vendor text and product versions, never identities. The IP masker would read a four-part version such as
                // 9.18.4.50 as an address, so these keys pass through, and only on rows that carry an advisory id (every
                // advisory row and every device verdict row of the advisory read interface does; no other payload has the key).
                case "title", "fixed_text", "version", "source_url" -> {
                    if (map.get("advisory_id") instanceof String advisoryId && advisoryId.matches("[a-z_]{1,20}:.{1,200}")) {
                        result.put(key, value);
                    } else {
                        result.put(key, maskObject(value, clusterRef));
                    }
                }
                default -> {
                    result.put(key, maskObject(value, clusterRef));
                }
            }
        }
        return result;
    }

    /**
     * A device row whose {@code virtual_systems} are the devices it manages, not virtual systems: an Infoblox grid
     * master, a FortiManager, a Symantec Management Center (the same vendors the device tree labels "Grid members",
     * "Managed FortiGates" and "Managed devices"), or any body that carries manager-listed {@code grid_members}.
     */
    static boolean isManagerListing(Map<?, ?> map) {
        Object vendor = map.get("vendor_hint");
        if ("infoblox".equals(vendor)) {
            return true;
        }
        if ("management_server".equals(map.get("role")) && ("fortinet".equals(vendor) || "bluecoat".equals(vendor))) {
            return true;
        }
        return map.get("grid_members") instanceof List<?> members && !members.isEmpty();
    }

    /** {@code aa:bb:cc:dd:ee:ff} to {@code aa:bb:cc:**:**:**}; anything else is withheld entirely. */
    static String maskMac(String mac) {
        return mac.matches("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}") ? mac.substring(0, 8).toLowerCase(java.util.Locale.ROOT) + ":**:**:**" : "[REDACTED_MAC]";
    }

    private Object maskAddressValue(Object value, String parentContextRef) {
        if (value instanceof String text) {
            // IPv6 has no relationship-preserving masker yet: fail closed, including mixed address lists.
            String masked = ipMasker.mask(text);
            if (!"[REDACTED_IP]".equals(masked) || text.contains(":")) {
                return masked;
            }
            String maskedText = ipMasker.maskText(text);
            return maskedText.equals(text) ? masked : maskedText;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(maskAddressValue(item, parentContextRef));
            }
            return result;
        }
        return maskObject(value, parentContextRef);
    }

    private String maskJobTargetName(JobEvent jobEvent) {
        String name = jobEvent.deviceName();
        String kind = jobEvent.targetKind();
        String jobType = jobEvent.jobType();
        String deviceId = jobEvent.targetDeviceId() != null && !jobEvent.targetDeviceId().isBlank()
                ? jobEvent.targetDeviceId().strip()
                : null;
        String clusterRef = jobEvent.clusterRef() != null && !jobEvent.clusterRef().isBlank()
                ? jobEvent.clusterRef().strip()
                : null;

        if ("fleet".equals(kind)) {
            return name;
        }
        if (name != null) {
            String trimmed = name.strip();
            if ("No target recorded".equals(trimmed) || trimmed.matches("Fleet \\(\\d+ devices?\\)")) {
                return trimmed;
            }
        }
        if ("cluster".equals(kind)) {
            String rawCluster = name != null && !name.isBlank() ? name : (clusterRef != null ? clusterRef : deviceId);
            return rawCluster != null ? topologyPseudonymizer.maskClusterName(rawCluster) : null;
        }
        if ("failover_unit".equals(kind)) {
            if (clusterRef != null) {
                topologyPseudonymizer.maskClusterName(clusterRef);
            }
            String rawUnit = name != null && !name.isBlank() ? name : (clusterRef != null ? clusterRef : deviceId);
            return rawUnit != null ? topologyPseudonymizer.maskVirtualSystem(rawUnit, clusterRef) : null;
        }
        if ("policy_container".equals(kind)) {
            String rawContainer = name != null && !name.isBlank() ? name : (clusterRef != null ? clusterRef : deviceId);
            return rawContainer != null ? topologyPseudonymizer.maskDomainName(rawContainer) : null;
        }
        if ("packet_analysis_case".equals(kind) || "packet_analysis_run".equals(jobType)) {
            String rawCase = name != null && !name.isBlank() ? name : (clusterRef != null ? clusterRef : deviceId);
            return rawCase != null ? topologyPseudonymizer.maskPolicyName("packet_case", rawCase) : null;
        }
        if ("policy_source".equals(kind)) {
            String fallbackId = deviceId != null ? deviceId : clusterRef;
            if (name != null && !name.isBlank()) {
                String masked = topologyPseudonymizer.maskDeviceName(name, null, deviceId);
                if (masked != null && !TopologyNamePseudonymizer.isGap(masked)) {
                    return masked;
                }
                if (fallbackId == null) {
                    return topologyPseudonymizer.maskUnknownHost("source:" + name.strip());
                }
            }
            // No hostname to mask: a keyed gap token for the opaque ID, never an invented FW- name.
            return fallbackId != null ? topologyPseudonymizer.maskUnknownHost(fallbackId) : null;
        }
        if (kind == null && jobType != null
                && com.securityexpert.nexus.ui2.service.audit.JobLogQueryService.FAILOVER_JOB_TYPES.contains(jobType)
                && (clusterRef != null || (name != null && name.startsWith("VSID ")))) {
            if (name != null && name.startsWith("VSID ")) {
                if (clusterRef != null) {
                    topologyPseudonymizer.maskClusterName(clusterRef);
                }
                return topologyPseudonymizer.maskVirtualSystem(name, clusterRef);
            }
            return topologyPseudonymizer.maskClusterName(clusterRef != null ? clusterRef : name);
        }
        if (kind == null && ("cp_policy_collect".equals(jobType) || "pan_policy_collect".equals(jobType))
                && deviceId == null && name != null && !name.isBlank()) {
            return topologyPseudonymizer.maskDomainName(name);
        }
        if (name != null && !name.isBlank()) {
            String masked = topologyPseudonymizer.maskDeviceName(name, clusterRef, deviceId);
            if (masked != null && !TopologyNamePseudonymizer.isGap(masked)) {
                return masked;
            }
            if (deviceId == null) {
                return topologyPseudonymizer.maskUnknownHost("device:" + name.strip());
            }
        }
        if (deviceId != null) {
            return topologyPseudonymizer.maskUnknownHost(deviceId);
        }
        return null;
    }

    private List<Object> maskList(List<?> list, String parentContextRef) {
        List<Object> result = new ArrayList<>(list.size());
        for (Object item : list) {
            result.add(maskObject(item, parentContextRef));
        }
        return result;
    }
}
