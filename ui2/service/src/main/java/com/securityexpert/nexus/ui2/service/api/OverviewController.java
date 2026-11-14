package com.securityexpert.nexus.ui2.service.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.overview.OverviewService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.privacy.TopologyNamePseudonymizer;
import com.securityexpert.nexus.ui2.service.system.BackgroundSnapshot;

/**
 * {@code GET /api/v2/overview} (OVERVIEW_EXCEPTION_SCREEN_CONTRACT §4.1). Cached 60 s per persona; labels and
 * cluster references are masked here for the AIView persona (the counts were computed on raw references).
 * Since 2026-11-14 (API latency work: 2.1 s on every cache miss, and the Compliance screen reads it too) the body is
 * rebuilt in the background ({@link BackgroundSnapshot}): a request returns the last body at once, a body older than
 * 60 s triggers one rebuild, and while somebody reads the Overview it is kept at most about 60 s old. Only the first
 * read after a pod start builds on the request thread. {@code generated_at} states the body's age, as before.
 */
@RestController
public final class OverviewController {

    static final Duration FRESH_FOR = Duration.ofSeconds(60);

    private final OverviewService overviewService;
    private final TopologyNamePseudonymizer pseudonymizer;
    private final Map<Boolean, BackgroundSnapshot<Map<String, Object>>> cache = new ConcurrentHashMap<>();

    public OverviewController(OverviewService overviewService, TopologyNamePseudonymizer pseudonymizer) {
        this.overviewService = overviewService;
        this.pseudonymizer = pseudonymizer;
    }

    @GetMapping("/api/v2/overview")
    public ResponseEntity<Map<String, Object>> overview(HttpServletRequest request) {
        boolean masked = PrivacyMaskingResponseBodyAdvice.isReplayViewer(request);
        BackgroundSnapshot.Value<Map<String, Object>> cached = cache.computeIfAbsent(masked, m -> new BackgroundSnapshot<>(
                m ? "overview-masked" : "overview", () -> build(m), FRESH_FOR, Duration.ofMinutes(10))).get();
        // no-cache, never max-age: the browser must ask each time, so the server -- not a cached copy from another
        // login in the same browser -- decides whether the body is masked (2026-09-24: real names showed under aiview
        // for up to 60 s after an admin session). The ETag still spares the body when nothing changed.
        return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).header("Vary", "Cookie")
                .eTag("\"" + cached.computedAt().toEpochMilli() + (masked ? "m" : "u") + "\"").body(cached.value());
    }

    private Map<String, Object> build(boolean masked) {
        Map<String, Object> body = overviewService.build();
        if (masked) {
            maskLabels(body);
        } else {
            renameClusterField(body);
        }
        body.put("masked", masked);
        return body;
    }

    @SuppressWarnings("unchecked")
    private static void renameClusterField(Map<String, Object> body) {
        if (body.get("exceptions") instanceof Map<?, ?> exceptions) {
            for (String list : List.of("failed_jobs", "config_changes")) {
                if (exceptions.get(list) instanceof Map<?, ?> section && section.get("rows") instanceof List<?> rows) {
                    for (Object o : rows) {
                        Map<String, Object> row = (Map<String, Object>) o;
                        row.put("cluster", row.remove("label_cluster"));
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void maskLabels(Map<String, Object> body) {
        if (!(body.get("exceptions") instanceof Map<?, ?> exceptions)) {
            return;
        }
        for (String list : List.of("failed_jobs", "config_changes")) {
            if (exceptions.get(list) instanceof Map<?, ?> section && section.get("rows") instanceof List<?> rows) {
                for (Object o : rows) {
                    Map<String, Object> row = (Map<String, Object>) o;
                    Object cluster = row.remove("label_cluster");
                    Object label = row.get("label");
                    if (label instanceof String s) {
                        row.put("label", pseudonymizer.maskDeviceName(s, cluster instanceof String c ? c : null));
                    }
                    if (cluster instanceof String c) {
                        row.put("cluster", pseudonymizer.maskClusterName(c));
                    }
                    if (row.get("terminal_reason") instanceof String reason) {
                        row.put("terminal_reason", pseudonymizer.maskText(reason));
                    }
                }
            }
        }
        if (exceptions.get("failed_jobs") instanceof Map<?, ?> failed && failed.get("reasons") instanceof List<?> reasons) {
            for (Object o : reasons) {
                Map<String, Object> row = (Map<String, Object>) o;
                if (row.get("reason") instanceof String reason) {
                    row.put("reason", pseudonymizer.maskText(reason));
                }
            }
        }
        if (exceptions.get("cluster_diff") instanceof Map<?, ?> diff) {
            Map<String, Object> d = (Map<String, Object>) diff;
            if (d.get("rows") instanceof List<?> rows) {
                for (Object o : rows) {
                    Map<String, Object> row = (Map<String, Object>) o;
                    if (row.get("cluster_ref") instanceof String ref) {
                        row.put("cluster_ref", pseudonymizer.maskClusterName(ref));
                    }
                }
            }
            if (d.get("all_refs") instanceof List<?> refs) {
                List<String> maskedRefs = new ArrayList<>();
                for (Object r : refs) {
                    maskedRefs.add(pseudonymizer.maskClusterName(String.valueOf(r)));
                }
                d.put("all_refs", maskedRefs);
            }
        }
    }
}
