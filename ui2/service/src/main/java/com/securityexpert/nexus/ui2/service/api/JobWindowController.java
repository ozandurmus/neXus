package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@RestController
public class JobWindowController {
    @EventListener(ApplicationReadyEvent.class)
    public void logPolicy() { JobWindowPolicy.SYSTEM.logPolicy(); }

    @GetMapping("/api/v2/job-window")
    public ResponseEntity<Map<String, Object>> status() {
        var policy = JobWindowPolicy.SYSTEM;
        var now = policy.now();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("open", policy.isOpen(now), "zone", JobWindowPolicy.ZONE,
                "window_minutes", policy.windowMinutes(), "server_time", now.toString(),
                "window_end", policy.slotStart(now).plusMinutes(policy.windowMinutes()).toInstant().toString(),
                "next_window_start", policy.nextStart(now).toString()));
    }
}
