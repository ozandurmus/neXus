package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * One test (or a small group) per row of the section 4.4 decision table and per gap kind. Synthetic sizes and
 * inodes. The messages family is used throughout; family isolation is checked in {@link ReadPlannerDeviceTest}.
 */
class ReadPlannerTest {

    private static void assertRead(ReadAction r, String path, long offset, long length, ReadPurpose purpose) {
        assertEquals(path, r.path());
        assertEquals(offset, r.offset(), "offset of " + path);
        assertEquals(length, r.length(), "length of " + path);
        assertEquals(purpose, r.purpose());
    }

    // ------------------------------------------------------------------ first run

    @Nested
    class FirstRun {
        @Test
        void readsTheLastBackfillOfTheLiveFileAndRecordsTheExactSkippedPrefix() {
            SourcePlan p = messages(null, msg("messages", 10, 20 * MIB), msg("messages.1", 9, 5 * MIB));
            assertEquals(1, p.reads().size());
            ReadAction r = p.reads().get(0);
            assertRead(r, MESSAGES, 12 * MIB, 8 * MIB, ReadPurpose.INITIAL_BASELINE);
            assertEquals(64, r.leadIn(), "lead-in finds the line boundary and feeds the first anchor");
            assertNull(r.expectedAnchor(), "no cursor, nothing to verify");
            assertEquals(12 * MIB - 64, r.rangeStart());
            assertEquals(8 * MIB + 64, r.rangeLength());
            Gap g = p.gap(GapKind.INITIAL_BASELINE);
            assertNotNull(g);
            assertEquals(12 * MIB, g.knownLostBytes());
            assertFalse(g.extentUnknown());
            assertEquals(SourceOutcome.GAP, p.outcome());
            assertEquals(8 * MIB, p.plannedBytes());
            assertEquals(0, p.pendingBytes());
            assertEquals(1, p.gaps().size());
            assertNull(p.seedCursorInode());
        }

        @Test
        void neverReadsRotationHistoryOnTheFirstRun() {
            SourcePlan p = messages(null, msg("messages", 10, 20 * MIB), msg("messages.1", 9, 5 * MIB),
                    msg("messages.2", 8, 5 * MIB));
            assertEquals(List.of(MESSAGES), paths(p));
        }

        @Test
        void aFileSmallerThanTheBackfillIsReadWholeWithNoGap() {
            SourcePlan p = messages(null, msg("messages", 10, 3 * MIB));
            assertRead(p.reads().get(0), MESSAGES, 0, 3 * MIB, ReadPurpose.INITIAL_BASELINE);
            assertEquals(0, p.reads().get(0).leadIn());
            assertTrue(p.gaps().isEmpty());
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
        }

        @Test
        void backfillBoundary() {
            SourcePlan exact = messages(null, msg("messages", 10, 8 * MIB));
            assertEquals(0, exact.reads().get(0).offset());
            assertTrue(exact.gaps().isEmpty());

            SourcePlan over = messages(null, msg("messages", 10, 8 * MIB + 1));
            assertEquals(1, over.reads().get(0).offset());
            assertEquals(1, over.reads().get(0).leadIn());
            assertEquals(1, over.gap(GapKind.INITIAL_BASELINE).knownLostBytes());
        }

        @Test
        void anEmptyFileReadsNothingButAsksForASeedCursor() {
            SourcePlan p = messages(null, msg("messages", 10, 0));
            assertTrue(p.reads().isEmpty());
            assertTrue(p.gaps().isEmpty());
            assertEquals(SourceOutcome.NO_NEW_DATA, p.outcome());
            assertEquals(10L, p.seedCursorInode());
        }

        @Test
        void withNoDeviceBudgetLeftTheFirstRunIsDeferredNotSkipped() {
            SourcePlan p = messages(null, new PlanContext(64 * MIB, java.util.Set.of()),
                    msg("messages", 10, 20 * MIB));
            assertTrue(p.reads().isEmpty());
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
            assertEquals(8 * MIB, p.pendingBytes());
            assertNull(p.gap(GapKind.INITIAL_BASELINE), "the skip is recorded by the run that really skips");
            assertEquals(8 * MIB, p.gap(GapKind.BACKLOG).pendingBytes());
        }

        @Test
        void withPartialDeviceBudgetTheFirstRunReadsWhatFitsAndKeepsTheRestPending() {
            SourcePlan p = messages(null, new PlanContext(60 * MIB, java.util.Set.of()),
                    msg("messages", 10, 20 * MIB));
            assertEquals(4 * MIB, p.reads().get(0).length());
            assertEquals(12 * MIB, p.reads().get(0).offset());
            assertEquals(4 * MIB, p.pendingBytes());
            assertEquals(12 * MIB, p.gap(GapKind.INITIAL_BASELINE).knownLostBytes());
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
        }
    }

