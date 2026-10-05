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

    public ResponseEntity<?> search(String q, Integer limit, HttpServletRequest request) {
        return search(q, limit, null, null, request);
    }

    @GetMapping("/api/v2/search")
    public ResponseEntity<?> search(@RequestParam(required = false) String q,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String group,
            @RequestParam(required = false) Integer offset, HttpServletRequest request) {
        if (q == null || q.strip().length() < 2 || q.strip().length() > 100
                || limit != null && limit < 1 || offset != null && (offset < 0 || offset > 10000)
                || group != null && !java.util.Set.of("interfaces", "routes", "policy_objects").contains(group)) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_SEARCH_QUERY"));
        }
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        var result = search.search(q.strip(), limit == null ? 20 : Math.min(limit, 50),
                masked, group == null ? "" : group, offset == null ? 0 : offset);
        return ResponseEntity.ok(com.securityexpert.nexus.ui2.service.search.IpQuery.parse(q.strip()) == null ? result
            : new com.securityexpert.nexus.ui2.service.search.IpSearchResponse(result, masked));
    }
}
