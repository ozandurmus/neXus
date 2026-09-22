package com.securityexpert.nexus.ui2.service.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Entry;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactEntryRepository.Listing;
import com.securityexpert.nexus.ui2.persistence.artefact.BackupArtefactManifestRepository.BackupArtefactSummary;
import com.securityexpert.nexus.ui2.service.device.backup.BackupCompareService;
import com.securityexpert.nexus.ui2.service.device.backup.BackupRelistService;

/**
 * V41 / docs/design/BACKUP_ARCHIVE_CONTENT_LISTING_AND_COMPARE.md: what a
 * backup holds ({@code GET /backups/{id}/entries}) and how two backups of
 * one device differ ({@code GET /backups/{a}/compare/{b}}). Names, types,
 * sizes and digests only -- never an entry's content, never a store path.
 * Both are posture reads, gated like {@code GET /backups}.
 */
@RestController
public final class BackupContentController {

    private static final Pattern ARTEFACT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");
    /** A Gaia archive lists in the thousands; the screen pages client-side, the wire carries at most this many. */
    private static final int MAX_ENTRIES_ON_WIRE = 20_000;

    private final BackupArtefactEntryRepository entryRepository;
    private final BackupCompareService compareService;
    private final BackupRelistService relistService;

    public BackupContentController(BackupArtefactEntryRepository entryRepository, BackupCompareService compareService,
            BackupRelistService relistService) {
        this.entryRepository = entryRepository;
        this.compareService = compareService;
        this.relistService = relistService;
    }

    /** "List now" for an artefact without a listing; role-gated like the download since it decrypts on the service. */
    @PostMapping("/backups/{artefactId}/relist")
    public ResponseEntity<Map<String, Object>> relist(@PathVariable String artefactId) {
        if (artefactId == null || !ARTEFACT_ID.matcher(artefactId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "INVALID_ARTEFACT_ID"));
        }
        BackupRelistService.Outcome outcome = relistService.relist(artefactId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artefact_id", artefactId);
        return switch (outcome) {
            case BackupRelistService.Outcome.Listed listed -> {
                body.put("listing_state", "LISTED");
                body.put("entry_count", listed.entryCount());
                yield ResponseEntity.ok(body);
            }
            case BackupRelistService.Outcome.ListingFailed ignored -> {
                body.put("listing_state", "FAILED");
                yield ResponseEntity.ok(body); // recorded as FAILED with its reason; GET /entries carries it
            }
            case BackupRelistService.Outcome.ArtefactNotFound ignored -> {
                body.put("error", "ARTEFACT_NOT_FOUND");
                yield ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            case BackupRelistService.Outcome.StoreUnavailable ignored -> {
                body.put("error", "ARTEFACT_STORE_NOT_MOUNTED");
                yield ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
            }
        };
    }

    @GetMapping("/backups/{artefactId}/entries")
    public ResponseEntity<Map<String, Object>> entries(@PathVariable String artefactId) {
        if (artefactId == null || !ARTEFACT_ID.matcher(artefactId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "INVALID_ARTEFACT_ID"));
        }
        Optional<Listing> listing = entryRepository.findListing(artefactId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("artefact_id", artefactId);
        if (listing.isEmpty()) {
            body.put("listing_state", "NOT_LISTED");
            body.put("entries", List.of());
            return ResponseEntity.ok(body);
        }
        body.put("listing_state", listing.get().listed() ? "LISTED" : "FAILED");
        listing.get().reason().ifPresent(reason -> body.put("reason", reason));
        body.put("listed_at", listing.get().listedAt().toString());
        body.put("entry_count", listing.get().entryCount());
        List<Entry> entries = listing.get().listed() ? entryRepository.findEntries(artefactId) : List.of();
        body.put("truncated", entries.size() > MAX_ENTRIES_ON_WIRE);
        body.put("entries", entries.stream().limit(MAX_ENTRIES_ON_WIRE).map(BackupContentController::toEntryBody).toList());
        return ResponseEntity.ok(body);
    }

    /**
     * One path variable only: the route map resolves a single wildcard segment per route (measured
     * live 2026-09-22: {@code /backups/a/compare/b} never resolved and every compare answered 403
     * ACTION_MAPPING_REQUIRED), so the second artefact travels as {@code ?with=}.
     */
    @GetMapping("/backups/{leftId}/compare")
    public ResponseEntity<Map<String, Object>> compare(@PathVariable String leftId,
            @org.springframework.web.bind.annotation.RequestParam(name = "with", required = false) String rightId) {
        if (leftId == null || rightId == null || !ARTEFACT_ID.matcher(leftId).matches()
                || !ARTEFACT_ID.matcher(rightId).matches()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", "INVALID_ARTEFACT_ID"));
        }
        BackupCompareService.Outcome outcome = compareService.compare(leftId, rightId);
        Map<String, Object> body = new LinkedHashMap<>();
        return switch (outcome) {
            case BackupCompareService.Outcome.Compared compared -> {
                body.put("left", toSideBody(compared.left()));
                body.put("right", toSideBody(compared.right()));
                body.put("identical", compared.identical());
                body.put("unchanged", compared.unchanged());
                body.put("added", compared.added());
                body.put("removed", compared.removed());
                body.put("changed", compared.changed().stream().map(c -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", c.path());
                    row.put("left_bytes", c.leftBytes());
                    row.put("right_bytes", c.rightBytes());
                    return row;
                }).toList());
                yield ResponseEntity.ok(body);
            }
            case BackupCompareService.Outcome.ArtefactNotFound notFound -> {
                body.put("error", "ARTEFACT_NOT_FOUND");
                body.put("artefact_id", notFound.artefactId());
                yield ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
            }
            case BackupCompareService.Outcome.DifferentDevices ignored -> {
                body.put("error", "DIFFERENT_DEVICES");
                body.put("reason", "Only two backups of the same device can be compared");
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
            case BackupCompareService.Outcome.NotListed notListed -> {
                body.put("error", "NOT_LISTED");
                body.put("artefact_id", notListed.artefactId());
                notListed.reason().ifPresent(reason -> body.put("reason", reason));
                yield ResponseEntity.status(HttpStatus.CONFLICT).body(body);
            }
        };
    }

    private static Map<String, Object> toEntryBody(Entry entry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("path", entry.path());
        row.put("type", entry.type().wire());
        row.put("bytes", entry.bytes());
        entry.sha256().ifPresent(digest -> row.put("digest_prefix", digest.substring(0, Math.min(12, digest.length()))));
        return row;
    }

    private static Map<String, Object> toSideBody(BackupArtefactSummary summary) {
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("artefact_id", summary.artefactId());
        side.put("device_id", summary.deviceId());
        side.put("collected_at", summary.createdAt().toString());
        side.put("size_bytes", summary.plaintextBytes());
        side.put("vendor", summary.vendor());
        return side;
    }
}
