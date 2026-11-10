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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.LdapSettingsService;

/**
 * Administration › LDAP (design LDAP_SERVICE_BIND_DESIGN_2026_11_11 §9). Read is {@code ldap_config_read}, save
 * and test are {@code ldap_config_write}; both are {@code role:security_admin} through the route map.
 */
@RestController
@RequestMapping("/api/v2/config/ldap")
public class DirectorySettingsController {
    private final LdapSettingsService service;

    public DirectorySettingsController(LdapSettingsService service) {
        this.service = service;
    }

    public record TestRequest(LdapSettingsService.Request settings, String testUser) {
        @Override public String toString() { return "TestRequest[redacted]"; }
    }

    @GetMapping
    public LdapSettingsService.View read() {
        return service.view();
    }

    @PutMapping
    public ResponseEntity<Object> save(@RequestBody LdapSettingsService.Request request, HttpServletRequest http) {
        Object actor = http.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        LdapSettingsService.Outcome outcome = service.save(request, actor instanceof String s ? s : null);
        if (outcome instanceof LdapSettingsService.Outcome.Invalid invalid) return invalid(invalid.problems());
        return ResponseEntity.ok(((LdapSettingsService.Outcome.Saved) outcome).view());
    }

    @PostMapping("/test")
    public ResponseEntity<Object> test(@RequestBody(required = false) TestRequest request) {
        if (request == null || request.settings() == null) {
            return invalid(List.of(new LdapSettingsService.Problem("", "The settings to test are missing.")));
        }
        LdapSettingsService.Outcome outcome = service.test(request.settings(), request.testUser());
        if (outcome instanceof LdapSettingsService.Outcome.Invalid invalid) return invalid(invalid.problems());
        return ResponseEntity.ok(((LdapSettingsService.Outcome.Tested) outcome).report());
    }

    private static ResponseEntity<Object> invalid(List<LdapSettingsService.Problem> problems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_SETTINGS");
        body.put("problems", problems);
        return ResponseEntity.badRequest().body(body);
    }
}
