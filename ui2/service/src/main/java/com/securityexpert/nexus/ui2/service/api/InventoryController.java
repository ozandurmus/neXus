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
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
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

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_id", found.deviceId());
        body.put("collected_at", run.map(r -> TIMESTAMP.format(r.collectedAt())).orElse(null));
        body.put("job", job.map(InventoryController::toJobBody).orElse(null));
        body.put("contexts", run.map(r -> r.contexts().stream().map(InventoryController::toContextBody).toList())
                .orElse(List.of()));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/clusters/{clusterMemberRef}/inventory")
    public ResponseEntity<Map<String, Object>> getClusterInventory(@PathVariable String clusterMemberRef) {
        InventoryQueryService.ClusterInventoryOutcome outcome = inventoryQueryService.clusterInventory(clusterMemberRef);
        if (outcome instanceof InventoryQueryService.ClusterInventoryOutcome.NotFound) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundBody());
        }
        InventoryQueryService.ClusterInventoryOutcome.Found found =
                (InventoryQueryService.ClusterInventoryOutcome.Found) outcome;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cluster_member_ref", found.clusterMemberRef());
        body.put("members", found.members().stream().map(InventoryController::toMemberBody).toList());
        body.put("contexts", found.contexts().stream().map(InventoryController::toMergedContextBody).toList());
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

    private static Map<String, Object> toContextBody(InventoryContext context) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context.context());
        body.put("interfaces", context.interfaces().stream().map(InventoryController::toInterfaceBody).toList());
        body.put("routes", context.routes().stream().map(InventoryController::toRouteBody).toList());
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("context", context.context());
        body.put("interfaces", context.interfaces().stream().map(InventoryController::toMergedInterfaceBody).toList());
        body.put("routes", context.routes().stream().map(InventoryController::toMergedRouteBody).toList());
        return body;
    }
}
