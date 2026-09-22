package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.search.GlobalSearchService;

@RestController
public final class GlobalSearchController {
    private final GlobalSearchService search;

    public GlobalSearchController(GlobalSearchService search) {
        this.search = search;
    }

    @GetMapping("/api/v2/search")
    public ResponseEntity<Map<String, Object>> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit, HttpServletRequest request) {
        if (q == null || q.strip().length() < 2 || q.strip().length() > 100
                || limit != null && limit < 1) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_SEARCH_QUERY"));
        }
        return ResponseEntity.ok(search.search(q.strip(), limit == null ? 20 : Math.min(limit, 50),
                PrivacyMaskingResponseBodyAdvice.isReplayViewer(request)));
    }
}
