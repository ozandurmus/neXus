package com.securityexpert.nexus.ui2.service.supportbundle;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.securityexpert.nexus.ui2.jobs.supportbundle.SupportBundleKind;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.ItemRow;
import com.securityexpert.nexus.ui2.persistence.supportbundle.JooqSupportBundleStore.RequestRow;
import com.securityexpert.nexus.ui2.service.security.GateChainInterceptor;

/**
 * Support bundle routes (design sections 5.10 and 5.11). Reads return metadata only: opaque ids, the deterministic device
 * pseudonym, kinds, states, sizes, a 12-character digest prefix and the expiry. For the replay viewer (aiview) the
 * request actor, the reason and the case reference are left out, and every write, download and delete is refused again
 * here as defence in depth (the route gate already refuses it).
 */
@RestController
public final class SupportBundleController {

    private static final int DIGEST_PREFIX = 12;
    private static final java.time.Duration TICKET_TTL = java.time.Duration.ofSeconds(60);
    private static final java.security.SecureRandom RANDOM = new java.security.SecureRandom();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    public record RequestBody_(@JsonProperty("kinds") List<String> kinds, @JsonProperty("run_at") String runAt,
            @JsonProperty("case_ref") String caseRef, @JsonProperty("reason") String reason) {
    }

    public record ReasonBody(@JsonProperty("reason") String reason, @JsonProperty("mode") String mode) {
    }

    private record Ticket(UUID requestId, String actor, String reason, boolean sanitized, Instant expiresAt) {
    }

    private final SupportBundleService service;
    private final SupportBundleDownloadService downloads;
    private final JooqSupportBundleStore store;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public SupportBundleController(SupportBundleService service, SupportBundleDownloadService downloads,
            JooqSupportBundleStore store) {
        this.service = service; this.downloads = downloads; this.store = store;
    }

    // ---------------------------------------------------------------- reads

