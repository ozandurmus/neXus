package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.notification.NotificationSettings;
import com.securityexpert.nexus.ui2.service.notification.NotificationSettingsStore;
import com.securityexpert.nexus.ui2.service.notification.SmtpRelaySender;
import com.securityexpert.nexus.ui2.service.notification.SyslogSender;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Administration › Notifications: remote logging (syslog) and the SMTP relay, their triggers, and a test send
 * for each. Read and write are {@code role:security_admin}; the route map carries the actions.
 */
@RestController
public final class NotificationSettingsController {

    private final NotificationSettingsStore store;

    public NotificationSettingsController(NotificationSettingsStore store) {
        this.store = store;
    }

    @GetMapping("/api/v2/config/notifications")
    public NotificationSettings read() {
        return store.read();
    }

    @PutMapping("/api/v2/config/notifications")
    public ResponseEntity<Object> save(@RequestBody NotificationSettings settings, HttpServletRequest request) {
        List<String> problems = settings.problems();
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_SETTINGS", "problems", problems));
        }
        store.save(settings, (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE));
        return ResponseEntity.ok(store.read());
    }

    @PostMapping("/api/v2/config/notifications/test-syslog")
    public ResponseEntity<Map<String, Object>> testSyslog() {
        NotificationSettings settings = store.read();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            SyslogSender.send(settings, SyslogSender.Severity.NOTICE, "TEST", "neXus remote logging test message");
            body.put("sent", true);
            body.put("detail", "sent over " + settings.syslogProtocol().toUpperCase() + " to the configured host");
        } catch (java.io.IOException | RuntimeException e) {
            body.put("sent", false);
            body.put("detail", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }

    @PostMapping("/api/v2/config/notifications/test-mail")
    public ResponseEntity<Map<String, Object>> testMail() {
        NotificationSettings settings = store.read();
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            SmtpRelaySender.send(settings, "neXus notification test",
                    "This is a test message from neXus. The SMTP relay settings are working.\n");
            body.put("sent", true);
            body.put("detail", "accepted by the relay for " + settings.recipients().size() + " recipient(s)");
        } catch (java.io.IOException | RuntimeException e) {
            body.put("sent", false);
            body.put("detail", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }
}
