package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryHaFact;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRun;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JobRow;
import com.securityexpert.nexus.ui2.service.device.inventory.ClusterInventoryMerger;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryCollectService;
import com.securityexpert.nexus.ui2.service.device.inventory.InventoryQueryService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * {@code GET /devices/{id}/inventory}, {@code GET /clusters/{cluster_
 * member_ref}/inventory} and {@code POST /devices/{id}/inventory/collect}
 * (WORKER.md "Routes"). Every JSON key below is exactly what the READ
 * CONTRACT shared with NXS-LOCAL-0159 spells; this class performs no
 * authorization check of its own ({@link GateChainInterceptor} already ran
 * by the time a handler method is invoked).
 */
@RestController
public final class InventoryController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    public record CollectRequest(@JsonProperty("nonce") String nonce) {
    }

    private final InventoryQueryService inventoryQueryService;
    private final InventoryCollectService inventoryCollectService;

    public InventoryController(InventoryQueryService inventoryQueryService,
            InventoryCollectService inventoryCollectService) {
        this.inventoryQueryService = inventoryQueryService;
        this.inventoryCollectService = inventoryCollectService;
    }

    @GetMapping("/devices/{deviceId}/inventory")
    public ResponseEntity<Map<String, Object>> getDeviceInventory(@PathVariable String deviceId) {
        InventoryQueryService.DeviceInventoryOutcome outcome = inventoryQueryService.deviceInventory(deviceId);
        if (outcome instanceof InventoryQueryService.DeviceInventoryOutcome.NotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundBody());
        }
        InventoryQueryService.DeviceInventoryOutcome.Found found =
                (InventoryQueryService.DeviceInventoryOutcome.Found) outcome;
        Optional<InventoryRun> run = found.run();
        Optional<JobRow> job = run.flatMap(inventoryQueryService::jobFor);

        List<String> deviceVsList = found.virtualSystems()
                .map(s -> java.util.Arrays.stream(s.split(",\\s*")).filter(v -> !v.isBlank()).distinct().sorted().toList())
                .orElse(List.of());
        Map<String, String> vsNamesByContext = run.map(r -> resolveVsNames(
                r.contexts().stream().map(InventoryContext::context).toList(),
                deviceVsList))
                .orElse(Map.of());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", found.deviceId());
        body.put("collected_at", run.map(r -> TIMESTAMP.format(r.collectedAt())).orElse(null));
        body.put("job", job.map(InventoryController::toJobBody).orElse(null));
        body.put("virtual_systems", found.virtualSystems().orElse(null));
        Map<String, InventoryHaFact> haByContext = run.map(InventoryController::haFactsByContext).orElse(Map.of());
        body.put("contexts", run.map(r -> r.contexts().stream()
                .map(context -> toContextBody(context, haByContext.get(context.context()), vsNamesByContext.get(context.context())))
                .toList())
                .orElse(List.of()));
        // V72: an Infoblox Grid Manager's members as facts; the member name goes out under "virtual_system" so aiview sees
        // the same pseudonym the Devices tree shows, and the VIP under "address" so it is masked as an address.
        body.put("grid_members", run.map(r -> r.gridMembers().stream().map(InventoryController::toGridMemberBody).toList()).orElse(List.of()));
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> toGridMemberBody(GridMember m) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("virtual_system", m.hostName());
        body.put("address", m.vipAddress().orElse(null));
        body.put("platform", m.platform().orElse(null));
        body.put("hardware_type", m.hardwareType().orElse(null));
        body.put("hypervisor", m.hypervisor().orElse(null));
        body.put("grid_master", m.gridMaster());
        body.put("master_candidate", m.masterCandidate());
        body.put("ha_enabled", m.haEnabled());
        body.put("ha_status", m.haStatus().orElse(null));
        body.put("node_status", m.nodeStatus().orElse(null));
        body.put("replication", m.replication().orElse(null));
        body.put("disk_percent", m.diskPercent().orElse(null));
        body.put("memory_percent", m.memoryPercent().orElse(null));
        body.put("cpu_percent", m.cpuPercent().orElse(null));
        body.put("db_percent", m.dbPercent().orElse(null));
        body.put("services", m.services().stream().map(sv -> Map.of("service", sv.service(), "status", sv.status())).toList());
        return body;
    }

    @GetMapping("/clusters/{clusterMemberRef}/inventory")
    public ResponseEntity<Map<String, Object>> getClusterInventory(@PathVariable String clusterMemberRef) {
        InventoryQueryService.ClusterInventoryOutcome outcome = inventoryQueryService.clusterInventory(clusterMemberRef);
        if (outcome instanceof InventoryQueryService.ClusterInventoryOutcome.NotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundBody());
        }
        InventoryQueryService.ClusterInventoryOutcome.Found found =
                (InventoryQueryService.ClusterInventoryOutcome.Found) outcome;

        List<String> clusterVsList = found.members().stream()
                .map(m -> m.virtualSystems().orElse(""))
                .filter(s -> !s.isBlank())
                .flatMap(s -> java.util.Arrays.stream(s.split(",\\s*")))
                .distinct()
                .sorted()
                .toList();

        Map<String, String> vsNamesByContext = resolveVsNames(
                found.contexts().stream().map(ClusterInventoryMerger.MergedContext::context).toList(),
                clusterVsList);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster_member_ref", found.clusterMemberRef());
        body.put("members", found.members().stream().map(InventoryController::toMemberBody).toList());
        body.put("contexts", found.contexts().stream()
                .map(ctx -> toMergedContextBody(ctx, vsNamesByContext.get(ctx.context())))
                .toList());
        body.put("virtual_systems", clusterVsList);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/devices/{deviceId}/inventory/collect")
    public ResponseEntity<Map<String, Object>> collect(@PathVariable String deviceId,
            @RequestBody(required = false) CollectRequest request, HttpServletRequest servletRequest) {
        String actorFingerprint = actingUser(servletRequest);
        Optional<String> nonce = request == null ? Optional.empty() : Optional.ofNullable(request.nonce());
        InventoryCollectService.Outcome outcome =
                inventoryCollectService.requestCollect(deviceId, actorFingerprint, nonce);
        return switch (outcome) {
            case InventoryCollectService.Outcome.Admitted admitted -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_id", admitted.jobId());
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
            }
            case InventoryCollectService.Outcome.DeviceNotFound notFound -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", "DEVICE_NOT_FOUND");
                body.put("reason", "no device row for device_id=" + deviceId);
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case InventoryCollectService.Outcome.AdmissionRefused refused -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("error", "ADMISSION_REFUSED");
                body.put("code", refused.code());
                body.put("reason", refused.reason());
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    @PostMapping("/devices/inventory/collect-all")
    public ResponseEntity<Map<String, Object>> collectAll(HttpServletRequest servletRequest) {
        InventoryCollectService.BulkOutcome outcome = inventoryCollectService.requestCollectAll(actingUser(servletRequest));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enrolled_devices", outcome.enrolledDevices());
        body.put("admitted", outcome.admitted());
        body.put("refused", outcome.refused());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    private static String actingUser(HttpServletRequest servletRequest) {
        return (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Map<String, Object> notFoundBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "NOT_FOUND");
        return body;
    }

    private static Map<String, Object> toJobBody(JobRow jobRow) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("job_id", jobRow.jobId());
        body.put("state", jobRow.state());
        body.put("outcome", jobRow.outcome());
        body.put("terminal_reason", jobRow.terminalReason());
        return body;
    }

    private static Map<String, Object> toMemberBody(DeviceSummaryRecord member) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", member.deviceId());
        body.put("hostname", member.observedHostname().orElse(null));
        body.put("latest_job_state", member.latestJobState().orElse(null));
        body.put("latest_job_type", member.latestJobType().orElse(null));
        body.put("latest_job_terminal_reason", member.latestJobTerminalReason().orElse(null));
        body.put("virtual_systems", member.virtualSystems().orElse(null));
        return body;
    }

    private static Map<String, Object> toAddressBody(InventoryAddress address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("address", address.address());
        body.put("family", address.family());
        body.put("role", address.role());
        return body;
    }

    private static Map<String, Object> toInterfaceBody(InventoryInterface iface) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", iface.name());
        body.put("parent", iface.parent().orElse(null));
        body.put("kind", iface.kind());
        body.put("state", iface.state());
        body.put("vlan_id", iface.vlanId().orElse(null));
        body.put("addresses", iface.addresses().stream().map(InventoryController::toAddressBody).toList());
        return body;
    }

    private static Map<String, Object> toRouteBody(InventoryRoute route) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", route.destination());
        body.put("next_hop", route.nextHop().orElse(null));
        body.put("interface", route.interfaceName().orElse(null));
        body.put("protocol", route.protocol());
        body.put("table", route.routeTable().orElse(null));
        return body;
    }

    private static Map<String, InventoryHaFact> haFactsByContext(InventoryRun run) {
        Map<String, InventoryHaFact> byContext = new LinkedHashMap<>();
        for (InventoryHaFact fact : run.haFacts()) {
            byContext.put(fact.context(), fact);
        }
        return byContext;
    }

    private static Map<String, Object> toContextBody(InventoryContext context, InventoryHaFact haFact) {
        return toContextBody(context, haFact, null);
    }

    /** {@code ha}: null when this run recorded no HA fact for this context (a non-HA standalone read, or an older run). */
    private static Map<String, Object> toContextBody(InventoryContext context, InventoryHaFact haFact, String vsName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context.context());
        if (vsName != null && !vsName.isBlank()) {
            body.put("vs_name", vsName);
        }
        body.put("interfaces", context.interfaces().stream().map(InventoryController::toInterfaceBody).toList());
        body.put("routes", context.routes().stream().map(InventoryController::toRouteBody).toList());
        body.put("ha", haFact == null ? null : toHaBody(haFact));
        return body;
    }

    private static Map<String, Object> toHaBody(InventoryHaFact fact) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("role", fact.role());
        body.put("cluster_mode", fact.clusterMode().orElse(null));
        return body;
    }

    private static Object toPresenceBody(ClusterInventoryMerger.Presence presence) {
        return switch (presence) {
            case ClusterInventoryMerger.Presence.All all -> "all";
            case ClusterInventoryMerger.Presence.Members members -> members.deviceIds();
        };
    }

    private static Map<String, Object> toDifferenceBody(ClusterInventoryMerger.Difference difference) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", difference.deviceId());
        body.put("field", difference.field());
        body.put("value", difference.value());
        return body;
    }

    private static Map<String, Object> toMergedInterfaceBody(ClusterInventoryMerger.MergedInterface iface) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", iface.name());
        body.put("kind", iface.kind());
        body.put("addresses", iface.addresses().stream().map(InventoryController::toAddressBody).toList());
        body.put("presence", toPresenceBody(iface.presence()));
        body.put("differences", iface.differences().stream().map(InventoryController::toDifferenceBody).toList());

        Map<String, Object> memberAddrs = new LinkedHashMap<>();
        for (Map.Entry<String, List<InventoryAddress>> entry : iface.memberAddresses().entrySet()) {
            memberAddrs.put(entry.getKey(), entry.getValue().stream().map(InventoryController::toAddressBody).toList());
        }
        body.put("member_addresses", memberAddrs);
        body.put("member_states", iface.memberStates());
        return body;
    }

    private static Map<String, Object> toMergedRouteBody(ClusterInventoryMerger.MergedRoute route) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("destination", route.destination());
        body.put("next_hop", route.nextHop().orElse(null));
        body.put("interface", route.interfaceName().orElse(null));
        body.put("protocol", route.protocol());
        body.put("presence", toPresenceBody(route.presence()));
        body.put("differences", route.differences().stream().map(InventoryController::toDifferenceBody).toList());
        return body;
    }

    private static Map<String, Object> toMergedContextBody(ClusterInventoryMerger.MergedContext context) {
        return toMergedContextBody(context, null);
    }

    private static Map<String, Object> toMergedContextBody(ClusterInventoryMerger.MergedContext context, String vsName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context.context());
        if (vsName != null && !vsName.isBlank()) {
            body.put("vs_name", vsName);
        }
        body.put("interfaces", context.interfaces().stream().map(InventoryController::toMergedInterfaceBody).toList());
        body.put("routes", context.routes().stream().map(InventoryController::toMergedRouteBody).toList());
        return body;
    }

    static Map<String, String> resolveVsNames(List<String> contexts, List<String> vsList) {
        Map<String, String> result = new LinkedHashMap<>();
        if (vsList == null || vsList.isEmpty()) {
            return result;
        }
        for (String ctx : contexts) {
            if ("physical".equalsIgnoreCase(ctx) || "0".equals(ctx)) {
                continue;
            }
            for (String vs : vsList) {
                if (ctx.equalsIgnoreCase(vs)) {
                    result.put(ctx, vs);
                    break;
                }
                String lowerVs = vs.toLowerCase();
                String lowerCtx = ctx.toLowerCase();
                String digitsOnlyCtx = ctx.replaceAll("\\D+", "");
                if (lowerVs.contains("(" + lowerCtx + ")")
                        || lowerVs.contains("(vsid " + lowerCtx + ")")
                        || lowerVs.contains("(vsys " + lowerCtx + ")")
                        || lowerVs.contains("(vsys" + lowerCtx + ")")
                        || (!digitsOnlyCtx.isEmpty() && (lowerVs.contains("(vsid " + digitsOnlyCtx + ")")
                                || lowerVs.contains("(vsys" + digitsOnlyCtx + ")")
                                || lowerVs.contains("(vsys " + digitsOnlyCtx + ")")
                                || lowerVs.contains("(" + digitsOnlyCtx + ")")))) {
                    result.put(ctx, vs);
                    break;
                }
            }
        }
        List<String> numericContexts = contexts.stream()
                .filter(c -> !"physical".equalsIgnoreCase(c) && !"0".equals(c) && !result.containsKey(c))
                .sorted(java.util.Comparator.comparingInt(c -> {
                    try {
                        return Integer.parseInt(c.replaceAll("\\D+", ""));
                    } catch (NumberFormatException e) {
                        return Integer.MAX_VALUE;
                    }
                }))
                .toList();

        List<String> unmappedVs = vsList.stream()
                .filter(vs -> !result.containsValue(vs))
                .toList();

        for (int i = 0; i < numericContexts.size() && i < unmappedVs.size(); i++) {
            result.put(numericContexts.get(i), unmappedVs.get(i));
        }
        return result;
    }
}
