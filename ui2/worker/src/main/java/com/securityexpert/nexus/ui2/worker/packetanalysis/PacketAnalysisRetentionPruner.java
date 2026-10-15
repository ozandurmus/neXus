package com.securityexpert.nexus.ui2.worker.packetanalysis;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

import com.securityexpert.nexus.ui2.persistence.packetanalysis.PacketAnalysisRepository;
import com.securityexpert.nexus.ui2.platform.PacketAnalysisPaths;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * Decision record 2026-10-15 §5: a case and everything derived from it are deleted once {@code expires_at}
 * (creation + 7 days) has passed. Mirrors backup retention's truthful-tombstone pattern
 * ({@code RetentionPruningService}): the case directory is removed first, and the rows plus the
 * {@code CASE_PRUNED} ledger row are written only when that succeeded; a case whose files could not be removed
 * keeps its rows and is retried on the next pass. A case with an analysis still queued or running waits.
 */
public final class PacketAnalysisRetentionPruner {

    public record Summary(int casesPruned, int failures) {
    }

    private static final System.Logger LOG = System.getLogger(PacketAnalysisRetentionPruner.class.getName());

    private final PacketAnalysisRepository repository;
    private final Path root;

    public PacketAnalysisRetentionPruner(PacketAnalysisRepository repository, Path root) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
    }

    public Summary runOnce() {
        int pruned = 0;
        int failures = 0;
        for (String caseId : repository.expiredCases(200)) {
            try {
                if (repository.prune(caseId, WorkerActor.RESERVED_ACTOR_FINGERPRINT, () -> deleteCaseDirectory(caseId))) pruned++;
            } catch (RuntimeException failed) {
                failures++;
                LOG.log(System.Logger.Level.WARNING, "[PACKET_ANALYSIS_RETENTION] case " + caseId + " not pruned: "
                        + failed.getClass().getSimpleName());
            }
        }
        if (pruned > 0 || failures > 0)
            LOG.log(System.Logger.Level.INFO, "[PACKET_ANALYSIS_RETENTION] pruned=" + pruned + " failures=" + failures);
        return new Summary(pruned, failures);
    }

    void deleteCaseDirectory(String caseId) {
        Path dir = PacketAnalysisPaths.caseDir(root, caseId);
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }
}
