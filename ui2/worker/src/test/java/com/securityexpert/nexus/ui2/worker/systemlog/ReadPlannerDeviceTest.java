package com.securityexpert.nexus.ui2.worker.systemlog;

import static com.securityexpert.nexus.ui2.worker.systemlog.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReadPlannerDeviceTest {
    private static final String CP_DIR = "/opt/synth/cp/log/";
    private static final String SPIKE = "/var/log/spike_detective/";

    private static FileStat live(SourceFamily f, long inode, long size) {
        String dir = switch (f) {
            case CP_MESSAGES -> "/var/log/";
            case CP_FWD_ELG, CP_FWK_ELG, CP_MQ_MNG_ELG -> FWD_DIR;
            case CP_CPD_ELG, CP_CPWD_ELG -> CP_DIR;
            case CP_SPIKE_LOG, CP_SPIKE_CSV -> SPIKE;
        };
        return file(dir + f.baseName(), inode, size);
    }

    private static FileStat[] allLive(long size) {
        List<SourceFamily> fs = SourceFamily.runOrder();
        FileStat[] out = new FileStat[fs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = live(fs.get(i), 100 + i, size);
        }
        return out;
    }

    @Test
    void theRunOrderIsTheFixedOrderOfTheContract() {
        assertEquals(List.of("cp_messages", "cp_fwd_elg", "cp_cpd_elg", "cp_cpwd_elg", "cp_fwk_elg", "cp_mq_mng_elg",
                "cp_spike_log", "cp_spike_csv"), SourceFamily.runOrder().stream().map(SourceFamily::sourceKey).toList());
        DevicePlan plan = ReadPlanner.planDevice(Map.of(), stat(allLive(MIB)), CFG);
        assertEquals(SourceFamily.runOrder(), plan.sources().stream().map(SourcePlan::family).toList());
    }

    @Test
    void theFirstRunBaselineOfEightLiveFilesFitsTheDeviceBudgetExactly() {
        DevicePlan plan = ReadPlanner.planDevice(Map.of(), stat(allLive(20 * MIB)), CFG);
        assertEquals(64 * MIB, plan.plannedBytes());
        for (SourcePlan p : plan.sources()) {
            assertEquals(SourceOutcome.GAP, p.outcome(), p.family().sourceKey());
            assertEquals(8 * MIB, p.plannedBytes());
            assertEquals(0, p.pendingBytes());
            assertEquals(12 * MIB, p.gap(GapKind.INITIAL_BASELINE).knownLostBytes());
        }
    }

    @Test
    void theDeviceBudgetIsSharedInRunOrderAndNeverSkipsAhead() {
        Map<SourceFamily, SourceCursor> cursors = new EnumMap<>(SourceFamily.class);
        FileStat[] files = allLive(MIB);
        // three sources have 40, 40 and 5 MiB of new data: 32 + 32 + the remaining 0
        files[0] = live(SourceFamily.CP_MESSAGES, 100, 40 * MIB);
        files[1] = live(SourceFamily.CP_FWD_ELG, 101, 40 * MIB);
        files[2] = live(SourceFamily.CP_CPD_ELG, 102, 5 * MIB);
        cursors.put(SourceFamily.CP_MESSAGES, cursor(100, 0));
        cursors.put(SourceFamily.CP_FWD_ELG, cursor(101, 0));
        cursors.put(SourceFamily.CP_CPD_ELG, cursor(102, 0));
        DevicePlan plan = ReadPlanner.planDevice(cursors, stat(files), CFG);

        assertEquals(32 * MIB, plan.of(SourceFamily.CP_MESSAGES).plannedBytes());
        assertEquals(8 * MIB, plan.of(SourceFamily.CP_MESSAGES).pendingBytes());
        assertEquals(32 * MIB, plan.of(SourceFamily.CP_FWD_ELG).plannedBytes());
        SourcePlan cpd = plan.of(SourceFamily.CP_CPD_ELG);
        assertTrue(cpd.reads().isEmpty());
        assertEquals(5 * MIB, cpd.pendingBytes());
        assertEquals(SourceOutcome.BACKLOG, cpd.outcome());
        assertEquals(64 * MIB, plan.plannedBytes());
        // later sources with a first run are deferred, not skipped past
        SourcePlan cpwd = plan.of(SourceFamily.CP_CPWD_ELG);
        assertTrue(cpwd.reads().isEmpty());
        assertEquals(SourceOutcome.BACKLOG, cpwd.outcome());
        assertNull(cpwd.gap(GapKind.INITIAL_BASELINE));
    }

    @Test
    void aSourceMissingOnTheDeviceIsAbsentWhileTheOthersAreCollected() {
        List<FileStat> files = new java.util.ArrayList<>(List.of(allLive(MIB)));
        files.removeIf(f -> f.baseName().equals("fwk.elg"));
        DevicePlan plan = ReadPlanner.planDevice(Map.of(), stat(files.toArray(new FileStat[0])), CFG);
        assertEquals(SourceOutcome.ABSENT, plan.of(SourceFamily.CP_FWK_ELG).outcome());
        assertEquals(SourceOutcome.COLLECTED, plan.of(SourceFamily.CP_MESSAGES).outcome());
        assertEquals(7 * MIB, plan.plannedBytes());
    }

    @Test
    void siblingsOfOneFamilyAreNeverMixedIntoAnother() {
        FileStat[] files = new FileStat[] {
                msg("messages", 1, 100), msg("messages.1", 2, 100),
                file(FWD_DIR + "fwd.elg", 3, 100), file(FWD_DIR + "fwd.elg.1", 4, 100),
                file(CP_DIR + "cpd.elg", 5, 100), file(CP_DIR + "cpwd.elg", 6, 0) };
        DevicePlan plan = ReadPlanner.planDevice(
                Map.of(SourceFamily.CP_MESSAGES, cursor(2, 50, msg("messages", 2, 50)),
                        SourceFamily.CP_FWD_ELG, cursor(4, 50, file(FWD_DIR + "fwd.elg", 4, 50))),
                stat(files), CFG);
        assertEquals(2, plan.of(SourceFamily.CP_MESSAGES).familySnapshot().size());
        assertEquals(2, plan.of(SourceFamily.CP_FWD_ELG).familySnapshot().size());
        assertEquals(1, plan.of(SourceFamily.CP_CPD_ELG).familySnapshot().size());
        // messages: cursor inode 2 is now .1 (rename); fwd: cursor inode 4 is fwd.elg.1
        assertEquals(List.of("/var/log/messages.1", "/var/log/messages"), paths(plan.of(SourceFamily.CP_MESSAGES)));
        assertEquals(List.of(FWD_DIR + "fwd.elg.1", FWD_DIR + "fwd.elg"), paths(plan.of(SourceFamily.CP_FWD_ELG)));
        assertEquals(SourceOutcome.NO_NEW_DATA, plan.of(SourceFamily.CP_CPWD_ELG).outcome(),
                "an empty file on a first run: nothing to read");
        assertEquals(6L, plan.of(SourceFamily.CP_CPWD_ELG).seedCursorInode());
    }

    @Test
    void anUnreadableOutputMakesEverySourceUnknown() {
        DevicePlan plan = ReadPlanner.planDevice(Map.of(), StatParser.parse("garbage only\n"), CFG);
        for (SourcePlan p : plan.sources()) {
            assertEquals(SourceOutcome.UNKNOWN, p.outcome());
        }
        assertEquals(0, plan.plannedBytes());
    }

    @Test
    void theBudgetsAreOneConfigRecord() {
        PlannerConfig small = new PlannerConfig(MIB, 2 * MIB, 3 * MIB, MIB, 64, "(\\.[0-9]{1,2})?", ".gz", 9, 1);
        DevicePlan plan = ReadPlanner.planDevice(
                Map.of(SourceFamily.CP_MESSAGES, cursor(100, 0), SourceFamily.CP_FWD_ELG, cursor(101, 0)),
                stat(live(SourceFamily.CP_MESSAGES, 100, 5 * MIB), live(SourceFamily.CP_FWD_ELG, 101, 5 * MIB)), small);
        assertEquals(2 * MIB, plan.of(SourceFamily.CP_MESSAGES).plannedBytes());
        assertEquals(2, plan.of(SourceFamily.CP_MESSAGES).reads().size());
        assertEquals(MIB, plan.of(SourceFamily.CP_FWD_ELG).plannedBytes(), "3 MiB device budget minus 2 MiB spent");
        assertEquals(4 * MIB, plan.of(SourceFamily.CP_FWD_ELG).pendingBytes());
        assertEquals(3 * MIB, plan.plannedBytes());
    }
}