    // ------------------------------------------------------------------ same inode: no change, growth

    @Nested
    class NoChangeAndGrowth {
        @Test
        void noChange() {
            SourcePlan p = messages(cursor(10, 1000, msg("messages", 10, 1000)), msg("messages", 10, 1000));
            assertEquals(SourceOutcome.NO_NEW_DATA, p.outcome());
            assertTrue(p.isEmpty());
            assertEquals(0, p.plannedBytes());
            assertEquals(1, p.familySnapshot().size());
        }

        @Test
        void growthReadsFromTheCursorWithTheAnchor() {
            SourcePlan p = messages(cursor(10, 1000, msg("messages", 10, 1000)), msg("messages", 10, 5000));
            assertEquals(1, p.reads().size());
            ReadAction r = p.reads().get(0);
            assertRead(r, MESSAGES, 1000, 4000, ReadPurpose.GROWTH);
            assertEquals(ANCHOR, r.expectedAnchor());
            assertTrue(r.verifiesAnchor());
            assertEquals(64, r.leadIn());
            assertEquals(936, r.rangeStart());
            assertFalse(r.continuation());
            assertFalse(r.finalFile());
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
            assertTrue(p.gaps().isEmpty());
            assertFalse(p.rotationFollowed());
        }

        @Test
        void theAnchorRegionIsShorterNearTheStartOfTheFile() {
            SourcePlan p = messages(cursor(10, 10, msg("messages", 10, 10)), msg("messages", 10, 500));
            assertEquals(10, p.reads().get(0).leadIn());
            assertEquals(0, p.reads().get(0).rangeStart());
        }

        @Test
        void aCursorAtOffsetZeroNeedsNoAnchor() {
            SourcePlan p = messages(cursor(10, 0, msg("messages", 10, 0)), msg("messages", 10, 500));
            ReadAction r = p.reads().get(0);
            assertEquals(0, r.leadIn());
            assertNull(r.expectedAnchor());
        }

        @Test
        void growthLargerThanTheReadCapIsSplitIntoCapSizedReads() {
            SourcePlan p = messages(cursor(10, 1000, msg("messages", 10, 1000)),
                    msg("messages", 10, 1000 + 20 * MIB));
            assertEquals(3, p.reads().size());
            assertEquals(8 * MIB, p.reads().get(0).length());
            assertEquals(8 * MIB, p.reads().get(1).length());
            assertEquals(4 * MIB, p.reads().get(2).length());
            assertEquals(1000 + 8 * MIB, p.reads().get(1).offset());
            assertEquals(1000 + 16 * MIB, p.reads().get(2).offset());
            assertEquals(List.of(false, true, true), p.reads().stream().map(ReadAction::continuation).toList());
            assertTrue(p.reads().get(0).verifiesAnchor());
            assertFalse(p.reads().get(1).verifiesAnchor(), "later anchors come from the executor's own cursor");
            for (ReadAction r : p.reads()) {
                assertTrue(r.rangeLength() <= 8_388_672L, "C2 bound 8388672");
            }
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
        }

        @Test
        void aRebasedContinuationFollowsTheTrimmedCursor() {
            SourcePlan p = messages(cursor(10, 1000, msg("messages", 10, 1000)),
                    msg("messages", 10, 1000 + 20 * MIB));
            ReadAction second = p.reads().get(1);
            long trimmed = second.offset() - 37; // the previous read ended 37 bytes before its nominal end
            ReadAction real = second.rebase(trimmed, "fresh-anchor", CFG);
            assertEquals(trimmed, real.offset());
            assertEquals("fresh-anchor", real.expectedAnchor());
            assertEquals(CFG.readCapBytes(), real.length(), "same planned end, clamped to the cap");
            assertEquals(trimmed + CFG.readCapBytes(), real.endOffset());
            assertEquals(64, real.leadIn());
        }
    }

    // ------------------------------------------------------------------ budgets and BACKLOG

    @Nested
    class Budgets {
        @Test
        void perReadCapBoundary() {
            SourcePlan exact = messages(cursor(10, 0), msg("messages", 10, 8 * MIB));
            assertEquals(1, exact.reads().size());
            SourcePlan over = messages(cursor(10, 0), msg("messages", 10, 8 * MIB + 1));
            assertEquals(2, over.reads().size());
            assertEquals(1, over.reads().get(1).length());
        }

        @Test
        void exactlyThePerSourceBudgetIsNotABacklog() {
            SourcePlan p = messages(cursor(10, 0), msg("messages", 10, 32 * MIB));
            assertEquals(4, p.reads().size());
            assertEquals(32 * MIB, p.plannedBytes());
            assertEquals(0, p.pendingBytes());
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
            assertNull(p.gap(GapKind.BACKLOG));
        }

