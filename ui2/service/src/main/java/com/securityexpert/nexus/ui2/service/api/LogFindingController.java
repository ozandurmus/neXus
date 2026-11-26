package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.logdetect.LogCatalogAdmin;
import com.securityexpert.nexus.ui2.service.logdetect.LogFindingReadService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

/**
 * Log detection engine P1 routes (LOG_DETECTION_ENGINE_DESIGN_2026_11_24.md section 9 CLI parity: {@code log-findings},
 * {@code log-finding}, {@code log-finding-ack}, {@code log-coverage}). Reads are {@code system_log_read} (aiview
 * included); the acknowledgement is {@code log_finding_acknowledge} and never aiview; the switch is
 * {@code log_detection_settings_write} (security_admin). Findings carry counts, device-local times, closed-vocabulary labels and pointers,
 * never a log line. Phase P2 adds the template catalog routes (design section 9 {@code log-catalog}, {@code log-catalog-diff},
 * {@code log-catalog-freeze}) under the same two settings actions: reading a catalog is {@code log_detection_settings_read},
 * building and freezing one is {@code log_detection_settings_write}; no new action id.
 */
@RestController
public final class LogFindingController {
    private final LogFindingReadService service;
    private final LogCatalogAdmin catalogs;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public LogFindingController(LogFindingReadService service, LogCatalogAdmin catalogs, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.catalogs = catalogs;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/log-findings")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(value = "device_id", required = false) String deviceId,
            @RequestParam(value = "severity", required = false) String severity,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return json(service.findings(deviceId, severity, state, days, limit));
    }

    @GetMapping("/api/v2/log-findings/chips")
    public ResponseEntity<Map<String, Object>> chips() {
        return json(service.chips());
    }

    @GetMapping("/api/v2/log-findings/coverage")
    public ResponseEntity<Map<String, Object>> coverage(@RequestParam(value = "device_id", required = false) String deviceId) {
        return json(service.coverage(deviceId));
    }

    @GetMapping("/api/v2/log-findings/budget")
    public ResponseEntity<Map<String, Object>> budget() {
        return json(service.budget());
    }

    @GetMapping("/api/v2/log-findings/{findingId}")
    public ResponseEntity<Map<String, Object>> one(@PathVariable String findingId) {
        return json(service.finding(findingId));
    }

    @PostMapping("/api/v2/log-findings/{findingId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledge(@PathVariable String findingId,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) {
            return error(new LogFindingReadService.Refusal(403, "LOG_FINDING_ACK_NOT_PERMITTED",
                    "Acknowledging a finding needs the Operation Admin or Security Admin role; the aiview persona never acknowledges."));
        }
        Object note = body == null ? null : body.get("note");
        return json(service.acknowledge(actor, findingId, note instanceof String s ? s : null));
    }

    @GetMapping("/api/v2/config/log-detection")
    public ResponseEntity<Map<String, Object>> settings() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.settings());
    }

    @PutMapping("/api/v2/config/log-detection")
    public ResponseEntity<Map<String, Object>> writeSettings(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !maySetSwitch(request, actor)) {
            return error(new LogFindingReadService.Refusal(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED",
                    "Changing the log detection switch needs the Security Admin role."));
        }
        return json(service.updateSettings(actor, body));
    }

    @GetMapping("/api/v2/config/log-detection/catalogs")
    public ResponseEntity<Map<String, Object>> catalogs() {
        return json(catalogs.list());
    }

    @GetMapping("/api/v2/config/log-detection/catalogs/{version}")
    public ResponseEntity<Map<String, Object>> catalogDiff(@PathVariable String version,
            @RequestParam(value = "from", required = false) String from) {
        return json(catalogs.diff(version, from));
    }

    @PostMapping("/api/v2/config/log-detection/catalogs")
    public ResponseEntity<Map<String, Object>> buildCatalog(@RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !maySetSwitch(request, actor)) {
            return error(new LogFindingReadService.Refusal(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED",
                    "Building a template catalog needs the Security Admin role."));
        }
        return json(catalogs.startBuild(actor, body));
    }

    @PostMapping("/api/v2/config/log-detection/catalogs/{version}/freeze")
    public ResponseEntity<Map<String, Object>> freezeCatalog(@PathVariable String version, HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || !maySetSwitch(request, actor)) {
            return error(new LogFindingReadService.Refusal(403, "LOG_DETECTION_SETTINGS_NOT_PERMITTED",
                    "Freezing a template catalog needs the Security Admin role."));
        }
        return json(catalogs.freeze(actor, version));
    }

    private boolean maySetSwitch(HttpServletRequest request, String actor) {
        if (PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.LOG_DETECTION_SETTINGS_WRITE).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static ResponseEntity<Map<String, Object>> json(LogFindingReadService.Answer answer) {
        if (!answer.isOk()) return error(answer.refusal());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answer.body());
    }

    private static ResponseEntity<Map<String, Object>> error(LogFindingReadService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        return ResponseEntity.status(HttpStatus.valueOf(refusal.status())).cacheControl(CacheControl.noStore())
                .contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
