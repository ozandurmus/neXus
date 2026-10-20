package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.service.security.AdminWindowBypass;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;

@RestController
public class JobWindowController {
    private AdminWindowBypass adminWindowBypass;

    @Autowired(required = false)
    public void setAdminWindowBypass(AdminWindowBypass adminWindowBypass) { this.adminWindowBypass = adminWindowBypass; }

    @EventListener(ApplicationReadyEvent.class)
    public void logPolicy() { JobWindowPolicy.SYSTEM.logPolicy(); }

    /**
     * The shared window, the business hours and -- PO 2026-10-20 -- whether this session's manual device jobs pass the
     * window ({@code admin_window_bypass}: security_admin / operation_admin, never a replay viewer). The UI enables an
     * administrator's job buttons from it and asks for the acknowledgement when {@code business_hours.inside}.
     */
    @GetMapping("/api/v2/job-window")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        var policy = JobWindowPolicy.SYSTEM;
        var now = policy.now();
        var hours = policy.businessHours();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("open", policy.isOpen(now));
        body.put("zone", JobWindowPolicy.ZONE);
        body.put("window_minutes", policy.windowMinutes());
        body.put("server_time", now.toString());
        body.put("window_end", policy.slotStart(now).plusMinutes(policy.windowMinutes()).toInstant().toString());
        body.put("next_window_start", policy.nextStart(now).toString());
        body.put("business_hours", Map.of("start", hours.start().toString(), "end", hours.end().toString(),
                "inside", hours.inside(now)));
        body.put("admin_window_bypass", adminWindowBypass(request));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    private boolean adminWindowBypass(HttpServletRequest request) {
        if (adminWindowBypass == null || request == null) return false;
        if (Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE))) return false;
        Object actor = request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        return actor instanceof String fingerprint && adminWindowBypass.role(fingerprint).isPresent();
    }
}