    @GetMapping("/api/v2/support-bundles/catalogue")
    public ResponseEntity<Map<String, Object>> catalogue() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SupportBundleKind k : SupportBundleKind.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", k.id());
            row.put("vendor", k.vendor());
            row.put("load", k.load());
            row.put("default_ticked", k.defaultTicked());
            row.put("enabled", k.enabled());
            row.put("heavy", k.heavy());
            rows.add(row);
        }
        return ResponseEntity.ok(Map.of("kinds", rows));
    }

    @GetMapping("/api/v2/support-bundles")
    public ResponseEntity<Map<String, Object>> list(@RequestParam(required = false) String state,
            @RequestParam(required = false, defaultValue = "100") int limit, HttpServletRequest http) {
        return listBody(Optional.empty(), state, limit, http);
    }

    @GetMapping("/api/v2/devices/{deviceId}/support-bundles")
    public ResponseEntity<Map<String, Object>> deviceList(@PathVariable String deviceId,
            @RequestParam(required = false) String state, @RequestParam(required = false, defaultValue = "100") int limit,
            HttpServletRequest http) {
        if (!safeId(deviceId)) return error(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_ID", "Device identifier must be an opaque id");
        return listBody(Optional.of(deviceId), state, limit, http);
    }

    private ResponseEntity<Map<String, Object>> listBody(Optional<String> deviceId, String state, int limit, HttpServletRequest http) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RequestRow r : store.list(deviceId, Optional.ofNullable(state).filter(s -> !s.isBlank()), limit)) {
            rows.add(requestBody(r, store.items(r.id()), viewer(http)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("support_bundles", rows);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/api/v2/support-bundles/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id, HttpServletRequest http) {
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        Optional<RequestRow> row = store.find(uuid.get());
        if (row.isEmpty()) return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "No support bundle with that id");
        return ResponseEntity.ok(requestBody(row.get(), store.items(row.get().id()), viewer(http)));
    }

    // ---------------------------------------------------------------- writes

    @PostMapping("/api/v2/devices/{deviceId}/support-bundles")
    public ResponseEntity<Map<String, Object>> request(@PathVariable String deviceId,
            @RequestBody(required = false) RequestBody_ body, HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        if (!safeId(deviceId)) return error(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_ID", "Device identifier must be an opaque id");
        if (body == null) return error(HttpStatus.BAD_REQUEST, "BODY_REQUIRED", "A request body is required");
        Optional<Instant> runAt = Optional.empty();
        if (body.runAt() != null && !body.runAt().isBlank()) {
            try {
                runAt = Optional.of(Instant.parse(body.runAt().strip()));
            } catch (DateTimeParseException e) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_RUN_AT", "run_at must be an ISO-8601 instant, for example 2026-11-16T01:00:00Z");
            }
        }
        SupportBundleService.Outcome outcome = service.request(deviceId, body.kinds(), runAt, body.caseRef(), body.reason(),
                actor(http));
        return switch (outcome) {
            case SupportBundleService.Outcome.Accepted accepted -> {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("id", accepted.requestId().toString());
                map.put("job_id", accepted.jobId());
                map.put("status", "ACCEPTED");
                map.put("mds_warning", accepted.mdsWarning());
                yield ResponseEntity.status(HttpStatus.ACCEPTED).body(map);
            }
            case SupportBundleService.Outcome.Refused refused ->
                    error(HttpStatus.valueOf(refused.status()), refused.code(), refused.reason());
        };
    }

    @PostMapping("/api/v2/support-bundles/{id}/cancel")
    public ResponseEntity<Map<String, Object>> cancel(@PathVariable String id, @RequestBody(required = false) ReasonBody body,
            HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        if (body == null || body.reason() == null || body.reason().strip().length() < 8) {
            return error(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason of at least 8 characters is required");
        }
        return service.cancel(uuid.get(), actor(http))
                ? ResponseEntity.ok(Map.of("id", id, "state", "CANCELLED"))
                : error(HttpStatus.CONFLICT, "NOT_CANCELLABLE", "Only a queued request can be cancelled");
    }

    @PostMapping("/api/v2/support-bundles/{id}/delete")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id, @RequestBody(required = false) ReasonBody body,
            HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        if (body == null || body.reason() == null || body.reason().strip().length() < 8) {
            return error(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason of at least 8 characters is required");
        }
        return service.delete(uuid.get(), actor(http), body.reason().strip())
                ? ResponseEntity.ok(Map.of("id", id, "state", "DELETED"))
                : error(HttpStatus.CONFLICT, "NOT_DELETABLE", "A queued or running request, or one already deleted, cannot be deleted");
    }

    // ---------------------------------------------------------------- download (two steps, as a backup)

    @PostMapping("/api/v2/support-bundles/{id}/download-ticket")
    public ResponseEntity<Map<String, Object>> ticket(@PathVariable String id, @RequestBody(required = false) ReasonBody body,
            HttpServletRequest http) {
        ResponseEntity<Map<String, Object>> refusal = refuseViewerOrAnonymous(http);
        if (refusal != null) return refusal;
        Optional<UUID> uuid = uuid(id);
        if (uuid.isEmpty()) return error(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        String reason = body == null || body.reason() == null ? "" : body.reason().strip();
        if (reason.length() < SupportBundleDownloadService.MIN_REASON_LENGTH) {
            return error(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason of at least 8 characters is required");
        }
        Instant now = Instant.now();
        tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String ticket = java.util.HexFormat.of().formatHex(raw);
        boolean sanitized = body != null && "sanitized".equals(body.mode());
        tickets.put(ticket, new Ticket(uuid.get(), actor(http), reason, sanitized, now.plus(TICKET_TTL)));
        return ResponseEntity.ok(Map.of("ticket", ticket, "expires_in_s", TICKET_TTL.toSeconds(),
                "href", "/api/v2/support-bundles/" + id + "/download?ticket=" + ticket));
    }

    @GetMapping("/api/v2/support-bundles/{id}/download")
    public ResponseEntity<StreamingResponseBody> downloadWithTicket(@PathVariable String id, @RequestParam String ticket,
            HttpServletRequest http) {
        Ticket t = ticket == null ? null : tickets.remove(ticket);
        String actor = actor(http);
        Optional<UUID> uuid = uuid(id);
        if (t == null || uuid.isEmpty() || t.expiresAt().isBefore(Instant.now()) || !t.requestId().equals(uuid.get())
                || actor == null || !actor.equals(t.actor()) || isViewer(http)) {
            return refusal(HttpStatus.FORBIDDEN, "TICKET_INVALID", "The download ticket is missing, used, expired, or not yours");
        }
        return stream(actor, t.requestId(), t.reason(), t.sanitized());
    }

    @PostMapping("/api/v2/support-bundles/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id, @RequestBody(required = false) ReasonBody body,
            HttpServletRequest http) {
        String actor = actor(http);
        Optional<UUID> uuid = uuid(id);
        if (actor == null || actor.isBlank() || isViewer(http)) {
            return refusal(HttpStatus.FORBIDDEN, "AUTHENTICATION_REQUIRED", "An authenticated administrator is required");
        }
        if (uuid.isEmpty()) return refusal(HttpStatus.BAD_REQUEST, "INVALID_ID", "Identifier must be a UUID");
        return stream(actor, uuid.get(), body == null ? null : body.reason(), body != null && "sanitized".equals(body.mode()));
    }

    private ResponseEntity<StreamingResponseBody> stream(String actor, UUID id, String reason, boolean sanitized) {
        SupportBundleDownloadService.Outcome outcome = downloads.prepare(actor, id, reason, sanitized);
        return switch (outcome) {
            case SupportBundleDownloadService.Outcome.Ready ready -> ResponseEntity.ok()
                    .header("Content-Type", "application/gzip")
                    .header("Content-Disposition", "attachment; filename=\"" + ready.fileName() + "\"")
                    .header("Cache-Control", "no-store")
                    .body(out -> ready.body().writeTo(out));
            case SupportBundleDownloadService.Outcome.ReasonTooShort ignored ->
                    refusal(HttpStatus.BAD_REQUEST, "REASON_REQUIRED", "A reason of at least 8 characters is required");
            case SupportBundleDownloadService.Outcome.NotFound ignored ->
                    refusal(HttpStatus.NOT_FOUND, "NOT_FOUND", "No support bundle with that id");
            case SupportBundleDownloadService.Outcome.NothingToDownload nothing ->
                    refusal(HttpStatus.CONFLICT, "NOTHING_TO_DOWNLOAD", "The bundle is " + nothing.state() + " and holds nothing to download");
            case SupportBundleDownloadService.Outcome.AuditRefused ignored ->
                    refusal(HttpStatus.INTERNAL_SERVER_ERROR, "AUDIT_REFUSED", "The retrieval could not be audited; no bytes were sent");
            case SupportBundleDownloadService.Outcome.StoreUnavailable unavailable ->
                    refusal(HttpStatus.SERVICE_UNAVAILABLE, "DOWNLOAD_UNAVAILABLE", unavailable.reason());
            case SupportBundleDownloadService.Outcome.MaskingUnavailable ignored ->
                    refusal(HttpStatus.SERVICE_UNAVAILABLE, "MASKING_UNAVAILABLE", "The identity dictionary is not ready; no bytes were sent");
        };
    }

    // ---------------------------------------------------------------- helpers

    private Map<String, Object> requestBody(RequestRow r, List<ItemRow> items, boolean viewer) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id().toString());
        m.put("device_id", r.deviceId());
        m.put("device_pseudonym", downloads.pseudonym(r.deviceId()));
        m.put("vendor", r.vendor());
        m.put("platform_role", r.platformRole());
        m.put("run_mode", r.runMode());
        m.put("run_at", r.runAt());
        m.put("requested_at", r.requestedAt());
        m.put("started_at", r.startedAt());
        m.put("finished_at", r.finishedAt());
        m.put("expires_at", r.expiresAt());
        m.put("expiry_warning", r.expiryWarnedAt() != null && !"DELETED".equals(r.state()));
        m.put("downloaded_at", r.downloadedAt());
        m.put("download_count", r.downloadCount());
        m.put("state", r.state());
        m.put("outcome", r.outcome());
        m.put("job_id", r.jobId());
        m.put("prechecks", precheck(r.precheckJson()));
        if (!viewer) {
            m.put("requested_by", r.requestedBy());
            m.put("reason", r.reason());
            m.put("case_ref", r.caseRef());
            m.put("deleted_by", r.deletedBy());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ItemRow i : items) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", i.kind());
            e.put("ordinal", i.ordinal());
            e.put("state", i.state());
            e.put("outcome_code", i.outcomeCode());
            e.put("size_bytes", i.sizeBytes());
            e.put("digest_prefix", i.sha256() == null ? null : i.sha256().substring(0, Math.min(DIGEST_PREFIX, i.sha256().length())));
            e.put("gate_ids_used", i.gateIdsUsed());
            e.put("detail", i.outcomeDetail());
            rows.add(e);
        }
        m.put("items", rows);
        return m;
    }

    private static Object precheck(String json) {
        try {
            return JSON.readTree(json == null ? "{}" : json);
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    private static ResponseEntity<Map<String, Object>> refuseViewerOrAnonymous(HttpServletRequest http) {
        String actor = actor(http);
        if (actor == null || actor.isBlank()) {
            return error(HttpStatus.FORBIDDEN, "AUTHENTICATION_REQUIRED", "An authenticated actor is required");
        }
        if (isViewer(http)) return error(HttpStatus.FORBIDDEN, "FORBIDDEN_FOR_VIEWER", "This action is not available to the replay viewer");
        return null;
    }

    private static boolean viewer(HttpServletRequest http) {
        return isViewer(http);
    }

    private static boolean isViewer(HttpServletRequest http) {
        return http != null && Boolean.TRUE.equals(http.getAttribute(GateChainInterceptor.IS_REPLAY_VIEWER_ATTRIBUTE));
    }

    private static String actor(HttpServletRequest http) {
        return http == null ? null : (String) http.getAttribute(GateChainInterceptor.ACTOR_FINGERPRINT_ATTRIBUTE);
    }

    private static Optional<UUID> uuid(String id) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean safeId(String id) {
        return id != null && !id.isBlank() && id.length() <= 128 && id.matches("[A-Za-z0-9._:-]+");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("code", code);
        body.put("reason", reason);
        return ResponseEntity.status(status).body(body);
    }

    private static ResponseEntity<StreamingResponseBody> refusal(HttpStatus status, String code, String reason) {
        byte[] payload;
        try {
            payload = JSON.writeValueAsBytes(Map.of("error", code, "code", code, "reason", reason));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            payload = ("{\"error\":\"" + code + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        byte[] bytes = payload;
        return ResponseEntity.status(status).header("Content-Type", "application/json").body(out -> out.write(bytes));
    }
}
