package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.systemlog.SystemHealthService;

/**
 * Operations &gt; System health ({@code syslog-health}; docs/design/SYSTEM_HEALTH_SCREEN_2026_10_20.md): one read-only
 * route under the system-log read action ({@code system_log_read}: operator and above, aiview included). The body is
 * counters, state words and opaque ids; the device names it carries ({@code hostname}, {@code cluster_member_ref})
 * go through the privacy masking advice for the aiview persona like every other JSON device row.
 */
@RestController
public final class SystemHealthController {
    private final SystemHealthService service;

    public SystemHealthController(SystemHealthService service) {
        this.service = service;
    }

    @GetMapping("/api/v2/system-logs/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.health(Instant.now()));
    }
}
