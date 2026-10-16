package com.securityexpert.nexus.ui2.packetanalysis.topology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.securityexpert.nexus.ui2.packetanalysis.numeric.PyFloat;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisFailure;
import com.securityexpert.nexus.ui2.packetanalysis.store.AnalysisLimitException;
import com.securityexpert.nexus.ui2.packetanalysis.store.Db;
import com.securityexpert.nexus.ui2.packetanalysis.store.ProjectStore;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The analysis window guard on packet tables built in SQL: within budget nothing changes; over budget the window is
 * a deterministic function of the stored timestamps; an explicit window is never touched; a range with no workable
 * window stops with a reason and asks for one.
 */
class AnalysisWindowGuardTest {

    private static final long GIB = 1024L * 1024 * 1024;

    @TempDir
    Path dir;

    private ProjectStore store;
    private ProjectDb project;

    /** A project whose captures hold {@code packets} rows each over {@code seconds} from {@code start}. */
    private void packets(String capture, long packets, double start, double seconds) {
        try (Db db = store.connect()) {
            db.execute("INSERT INTO packets (capture_id, frame, ts) SELECT ?, i, ? + i * (? / ?) FROM range(?) t(i)",
                    capture, start, seconds, (double) packets, packets);
        }
    }

    private void open() {
        store = ProjectStore.open(dir.resolve("case"), new ProjectStore.Settings(null, 8 * GIB, 1));
        project = new ProjectDb(store.path());
    }

    private static Topology topology(String... captures) {
        Topology t = new Topology();
        t.points.clear();
        for (int i = 0; i < captures.length; i++) {
            t.points.add(Point.of("p" + i, "P" + i, "D" + i, captures[i], Py.i(i * 100L), Py.i(0)));
        }
        return t;
    }

    @Test
    void withinBudgetTheTopologyIsLeftExactlyAsItWas() {
        open();
        packets("a", 1000, 100.0, 10.0);
        packets("b", 1000, 100.0, 10.0);
        Topology t = topology("a", "b");
        Map<String, Object> before = t.dump();
        AnalysisWindowGuard.Decision d = AnalysisWindowGuard.apply(project, t, new AnalysisWindowGuard.Budget(1000, 0));
        assertEquals("full", d.mode());
        assertEquals(1000, d.rangePackets());
        assertEquals(before, t.dump(), "the engine analyses what it always did");
        try (ProjectDb.Db db = project.connect()) {
            assertNull(db.get(AnalysisWindowGuard.SETTING, null), "nothing is recorded for a run within budget");
        }
    }

    @Test
    void aDisabledBudgetNeverChangesAnything() {
        open();
        packets("a", 5000, 0.0, 10.0);
        packets("b", 5000, 0.0, 10.0);
        Topology t = topology("a", "b");
        assertEquals("disabled", AnalysisWindowGuard.apply(project, t, AnalysisWindowGuard.Budget.disabled()).mode());
        assertNull(t.settings.get("start"));
    }

    @Test
    void overBudgetTheWidestEarliestWindowThatFitsIsChosenAndRecorded() {
        open();
        // 1 kHz on two captures for 20 s: 5 s holds 5000 packets, 2 s holds 2000.
        packets("a", 20000, 1000.0, 20.0);
        packets("b", 20000, 1000.0, 20.0);
        Topology t = topology("a", "b");
        AnalysisWindowGuard.Decision d = AnalysisWindowGuard.apply(project, t, new AnalysisWindowGuard.Budget(3000, 0));
        assertTrue(d.guarded());
        assertEquals("budget", d.reason());
        assertEquals(2.0, d.end() - d.start(), 1e-9, "the widest width with at most 3000 packets per capture is 2 s");
        assertEquals(1000.0, d.start(), 1e-9, "the earliest start wins among equal windows");
        assertEquals(2, d.captures());
        assertTrue(d.windowPackets() <= 3000 && d.windowPackets() >= 1999, "packets in the window: " + d.windowPackets());
        assertEquals(20000, d.rangePackets(), 20, "the busiest capture in the full range");
        assertEquals(d.start(), ((PyFloat) t.settings.get("start")).value());
        assertEquals(d.end(), ((PyFloat) t.settings.get("end")).value());
        Map<String, Object> m = d.toMap();
        assertEquals("guarded", m.get("mode"));
        assertTrue(((String) m.get("note")).startsWith("analysed window: 1000 - 1002 (2 s), reason: budget"), (String) m.get("note"));
        try (ProjectDb.Db db = project.connect()) {
            assertEquals("guarded", ((Map<?, ?>) db.get(AnalysisWindowGuard.SETTING, null)).get("mode"));
        }
    }

