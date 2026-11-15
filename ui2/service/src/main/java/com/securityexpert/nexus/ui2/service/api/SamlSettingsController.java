package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.SamlSettingsService;

/**
 * Administration > SAML (migration plan Step 4). Read is {@code saml_config_read}, save is {@code saml_config_write}; both are
 * {@code role:security_admin} through the route map (the aiview persona holds neither). The private key is write-only.
 */
@RestController
@RequestMapping("/api/v2/config/saml")
public class SamlSettingsController {
    private final SamlSettingsService service;

    public SamlSettingsController(SamlSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<SamlSettingsService.View> read() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.view());
    }

    @PutMapping
    public ResponseEntity<Object> save(@RequestBody SamlSettingsService.Request request, HttpServletRequest http) {
        Object actor = http.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        SamlSettingsService.Outcome outcome = service.save(request, actor instanceof String s ? s : null);
        if (outcome instanceof SamlSettingsService.Outcome.Invalid invalid) return invalid(invalid.problems());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(((SamlSettingsService.Outcome.Saved) outcome).view());
    }

    private static ResponseEntity<Object> invalid(List<SamlSettingsService.Problem> problems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "INVALID_SETTINGS");
        body.put("problems", problems);
        return ResponseEntity.badRequest().body(body);
    }
}
