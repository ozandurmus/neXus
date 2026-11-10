package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.advisorystore.AdvisoryReadService;

/**
 * Read interface of advisory matching, part 1 (docs/design/ADVISORY_MATCHING_CONTRACT.md section 8). All three routes are
 * {@code GET} and map to the action of {@code GET /api/v2/lifecycle} (device read); there is no write action. Advisory
 * data is public; device names are masked for the replay viewer by the response masking advice (key {@code hostname}).
 */
@RestController
public final class AdvisoryController {

    private final AdvisoryReadService advisories;

    public AdvisoryController(AdvisoryReadService advisories) {
        this.advisories = advisories;
    }

    @GetMapping("/api/v2/lifecycle/advisories")
    public Map<String, Object> list() {
        return advisories.list(Instant.now());
    }

    @GetMapping("/api/v2/lifecycle/advisories/{id}")
    public ResponseEntity<?> detail(@PathVariable String id) {
        return advisories.detail(id, Instant.now()).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "ADVISORY_NOT_FOUND")));
    }

    @GetMapping("/api/v2/lifecycle/advisory-sources")
    public Map<String, Object> sources() {
        return advisories.sources(Instant.now());
    }
}
