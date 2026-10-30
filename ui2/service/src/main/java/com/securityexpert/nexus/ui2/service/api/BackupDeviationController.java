package com.securityexpert.nexus.ui2.service.api;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService;
import com.securityexpert.nexus.ui2.service.device.configuration.BackupSizeAnomalyService;
import com.securityexpert.nexus.ui2.service.device.configuration.BackupDeviationService.DeviceClass;
import com.securityexpert.nexus.ui2.service.device.configuration.MajorDeviationRules;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code GET /api/v2/backups/deviations} (gate {@code device_backup_read}, unchanged): the Backups screen's "Active
 * major deviations" from stored configuration runs (docs/design/BACKUP_MAJOR_DEVIATIONS_2026_10_23.md). Each row's
 * {@code hostname} sits next to its {@code device_id} and {@code cluster_member_ref}, so the replay viewer gets the
 * Devices screen's own pseudonym through {@code PrivacyMaskingResponseBodyAdvice}; no context name (a vsys or VDOM
 * name) and no configuration value is in the body.
 *
 * <p>V180 (PO decision 2026-10-30, docs/design/BACKUP_SIZE_ANOMALY_RETENTION.md): the body also carries
 * {@code size_anomalies}, one row per device with an open backup size anomaly (sizes and a ratio only), and
 * {@code can_accept_size}; {@code POST /api/v2/backups/deviations/size-anomalies/{deviceId}/accept} with a reason
 * (security_admin, route action {@code backup_size_anomaly_accept}) accepts the new size.</p>
 */
@RestController
public final class BackupDeviationController {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ISO_INSTANT;

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9-]{8,64}");

    private final BackupDeviationService service;
    private final BackupSizeAnomalyService sizeAnomalies;

    public BackupDeviationController(BackupDeviationService service, BackupSizeAnomalyService sizeAnomalies) {
        this.service = Objects.requireNonNull(service, "service");
        this.sizeAnomalies = Objects.requireNonNull(sizeAnomalies, "sizeAnomalies");
    }

    @GetMapping("/api/v2/backups/deviations")
    public ResponseEntity<Map<String, Object>> listDeviations(HttpServletRequest request) {
        Map<String, Object> body = toBody(service.evaluate());
        boolean canAccept = !com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)
                && sizeAnomalies.mayAccept(actor(request));
        body.putAll(sizeAnomalyBody(sizeAnomalies.open(), canAccept));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    /**
     * Accept a device's new backup size: resolves its open size anomaly (every open copy) with the reason, which
     * releases the last-good pin. The body carries the reason only; the actor is the session.
     */
    @PostMapping("/api/v2/backups/deviations/size-anomalies/{deviceId}/accept")
    public ResponseEntity<Map<String, Object>> acceptSize(@PathVariable String deviceId,
            @RequestBody(required = false) Map<String, Object> request, HttpServletRequest servletRequest) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (deviceId == null || !ID.matcher(deviceId).matches()) {
            body.put("error", "INVALID_DEVICE_ID");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        if (request != null && !java.util.Set.of("reason").containsAll(request.keySet())) {
            body.put("error", "INVALID_REQUEST");
            body.put("reason", "only a reason may be supplied");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Object reason = request == null ? null : request.get("reason");
        if (reason != null && !(reason instanceof String)) {
            body.put("error", "REASON_REQUIRED");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        String actor = actor(servletRequest);
        if (actor == null || actor.isBlank()
                || com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice.isReplayViewer(servletRequest)) {
            body.put("error", "WRONG_ROLE");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
        }
        try {
            int resolved = sizeAnomalies.accept(deviceId, (String) reason, actor);
            body.put("device_id", deviceId);
            body.put("resolved", resolved);
            body.put("status", "ACCEPTED");
            return ResponseEntity.ok(body);
        } catch (BackupSizeAnomalyService.Refusal refusal) {
            body.put("error", refusal.code());
            HttpStatus status = switch (refusal.code()) {
                case "WRONG_ROLE" -> HttpStatus.FORBIDDEN;
                case "NO_OPEN_SIZE_ANOMALY" -> HttpStatus.NOT_FOUND;
                default -> HttpStatus.BAD_REQUEST;
            };
            return ResponseEntity.status(status).body(body);
        }
    }

    private static String actor(HttpServletRequest request) {
        return request == null ? null : (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    /** The size-anomaly part of the body: sizes, ratio and opaque ids; the name next to the device id for masking. */
    public static Map<String, Object> sizeAnomalyBody(List<BackupSizeAnomalyService.View> open, boolean canAccept) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size_anomalies", open.stream().map(view -> {
            var a = view.anomaly();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", a.deviceId());
            item.put("hostname", view.hostname().orElse(null));
            item.put("cluster_member_ref", view.clusterMemberRef().orElse(null));
            item.put("deviation_id", a.deviationId());
            item.put("artefact_id", a.artefactId());
            item.put("last_good_artefact_id", a.lastGoodArtefactId());
            item.put("direction", a.direction());
            item.put("plaintext_bytes", a.newBytes());
            item.put("median_bytes", a.medianBytes());
            item.put("ratio", a.ratio());
            item.put("summary", a.summary());
            item.put("detected_at", TIMESTAMP.format(a.detectedAt()));
            item.put("open_copies", a.openCopies());
            return item;
        }).toList());
        body.put("can_accept_size", canAccept);
        body.put("size_role_note", canAccept ? null : BackupSizeAnomalyService.ROLE_NOTE);
        return body;
    }

    public static Map<String, Object> toBody(BackupDeviationService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("window_days", result.window().toDays());
        body.put("evaluated_at", TIMESTAMP.format(result.evaluatedAt()));
        // The card's number: devices with at least one valid comparison in the window (not a run count).
        body.put("total_deviations_checked", result.compared().size());
        body.put("devices_known", result.devicesKnown());
        Map<String, Object> notCompared = new LinkedHashMap<>();
        for (BackupDeviationService.NotComparedReason reason : BackupDeviationService.NotComparedReason.values()) {
            notCompared.put(reason.wire(), result.notCompared().getOrDefault(reason, 0));
        }
        body.put("not_compared", notCompared);
        body.put("major_devices", result.count(DeviceClass.MAJOR));
        body.put("minor_devices", result.count(DeviceClass.MINOR));
        body.put("unchanged_devices", result.count(DeviceClass.NONE));
        body.put("active_major_deviations", result.activeMajor().stream().map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", row.deviceId());
            item.put("hostname", row.hostname().orElse(null));
            item.put("cluster_member_ref", row.clusterMemberRef().orElse(null));
            item.put("vendor", row.vendor());
            item.put("category", row.category().wire());
            item.put("changed_at", TIMESTAMP.format(row.changedAt()));
            item.put("count", row.count());
            item.put("sections", List.copyOf(row.sections()));
            return item;
        }).toList());
        body.put("devices", result.compared().stream().map(device -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("device_id", device.deviceId());
            item.put("vendor", device.vendor());
            item.put("deviation", device.deviceClass().name());
            item.put("last_compared_at", TIMESTAMP.format(device.lastComparedAt()));
            item.put("categories", device.categories().stream().map(MajorDeviationRules.Category::wire).toList());
            return item;
        }).toList());
        body.put("coverage", new LinkedHashMap<>(new java.util.TreeMap<>(MajorDeviationRules.COVERAGE)));
        return body;
    }
}
