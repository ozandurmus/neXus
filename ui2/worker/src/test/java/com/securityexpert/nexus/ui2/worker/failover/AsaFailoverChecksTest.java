package com.securityexpert.nexus.ui2.worker.failover;

import static com.securityexpert.nexus.ui2.worker.failover.AsaFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.device.HaPairIdentity;
import com.securityexpert.nexus.ui2.worker.failover.AsaFailoverChecks.Observer;
import com.securityexpert.nexus.ui2.worker.failover.AsaFailoverChecks.Result;

/** Every PASS / FAIL / UNKNOWN branch of the nine Cisco ASA checks, on fixtures shaped like the measured pair. */
class AsaFailoverChecksTest {
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final Optional<HaPairIdentity> RECORDED = Optional.of(new HaPairIdentity("CLS-TANGO-01", "cisco_asa",
        SERIAL_A, SERIAL_B, HaPairIdentity.State.MATCH));

    private static Observer observer(Show first, Show second, String state, String history, String conn) {
        return new Observer(AsaFailoverChecks.parseShow(first.text()), AsaFailoverChecks.parseShow(second.text()),
            AsaFailoverChecks.parseState(state), AsaFailoverChecks.parseHistory(history), AsaFailoverChecks.inUse(conn));
    }
    private static Observer activeObserver(Show a1, Show a2, long inUse) {
        return observer(a1, a2, stateOk(false), history(2, "10:11:12 +03 Nov 10 2026"), conn(inUse, inUse * 100));
    }
    private static Observer standbyObserver(Show s1, Show s2, long inUse) {
        return observer(s1, s2, stateOk(true), history(2, "10:11:12 +03 Nov 10 2026"), conn(inUse, inUse * 100));
    }
    private static List<Result> healthy() {
        return AsaFailoverChecks.evaluate(activeObserver(active(), active(), 4778), standbyObserver(standby(), standby(), 3801), RECORDED, NOW);
    }
    /** Evaluate with one knob turned on the active's and/or standby's {@code show failover}. */
    private static List<Result> with(Consumer<Show> onActive, Consumer<Show> onStandby) {
        Show a1 = active(), a2 = active(), s1 = standby(), s2 = standby();
        onActive.accept(a1); onActive.accept(a2); onStandby.accept(s1); onStandby.accept(s2);
        return AsaFailoverChecks.evaluate(activeObserver(a1, a2, 4778), standbyObserver(s1, s2, 3801), RECORDED, NOW);
    }
    private static Result check(List<Result> results, int no) { return results.get(no - 1); }
    private static void expect(Result r, String status, String reason) {
        assertEquals(status, r.status(), r.check() + " " + r.reason());
        assertEquals(reason, r.reason(), "check " + r.check());
    }

    @Test void healthyPairIsReadyAndInformationalLastFailoverNeverBlocks() {
        List<Result> results = healthy();
        for (int no = 1; no <= 8; no++) assertEquals("PASS", check(results, no).status(), "check " + no);
        assertEquals("PASS", check(results, 9).status());
        assertEquals("READY", AsaFailoverChecks.outcome(results));
    }

