package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.estate.EstateAsOfResponse;
import com.securityexpert.nexus.ui2.service.estate.EstateAsOfService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;

/**
 * {@code GET /api/v2/estate/as-of?at=<ISO>}: read-only Estate Rewind slice
 * ({@code docs/design/ESTATE_REWIND_AS_OF_DRAFT_2026_11_20.md}).
 *
 * <p>Returns per-unit outcomes and counts as of {@code at} for failover readiness, backup presence/age, and compliance
 * pass/fail checks, with evidence timestamps per figure and {@code UNKNOWN} where stored history does not reach.
 * Labels and cluster references are masked for {@code role:replay_viewer} ({@code aiview}) and never cached across
 * browser sessions.</p>
 */
@RestController
public final class EstateAsOfController {

    private final EstateAsOfService service;

    public EstateAsOfController(EstateAsOfService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @GetMapping("/api/v2/estate/as-of")
    public ResponseEntity<EstateAsOfResponse> asOf(
            @RequestParam(name = "at", required = false) String at,
            HttpServletRequest request
    ) {
        Instant moment = EstateAsOfService.parseAt(at);
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("Vary", "Cookie")
                .body(service.asOf(moment, masked));
    }

    @ExceptionHandler(EstateAsOfService.InvalidMomentException.class)
    public ResponseEntity<Map<String, Object>> invalidMoment(EstateAsOfService.InvalidMomentException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.code());
        body.put("code", ex.code());
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest()
                .cacheControl(CacheControl.noCache().cachePrivate())
                .header("Vary", "Cookie")
                .body(body);
    }
}
