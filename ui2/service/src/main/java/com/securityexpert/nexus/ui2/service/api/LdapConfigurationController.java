package com.securityexpert.nexus.ui2.service.api;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.securityexpert.nexus.ui2.platform.LdapConfigurationPort;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

@RestController
public final class LdapConfigurationController {

    public record UpdateLdapRequest(
            @JsonProperty("server_url") String serverUrl,
            @JsonProperty("bind_dn") String bindDn,
            @JsonProperty("bind_password") char[] bindPassword,
            @JsonProperty("base_dn") String baseDn,
            @JsonProperty("search_filter") String searchFilter,
            @JsonProperty("ca_certificate") String caCertificate) {
    }

    private final LdapConfigurationPort ldapConfigurationPort;

    public LdapConfigurationController(LdapConfigurationPort ldapConfigurationPort) {
        this.ldapConfigurationPort = ldapConfigurationPort;
    }

    @GetMapping("/ldap-configuration")
    public ResponseEntity<Map<String, Object>> getConfiguration() {
        return ldapConfigurationPort.getConfiguration()
                .map(view -> ResponseEntity.ok(toBody(view)))
                .orElseGet(() -> ResponseEntity.ok(new LinkedHashMap<>()));
    }

    @PostMapping("/ldap-configuration")
    public ResponseEntity<Map<String, Object>> updateConfiguration(@RequestBody UpdateLdapRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        char[] password = request.bindPassword();
        try {
            LdapConfigurationPort.LdapConfigurationView view = ldapConfigurationPort.updateConfiguration(
                    actorFingerprint,
                    request.serverUrl(),
                    request.bindDn(),
                    password,
                    request.baseDn(),
                    request.searchFilter(),
                    request.caCertificate()
            );
            return ResponseEntity.ok(toBody(view));
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private static Map<String, Object> toBody(LdapConfigurationPort.LdapConfigurationView view) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("server_url", view.serverUrl());
        body.put("bind_dn", view.bindDn());
        body.put("base_dn", view.baseDn());
        body.put("search_filter", view.searchFilter());
        body.put("ca_certificate", view.caCertificate());
        return body;
    }
}
