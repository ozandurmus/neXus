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
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;
import com.securityexpert.nexus.ui2.service.security.RbacEvaluator;

@RestController
public final class LifecycleController {
    private final LifecycleService lifecycle;
    private final LifecycleCatalogRepository catalog;
    private final RbacEvaluator rbac;

    public LifecycleController(LifecycleService lifecycle, LifecycleCatalogRepository catalog, RbacEvaluator rbac) {
        this.lifecycle = lifecycle; this.catalog = catalog; this.rbac = rbac;
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
        boolean masked = Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
        boolean manage = !masked && rbac.evaluate(actor(request), Optional.of(RoleToken.SECURITY_ADMIN), Instant.now()).outcome().proceeds();
        return Map.of("entries", catalog.list().stream().map(e -> LifecycleProjection.catalog(e, masked)).toList(), "can_manage", manage);
    }

    public record EntryRequest(String vendor, String kind, String product, String end_of_sale,
            String end_of_support, String end_of_engineering, String note) { }
    public record ImportRequest(String csv) { }

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
        catalog.save(result.entries(), null, actor(request));
        return ResponseEntity.ok(Map.of("imported", result.entries().size()));
    }

    @PostMapping("/api/v2/lifecycle/catalog/{id}/delete")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        if (masked(request)) return readOnly();
        return catalog.delete(id, actor(request)) ? ResponseEntity.ok(Map.of("deleted", true))
                : ResponseEntity.status(404).body(Map.of("error", "NOT_FOUND"));
    }

    private static String actor(HttpServletRequest request) { return (String) request.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE); }
    private static String value(String value) { return value == null ? "" : value; }
    private static boolean masked(HttpServletRequest request) { return Boolean.TRUE.equals(request.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE)); }
    private static ResponseEntity<?> readOnly() { return ResponseEntity.status(403).body(Map.of("error", "READ_ONLY_PERSONA")); }
}