    // ---- check 1 ----
    @Test void rolesNotOneActiveOneStandbyReadyFail() {
        expect(check(with(s -> s.thisState = "Active", s -> { s.thisState = "Active"; s.otherState = "Active"; }), 1), "FAIL", "VIEWS_DISAGREE");
        Show a = active(), b = standby();
        a.thisState = "Failed"; b.otherState = "Failed";
        expect(AsaFailoverChecks.roles(AsaFailoverChecks.parseShow(a.text()), AsaFailoverChecks.parseShow(b.text())), "FAIL", "ROLES_NOT_ACTIVE_STANDBY_READY");
        Show c = active(), d = standby();
        c.thisState = "Standby Ready"; d.otherState = "Standby Ready";
        expect(AsaFailoverChecks.roles(AsaFailoverChecks.parseShow(c.text()), AsaFailoverChecks.parseShow(d.text())), "FAIL", "ROLES_NOT_ACTIVE_STANDBY_READY");
    }
    @Test void viewsThatDisagreeFail() {
        expect(check(with(s -> {}, s -> s.otherState = "Standby Ready"), 1), "FAIL", "VIEWS_DISAGREE");
    }
    @Test void failoverGroupLinesAreUnsupportedModeUnknown() {
        Result r = check(with(s -> s.groups = true, s -> {}), 1);
        expect(r, "UNKNOWN", "UNSUPPORTED_MODE");
        assertEquals("UNKNOWN", AsaFailoverChecks.outcome(with(s -> s.groups = true, s -> {})));
    }
    @Test void failoverOffFailsAndUnparsableAnswerIsUnknown() {
        expect(check(with(s -> s.on = false, s -> {}), 1), "FAIL", "FAILOVER_OFF");
        expect(AsaFailoverChecks.roles(AsaFailoverChecks.parseShow("% garbled"), AsaFailoverChecks.parseShow(standby().text())), "UNKNOWN", "FAILOVER_STATE_NOT_RECOGNISED");
        expect(AsaFailoverChecks.roles(AsaFailoverChecks.parseShow(null), AsaFailoverChecks.parseShow(null)), "UNKNOWN", "FAILOVER_STATE_NOT_RECOGNISED");
    }
    @Test void missingHostLinesAreUnknown() {
        Show a = active();
        String text = a.text().replace("This host: Primary - Active", "This hos: Primary - Active");
        expect(AsaFailoverChecks.roles(AsaFailoverChecks.parseShow(text), AsaFailoverChecks.parseShow(standby().text())), "UNKNOWN", "HOST_LINES_MISSING");
    }

    // ---- check 2 ----
    @Test void serialNotInRecordedPairFails() {
        Show a = active(), b = standby();
        a.serialOurs = "SYNTH0009"; b.serialMate = "SYNTH0009";
        Result r = AsaFailoverChecks.identity(AsaFailoverChecks.parseShow(a.text()), AsaFailoverChecks.parseShow(b.text()), RECORDED);
        expect(r, "FAIL", "SERIAL_NOT_IN_RECORDED_PAIR");
        assertEquals("MISMATCH", r.evidence().get("identity"));
    }
    @Test void noRecordedPairIsUnknownAndMissingSerialIsUnknown() {
        Result none = AsaFailoverChecks.identity(AsaFailoverChecks.parseShow(active().text()), AsaFailoverChecks.parseShow(standby().text()), Optional.empty());
        expect(none, "UNKNOWN", "NO_RECORDED_PAIR");
        assertEquals("MISSING", none.evidence().get("identity"));
        String text = active().text().replace("Serial Number: Ours SYNTH0001, Mate SYNTH0002\n", "");
        expect(AsaFailoverChecks.identity(AsaFailoverChecks.parseShow(text), AsaFailoverChecks.parseShow(standby().text()), RECORDED), "UNKNOWN", "SERIAL_MISSING");
    }
    @Test void serialsThatDoNotMirrorAreInconsistentUnknown() {
        Show b = standby();
        b.serialOurs = SERIAL_A; b.serialMate = SERIAL_B;
        expect(AsaFailoverChecks.identity(AsaFailoverChecks.parseShow(active().text()), AsaFailoverChecks.parseShow(b.text()), RECORDED),
            "UNKNOWN", "RELATIONSHIP_INCONSISTENT");
    }
    @Test void serialsAreComparedExactly() {
        Show a = active(), b = standby();
        a.serialOurs = "synth0001"; b.serialMate = "synth0001";
        expect(AsaFailoverChecks.identity(AsaFailoverChecks.parseShow(a.text()), AsaFailoverChecks.parseShow(b.text()), RECORDED),
            "FAIL", "SERIAL_NOT_IN_RECORDED_PAIR");
    }

    // ---- check 3 ----
    @Test void linksNotUpFailAndMissingLinesAreUnknown() {
        expect(check(with(s -> s.lan = "(down)", s -> {}), 3), "FAIL", "LINK_NOT_UP");
        expect(check(with(s -> {}, s -> s.link = "(down)"), 3), "FAIL", "LINK_NOT_UP");
        Show a = active();
        a.link = "Unconfigured.";
        expect(AsaFailoverChecks.links(AsaFailoverChecks.parseShow(a.text()), AsaFailoverChecks.parseShow(standby().text())), "UNKNOWN", "LINK_LINES_MISSING");
    }