        @Test
        void oneByteOverThePerSourceBudgetIsAnExactBacklog() {
            SourcePlan p = messages(cursor(10, 0), msg("messages", 10, 32 * MIB + 1));
            assertEquals(32 * MIB, p.plannedBytes());
            assertEquals(1, p.pendingBytes());
            Gap g = p.gap(GapKind.BACKLOG);
            assertEquals(1, g.pendingBytes());
            assertEquals(0, g.knownLostBytes(), "a backlog is not yet a loss");
            assertFalse(g.extentUnknown());
            assertEquals(List.of(MESSAGES), g.affectedPaths());
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
        }

        @Test
        void thePerDeviceBudgetCutsTheReadAtTheByte() {
            SourcePlan p = messages(cursor(10, 0), new PlanContext(64 * MIB - 1, java.util.Set.of()),
                    msg("messages", 10, 10 * MIB));
            assertEquals(1, p.plannedBytes());
            assertEquals(1, p.reads().size());
            assertEquals(10 * MIB - 1, p.pendingBytes());
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
        }

        @Test
        void aSpentDeviceBudgetPlansNothingAndKeepsEverythingPending() {
            SourcePlan p = messages(cursor(10, 1000, msg("messages", 10, 1000)),
                    new PlanContext(64 * MIB, java.util.Set.of()), msg("messages", 10, 5000));
            assertTrue(p.reads().isEmpty());
            assertEquals(4000, p.pendingBytes());
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
        }

        @Test
        void aBacklogMidChainStopsAndDoesNotSkipAhead() {
            // cursor in .2 with 30 MiB left; .1 is 10 MiB; live is 5 MiB. Source budget 32 MiB.
            SourcePlan p = messages(
                    cursor(8, MIB, msg("messages", 8, 31 * MIB)),
                    msg("messages", 10, 5 * MIB), msg("messages.1", 9, 10 * MIB), msg("messages.2", 8, 31 * MIB));
            assertEquals(30 * MIB + 2 * MIB, p.plannedBytes());
            assertEquals(List.of(ReadPurpose.ROTATED_REMAINDER, ReadPurpose.ROTATED_REMAINDER,
                    ReadPurpose.ROTATED_REMAINDER, ReadPurpose.ROTATED_REMAINDER, ReadPurpose.ROTATED_SIBLING_WHOLE),
                    p.reads().stream().map(ReadAction::purpose).toList());
            assertEquals(2 * MIB, p.reads().get(4).length());
            assertEquals(8 * MIB + 5 * MIB, p.pendingBytes(), ".1 remainder 8 MiB plus the whole live file");
            assertFalse(paths(p).contains(MESSAGES), "the live file is not reached");
            assertEquals(SourceOutcome.BACKLOG, p.outcome());
            assertEquals(2, p.gap(GapKind.BACKLOG).affectedPaths().size());
        }
    }

    // ------------------------------------------------------------------ rename rotation

    @Nested
    class RenameRotation {
        private final FileStat before = msg("messages", 10, 1000);

        @Test
        void readsTheRemainderOfTheRotatedFileThenTheNewFileFromZero() {
            SourcePlan p = messages(cursor(10, 900, before, msg("messages.1", 9, 500)),
                    msg("messages", 11, 300), msg("messages.1", 10, 1000), msg("messages.2", 9, 500));
            assertEquals(2, p.reads().size());
            ReadAction rem = p.reads().get(0);
            assertRead(rem, "/var/log/messages.1", 900, 100, ReadPurpose.ROTATED_REMAINDER);
            assertEquals(ANCHOR, rem.expectedAnchor());
            assertTrue(rem.finalFile(), "a rotated file no longer grows: its last line is complete");
            ReadAction cur = p.reads().get(1);
            assertRead(cur, MESSAGES, 0, 300, ReadPurpose.CURRENT_FROM_START);
            assertNull(cur.expectedAnchor());
            assertFalse(cur.finalFile());
            assertTrue(p.gaps().isEmpty());
            assertTrue(p.rotationFollowed());
            assertEquals(1, p.rotationsSinceLastRead());
            assertFalse(p.fastRotation());
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
        }

        @Test
        void aFullyReadRotatedFileIsNotReadAgain() {
            SourcePlan p = messages(cursor(10, 1000, before), msg("messages", 11, 300), msg("messages.1", 10, 1000));
            assertEquals(List.of(MESSAGES), paths(p));
            assertTrue(p.rotationFollowed());
        }

