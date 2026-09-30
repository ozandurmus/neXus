package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.https.HttpsCertificateTrustRepository;
import com.securityexpert.nexus.ui2.persistence.identity.AuthzDecisionRepository;
import com.securityexpert.nexus.ui2.platform.AuthzOutcome;
import com.securityexpert.nexus.ui2.service.device.DeviceWorkspaceAffordanceEvaluator;
import com.securityexpert.nexus.ui2.service.security.*;

/** Stored evidence only: accepting a certificate never contacts an appliance. */
@RestController
public final class HttpsCertificateController {
    private final HttpsCertificateTrustRepository trust;
    private final DeviceWorkspaceAffordanceEvaluator affordances;

    public HttpsCertificateController(TransactionBoundary transactions, ActionRegistry actions, RbacEvaluator rbac,
            AuthzDecisionRepository decisions) {
        trust = new HttpsCertificateTrustRepository(transactions);
        affordances = new DeviceWorkspaceAffordanceEvaluator(actions, rbac, decisions);
    }

    @GetMapping("/devices/{deviceId}/https-certificate")
    public ResponseEntity<?> read(@PathVariable String deviceId, HttpServletRequest request) {
        var endpoint = trust.deviceEndpoint(deviceId);
        if (endpoint.isEmpty()) return ResponseEntity.ok(Map.of("available", false));
        var e = endpoint.get();
        var entries = trust.entries(e.address(), e.port());
        var permitted = affordances.evaluate(List.of(ActionRegistry.HTTPS_CERTIFICATE_ACCEPT, ActionRegistry.HTTPS_CERTIFICATE_STRICT),
                (String) request.getAttribute(GateChainInterceptor.SESSION_ID_ATTRIBUTE), actor(request), deviceId, Instant.now());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("available", true);
        body.put("strict", e.strict());
        body.put("certificate_changed", entries.stream().anyMatch(entry -> "PENDING".equals(entry.status())));
        body.put("can_accept", permitted.get(ActionRegistry.HTTPS_CERTIFICATE_ACCEPT).outcome() == AuthzOutcome.PERMITTED);
        body.put("can_set_strict", permitted.get(ActionRegistry.HTTPS_CERTIFICATE_STRICT).outcome() == AuthzOutcome.PERMITTED);
        body.put("certificates", entries.stream().map(entry -> Map.of(
                "trust_entry_id", entry.trustEntryId(), "status", entry.status(),
                "fingerprint_sha256", entry.certificate().fingerprintSha256(),
                "subject_cn", entry.certificate().subjectCn(), "issuer_cn", entry.certificate().issuerCn(),
                "not_after", entry.certificate().notAfter().toString())).toList());
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(body);
    }

    public record AcceptRequest(String trustEntryId) {}
    @PostMapping("/devices/{deviceId}/https-certificate/accept")
    public ResponseEntity<?> accept(@PathVariable String deviceId, @RequestBody AcceptRequest body, HttpServletRequest request) {
        var endpoint = trust.deviceEndpoint(deviceId);
        if (endpoint.isEmpty()) return ResponseEntity.notFound().build();
        var e = endpoint.get();
        return trust.accept(e.address(), e.port(), body.trustEntryId(), actor(request))
                ? ResponseEntity.ok(Map.of("accepted", true))
                : ResponseEntity.status(409).body(Map.of("error", "PENDING_CERTIFICATE_CHANGED"));
    }

    public record StrictRequest(boolean strict) {}
    @PutMapping("/devices/{deviceId}/https-certificate/strict")
    public ResponseEntity<?> strict(@PathVariable String deviceId, @RequestBody StrictRequest body, HttpServletRequest request) {
        return trust.setStrict(deviceId, body.strict(), actor(request))
                ? ResponseEntity.ok(Map.of("strict", body.strict())) : ResponseEntity.notFound().build();
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }
}