    // ---- check 4 ----
    @Test void interfaceNotNormalFails() {
        for (String bad : List.of("Failed", "No Link", "Link Down", "Testing", "Unknown", "Waiting", "Normal (Waiting)")) {
            Result r = check(with(s -> s.thisInterfaces.set(1, bad), s -> {}), 4);
            expect(r, "FAIL", "INTERFACE_NOT_NORMAL");
        }
        expect(check(with(s -> {}, s -> s.otherInterfaces.set(0, "Failed")), 4), "FAIL", "INTERFACE_NOT_NORMAL");
    }
    @Test void notMonitoredIsShownNotJudged() {
        Result r = check(with(s -> s.thisInterfaces.set(2, "Normal (Not-Monitored)"), s -> {}), 4);
        assertEquals("PASS", r.status());
        assertEquals(1, ((Integer) r.evidence().get("notMonitored")).intValue());
    }
    @Test void noInterfaceLinesOrUnrecognisedStatusIsUnknown() {
        expect(check(with(s -> { s.thisInterfaces.clear(); s.otherInterfaces.clear(); }, s -> {}), 4), "UNKNOWN", "INTERFACE_LINES_MISSING");
        expect(check(with(s -> s.thisInterfaces.set(0, "Strange (Thing)"), s -> {}), 4), "UNKNOWN", "INTERFACE_STATUS_NOT_RECOGNISED");
    }
    @Test void lastFailureHistoryLinesDoNotFailChecks4And5() {
        // The measured pair: last-failure reason "Ifc Failure" and "outside: No Link" in show failover state.
        List<Result> results = healthy();
        assertEquals("PASS", check(results, 4).status());
        assertEquals("PASS", check(results, 5).status());
        assertTrue(stateOk(false).contains("Ifc Failure") && stateOk(false).contains("No Link"));
    }

    // ---- check 5 ----
    @Test void configurationNotSyncedFails() {
        for (String line : List.of("Cold Standby", "Sync Config", "Sync File System", "Bulk Sync")) {
            Result r = AsaFailoverChecks.configurationSync(AsaFailoverChecks.parseState(state(false, line)),
                AsaFailoverChecks.parseState(stateOk(true)));
            expect(r, "FAIL", "SYNC_NOT_DONE");
        }
    }
    @Test void missingConfigurationSectionIsUnknown() {
        expect(AsaFailoverChecks.configurationSync(AsaFailoverChecks.parseState("State only\n"), AsaFailoverChecks.parseState(stateOk(true))),
            "UNKNOWN", "CONFIGURATION_STATE_MISSING");
        expect(AsaFailoverChecks.configurationSync(AsaFailoverChecks.parseState(state(false, null)), AsaFailoverChecks.parseState(stateOk(true))),
            "UNKNOWN", "CONFIGURATION_STATE_MISSING");
        expect(AsaFailoverChecks.configurationSync(AsaFailoverChecks.parseState(state(false, "Something else")), AsaFailoverChecks.parseState(stateOk(true))),
            "UNKNOWN", "CONFIGURATION_STATE_MISSING");
        expect(AsaFailoverChecks.configurationSync(AsaFailoverChecks.parseState(state(false, "Sync Done\n        Something else")),
            AsaFailoverChecks.parseState(stateOk(true))), "UNKNOWN", "SYNC_STATE_NOT_RECOGNISED");
    }

