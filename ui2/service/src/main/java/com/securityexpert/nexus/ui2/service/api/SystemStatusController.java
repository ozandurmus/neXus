package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.system.SystemStatusService;

/** Administration › System: pods (a read-only top) and storage use. Both are {@code system_status_read}. */
@RestController
public final class SystemStatusController {

    private final SystemStatusService systemStatusService;

    public SystemStatusController(SystemStatusService systemStatusService) {
        this.systemStatusService = systemStatusService;
    }

    @GetMapping("/api/v2/system/pods")
    public Map<String, Object> pods() {
        return systemStatusService.pods();
    }

    @GetMapping("/api/v2/system/storage")
    public Map<String, Object> storage() {
        return systemStatusService.storage();
    }
}
