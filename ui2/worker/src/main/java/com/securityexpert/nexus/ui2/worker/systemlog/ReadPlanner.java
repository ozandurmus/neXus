package com.securityexpert.nexus.ui2.worker.systemlog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The incremental-read planner of DEVICE_SYSTEM_LOG_PHASE_B_CONTRACT.md section 4.4: a pure function from
 * (stored cursor, this run's C1 stat) to the reads and gap records of one source. It follows every row of the
 * decision table and both rotation modes, because the mode is still PENDING (section 11): a rename rotation is
 * recognised by the cursor's inode being found under a sibling name, a copytruncate by the cursor's file being
 * smaller than the cursor offset.
 *
 * <h2>Budgets</h2>
 * The per-read cap, the per-source budget and the per-device budget limit <em>payload</em> bytes. The plan reads
 * in order and stops at the first budget it hits; it never skips ahead. What it leaves unread is the exact
 * {@code pendingBytes} (still visible in the stat) and a {@code BACKLOG} gap row; nothing is lost silently.
 *
 * <h2>Anchor feedback</h2>
 * The anchor check needs bytes from the device, so the planner learns its result only by being called again with
 * {@link PlanContext#withMismatch}. The first plan assumes every anchor matches. A copytruncate has several possible
 * continuation files; the plan names the newest plausible one and the re-plan after a mismatch names the next.
 *
 * <h2>Choices the contract leaves open</h2>
 * <ul>
 * <li>A rotated sibling already in the stored family snapshot and older than the cursor's file was read in an
 *     earlier run and is not read again.</li>
 * <li>A missing base file is {@code ABSENT} even when a cursor exists; the cursor is untouched and the next run
 *     continues from it.</li>
 * <li>A first run on an empty file reads nothing and asks the executor to seed a cursor at offset 0.</li>
 * <li>A first run with no per-device budget left is deferred (no read, no baseline gap, {@code BACKLOG}); the
 *     baseline gap is recorded by the run that really skips the prefix.</li>
 * </ul>
 */
public final class ReadPlanner {

    private ReadPlanner() {
    }

    /** Plans every incremental source of one device against one shared per-device budget, in run order. */
    public static DevicePlan planDevice(Map<SourceFamily, SourceCursor> cursors, StatParseResult stat,
            PlannerConfig config) {
        Objects.requireNonNull(cursors, "cursors");
        Map<SourceFamily, SourcePlan> plans = new EnumMap<>(SourceFamily.class);
        long spent = 0;
        for (SourceFamily family : SourceFamily.runOrder()) {
            SourcePlan plan = plan(family, cursors.get(family), stat, config, new PlanContext(spent, Set.of()));
            plans.put(family, plan);
            spent += plan.plannedBytes();
        }
        return new DevicePlan(new ArrayList<>(plans.values()), spent);
    }

    public static SourcePlan plan(SourceFamily family, SourceCursor cursor, StatParseResult stat,
            PlannerConfig config) {
        return plan(family, cursor, stat, config, PlanContext.fresh());
    }

    /**
     * Plans one source.
     *
     * @param cursor the stored cursor, or null for a first run
     * @param stat the parsed C1 output of this run
     * @param context budget already spent and anchor results already known in this run
     */
    public static SourcePlan plan(SourceFamily family, SourceCursor cursor, StatParseResult stat,
            PlannerConfig config, PlanContext context) {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(stat, "stat");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(context, "context");
        if (!stat.usable()) {
            return SourcePlan.unknown(family, UnknownReason.STAT_UNREADABLE, List.of());
        }
        if (stat.malformedFamilies().contains(family)) {
            return SourcePlan.unknown(family, UnknownReason.STAT_MALFORMED_FOR_SOURCE, List.of());
        }
        Names names = new Names(family, config);
        View view = View.of(stat.membersOf(family), names);
        if (view.base == null) {
            if (stat.errorFamilies().contains(family)) {
                return SourcePlan.unknown(family, UnknownReason.STAT_ERROR_FOR_SOURCE, view.all);
            }
            return SourcePlan.absent(family, view.all);
        }
        if (cursor != null && !cursor.isConsistent()) {
            return SourcePlan.unknown(family, UnknownReason.CURSOR_INVALID, view.all);
        }

        long windowEnd = stat.deviceEpoch();
        long windowStart = cursor == null ? windowEnd : cursor.deviceEpochAtRead();
        Builder b = new Builder(config, windowStart, windowEnd, context);
        Long seed = null;
        if (cursor == null) {
            seed = firstRun(b, view, config);
        } else {
            Planning p = new Planning(b, view, cursor, names, context, config);
            FileStat live = view.base;
            if (live.inode() == cursor.inode()) {
                p.cursorFileIsLive();
            } else {
                Member sibling = view.byInode(cursor.inode());
                if (sibling != null) {
                    p.cursorFileIsSibling(sibling);
                } else {
                    p.cursorFileGone();
                }
            }
        }
        return b.finish(family, view.all, seed);
    }

    // ---------------------------------------------------------------- first run

    private static Long firstRun(Builder b, View view, PlannerConfig config) {
        FileStat live = view.base;
        if (live.size() == 0) {
            return live.inode();
        }
        long start = Math.max(0, live.size() - config.backfillBytes());
        b.segment(live, start, ReadPurpose.INITIAL_BASELINE, null, false, false);
        if (!b.reads.isEmpty() && start > 0) {
            b.gaps.add(Gap.initialBaseline(start, b.windowEnd, live.path()));
        }
        return null;
    }

    // ---------------------------------------------------------------- cursor present

    private static final class Planning {
        private final Builder b;
        private final View view;
        private final SourceCursor cursor;
        private final Names names;
        private final PlanContext context;
        private final PlannerConfig config;

        Planning(Builder b, View view, SourceCursor cursor, Names names, PlanContext context, PlannerConfig config) {
            this.b = b;
            this.view = view;
            this.cursor = cursor;
            this.names = names;
            this.context = context;
            this.config = config;
        }

        private String anchor() {
            return cursor.offsetBytes() == 0 ? null : cursor.anchorHmac();
        }

        /** The cursor's inode is the live file: no change, growth, anchor mismatch or copytruncate. */
        void cursorFileIsLive() {
            FileStat live = view.base;
            long offset = cursor.offsetBytes();
            if (live.size() == offset) {
                return; // NO_NEW_DATA
            }
            if (live.size() > offset) {
                if (context.anchorMismatchPaths().contains(live.path())) {
                    b.gaps.add(anchorMismatch(live.path()));
                    b.segment(live, 0, ReadPurpose.ANCHOR_RESTART, null, false, false);
                } else {
                    b.segment(live, offset, ReadPurpose.GROWTH, anchor(), false, false);
                }
                return;
            }
            copytruncate();
        }

        /** The live file is smaller than the cursor offset: truncated in place, possibly after a copy. */
        private void copytruncate() {
            FileStat live = view.base;
            long offset = cursor.offsetBytes();
            // The cursor's file is the live one, so its rank is 0 and every snapshot sibling is older.
            List<Member> candidates = new ArrayList<>();
            for (Member m : view.readableByRankAscending()) {
                if (m.stat.inode() != cursor.inode() && m.stat.size() >= offset
                        && !context.anchorMismatchPaths().contains(m.stat.path()) && !consumed(m, 0)) {
                    candidates.add(m);
                }
            }
            if (!candidates.isEmpty()) {
                Member c = candidates.get(0);
                b.followedRotation = true;
                b.rotations = c.rank;
                b.segment(c.stat, offset, ReadPurpose.COPYTRUNCATE_REMAINDER, anchor(), true, true);
                readNewerSiblingsWhole(c.rank, 0);
                b.segment(live, 0, ReadPurpose.CURRENT_FROM_START, null, false, false);
                return;
            }
            b.rotations = -1;
            b.gaps.add(new Gap(GapKind.TRUNCATED, offset, true, 0, true, b.windowStart, b.windowEnd,
                    List.of(live.path()), 0, 0));
            for (Member m : view.readableByRankDescending()) {
                if (!consumed(m, 0)) {
                    b.segment(m.stat, 0, ReadPurpose.ROTATED_SIBLING_WHOLE, null, true, false);
                }
            }
            b.segment(live, 0, ReadPurpose.CURRENT_FROM_START, null, false, false);
        }

        /** The cursor's inode is found under a sibling name: rename rotation (or an unreadable sibling). */
        void cursorFileIsSibling(Member s) {
            FileStat live = view.base;
            long offset = cursor.offsetBytes();
            Integer cursorRank = snapshotRank(cursor.inode());
            if (!s.readable) {
                unreadableCursorFile(s, cursorRank);
                return;
            }
            b.followedRotation = true;
            b.rotations = cursorRank == null ? s.rank : Math.max(1, s.rank - cursorRank);
            if (context.anchorMismatchPaths().contains(s.stat.path())) {
                b.gaps.add(anchorMismatch(s.stat.path()));
                b.segment(s.stat, 0, ReadPurpose.ANCHOR_RESTART, null, true, false);
            } else if (s.stat.size() < offset) {
                b.gaps.add(new Gap(GapKind.TRUNCATED, offset, true, 0, true, b.windowStart, b.windowEnd,
                        List.of(s.stat.path()), 0, 0));
            } else {
                b.segment(s.stat, offset, ReadPurpose.ROTATED_REMAINDER, anchor(), true, false);
            }
            readNewerSiblingsWhole(s.rank, cursorRank);
            b.segment(live, 0, ReadPurpose.CURRENT_FROM_START, null, false, false);
        }

        /** The sibling holding the cursor's inode cannot be read (compressed, or outside the name pattern). */
        private void unreadableCursorFile(Member s, Integer cursorRank) {
            long offset = cursor.offsetBytes();
            Long lastSeen = snapshotSize(cursor.inode());
            long lost;
            if (lastSeen != null) {
                lost = Math.max(0, lastSeen - offset);
            } else {
                lost = s.compressed ? 0 : Math.max(0, s.stat.size() - offset);
            }
            long compressed = s.compressed ? s.stat.size() : 0;
            int unreadable = 1;
            if (s.rank != null) {
                for (Member m : view.siblings) {
                    if (!m.readable && m != s && m.rank != null && m.rank < s.rank && !inSnapshot(m.stat.inode())) {
                        unreadable++;
                        compressed += m.compressed ? m.stat.size() : 0;
                    }
                }
            }
            b.rotations = s.rank != null && cursorRank != null ? Math.max(1, s.rank - cursorRank) : -1;
            b.gaps.add(new Gap(GapKind.ROTATED_AWAY, lost, false, 0, true, b.windowStart, b.windowEnd,
                    List.of(s.stat.path()), compressed, unreadable));
            if (s.rank != null) {
                readNewerSiblingsWhole(s.rank, cursorRank);
            } else {
                for (Member m : view.readableByRankDescending()) {
                    if (!consumed(m, cursorRank)) {
                        b.segment(m.stat, 0, ReadPurpose.ROTATED_SIBLING_WHOLE, null, true, false);
                    }
                }
            }
            b.segment(view.base, 0, ReadPurpose.CURRENT_FROM_START, null, false, false);
        }

        /** The cursor's inode is nowhere in this run's stat: rotated away. */
        void cursorFileGone() {
            long offset = cursor.offsetBytes();
            Integer cursorRank = snapshotRank(cursor.inode());
            Long lastSeen = snapshotSize(cursor.inode());
            long lost = lastSeen == null ? 0 : Math.max(0, lastSeen - offset);
            if (cursorRank != null) {
                Set<Long> present = view.inodes();
                for (FileStat old : cursor.familySnapshot()) {
                    Integer r = names.rank(old.baseName());
                    if (old.inode() != cursor.inode() && r != null && r < cursorRank && !present.contains(old.inode())) {
                        lost = Math.addExact(lost, old.size()); // newer than the cursor's file, gone, never read
                    }
                }
            }
            List<Member> chain = new ArrayList<>();
            for (Member m : view.readableByRankDescending()) {
                if (!consumed(m, cursorRank)) {
                    chain.add(m);
                }
            }
            long firstInode = chain.isEmpty() ? view.base.inode() : chain.get(0).stat.inode();
            boolean extentUnknown = lastSeen == null || cursorRank == null || !inSnapshot(firstInode);
            long compressed = 0;
            int unreadable = 0;
            List<String> affected = new ArrayList<>();
            for (Member m : view.siblings) {
                if (!m.readable && !inSnapshot(m.stat.inode())) {
                    unreadable++;
                    compressed += m.compressed ? m.stat.size() : 0;
                    affected.add(m.stat.path());
                }
            }
            b.rotations = -1;
            b.gaps.add(new Gap(GapKind.ROTATED_AWAY, lost, false, 0, extentUnknown, b.windowStart, b.windowEnd,
                    affected, compressed, unreadable));
            for (Member m : chain) {
                b.segment(m.stat, 0, ReadPurpose.ROTATED_SIBLING_WHOLE, null, true, false);
            }
            b.segment(view.base, 0, ReadPurpose.CURRENT_FROM_START, null, false, false);
        }

        /** Siblings newer than rank {@code rank} that this product never read, whole, oldest first. */
        private void readNewerSiblingsWhole(Integer rank, Integer cursorRank) {
            long compressed = 0;
            int unreadable = 0;
            List<String> affected = new ArrayList<>();
            for (Member m : view.siblings) {
                if (!m.readable && m.rank != null && rank != null && m.rank < rank && !inSnapshot(m.stat.inode())) {
                    unreadable++;
                    compressed += m.compressed ? m.stat.size() : 0;
                    affected.add(m.stat.path());
                }
            }
            if (unreadable > 0) {
                b.gaps.add(new Gap(GapKind.ROTATED_AWAY, 0, false, 0, true, b.windowStart, b.windowEnd, affected,
                        compressed, unreadable));
            }
            for (Member m : view.readableByRankDescending()) {
                if (rank != null && m.rank < rank && !consumed(m, cursorRank)) {
                    b.segment(m.stat, 0, ReadPurpose.ROTATED_SIBLING_WHOLE, null, true, false);
                }
            }
        }

        private Gap anchorMismatch(String path) {
            return new Gap(GapKind.ANCHOR_MISMATCH, 0, false, 0, true, b.windowStart, b.windowEnd, List.of(path), 0, 0);
        }

        /** True when the stored snapshot shows this inode at a rank older than the cursor's file: already read. */
        private boolean consumed(Member m, Integer cursorRank) {
            if (cursorRank == null) {
                return false;
            }
            Integer r = snapshotRank(m.stat.inode());
            return r != null && r > cursorRank;
        }

        private Integer snapshotRank(long inode) {
            for (FileStat old : cursor.familySnapshot()) {
                if (old.inode() == inode) {
                    return names.rank(old.baseName());
                }
            }
            return null;
        }

        private Long snapshotSize(long inode) {
            for (FileStat old : cursor.familySnapshot()) {
                if (old.inode() == inode) {
                    return old.size();
                }
            }
            return null;
        }

        private boolean inSnapshot(long inode) {
            for (FileStat old : cursor.familySnapshot()) {
                if (old.inode() == inode) {
                    return true;
                }
            }
            return false;
        }
    }

    // ---------------------------------------------------------------- builder

    private static final class Builder {
        final PlannerConfig config;
        final long windowStart;
        final long windowEnd;
        long sourceRemaining;
        long deviceRemaining;
        boolean stopped;
        final List<ReadAction> reads = new ArrayList<>();
        final List<Gap> gaps = new ArrayList<>();
        final Set<String> pendingPaths = new LinkedHashSet<>();
        long planned;
        long pending;
        boolean followedRotation;
        int rotations;

        Builder(PlannerConfig config, long windowStart, long windowEnd, PlanContext context) {
            this.config = config;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.sourceRemaining = config.sourceBudgetBytes();
            this.deviceRemaining = Math.max(0, config.deviceBudgetBytes() - context.deviceBytesAlreadyPlanned());
        }

        /**
         * Plans the bytes {@code [start, size)} of one file, split into reads of at most the per-read cap and cut at
         * the budgets. Once a budget has stopped the plan, later files add only their unread size to the pending
         * bytes. {@code verifyEmpty} plans a zero-length anchor-verification read when nothing is left to read.
         */
        void segment(FileStat file, long start, ReadPurpose purpose, String anchor, boolean rotatedFile,
                boolean verifyEmpty) {
            long remaining = Math.max(0, file.size() - start);
            if (stopped) {
                addPending(file, remaining);
                return;
            }
            if (remaining == 0) {
                if (anchor != null && verifyEmpty) {
                    reads.add(new ReadAction(file.path(), file.inode(), start, 0,
                            (int) Math.min(config.anchorBytes(), start), anchor, purpose, false, rotatedFile));
                }
                return;
            }
            long allowed = Math.min(remaining, Math.min(sourceRemaining, deviceRemaining));
            if (allowed < remaining) {
                stopped = true;
                addPending(file, remaining - allowed);
            }
            long position = start;
            long left = allowed;
            boolean first = true;
            while (left > 0) {
                long length = Math.min(config.readCapBytes(), left);
                boolean reachesEnd = position + length == file.size();
                reads.add(new ReadAction(file.path(), file.inode(), position, length,
                        (int) Math.min(config.anchorBytes(), position), first ? anchor : null, purpose, !first,
                        rotatedFile && reachesEnd));
                position += length;
                left -= length;
                first = false;
            }
            sourceRemaining -= allowed;
            deviceRemaining -= allowed;
            planned += allowed;
        }

        private void addPending(FileStat file, long bytes) {
            if (bytes > 0) {
                pending = Math.addExact(pending, bytes);
                pendingPaths.add(file.path());
            }
        }

        SourcePlan finish(SourceFamily family, List<FileStat> snapshot, Long seed) {
            List<Gap> all = new ArrayList<>(gaps);
            if (pending > 0) {
                all.add(Gap.backlog(pending, windowStart, windowEnd, new ArrayList<>(pendingPaths)));
            }
            boolean loss = gaps.stream().anyMatch(Gap::isLoss);
            boolean rotatedAway = gaps.stream().anyMatch(g -> g.kind() == GapKind.ROTATED_AWAY);
            SourceOutcome outcome;
            if (pending > 0) {
                outcome = SourceOutcome.BACKLOG;
            } else if (loss) {
                outcome = SourceOutcome.GAP;
            } else if (!reads.isEmpty()) {
                outcome = SourceOutcome.COLLECTED;
            } else {
                outcome = SourceOutcome.NO_NEW_DATA;
            }
            boolean followed = followedRotation && !loss;
            boolean fast = rotations > 1 || rotatedAway;
            return new SourcePlan(family, outcome, null, reads, all, planned, pending, followed, fast, rotations,
                    snapshot, seed);
        }
    }

    // ---------------------------------------------------------------- family view

    /** File-name classification for one family under the configured sibling pattern. */
    private static final class Names {
        private final String base;
        private final String compressedSuffix;
        private final Pattern rank;
        private final Pattern eligible;

        Names(SourceFamily family, PlannerConfig config) {
            this.base = family.baseName();
            this.compressedSuffix = config.compressedSuffix();
            this.rank = Pattern.compile("^" + Pattern.quote(base) + "\\.([0-9]{1,6})(?:" + Pattern.quote(compressedSuffix)
                    + ")?$");
            this.eligible = config.eligibleNamePattern(base);
        }

        /** 0 for the base name, N for {@code <base>.N} or {@code <base>.N.gz}, null for any other name. */
        Integer rank(String name) {
            if (name.equals(base)) {
                return 0;
            }
            Matcher m = rank.matcher(name);
            return m.matches() ? Integer.valueOf(m.group(1)) : null;
        }

        boolean compressed(String name) {
            return name.endsWith(compressedSuffix);
        }

        boolean eligible(String name) {
            return eligible.matcher(name).matches();
        }
    }

    private record Member(FileStat stat, Integer rank, boolean compressed, boolean readable) {
    }

    private static final class View {
        final FileStat base;
        final List<Member> siblings;
        final List<FileStat> all;

        private View(FileStat base, List<Member> siblings, List<FileStat> all) {
            this.base = base;
            this.siblings = siblings;
            this.all = all;
        }

        static View of(List<FileStat> members, Names names) {
            FileStat base = null;
            for (FileStat m : members) {
                if (names.rank(m.baseName()) != null && m.baseName().equals(names.base)) {
                    base = m;
                    break;
                }
            }
            List<Member> siblings = new ArrayList<>();
            for (FileStat m : members) {
                if (m == base) {
                    continue;
                }
                String name = m.baseName();
                boolean compressed = names.compressed(name);
                Integer rank = names.rank(name);
                boolean readable = base != null && !compressed && rank != null
                        && m.directory().equals(base.directory()) && names.eligible(name);
                siblings.add(new Member(m, rank, compressed, readable));
            }
            siblings.sort(Comparator.comparing((Member m) -> m.rank == null ? Integer.MAX_VALUE : m.rank)
                    .thenComparing(m -> m.stat.path()));
            List<FileStat> all = new ArrayList<>(members);
            all.sort(Comparator.comparing(FileStat::path));
            return new View(base, siblings, all);
        }

        Member byInode(long inode) {
            for (Member m : siblings) {
                if (m.stat.inode() == inode) {
                    return m;
                }
            }
            return null;
        }

        Set<Long> inodes() {
            Set<Long> out = new HashSet<>();
            for (FileStat f : all) {
                out.add(f.inode());
            }
            return out;
        }

        List<Member> readableByRankAscending() {
            List<Member> out = new ArrayList<>();
            for (Member m : siblings) {
                if (m.readable) {
                    out.add(m);
                }
            }
            return out;
        }

        List<Member> readableByRankDescending() {
            List<Member> out = readableByRankAscending();
            java.util.Collections.reverse(out);
            return out;
        }
    }
}
