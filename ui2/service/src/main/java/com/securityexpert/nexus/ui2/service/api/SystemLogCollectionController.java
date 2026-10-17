package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.diagnostic.SystemLogCollectionService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * System-log programme phase B manual start (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14,
 * {@code syslog-collect-start [deviceId]}): admits the collection job of one device, or of every supported device when no
 * device id is given, inside the shared job windows (outside a window: 409 {@code OUTSIDE_JOB_WINDOW}). Roles
 * operation_admin and security_admin; the action is {@code system_log_collect_start}.
 */
@RestController
public final class SystemLogCollectionController {
    private static final Pattern DEVICE_ID = Pattern.compile("[A-Za-z0-9_.:-]{1,128}");
    private final SystemLogCollectionService service;

    public SystemLogCollectionController(SystemLogCollectionService service) {
        this.service = service;
    }

    @PostMapping("/api/v2/system-logs/collect")
    public ResponseEntity<Map<String, Object>> collect(@RequestBody(required = false) Map<String, String> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("code", "SYSTEM_LOG_COLLECT_NOT_PERMITTED"));
        }
        String deviceId = body == null ? null : body.get("device_id");
        if (deviceId != null && !deviceId.isBlank() && !DEVICE_ID.matcher(deviceId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "DEVICE_ID_INVALID"));
        }
        var outcome = service.start(Optional.ofNullable(deviceId).filter(id -> !id.isBlank()), actor);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("targets", outcome.targets());
        response.put("admitted", outcome.admitted());
        response.put("deduplicated", outcome.deduplicated());
        response.put("refused", outcome.refused());
        response.put("refusal_codes", outcome.refusalCodes());
        boolean refusedAll = deviceId != null && !deviceId.isBlank() && outcome.admitted() + outcome.deduplicated() == 0;
        return ResponseEntity.status(refusedAll ? HttpStatus.CONFLICT : HttpStatus.ACCEPTED).body(response);
    }
}
