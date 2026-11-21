package com.securityexpert.nexus.ui2.service.api;

import com.securityexpert.nexus.ui2.platform.OverwatchEnv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStoreQuotaRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.service.security.ActionRegistry;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

import jakarta.servlet.http.HttpServletRequest;

/**
 * V225: the per-store storage quota of the artefact store (backlog backup_screen_backbox_model). A store is the first
 * directory level under the artefact root (the backup vendor). Reads are {@code device_backup_read}; setting or clearing
 * a quota is {@code backup_store_quota_set} ({@code role:backup_admin}) and audited by the table's trigger. Writes over a
 * store's quota are refused by {@link FileArtefactStore} with the same {@code artefact_store_quota_exceeded} reason as
 * the global quota, naming the store. No quota set means unlimited.
 */
@RestController
public final class BackupStoreQuotaController {

    public record UpdateRequest(@JsonProperty("quota_bytes") Long quotaBytes) {
    }

    static final Pattern STORE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** One byte below a pebibyte: a sanity bound, not a policy. */
    static final long MAX_QUOTA_BYTES = 1L << 50;

    private final ArtefactStoreQuotaRepository repository;
    private final Path root;

    @Autowired
    public BackupStoreQuotaController(ArtefactStoreQuotaRepository repository) {
        this(repository, OverwatchEnv.getOrDefault("UI2_ARTEFACT_STORE_ROOT", "").isBlank()
                ? null : Path.of(OverwatchEnv.get("UI2_ARTEFACT_STORE_ROOT")));
    }

    BackupStoreQuotaController(ArtefactStoreQuotaRepository repository, Path root) {
        this.repository = repository;
        this.root = root;
    }

    @GetMapping("/api/v2/backups/store-quotas")
    public ResponseEntity<Map<String, Object>> list() {
        return ResponseEntity.ok(view());
    }

    @PutMapping("/api/v2/backups/store-quotas/{store}")
    public ResponseEntity<Map<String, Object>> set(@PathVariable("store") String store,
            @RequestBody(required = false) UpdateRequest request, HttpServletRequest servletRequest) {
        String actor = (String) servletRequest.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
        if (actor == null || actor.isBlank()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "ACTOR_REQUIRED"));
        }
        Optional<String> invalid = validate(store, request);
        if (invalid.isPresent()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Map.of("error", "VALIDATION_FAILED", "reason", invalid.get()));
        }
        if (request.quotaBytes() == null) {
            repository.clear(store, actor, ActionRegistry.BACKUP_STORE_QUOTA_SET);
        } else {
            repository.set(store, request.quotaBytes(), actor, ActionRegistry.BACKUP_STORE_QUOTA_SET);
        }
        return ResponseEntity.ok(view());
    }

    public static Optional<String> validate(String store, UpdateRequest request) {
        if (store == null || !STORE_NAME.matcher(store).matches()) {
            return Optional.of("the store name must be 1 to 64 characters from A-Z a-z 0-9 . _ -");
        }
        if (request == null) {
            return Optional.of("a body is required: {\"quota_bytes\": <bytes>} to set, {\"quota_bytes\": null} to remove the quota");
        }
        Long bytes = request.quotaBytes();
        if (bytes != null && (bytes <= 0 || bytes > MAX_QUOTA_BYTES)) {
            return Optional.of("quota_bytes must be between 1 and " + MAX_QUOTA_BYTES + ", or null to remove the quota");
        }
        return Optional.empty();
    }

    Map<String, Object> view() {
        Map<String, Long> quotas = repository.findAll();
        TreeSet<String> names = new TreeSet<>(quotas.keySet());
        boolean mounted = root != null && Files.isDirectory(root);
        if (mounted) {
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                        .filter(n -> STORE_NAME.matcher(n).matches()).forEach(names::add);
            } catch (IOException e) {
                mounted = false;
            }
        }
        List<Map<String, Object>> stores = new ArrayList<>();
        for (String name : names) {
            Long quota = quotas.get(name);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("store", name);
            Long used = null;
            if (mounted) {
                try {
                    used = FileArtefactStore.usedBytes(root.resolve(name));
                } catch (RuntimeException ignored) {
                    // unreadable: reported as unknown, never as zero
                }
            }
            row.put("used_bytes", used);
            row.put("quota_bytes", quota);
            boolean exceeded = quota != null && used != null && used >= quota;
            row.put("remaining_bytes", quota != null && used != null ? Math.max(0L, quota - used) : null);
            row.put("percent", quota != null && used != null ? Math.round(used * 1000.0 / quota) / 10.0 : null);
            row.put("near_limit", quota != null && used != null && FileArtefactStore.isNearLimit(used, quota));
            row.put("quota_exceeded", exceeded);
            if (quota != null && used != null && (exceeded || FileArtefactStore.isNearLimit(used, quota))) {
                row.put("warning", exceeded
                        ? ArtefactStore.QUOTA_EXCEEDED_REASON + ": store=" + name + " is at or over its quota; new backups to it are refused until space is freed or the quota is raised"
                        : FileArtefactStore.NEAR_QUOTA_WARNING_CODE + ": store=" + name + " has used at least "
                                + Math.round(FileArtefactStore.NEAR_LIMIT_WARN_FRACTION * 100) + "% of its quota");
            }
            stores.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("store_mounted", mounted);
        body.put("note", "A store is the first directory level of the artefact store (the vendor). No quota means unlimited; "
                + "the global artefact store quota applies to every store as well. Writes are refused, never deleted to make room.");
        body.put("stores", stores);
        return body;
    }
}
