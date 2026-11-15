package com.securityexpert.nexus.ui2.service.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.securityexpert.nexus.ui2.service.device.inventory.ClusterMemberDiffService;

/**
 * {@code GET /clusters/{cluster_member_ref}/member-diff}: read-only, gated exactly like the cluster inventory
 * ({@code device_read}); this class performs no authorization check of its own.
 */
@RestController
public final class ClusterMemberDiffController {
    private final ClusterMemberDiffService service;

    public ClusterMemberDiffController(ClusterMemberDiffService service) {
        this.service = service;
    }

    @GetMapping("/clusters/{clusterMemberRef}/member-diff")
    public ResponseEntity<Map<String, Object>> memberDiff(@PathVariable String clusterMemberRef) {
        return service.forCluster(clusterMemberRef)
                .map(result -> ResponseEntity.ok(result.toMap()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND")));
    }
}
