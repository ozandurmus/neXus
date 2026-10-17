package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.List;
import java.util.Objects;

/**
 * The plan of one source for one run (contract section 4.4): reads in execution order, gap rows, and what the
 * executor needs to write the cursor afterwards.
 *
 * @param family the source
 * @param outcome the predicted outcome (section 9); BACKLOG wins over GAP when both apply
 * @param unknownReason set only when {@code outcome} is UNKNOWN
 * @param reads reads in execution order; each is stored before the next is sent
 * @param gaps gap rows, including the BACKLOG row when {@code pendingBytes > 0}
 * @param plannedBytes payload bytes of {@code reads}
 * @param pendingBytes exact bytes the budgets left unread, all still visible in the stat
 * @param rotationFollowed true when the plan continues across a rotation without a loss (ROTATION_FOLLOWED flag)
 * @param fastRotation true when more than one rotation happened since the last read, or a rotation was lost
 * @param rotationsSinceLastRead rotations detected; -1 when it cannot be known
 * @param familySnapshot every family member of this run's stat, to be stored as the new {@code family_snapshot}
 * @param seedCursorInode set only for a first run on an empty file: the executor creates a cursor at offset 0 for
 *        this inode, so the first bytes ever written are not mistaken for a first run later
 */
public record SourcePlan(
        SourceFamily family,
        SourceOutcome outcome,
        UnknownReason unknownReason,
        List<ReadAction> reads,
        List<Gap> gaps,
        long plannedBytes,
        long pendingBytes,
        boolean rotationFollowed,
        boolean fastRotation,
        int rotationsSinceLastRead,
        List<FileStat> familySnapshot,
        Long seedCursorInode) {

    public SourcePlan {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(outcome, "outcome");
        reads = List.copyOf(reads);
        gaps = List.copyOf(gaps);
        familySnapshot = List.copyOf(familySnapshot);
    }

    static SourcePlan unknown(SourceFamily family, UnknownReason reason, List<FileStat> snapshot) {
        return new SourcePlan(family, SourceOutcome.UNKNOWN, reason, List.of(), List.of(), 0, 0, false, false, 0,
                snapshot, null);
    }

    static SourcePlan absent(SourceFamily family, List<FileStat> snapshot) {
        return new SourcePlan(family, SourceOutcome.ABSENT, null, List.of(), List.of(), 0, 0, false, false, 0,
                snapshot, null);
    }

    /** True when the plan reads nothing and records nothing. */
    public boolean isEmpty() {
        return reads.isEmpty() && gaps.isEmpty();
    }

    /** The first gap of a kind, or null. */
    public Gap gap(GapKind kind) {
        for (Gap gap : gaps) {
            if (gap.kind() == kind) {
                return gap;
            }
        }
        return null;
    }
}