    @Test
    void theSameInputGivesTheSameWindowEveryTime() {
        open();
        packets("a", 20000, 1000.0, 20.0);
        packets("b", 8000, 1004.0, 12.0);
        packets("c", 20000, 1000.0, 20.0);
        AnalysisWindowGuard.Budget budget = new AnalysisWindowGuard.Budget(3000, 0);
        Topology first = topology("a", "b", "c");
        AnalysisWindowGuard.Decision one = AnalysisWindowGuard.apply(project, first, budget);
        Topology second = topology("c", "b", "a");
        ProjectStore again = ProjectStore.open(dir.resolve("case"), new ProjectStore.Settings(null, 8 * GIB, 4));
        AnalysisWindowGuard.Decision two = AnalysisWindowGuard.apply(new ProjectDb(again.path()), second, budget);
        // A later run on the same case: the stored window is recognised as the guard's own and recomputed (the same).
        AnalysisWindowGuard.Decision three = AnalysisWindowGuard.apply(project, first, budget);
        for (AnalysisWindowGuard.Decision d : List.of(two, three)) {
            assertEquals(one.start(), d.start());
            assertEquals(one.end(), d.end());
            assertEquals("guarded", d.mode());
        }
    }

    @Test
    void anExplicitWindowIsKeptAsGivenEvenOverBudget() {
        open();
        packets("a", 20000, 1000.0, 20.0);
        packets("b", 20000, 1000.0, 20.0);
        Topology t = topology("a", "b");
        t.settings.put("start", new PyFloat(1001.0));
        t.settings.put("end", new PyFloat(1015.0));
        AnalysisWindowGuard.Decision d = AnalysisWindowGuard.apply(project, t, new AnalysisWindowGuard.Budget(3000, 0));
        assertEquals("explicit", d.mode());
        assertEquals(1001.0, ((PyFloat) t.settings.get("start")).value());
        assertEquals(1015.0, ((PyFloat) t.settings.get("end")).value());
    }

    @Test
    void aWindowTheUserEditedAfterTheGuardIsTheirOwn() {
        open();
        packets("a", 20000, 1000.0, 20.0);
        packets("b", 20000, 1000.0, 20.0);
        AnalysisWindowGuard.Budget budget = new AnalysisWindowGuard.Budget(3000, 0);
        Topology t = topology("a", "b");
        AnalysisWindowGuard.apply(project, t, budget);
        t.settings.put("end", new PyFloat(1010.0)); // the user widens the window
        AnalysisWindowGuard.Decision d = AnalysisWindowGuard.apply(project, t, budget);
        assertEquals("explicit", d.mode());
        assertEquals(1010.0, ((PyFloat) t.settings.get("end")).value());
        try (ProjectDb.Db db = project.connect()) {
            assertNull(db.get(AnalysisWindowGuard.SETTING, null), "the stale guard record is dropped");
        }
    }

    @Test
    void noWindowThatFitsStopsTheRunAndAsksForOne() {
        open();
        packets("a", 20000, 1000.0, 1.0); // 20 kHz: even 0.1 s holds 2000 packets
        packets("b", 20000, 1000.0, 1.0);
        AnalysisLimitException e = assertThrows(AnalysisLimitException.class, () ->
                AnalysisWindowGuard.apply(project, topology("a", "b"), new AnalysisWindowGuard.Budget(1000, 0)));
        assertEquals(AnalysisFailure.WINDOW_REQUIRED, e.reason());
        assertEquals(AnalysisFailure.WINDOW_REQUIRED, AnalysisFailure.reasonOf(e));
    }

    @Test
    void aWindowMustCoverAtLeastTwoCaptures() {
        double[] busy = steps(0.0, 10000, 0.001); // 10 s at 1 kHz
        double[] sparse = steps(0.0, 5, 2.0);     // 5 packets in 10 s: never 20 in a window
        assertNull(AnalysisWindowGuard.search(new double[][] {busy, sparse}, 0.0, 10.0, new AnalysisWindowGuard.Budget(3000, 0)));
        assertNotNull(AnalysisWindowGuard.search(new double[][] {busy, busy}, 0.0, 10.0, new AnalysisWindowGuard.Budget(3000, 0)));
    }

    @Test
    void theSecondsBudgetBoundsTheWidthAndTheRange() {
        open();
        packets("a", 2000, 5000.0, 20.0);
        packets("b", 2000, 5000.0, 20.0);
        Topology t = topology("a", "b");
        AnalysisWindowGuard.Decision d = AnalysisWindowGuard.apply(project, t, new AnalysisWindowGuard.Budget(0, 5));
        assertTrue(d.guarded());
        assertEquals(5.0, d.end() - d.start(), 1e-9, "20 s range > 5 s budget: the 5 s window");
        assertEquals(List.of(120.0, 60.0, 30.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1),
                AnalysisWindowGuard.widths(new AnalysisWindowGuard.Budget(0, 120)));
        assertEquals(List.of(5.0, 2.0, 1.0, 0.5, 0.2, 0.1), AnalysisWindowGuard.widths(new AnalysisWindowGuard.Budget(0, 5)));
        assertEquals(List.of(60.0, 30.0, 10.0, 5.0, 2.0, 1.0, 0.5, 0.2, 0.1),
                AnalysisWindowGuard.widths(new AnalysisWindowGuard.Budget(1000, 0)));
        assertFalse(new AnalysisWindowGuard.Budget(0, 0).enabled());
    }

    private static double[] steps(double start, int n, double step) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = start + i * step;
        }
        return out;
    }
}