    // ---- check 6 ----
    @Test void largeCumulativeRerrThatDoesNotIncreaseIsPass() {
        Result r = check(healthy(), 6);
        assertEquals("PASS", r.status());
        assertTrue(active().general[3] > 900_000_000L, "the fixture carries a large cumulative rerr, as the measured pair does");
        assertEquals(0, r.evidence().get("increasedCounters"));
    }
    @Test void rerrOrXerrIncreaseFails() {
        Show a1 = active(), a2 = active();
        a2.general = new long[] {a1.general[0] + 10, 0, a1.general[2] + 10, a1.general[3] + 1};
        expect(AsaFailoverChecks.replication(activeObserver(a1, a2, 4778), standbyObserver(standby(), standby(), 3801)),
            "FAIL", "ERROR_COUNTER_INCREASED");
        Show s1 = standby(), s2 = standby();
        s2.tcp = new long[] {s1.tcp[0], 1, s1.tcp[2], s1.tcp[3]};
        expect(AsaFailoverChecks.replication(activeObserver(active(), active(), 4778), standbyObserver(s1, s2, 3801)),
            "FAIL", "ERROR_COUNTER_INCREASED");
    }
    @Test void unparsableTableOrDecreasedCounterIsUnknown() {
        Show a2 = active();
        String broken = a2.text().replace("Stateful Obj    xmit       xerr       rcv        rerr", "Stateful Obj    xmit");
        Observer a = new Observer(AsaFailoverChecks.parseShow(active().text()), AsaFailoverChecks.parseShow(broken),
            AsaFailoverChecks.parseState(stateOk(false)), AsaFailoverChecks.parseHistory(""), AsaFailoverChecks.inUse(conn(4778, 9)));
        expect(AsaFailoverChecks.replication(a, standbyObserver(standby(), standby(), 3801)), "UNKNOWN", "STATEFUL_TABLE_NOT_RECOGNISED");
        Show reset = active();
        reset.general = new long[] {5, 0, 5, 5};
        expect(AsaFailoverChecks.replication(activeObserver(active(), reset, 4778), standbyObserver(standby(), standby(), 3801)),
            "UNKNOWN", "COUNTER_DECREASED");
        String garbledRow = active().text().replace("300000", "3x0000");
        Observer g = new Observer(AsaFailoverChecks.parseShow(garbledRow), AsaFailoverChecks.parseShow(garbledRow),
            AsaFailoverChecks.parseState(stateOk(false)), AsaFailoverChecks.parseHistory(""), AsaFailoverChecks.inUse(conn(4778, 9)));
        expect(AsaFailoverChecks.replication(g, standbyObserver(standby(), standby(), 3801)), "UNKNOWN", "STATEFUL_TABLE_NOT_RECOGNISED");
    }
    @Test void connectionsInUseWithNoReplicationTrafficFail() {
        Show s = standby();
        s.general = new long[] {20, 0, 0, 4321L};
        expect(AsaFailoverChecks.replication(activeObserver(active(), active(), 4778), standbyObserver(s, s, 3801)), "FAIL", "NO_REPLICATION_TRAFFIC");
        // With nothing in use the positivity rule is not applied.
        assertEquals("PASS", AsaFailoverChecks.replication(activeObserver(active(), active(), 0), standbyObserver(s, s, 0)).status());
    }
    @Test void unknownConnectionCountLeavesReplicationUnknown() {
        Observer a = observer(active(), active(), stateOk(false), "", "no counts here");
        expect(AsaFailoverChecks.replication(a, standbyObserver(standby(), standby(), 3801)), "UNKNOWN", "ACTIVE_OR_CONNECTIONS_UNKNOWN");
    }

    // ---- check 7 ----
    @Test void standbyAt3801AgainstActiveAt4778Passes() {
        Result r = check(healthy(), 7);
        assertEquals("PASS", r.status());
        assertEquals("LOW_VOLUME_ABSOLUTE_OR_RATIO", r.evidence().get("rule"));
    }
    @Test void sessionRuleBoundaries() {
        assertEquals("PASS", sessions(5000, 3500).status());      // ratio 70 %
        assertEquals("FAIL", sessions(5000, 2000).status());      // diff 3000, ratio 40 %
        assertEquals("PASS", sessions(3000, 1500).status());      // diff 1500 < 2000
        assertEquals("PASS", sessions(20000, 16000).status());    // ratio exactly 80 %
        assertEquals("FAIL", sessions(20000, 15900).status());
        assertEquals("FAIL", sessions(50000, 3000).status());
        assertEquals("PASS", sessions(0, 12).status());           // zero active passes
        assertEquals("PASS", sessions(0, 0).status());
        assertEquals("PASS", sessions(20000, 40000).status());    // a larger standby is not a failure
    }
    private static Result sessions(long active, long standby) {
        return AsaFailoverChecks.sessions(activeObserver(active(), active(), active), standbyObserver(standby(), standby(), standby));
    }
    @Test void unparsableConnectionCountIsUnknown() {
        Observer a = observer(active(), active(), stateOk(false), "", "% Invalid input detected");
        expect(AsaFailoverChecks.sessions(a, standbyObserver(standby(), standby(), 10)), "UNKNOWN", "CONNECTION_COUNT_NOT_RECOGNISED");
        assertTrue(AsaFailoverChecks.inUse("4778 in use, 1085767 most used").isPresent());
        assertEquals(4778L, AsaFailoverChecks.inUse("4778 in use, 1085767 most used").get());
        assertTrue(AsaFailoverChecks.inUse("4778 in use").isEmpty());
    }

