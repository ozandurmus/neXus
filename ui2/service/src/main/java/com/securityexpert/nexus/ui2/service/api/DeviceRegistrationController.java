package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.DeviceRegistrationService;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Manual device registration (B1-4b contract §4). Gated by {@link
 * GateChainInterceptor} -- reached only after {@code E1}-{@code E4}
 * required {@code role:onboarding_admin} (module placement: registration
 * controller lives in {@code service}, adjudication F12).
 */
@RestController
public final class DeviceRegistrationController {

    public record RegisterRequest(String vendorHint, String transportKind, String addressRef,
            String credentialReferenceId, boolean isTestTarget) {
    }

    private final DeviceRegistrationService deviceRegistrationService;

    public DeviceRegistrationController(DeviceRegistrationService deviceRegistrationService) {
        this.deviceRegistrationService = deviceRegistrationService;
    }

    @PostMapping("/devices")
    public ResponseEntity<Map<String, Object>> register(@RequestBody RegisterRequest request,
            HttpServletRequest servletRequest) {
        String actorFingerprint = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        DeviceRegistrationService.Outcome outcome = deviceRegistrationService.register(actorFingerprint,
                request.vendorHint(), request.transportKind(), request.addressRef(), request.credentialReferenceId(),
                request.isTestTarget());
        return respond(outcome);
    }

    private static ResponseEntity<Map<String, Object>> respond(DeviceRegistrationService.Outcome outcome) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (outcome instanceof DeviceRegistrationService.Outcome.ValidationFailed failed) {
            body.put("error", "VALIDATION_FAILED");
            body.put("reason_code", failed.reasonCode());
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
        }
        DeviceRegistrationService.Outcome.Registered registered =
                (DeviceRegistrationService.Outcome.Registered) outcome;
        body.put("device_id", registered.deviceId());
        body.put("endpoint_id", registered.endpointId());
        body.put("enrollment_state", "DRAFT");
        return ResponseEntity.ok(body);
    }
}
