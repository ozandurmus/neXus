package com.securityexpert.nexus.ui2.worker.diagnostic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactRef;

/**
 * The artefact volume as the system-log collector sees it (DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 5.1): the
 * free-space guard (no chunk is written below 15 % free; reason: the 2026-10-10 disk incident) and the removal of a
 * stored chunk whose database record could not be written, so a refused write leaves no orphan behind.
 */
public interface SystemLogArtefactVolume {
    /** Section 5.1: a chunk is not written when less than this fraction of the volume is free. */
    double MIN_FREE_FRACTION = 0.15;

    /** Free fraction of the volume in [0, 1]; a volume that cannot be measured is reported as 0 (fail closed). */
    double freeFraction();

    /** Best effort: removes the stored object; never throws. */
    void discard(ArtefactRef ref);

    final class FileSystem implements SystemLogArtefactVolume {
        private final Path root;

        public FileSystem(Path root) {
            this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        }

        @Override
        public double freeFraction() {
            try {
                var store = Files.getFileStore(root);
                long total = store.getTotalSpace();
                return total <= 0 ? 0.0 : (double) store.getUsableSpace() / total;
            } catch (IOException | RuntimeException unreadable) {
                return 0.0;
            }
        }

        @Override
        public void discard(ArtefactRef ref) {
            try {
                Path file = root.resolve(ref.value()).normalize();
                if (file.startsWith(root)) Files.deleteIfExists(file);
            } catch (IOException | RuntimeException ignored) {
                // An unreferenced file is later removed by the orphan purger; nothing here may fail the job.
            }
        }
    }
}