        @Test
        void aNewEmptyFileAfterTheRotationReadsOnlyTheRemainder() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 11, 0), msg("messages.1", 10, 1000));
            assertEquals(List.of("/var/log/messages.1"), paths(p));
        }

        @Test
        void moreThanOneRotationSinceTheLastReadIsFollowedWithoutALoss() {
            SourcePlan p = messages(cursor(10, 900, before, msg("messages.1", 9, 500)),
                    msg("messages", 12, 100), msg("messages.1", 11, 700), msg("messages.2", 10, 1000));
            assertEquals(List.of("/var/log/messages.2", "/var/log/messages.1", MESSAGES), paths(p));
            assertEquals(ReadPurpose.ROTATED_REMAINDER, p.reads().get(0).purpose());
            assertRead(p.reads().get(1), "/var/log/messages.1", 0, 700, ReadPurpose.ROTATED_SIBLING_WHOLE);
            assertTrue(p.reads().get(1).finalFile());
            assertEquals(2, p.rotationsSinceLastRead());
            assertTrue(p.fastRotation());
            assertTrue(p.rotationFollowed());
            assertTrue(p.gaps().isEmpty());
        }

        @Test
        void aRotatedSiblingShorterThanTheCursorIsATruncation() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 11, 300), msg("messages.1", 10, 400));
            Gap g = p.gap(GapKind.TRUNCATED);
            assertNotNull(g);
            assertEquals(900, g.knownLostBytes());
            assertTrue(g.knownLostIsUpperBound());
            assertEquals(List.of(MESSAGES), paths(p));
            assertFalse(p.rotationFollowed());
            assertEquals(SourceOutcome.GAP, p.outcome());
        }
    }

    // ------------------------------------------------------------------ copytruncate

    @Nested
    class Copytruncate {
        private final FileStat before = msg("messages", 10, 1000);

        @Test
        void followsTheCopyThatHoldsTheUnreadRemainderThenReadsTheLiveFileFromZero() {
            SourcePlan p = messages(cursor(10, 900, before, msg("messages.1", 9, 500)),
                    msg("messages", 10, 50), msg("messages.1", 20, 960));
            assertEquals(2, p.reads().size());
            ReadAction rem = p.reads().get(0);
            assertRead(rem, "/var/log/messages.1", 900, 60, ReadPurpose.COPYTRUNCATE_REMAINDER);
            assertEquals(ANCHOR, rem.expectedAnchor(), "the copy is accepted only if the anchor matches");
            assertTrue(rem.finalFile());
            assertRead(p.reads().get(1), MESSAGES, 0, 50, ReadPurpose.CURRENT_FROM_START);
            assertTrue(p.gaps().isEmpty());
            assertTrue(p.rotationFollowed());
            assertEquals(1, p.rotationsSinceLastRead());
            assertEquals(SourceOutcome.COLLECTED, p.outcome());
        }

        @Test
        void aCopyExactlyAsLongAsTheCursorStillGetsAVerificationRead() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 10, 50), msg("messages.1", 20, 900));
            ReadAction verify = p.reads().get(0);
            assertEquals(0, verify.length());
            assertEquals(64, verify.leadIn());
            assertEquals(ANCHOR, verify.expectedAnchor());
            assertEquals(MESSAGES, p.reads().get(1).path());
        }

        @Test
        void noSiblingMeansATruncatedGapWithTheContractsUpperBound() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 10, 50));
            Gap g = p.gap(GapKind.TRUNCATED);
            assertEquals(900, g.knownLostBytes());
            assertTrue(g.knownLostIsUpperBound());
            assertTrue(g.extentUnknown());
            assertEquals(CURSOR_EPOCH, g.windowStartEpoch());
            assertEquals(NOW, g.windowEndEpoch());
            assertEquals(List.of(MESSAGES), paths(p));
            assertRead(p.reads().get(0), MESSAGES, 0, 50, ReadPurpose.CURRENT_FROM_START);
            assertFalse(p.rotationFollowed());
            assertEquals(SourceOutcome.GAP, p.outcome());
        }

        @Test
        void aSiblingShorterThanTheCursorCannotHoldTheRemainder() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 10, 50), msg("messages.1", 20, 899));
            assertNotNull(p.gap(GapKind.TRUNCATED));
        }

        @Test
        void aSiblingAlreadyReadInAnEarlierRunIsNotACandidate() {
            // inode 9 was messages.1 in the snapshot, older than the cursor's file: consumed
            SourcePlan p = messages(cursor(10, 900, before, msg("messages.1", 9, 5000)),
                    msg("messages", 10, 50), msg("messages.1", 9, 5000));
            assertNotNull(p.gap(GapKind.TRUNCATED));
            assertEquals(List.of(MESSAGES), paths(p), "and it is not re-read as an unseen sibling either");
        }

        @Test
        void aWrongCopyIsRejectedByTheAnchorAndTheTruncationIsReported() {
            FileStat live = msg("messages", 10, 50);
            FileStat copy = msg("messages.1", 20, 960);
            SourceCursor c = cursor(10, 900, before);
            assertEquals(COPY, messages(c, live, copy).reads().get(0).path());
            SourcePlan p = messages(c, PlanContext.fresh().withMismatch(COPY), live, copy);
            assertNotNull(p.gap(GapKind.TRUNCATED));
            assertEquals(List.of(COPY, MESSAGES), paths(p),
                    "the rejected copy holds content this product never read: it is read whole, not skipped");
            assertEquals(ReadPurpose.ROTATED_SIBLING_WHOLE, p.reads().get(0).purpose());
        }

        private static final String COPY = "/var/log/messages.1";

        @Test
        void twoCopytruncateRotationsAreFollowedOnceTheAnchorPicksTheOlderCopy() {
            FileStat live = msg("messages", 10, 30);
            FileStat newer = msg("messages.1", 21, 960);   // also long enough: a candidate by size
            FileStat older = msg("messages.2", 20, 950);
            SourceCursor c = cursor(10, 900, before);

            SourcePlan first = messages(c, live, newer, older);
            assertEquals("/var/log/messages.1", first.reads().get(0).path(), "newest candidate is tried first");

            SourcePlan second = messages(c, PlanContext.fresh().withMismatch("/var/log/messages.1"), live, newer, older);
            assertEquals(List.of("/var/log/messages.2", "/var/log/messages.1", MESSAGES), paths(second));
            assertRead(second.reads().get(0), "/var/log/messages.2", 900, 50, ReadPurpose.COPYTRUNCATE_REMAINDER);
            assertRead(second.reads().get(1), "/var/log/messages.1", 0, 960, ReadPurpose.ROTATED_SIBLING_WHOLE);
            assertEquals(2, second.rotationsSinceLastRead());
            assertTrue(second.fastRotation());
            assertTrue(second.rotationFollowed());
            assertTrue(second.gaps().isEmpty());
        }

        @Test
        void aShortNewerCopyIsReadWholeAfterTheOlderOneThatHoldsTheRemainder() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 10, 30), msg("messages.1", 21, 400),
                    msg("messages.2", 20, 950));
            assertEquals(List.of("/var/log/messages.2", "/var/log/messages.1", MESSAGES), paths(p));
            assertTrue(p.fastRotation());
        }

        @Test
        void aLiveFileTruncatedToEmptyIsATruncationToo() {
            SourcePlan p = messages(cursor(10, 900, before), msg("messages", 10, 0));
            assertNotNull(p.gap(GapKind.TRUNCATED));
            assertTrue(p.reads().isEmpty());
            assertEquals(SourceOutcome.GAP, p.outcome());
        }
    }

    // ------------------------------------------------------------------ rotated away

    @Nested
    class RotatedAway {
        @Test
        void theCursorsFileIsGoneAndOnlyTheNewLiveFileSurvives() {
            SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000), msg("messages.1", 9, 500)),
                    msg("messages", 30, 40));
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertNotNull(g);
            assertEquals(100, g.knownLostBytes(), "last seen size of the cursor's file minus the offset");
            assertTrue(g.extentUnknown(), "the new live file's inode was never seen, whole files may have come and gone");
            assertFalse(g.knownLostIsUpperBound());
            assertEquals(CURSOR_EPOCH, g.windowStartEpoch());
            assertEquals(NOW, g.windowEndEpoch());
            assertRead(p.reads().get(0), MESSAGES, 0, 40, ReadPurpose.CURRENT_FROM_START);
            assertEquals(1, p.reads().size());
            assertEquals(SourceOutcome.GAP, p.outcome());
            assertTrue(p.fastRotation());
            assertFalse(p.rotationFollowed());
        }

        @Test
        void survivingSiblingsAreReadOldestFirstAndACompressedOneIsReportedNotRead() {
            SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000), msg("messages.1", 9, 500)),
                    msg("messages", 30, 40), msg("messages.1", 31, 700), msg("messages.2.gz", 32, 90));
            assertEquals(List.of("/var/log/messages.1", MESSAGES), paths(p));
            assertEquals(ReadPurpose.ROTATED_SIBLING_WHOLE, p.reads().get(0).purpose());
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertEquals(100, g.knownLostBytes());
            assertTrue(g.extentUnknown());
            assertEquals(90, g.compressedBytesObserved());
            assertEquals(1, g.unreadableSiblings());
            assertEquals(List.of("/var/log/messages.2.gz"), g.affectedPaths());
        }

        @Test
        void theExtentIsKnownWhenTheOldestSurvivorWasInTheSnapshot() {
            // cursor in .1 (inode 10); the snapshot had the live file 12 newer than it. Now 12 sits at .1.
            SourcePlan p = messages(
                    cursor(10, 900, msg("messages", 12, 800), msg("messages.1", 10, 1000), msg("messages.2", 9, 500)),
                    msg("messages", 40, 20), msg("messages.1", 12, 850));
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertEquals(100, g.knownLostBytes());
            assertFalse(g.extentUnknown(), "nothing could sit between inode 10 and inode 12");
            assertEquals(List.of("/var/log/messages.1", MESSAGES), paths(p));
            assertRead(p.reads().get(0), "/var/log/messages.1", 0, 850, ReadPurpose.ROTATED_SIBLING_WHOLE);
        }

        @Test
        void goneSiblingsNewerThanTheCursorAreCountedAndOlderOnesAreNotReadAgain() {
            SourceCursor c = cursor(11, 900,
                    msg("messages", 13, 100), msg("messages.1", 12, 200), msg("messages.2", 11, 1000),
                    msg("messages.3", 10, 300));
            SourcePlan p = messages(c, msg("messages", 20, 50), msg("messages.1", 19, 70), msg("messages.3", 10, 300));
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertEquals(100 + 100 + 200, g.knownLostBytes(), "(1000-900) + gone inode 13 + gone inode 12");
            assertTrue(g.extentUnknown());
            assertEquals(List.of("/var/log/messages.1", MESSAGES), paths(p),
                    "inode 10 was read in an earlier run and sits older than the cursor's file");
        }

        @Test
        void aBacklogThatRotatesAwayBecomesARotatedAwayLoss() {
            // run N stopped inside .1 (budget); run N+1 finds it gone
            SourcePlan p = messages(cursor(9, 5 * MIB, msg("messages", 10, 10 * MIB), msg("messages.1", 9, 40 * MIB)),
                    msg("messages", 30, 100));
            // 35 MiB left in .1, plus the 10 MiB live file of the snapshot that is gone and was never read
            assertEquals(45 * MIB, p.gap(GapKind.ROTATED_AWAY).knownLostBytes());
        }

        @Test
        void withoutASnapshotNothingIsAssumedReadAndTheLossIsUnknown() {
            SourcePlan p = messages(cursor(10, 900), msg("messages", 30, 40), msg("messages.1", 31, 700));
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertEquals(0, g.knownLostBytes());
            assertTrue(g.extentUnknown());
            assertEquals(List.of("/var/log/messages.1", MESSAGES), paths(p));
        }

        @Test
        void theCursorsFileFoundAsACompressedSiblingIsAReportedGap() {
            SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000), msg("messages.1", 9, 500)),
                    msg("messages", 30, 40), msg("messages.1", 31, 600), msg("messages.2.gz", 10, 400));
            Gap g = p.gap(GapKind.ROTATED_AWAY);
            assertEquals(100, g.knownLostBytes(), "uncompressed size last seen minus the offset, not the gz size");
            assertTrue(g.extentUnknown());
            assertEquals(400, g.compressedBytesObserved());
            assertEquals(1, g.unreadableSiblings());
            assertEquals(List.of("/var/log/messages.1", MESSAGES), paths(p), "newer plain siblings are still read");
            assertTrue(p.fastRotation());
            assertEquals(SourceOutcome.GAP, p.outcome());
        }

        @Test
        void aSiblingOutsideTheNamePatternIsNeverRead() {
            SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000)),
                    msg("messages", 30, 40), msg("messages.old", 10, 1000));
            assertNotNull(p.gap(GapKind.ROTATED_AWAY));
            assertEquals(List.of(MESSAGES), paths(p));
            assertEquals(100, p.gap(GapKind.ROTATED_AWAY).knownLostBytes());
        }
    }

    // ------------------------------------------------------------------ anchor mismatch

    @Nested
    class AnchorMismatch {
        @Test
        void aMismatchOnTheLiveFileRestartsFromZeroWithAnUnknownExtent() {
            FileStat live = msg("messages", 10, 5000);
            SourceCursor c = cursor(10, 1000, msg("messages", 10, 1000));
            assertEquals(ReadPurpose.GROWTH, messages(c, live).reads().get(0).purpose());
            SourcePlan p = messages(c, PlanContext.fresh().withMismatch(MESSAGES), live);
            Gap g = p.gap(GapKind.ANCHOR_MISMATCH);
            assertNotNull(g);
            assertTrue(g.extentUnknown());
            assertEquals(0, g.knownLostBytes());
            assertRead(p.reads().get(0), MESSAGES, 0, 5000, ReadPurpose.ANCHOR_RESTART);
            assertNull(p.reads().get(0).expectedAnchor());
            assertEquals(SourceOutcome.GAP, p.outcome());
        }

        @Test
        void aMismatchOnTheRotatedFileReadsThatFileWholeThenTheRest() {
            SourceCursor c = cursor(10, 900, msg("messages", 10, 1000));
            SourcePlan p = messages(c, PlanContext.fresh().withMismatch("/var/log/messages.1"),
                    msg("messages", 11, 300), msg("messages.1", 10, 1000));
            assertNotNull(p.gap(GapKind.ANCHOR_MISMATCH));
            assertRead(p.reads().get(0), "/var/log/messages.1", 0, 1000, ReadPurpose.ANCHOR_RESTART);
            assertEquals(MESSAGES, p.reads().get(1).path());
            assertFalse(p.rotationFollowed());
        }
    }

    // ------------------------------------------------------------------ absent, empty, unknown

    @Nested
    class AbsentEmptyUnknown {
        @Test
        void aMissingBaseFileIsAbsentEvenWithACursor() {
            SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000)), msg("messages.1", 9, 500));
            assertEquals(SourceOutcome.ABSENT, p.outcome());
            assertTrue(p.isEmpty());
        }

        @Test
        void aGlobThatMatchedNothingIsAbsentNotAFailure() {
            StatParseResult stat = StatParser.parse("1760700000\n"
                    + "stat: cannot statx '/log/fwk.elg*': No such file or directory\n"
                    + "/var/log/messages 10 4096 5\n");
            SourcePlan p = ReadPlanner.plan(SourceFamily.CP_FWK_ELG, null, stat, CFG);
            assertEquals(SourceOutcome.ABSENT, p.outcome());
            assertNull(p.unknownReason());
        }

        @Test
        void anotherStatErrorForAMissingFileIsUnknownNotAbsent() {
            StatParseResult stat = StatParser.parse("1760700000\n"
                    + "stat: cannot statx '/var/log/messages*': Permission denied\n"
                    + "/opt/synth/fw/log/fwd.elg 10 4096 5\n");
            SourcePlan p = ReadPlanner.plan(SourceFamily.CP_MESSAGES, null, stat, CFG);
            assertEquals(SourceOutcome.UNKNOWN, p.outcome());
            assertEquals(UnknownReason.STAT_ERROR_FOR_SOURCE, p.unknownReason());
        }

        @Test
        void anEmptyLiveFileWithTheCursorAtZeroIsNoNewData() {
            SourcePlan p = messages(cursor(10, 0, msg("messages", 10, 0)), msg("messages", 10, 0));
            assertEquals(SourceOutcome.NO_NEW_DATA, p.outcome());
            assertNull(p.seedCursorInode());
        }

        @Test
        void anUnreadableOutputIsUnknownForEverySource() {
            StatParseResult stat = StatParser.parse("1760700000\n");
            for (SourceFamily family : SourceFamily.values()) {
                SourcePlan p = ReadPlanner.plan(family, null, stat, CFG);
                assertEquals(SourceOutcome.UNKNOWN, p.outcome());
                assertEquals(UnknownReason.STAT_UNREADABLE, p.unknownReason());
                assertTrue(p.isEmpty());
            }
            assertEquals(UnknownReason.STAT_UNREADABLE,
                    ReadPlanner.plan(SourceFamily.CP_MESSAGES, null, StatParser.parse("/var/log/messages 1 2 3\n"), CFG)
                            .unknownReason(), "no device clock");
        }

        @Test
        void aMalformedLineThatCouldBelongToTheFamilyMakesOnlyThatFamilyUnknown() {
            StatParseResult stat = StatParser.parse("1760700000\n/var/log/messages 10 4096 5\n"
                    + "/var/log/messages.1 oops 2 3\n/opt/synth/fw/log/fwd.elg 11 4096 5\n");
            SourcePlan messages = ReadPlanner.plan(SourceFamily.CP_MESSAGES, null, stat, CFG);
            assertEquals(SourceOutcome.UNKNOWN, messages.outcome());
            assertEquals(UnknownReason.STAT_MALFORMED_FOR_SOURCE, messages.unknownReason());
            assertTrue(messages.reads().isEmpty(), "nothing half applied");
            assertEquals(SourceOutcome.COLLECTED, ReadPlanner.plan(SourceFamily.CP_FWD_ELG, null, stat, CFG).outcome());
        }

        @Test
        void aCursorThatBreaksItsInvariantsIsUnknown() {
            SourceCursor noAnchor = new SourceCursor(10, 500, null, List.of(), CURSOR_EPOCH);
            SourcePlan p = messages(noAnchor, msg("messages", 10, 900));
            assertEquals(SourceOutcome.UNKNOWN, p.outcome());
            assertEquals(UnknownReason.CURSOR_INVALID, p.unknownReason());
            assertEquals(UnknownReason.CURSOR_INVALID,
                    messages(new SourceCursor(10, -1, "x", List.of(), CURSOR_EPOCH), msg("messages", 10, 900))
                            .unknownReason());
        }
    }

    // ------------------------------------------------------------------ read-eligible siblings

    @Nested
    class SiblingEligibility {
        private SourcePlan rotateWith(PlannerConfig cfg, FileStat... extra) {
            FileStat[] files = new FileStat[extra.length + 2];
            files[0] = msg("messages", 11, 300);
            files[1] = msg("messages.2", 10, 1000);
            System.arraycopy(extra, 0, files, 2, extra.length);
            return ReadPlanner.plan(SourceFamily.CP_MESSAGES, cursor(10, 900, msg("messages", 10, 1000)), stat(files),
                    cfg);
        }

        @Test
        void onlyTheContractPatternIsReadable() {
            SourcePlan p = rotateWith(CFG, msg("messages.123", 50, 10), msg("messages-20261001", 51, 10),
                    msg("messages.1.bak", 52, 10), msg("messages.1.gz", 53, 10), msg("messages.old", 54, 10),
                    file("/elsewhere/messages.1", 55, 10));
            assertEquals(List.of("/var/log/messages.2", MESSAGES), paths(p));
            assertEquals(8, p.familySnapshot().size(), "every member is listed in the snapshot, readable or not");
        }

        @Test
        void theProvisionalPatternIsOneConfigValue() {
            PlannerConfig wide = new PlannerConfig(CFG.readCapBytes(), CFG.sourceBudgetBytes(), CFG.deviceBudgetBytes(),
                    CFG.backfillBytes(), CFG.anchorBytes(), "(\\.[0-9]{1,3})?", ".gz", 9, 1);
            // with 3-digit suffixes allowed, messages.100 is a readable sibling newer than... older than .2? no: rank
            // 100 is older than the cursor's file at rank 2, so it is not in the chain, but it is now eligible
            SourcePlan p = rotateWith(wide, msg("messages.1", 60, 10));
            assertEquals(List.of("/var/log/messages.2", "/var/log/messages.1", MESSAGES), paths(p));
            assertTrue(wide.eligibleNamePattern("messages").matcher("messages.100").matches());
            assertFalse(CFG.eligibleNamePattern("messages").matcher("messages.100").matches());
        }
    }

    // ------------------------------------------------------------------ outputs every plan carries

    @Test
    void windowEpochsAndSnapshotAreCarried() {
        SourcePlan p = messages(cursor(10, 900, msg("messages", 10, 1000)), msg("messages", 30, 40),
                msg("messages.1", 31, 5));
        Gap g = p.gap(GapKind.ROTATED_AWAY);
        assertEquals(CURSOR_EPOCH, g.windowStartEpoch());
        assertEquals(NOW, g.windowEndEpoch());
        assertEquals(List.of("/var/log/messages", "/var/log/messages.1"),
                p.familySnapshot().stream().map(FileStat::path).toList());
    }

    @Test
    void collectionFailedIsAGapTheExecutorRecords() {
        Gap g = Gap.collectionFailed(CURSOR_EPOCH, NOW);
        assertEquals(GapKind.COLLECTION_FAILED, g.kind());
        assertEquals(0, g.knownLostBytes());
        assertTrue(g.extentUnknown());
        assertEquals(CURSOR_EPOCH, g.windowStartEpoch());
    }

    @Test
    void anInitialBaselineGapCanTakeTheTrimmedLeadingPartialLine() {
        Gap g = Gap.initialBaseline(1000, NOW, MESSAGES).withAdditionalLostBytes(37);
        assertEquals(1037, g.knownLostBytes());
        assertEquals(GapKind.INITIAL_BASELINE, g.kind());
    }

    @Test
    void everyReadStaysInsideTheStatSizeAndTheCaps() {
        SourcePlan p = messages(cursor(10, 12345, msg("messages", 10, 12345)), msg("messages", 10, 12345 + 45 * MIB));
        long total = 0;
        for (ReadAction r : p.reads()) {
            assertTrue(r.length() <= CFG.readCapBytes());
            assertTrue(r.endOffset() <= 12345 + 45 * MIB);
            assertTrue(r.rangeLength() <= CFG.maxRangeBytes());
            total += r.length();
        }
        assertEquals(32 * MIB, total);
        assertEquals(total, p.plannedBytes());
        assertEquals(45 * MIB - 32 * MIB, p.pendingBytes());
    }
}
