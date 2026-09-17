package com.securityexpert.nexus.ui2.service.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.audit.AuditLogQueryService;

/** Global, bounded audit summary. Route authorization is enforced by {@code GateChainInterceptor}. */
@RestController
public final class AuditLogController {

    private final AuditLogQueryService auditLogQueryService;

    public AuditLogController(AuditLogQueryService auditLogQueryService) {
        this.auditLogQueryService = auditLogQueryService;
    }

    @GetMapping("/audit-log")
    public ResponseEntity<Map<String, List<AuditLogQueryService.AuditEvent>>> recent() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(Map.of("events", auditLogQueryService.recent()));
    }
}
