package com.securityexpert.nexus.ui2.service.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.service.compliance.ComplianceService;

@RestController
public final class ComplianceController {

    public record EvaluateRequest(@JsonProperty("device_id") String deviceId) {
    }

    private final ComplianceService complianceService;

    public ComplianceController(ComplianceService complianceService) {
        this.complianceService = complianceService;
    }

    @GetMapping("/compliance/overview")
    public ResponseEntity<Map<String, Object>> getOverview() {
        return ResponseEntity.ok(complianceService.getOverview());
    }

    @GetMapping("/compliance")
    public ResponseEntity<Map<String, Object>> getCompliance() {
        return ResponseEntity.ok(complianceService.getOverview());
    }

    @GetMapping("/compliance/controls")
    public ResponseEntity<Map<String, Object>> getControls() {
        List<Map<String, Object>> controls = complianceService.getControls();
        return ResponseEntity.ok(Map.of(
                "total_controls", controls.size(),
                "controls", controls
        ));
    }

    @GetMapping("/devices/{deviceId}/compliance")
    public ResponseEntity<Map<String, Object>> getDeviceCompliance(@PathVariable String deviceId) {
        Map<String, Object> result = complianceService.getDeviceCompliance(deviceId);
        if (result.containsKey("error") && "NOT_FOUND".equals(result.get("error"))) {
            return ResponseEntity.status(404).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @PostMapping("/compliance/evaluate")
    public ResponseEntity<Map<String, Object>> evaluate(@RequestBody(required = false) EvaluateRequest req) {
        if (req != null && req.deviceId() != null && !req.deviceId().isBlank()) {
            return ResponseEntity.ok(complianceService.getDeviceCompliance(req.deviceId()));
        }
        return ResponseEntity.ok(complianceService.getOverview());
    }
}
