package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.ArrayList;
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
import com.securityexpert.nexus.ui2.service.notification.NotificationRoute;
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
        List<String> problems = problems(settings);
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

    public record TestMailRequest(String type, NotificationSettings settings) {}

    private static List<String> problems(NotificationSettings settings) {
        if (settings == null) return List.of("notification settings are missing");
        List<String> problems = new ArrayList<>(settings.problems());
        if (settings.routes() == null || settings.routes().isEmpty()) {
            problems.add("all notification types must be provided once");
        }
        return problems;
    }

    @PostMapping("/api/v2/config/notifications/test-mail")
    public ResponseEntity<Map<String, Object>> testMail(@RequestBody(required = false) TestMailRequest request) {
        NotificationSettings settings = request != null && request.settings() != null ? request.settings() : store.read();
        String type = request == null ? null : request.type();
        if (type != null && !NotificationRoute.TYPES.contains(type)) {
            return ResponseEntity.badRequest().body(Map.of("sent", false, "detail", "unknown notification type"));
        }
        List<String> problems = problems(settings);
        if (!problems.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("sent", false, "detail", String.join("; ", problems)));
        }
        List<String> recipients = type == null ? settings.recipients() : settings.routes().stream()
                .filter(r -> type.equals(r.type())).findFirst()
                .map(r -> r.effectiveRecipients(settings)).orElse(List.of());
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            SmtpRelaySender.send(settings, recipients,
                    type == null ? "neXus notification test" : "neXus – " + type + ": test digest",
                    "This is a sample neXus " + (type == null ? "notification" : type) + " digest.\n");
            body.put("sent", true);
            body.put("detail", "accepted by the relay for " + recipients.size() + " recipient(s)");
        } catch (java.io.IOException | RuntimeException e) {
            body.put("sent", false);
            body.put("detail", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }
}
