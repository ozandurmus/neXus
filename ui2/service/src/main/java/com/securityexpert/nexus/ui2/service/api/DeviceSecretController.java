package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.device.DeviceRepository;
import com.securityexpert.nexus.ui2.persistence.device.DeviceSecretReferenceRepository;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * A device's second secret (V64): binds a credential-store reference to a purpose -- the Radware export passphrase
 * today. The value never passes this route; only the reference of a credential already in the store.
 */
@RestController
public final class DeviceSecretController {

    private static final Set<String> PURPOSES = Set.of(DeviceSecretReferenceRepository.EXPORT_PASSPHRASE);

    private final DeviceRepository devices;
    private final DeviceSecretReferenceRepository secrets;
    private final com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository references;

    public DeviceSecretController(DeviceRepository devices, DeviceSecretReferenceRepository secrets,
            com.securityexpert.nexus.ui2.persistence.device.CredentialReferenceRepository references) {
        this.devices = devices;
        this.secrets = secrets;
        this.references = references;
    }

    public record SecretRequest(@JsonProperty("credential_reference_id") String credentialReferenceId) {
    }

    /**
     * Replace the device's login credential (PO, 2026-09-24: "user değiştirme imkanım olmalı"). A reference only; the
     * next job uses it. A draft whose confirm failed is re-confirmed by the caller with {@code POST /devices/{id}/confirm}.
     */
    @PutMapping("/devices/{deviceId}/credential")
    public ResponseEntity<Map<String, Object>> setCredential(@PathVariable String deviceId, @RequestBody SecretRequest request,
            HttpServletRequest servletRequest) {
        if (devices.find(deviceId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }
        if (request == null || request.credentialReferenceId() == null || !references.exists(request.credentialReferenceId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "CREDENTIAL_REFERENCE_NOT_FOUND"));
        }
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        boolean changed = devices.setCredentialReference(deviceId, request.credentialReferenceId(), actor, "device_credential_set");
        return ResponseEntity.ok(Map.of("ok", true, "changed", changed));
    }

    @PostMapping("/devices/{deviceId}/secrets/{purpose}")
    public ResponseEntity<Map<String, Object>> set(@PathVariable String deviceId, @PathVariable String purpose,
            @RequestBody SecretRequest request, HttpServletRequest servletRequest) {
        if (!PURPOSES.contains(purpose)) {
            return ResponseEntity.badRequest().body(Map.of("error", "UNKNOWN_PURPOSE"));
        }
        if (devices.find(deviceId).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }
        if (request == null || request.credentialReferenceId() == null || !references.exists(request.credentialReferenceId())) {
            return ResponseEntity.badRequest().body(Map.of("error", "CREDENTIAL_REFERENCE_NOT_FOUND"));
        }
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        secrets.set(deviceId, purpose, request.credentialReferenceId(), actor, "device_secret_set");
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
