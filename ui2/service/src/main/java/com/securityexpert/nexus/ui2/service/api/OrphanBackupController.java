package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Delete a backup whose device no longer exists (V70; PO, 2026-09-24). The service records the request, with who asked
 * and why; the worker -- the artefact store's only reader and writer (BK-17) -- removes the encrypted file and the
 * manifest rows within a minute. A backup of a device that still exists is refused: its lifecycle is retention's.
 */
@RestController
public final class OrphanBackupController {

    private static final int MIN_REASON = 8;
    private static final int MAX_REASON = 300;

    private final TransactionBoundary tx;

    public OrphanBackupController(TransactionBoundary tx) {
        this.tx = tx;
    }

    public record DeleteRequest(@JsonProperty("reason") String reason) {
    }

    @PostMapping("/backups/{artefactId}/delete")
    public ResponseEntity<Map<String, Object>> requestDelete(@PathVariable String artefactId, @RequestBody DeleteRequest request,
            HttpServletRequest servletRequest) {
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        String reason = request == null || request.reason() == null ? "" : request.reason().strip();
        if (reason.length() < MIN_REASON || reason.length() > MAX_REASON) {
            return ResponseEntity.badRequest().body(Map.of("error", "REASON_REQUIRED",
                    "reason", "a reason of 8 to 300 characters is required"));
        }
        String state = tx.inTransaction(dsl -> {
            var row = dsl.fetchOptional("select exists (select 1 from devices d where d.device_id = a.device_id) as device_exists "
                    + "from backup_artefact a where a.artefact_id = {0}", artefactId);
            if (row.isEmpty()) {
                return "NOT_FOUND";
            }
            return Boolean.TRUE.equals(row.get().get("device_exists", Boolean.class)) ? "DEVICE_EXISTS" : "ORPHAN";
        });
        if ("NOT_FOUND".equals(state)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND"));
        }
        if ("DEVICE_EXISTS".equals(state)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "DEVICE_EXISTS",
                    "reason", "this backup's device is still registered; only a backup of a deleted device can be removed here"));
        }
        new AuditedTransactionBoundary(tx).inTransaction(actor, ActionRegistry.DEVICE_BACKUP_ORPHAN_DELETE, dsl -> dsl.execute(
                "insert into backup_artefact_deletion_request(artefact_id, requested_by_actor_fingerprint, reason) values ({0}, {1}, {2}) "
                        + "on conflict (artefact_id) do nothing", artefactId, actor, reason));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("queued", true));
    }
}
