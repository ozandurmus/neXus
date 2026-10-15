package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.service.policy.PolicyChangesService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.Map;

@RestController
public final class PolicyChangesController {
    private final PolicyChangesService changes;
    public PolicyChangesController(PolicyChangesService changes) { this.changes = changes; }
    @GetMapping("/api/v2/policy/changes")
    public ResponseEntity<?> changes(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String policy, @RequestParam(defaultValue = "") String device,
            @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "") String changeType,
            @RequestParam(defaultValue = "") String field, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean includeInitial, HttpServletRequest request) {
        var filters = new PolicyChangesService.Request(from, to, policy, device, actor, changeType, field, q, cursor, limit, includeInitial);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(changes.changes(filters, masked));
    }
    @GetMapping("/api/v2/policy/changes.csv")
    public ResponseEntity<StreamingResponseBody> changesCsv(@RequestParam(defaultValue = "") String from, @RequestParam(defaultValue = "") String to,
            @RequestParam(defaultValue = "") String policy, @RequestParam(defaultValue = "") String device,
            @RequestParam(defaultValue = "") String actor, @RequestParam(defaultValue = "") String changeType,
            @RequestParam(defaultValue = "") String field, @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String cursor, @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "false") boolean includeInitial, HttpServletRequest request) {
        var filters = new PolicyChangesService.Request(from, to, policy, device, actor, changeType, field, q, cursor, limit, includeInitial);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        changes.validate(filters);
        StreamingResponseBody body = output -> changes.csv(filters, masked, output);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=policy-changes.csv")
            .header(PrivacyMaskingResponseBodyAdvice.MASKED_HEADER, String.valueOf(masked))
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8")).body(body);
    }
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalidFilters(IllegalArgumentException invalid) {
        return ResponseEntity.badRequest().cacheControl(CacheControl.noStore()).body(Map.of("error", "INVALID_CHANGE_FILTERS"));
    }
}
