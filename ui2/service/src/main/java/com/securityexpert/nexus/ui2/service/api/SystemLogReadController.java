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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;
import com.securityexpert.nexus.ui2.service.systemlog.SystemLogReadService;

/**
 * System-log programme phase B read routes (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 14: {@code syslog-sources},
 * {@code syslog-chunks}, {@code syslog-view}, {@code syslog-gaps}, {@code syslog-crash-files}, {@code syslog-hcp}). The
 * routes are {@code system_log_read} (operator and above; aiview included). The text route serves the masked projection
 * to everyone unless the caller explicitly asks for {@code unmasked=true} <em>and</em> holds
 * {@code system_log_read_unmasked} (operation_admin, security_admin) <em>and</em> is not a replay viewer (aiview is
 * always masked); an unmasked view writes a ledger row before any byte is decrypted.
 *
 * <p>The text route answers {@code text/plain} (the page) with the paging facts in {@code X-Syslog-*} headers, so a
 * command line can pipe it and the masking response advice (which works on objects) never sees it twice. Every other
 * route answers JSON.</p>
 *
 * <p>A GET never answers 409 for "does not apply": a management server while {@code include_management_servers} is off,
 * or a vendor outside Check Point and Palo Alto, is answered 200 with the route's shape empty and an explicit
 * {@code collection_state} ({@code DISABLED_ON_MANAGEMENT}, {@code VENDOR_UNSUPPORTED}); a bad id is 400, an unknown
 * device 404.</p>
 */
@RestController
public final class SystemLogReadController {
    private final SystemLogReadService service;
    private final ActionRegistry actions;
    private final RbacEvaluator rbac;

    public SystemLogReadController(SystemLogReadService service, ActionRegistry actions, RbacEvaluator rbac) {
        this.service = service;
        this.actions = actions;
        this.rbac = rbac;
    }

    @GetMapping("/api/v2/system-logs/{deviceId}/sources")
    public ResponseEntity<Map<String, Object>> sources(@PathVariable String deviceId, HttpServletRequest request) {
        return json(service.sources(deviceId, mayViewUnmasked(request)));
    }

    @GetMapping("/api/v2/system-logs/{deviceId}/chunks")
    public ResponseEntity<Map<String, Object>> chunks(@PathVariable String deviceId,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "day", required = false) String day,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return json(service.chunks(deviceId, source, day, limit));
    }

    @GetMapping("/api/v2/system-logs/chunks/{chunkId}/text")
    public ResponseEntity<?> text(@PathVariable String chunkId,
            @RequestParam(value = "offset", defaultValue = "0") long offset,
            @RequestParam(value = "limit", defaultValue = "0") int limit,
            @RequestParam(value = "unmasked", defaultValue = "false") boolean unmasked, HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null) {
            return error(new SystemLogReadService.Refusal(403, "SYSTEM_LOG_READ_NOT_PERMITTED", "No authenticated session."));
        }
        var result = service.text(new SystemLogReadService.TextRequest(chunkId, offset, limit, unmasked,
                mayViewUnmasked(request), actor));
        if (!result.isOk()) return error(result.refusal());
        var page = result.page();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Syslog-Masked", String.valueOf(page.masked()))
                .header("X-Syslog-Source", page.sourceKey())
                .header("X-Syslog-Offset", String.valueOf(page.offset()))
                .header("X-Syslog-Next-Offset", String.valueOf(page.nextOffset()))
                .header("X-Syslog-Eof", String.valueOf(page.eof()))
                .header("X-Syslog-Lines", String.valueOf(page.lines()))
                .header("X-Syslog-Chunk-Bytes", String.valueOf(page.chunkBytes()))
                .header("X-Syslog-Page-Limit", String.valueOf(page.pageLimit()))
                .body(page.text());
    }

    @GetMapping("/api/v2/system-logs/gaps")
    public ResponseEntity<Map<String, Object>> gaps(@RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return json(service.gaps(deviceId, limit));
    }

    @GetMapping("/api/v2/system-logs/crash-files")
    public ResponseEntity<Map<String, Object>> crashFiles(@RequestParam(value = "deviceId", required = false) String deviceId,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        return json(service.crashFiles(deviceId, limit, Instant.now()));
    }

    @GetMapping("/api/v2/system-logs/{deviceId}/hcp")
    public ResponseEntity<Map<String, Object>> hcp(@PathVariable String deviceId) {
        return json(service.hcp(deviceId));
    }

    /** The caller may ask for unmasked text: holds system_log_read_unmasked and is not the aiview persona. */
    boolean mayViewUnmasked(HttpServletRequest request) {
        String actor = (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)) return false;
        return rbac.evaluateAny(actor, actions.find(ActionRegistry.SYSTEM_LOG_READ_UNMASKED).orElseThrow().requiredRoleTokens(),
                Instant.now()).outcome() == AuthzOutcome.PERMITTED;
    }

    private static ResponseEntity<Map<String, Object>> json(SystemLogReadService.Read read) {
        if (!read.isOk()) return error(read.refusal());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read.body());
    }

    private static <T> ResponseEntity<T> error(SystemLogReadService.Refusal refusal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", refusal.code());
        body.put("error", refusal.code());
        body.put("message", refusal.message());
        @SuppressWarnings("unchecked")
        ResponseEntity<T> response = (ResponseEntity<T>) ResponseEntity.status(HttpStatus.valueOf(refusal.status()))
                .cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_JSON).body(body);
        return response;
    }
}