    // ---- check 8 ----
    @Test void versionParity() {
        expect(check(with(s -> s.versionMate = "9.18(5)", s -> {}), 8), "FAIL", "VERSIONS_DIFFER");
        expect(check(with(s -> {}, s -> { s.versionOurs = "9.18(5)"; s.versionMate = "9.18(5)"; }), 8), "FAIL", "VERSIONS_DIFFER");
        String text = active().text().replace("Version: Ours 9.18(4), Mate 9.18(4)\n", "");
        expect(AsaFailoverChecks.versions(AsaFailoverChecks.parseShow(text), AsaFailoverChecks.parseShow(standby().text())), "UNKNOWN", "VERSION_MISSING");
    }

    // ---- check 9 ----
    @Test void lastFailoverUsesTheLastEntryOfALongHistory() {
        String longHistory = history(40, "10:11:12 +03 Nov 10 2026");
        assertTrue(longHistory.lines().count() > 80, "longer than the measurement cap");
        Observer a = observer(active(), active(), stateOk(false), longHistory, conn(1, 1));
        Result old = AsaFailoverChecks.lastFailover(a.history(), a.history(), NOW);
        expect(old, "PASS", "OLDER_THAN_6H");
        assertEquals(82, old.evidence().get("entries"));
        // The head of the list is August; only the tail decides.
        Result recent = AsaFailoverChecks.lastFailover(a.history(), a.history(), Instant.parse("2026-11-10T09:00:00Z"));
        expect(recent, "WARN", "WITHIN_6H");
    }
    @Test void unresolvableTimeZoneOrEmptyHistoryIsUnknownInformation() {
        var est = AsaFailoverChecks.parseHistory(history(0, "10:11:12 EST Nov 10 2026"));
        expect(AsaFailoverChecks.lastFailover(est, est, NOW), "UNKNOWN", "TIME_ZONE_NOT_RESOLVED");
        var utc = AsaFailoverChecks.parseHistory(history(0, "10:11:12 UTC Nov 10 2026"));
        expect(AsaFailoverChecks.lastFailover(utc, utc, NOW), "PASS", "OLDER_THAN_6H");
        var none = AsaFailoverChecks.parseHistory("");
        expect(AsaFailoverChecks.lastFailover(none, none, NOW), "UNKNOWN", "HISTORY_EMPTY_OR_NOT_RECOGNISED");
    }

    // ---- outcome and privacy ----
    @Test void outcomeRule() {
        assertEquals("NOT_READY", AsaFailoverChecks.outcome(with(s -> s.versionMate = "9.18(5)", s -> {})));
        assertEquals("UNKNOWN", AsaFailoverChecks.outcome(with(s -> s.groups = true, s -> {})));
        // A FAIL beats an UNKNOWN.
        assertEquals("NOT_READY", AsaFailoverChecks.outcome(with(s -> { s.groups = false; s.lan = "(down)"; }, s -> s.versionMate = "x")));
        // Check 9 never blocks, even as WARN.
        List<Result> warn = AsaFailoverChecks.evaluate(activeObserver(active(), active(), 4778), standbyObserver(standby(), standby(), 3801), RECORDED,
            Instant.parse("2026-11-10T08:00:00Z"));
        assertEquals("WARN", check(warn, 9).status());
        assertEquals("READY", AsaFailoverChecks.outcome(warn));
    }
    @Test void evidenceNeverCarriesAValueFromTheDevice() {
        String all = healthy().toString() + with(s -> s.versionMate = "9.18(5)", s -> s.serialOurs = "SYNTH0009");
        for (String forbidden : List.of("SYNTH000", "9.18", "192.0.2.", "outside", "inside", "failover GigabitEthernet"))
            assertFalse(all.contains(forbidden), forbidden);
    }
}
