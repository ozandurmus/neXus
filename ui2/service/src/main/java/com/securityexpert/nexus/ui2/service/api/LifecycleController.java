package com.securityexpert.nexus.ui2.service.api;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogRepository;
import com.securityexpert.nexus.ui2.platform.RoleToken;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleCatalogImport;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleProjection;
import com.securityexpert.nexus.ui2.service.lifecycle.LifecycleService;
import com.securityexpert.nexus.ui2.service.lifecyclesource.LifecycleSourceService;
import com.securityexpert.nexus.ui2.service.privacy.PrivacyMaskingResponseBodyAdvice;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

@RestController
public final class LifecycleController {
    private final LifecycleService lifecycle;
    private final LifecycleCatalogRepository catalog;
    private final RbacEvaluator rbac;
    private final LifecycleSourceService sources;

    public LifecycleController(LifecycleService lifecycle, LifecycleCatalogRepository catalog, RbacEvaluator rbac) {
        this(lifecycle, catalog, rbac, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public LifecycleController(LifecycleService lifecycle, LifecycleCatalogRepository catalog, RbacEvaluator rbac,
            LifecycleSourceService sources) {
        this.lifecycle = lifecycle; this.catalog = catalog; this.rbac = rbac; this.sources = sources;
    }

    @GetMapping("/api/v2/lifecycle")
    public Map<String, Object> fleet() {
        var today = LocalDate.now(ZoneOffset.UTC);
        var rows = lifecycle.fleet(today);
        return Map.of("as_of", today.toString(), "devices", rows, "summary", LifecycleService.summary(rows));
    }

    @GetMapping("/devices/{id}/lifecycle")
    public ResponseEntity<?> device(@PathVariable String id) {
        return lifecycle.device(id).<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND")));
    }

    @GetMapping("/api/v2/lifecycle/catalog")
    public Map<String, Object> catalog(HttpServletRequest request) {
        boolean masked = masked(request);
        boolean manage = !masked && rbac.evaluate(actor(request), Optional.of(RoleToken.SECURITY_ADMIN), Instant.now()).outcome().proceeds();
        return Map.of("entries", catalog.list().stream().map(e -> LifecycleProjection.catalog(e, masked)).toList(), "can_manage", manage);
    }

    public record EntryRequest(String vendor, String kind, String product, String end_of_sale,
            String end_of_support, String end_of_engineering, String note) { }
    public record ImportRequest(String csv) { }
    public record VendorPageRequest(String page_form, String page) { }

    /**
     * The lifecycle catalog's sources (LIFECYCLE_CATALOG_SOURCES_CONTRACT.md section 6): the online source with its age, the
     * vendor page forms an operator may upload, the last uploads and the entries that need review.
     */
    @GetMapping("/api/v2/lifecycle/catalog-sources")
    public ResponseEntity<?> catalogSources(HttpServletRequest request) {
        if (sources == null) return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        return ResponseEntity.ok(sources.sources(Instant.now(), masked(request)));
    }

    /** An operator upload of a saved vendor lifecycle page (contract section 3.2): parsed, validated, audited. */
    @PostMapping("/api/v2/lifecycle/catalog/vendor-page")
    public ResponseEntity<?> vendorPage(@RequestBody VendorPageRequest body, HttpServletRequest request) {
        if (masked(request)) return readOnly();
        if (sources == null) return ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
        var result = sources.upload(body == null ? null : body.page_form(), body == null ? null : body.page(), actor(request));
        if (result.error() != null) return ResponseEntity.badRequest().body(Map.of("error", result.error(), "reason", result.message()));
        return ResponseEntity.ok(Map.of("upload_id", result.uploadId(), "parsed", result.parsed(), "needs_review", result.needsReview(),
                "skipped", result.skipped()));
    }

    @PostMapping("/api/v2/lifecycle/catalog")
    public ResponseEntity<?> add(@RequestBody EntryRequest body, HttpServletRequest request) {
        return save(null, body, request);
    }

    @PutMapping("/api/v2/lifecycle/catalog/{id}")
    public ResponseEntity<?> edit(@PathVariable String id, @RequestBody EntryRequest body, HttpServletRequest request) {
        return save(id, body, request);
    }

    private ResponseEntity<?> save(String id, EntryRequest body, HttpServletRequest request) {
        if (masked(request)) return readOnly();
        try {
            var entry = LifecycleCatalogImport.entry(Arrays.asList(value(body.vendor()), value(body.kind()), value(body.product()),
                    value(body.end_of_sale()), value(body.end_of_support()), value(body.end_of_engineering()), value(body.note())),
                    "MANUAL", actor(request), Instant.now());
            if (keyConflict(entry, id)) return ResponseEntity.status(409).body(Map.of("error", "CATALOG_KEY_CONFLICT"));
            catalog.save(java.util.List.of(entry), id, actor(request));
            return ResponseEntity.ok(Map.of("saved", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "INVALID_CATALOG_ROW", "reason", e.getMessage()));
        } catch (org.jooq.exception.IntegrityConstraintViolationException e) {
            return ResponseEntity.status(409).body(Map.of("error", "CATALOG_KEY_CONFLICT"));
        }
    }

    @PostMapping("/api/v2/lifecycle/catalog/import")
    public ResponseEntity<?> importCsv(@RequestBody ImportRequest body, HttpServletRequest request) {
        if (masked(request)) return readOnly();
        var result = LifecycleCatalogImport.parse(body.csv(), actor(request), Instant.now());
        if (!result.errors().isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "INVALID_CSV", "errors", result.errors()));
        var conflicts = new java.util.ArrayList<LifecycleCatalogImport.RowError>();
        for (int i = 0; i < result.entries().size(); i++) {
            if (keyConflict(result.entries().get(i), null)) {
                conflicts.add(new LifecycleCatalogImport.RowError(i + 2, "Another catalog row already names the same product"));
            }
        }
        if (!conflicts.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "INVALID_CSV", "errors", conflicts));
        catalog.save(result.entries(), null, actor(request));
        return ResponseEntity.ok(Map.of("imported", result.entries().size()));
    }

    @PostMapping("/api/v2/lifecycle/catalog/{id}/delete")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        if (masked(request)) return readOnly();
        return catalog.delete(id, actor(request)) ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
    }

    /**
     * True when another stored row has the same match key under a different product text (for example "6600" beside
     * "Check Point 6600"): both would match the same devices and the match would be ambiguous. The same product text is
     * an update and is not a conflict.
     */
    private boolean keyConflict(com.securityexpert.nexus.ui2.persistence.lifecycle.LifecycleCatalogEntry entry, String editedId) {
        String key = com.securityexpert.nexus.ui2.service.lifecycle.LifecycleMatcher.catalogKey(entry.vendor(), entry.kind(), entry.product());
        return catalog.list().stream().anyMatch(e -> e.vendor().equals(entry.vendor()) && e.kind().equals(entry.kind())
                && !e.catalogId().equals(editedId) && !e.product().equals(entry.product())
                && key != null && key.equals(com.securityexpert.nexus.ui2.service.lifecycle.LifecycleMatcher.catalogKey(e.vendor(), e.kind(), e.product())));
    }

    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }
    private static String value(String value) { return value == null ? "" : value; }
    private static boolean masked(HttpServletRequest request) { return PrivacyMaskingResponseBodyAdvice.isReplayViewer(request); }
    private static ResponseEntity<?> readOnly() { return ResponseEntity.status(403).body(Map.of("error", "READ_ONLY_PERSONA")); }
}
