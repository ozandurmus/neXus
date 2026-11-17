package com.securityexpert.nexus.ui2.worker.failover;

import static com.securityexpert.nexus.ui2.worker.failover.FgtFixtures.*;
import com.securityexpert.nexus.ui2.worker.failover.FgtFixtures.Ha;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.failover.FgtFailoverChecks.*;

class FgtFailoverChecksTest {
    private static Observation obs(Ha ha) { return obs(ha, FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), FgtFixtures.dump(1000), FgtFixtures.flag(false)); }
    private static Observation obs(Ha ha, String cfg, String sums, String dump, String flag) {
        return new Observation(FgtFailoverChecks.parseHaStatus(ha == null ? null : ha.text()),
            cfg == null ? null : FgtFailoverChecks.parseHaConfig(cfg), sums == null ? null : FgtFailoverChecks.parseChecksums(sums),
            dump == null ? null : FgtFailoverChecks.parseUptimes(dump), flag == null ? null : FgtFailoverChecks.parseFlag(flag),
            FgtFailoverChecks.systemTime(FgtFixtures.status(SERIAL_A, false)), Optional.of(SERIAL_A), true);
    }
    private static List<Result> eval(Observation o) { return FgtFailoverChecks.evaluate(o, Optional.of(SERIAL_A)); }
    private static Result check(List<Result> r, int no) { return r.get(no - 1); }
    private static Result one(Ha ha, int no) { return check(eval(obs(ha)), no); }
    private static Ha ok() { return Ha.ok(); }

    @Test void primarySecondaryWordingWithHostnamesPasses() {
        var r = eval(obs(ok()));
        for (int no : new int[] {1, 2, 3, 4, 5, 8}) assertEquals("PASS", check(r, no).status(), "check " + no + " " + check(r, no).reason());
        assertEquals("PASS", check(r, 9).status());
        assertEquals("HA_OK", check(r, 1).reason());
        assertEquals("MATCH", check(r, 1).evidence().get("identity"));
    }
    @Test void masterSlaveWordingAndTheMeasuredBareOperatingIndexFormAreAccepted() {
        assertEquals("PASS", one(ok().words("Master", "Slave"), 1).status());
        assertEquals("PASS", one(ok().bare(), 1).status());
        assertEquals("PASS", one(ok().bare().words("Master", "Slave"), 1).status());
        assertEquals(2, FgtFailoverChecks.parseHaStatus(ok().text()).members().size());
    }
    @Test void outOfSyncFailsEvenWithoutTheChecksumRead() {
        var r = eval(obs(ok().outOfSync(), FgtFixtures.config("disable", "enable", "300"), null, null, null));
        assertEquals("FAIL", check(r, 4).status());
        assertEquals("SYNC_NOT_DONE", check(r, 4).reason());
        assertEquals("NOT_READY", FgtFailoverChecks.outcome(r));
    }
    @Test void inSyncWithoutTheChecksumReadIsUnknownAndDifferingChecksumsFail() {
        assertEquals("UNKNOWN", check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), null, FgtFixtures.dump(1000), FgtFixtures.flag(false))), 4).status());
        var differ = check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(false), FgtFixtures.dump(1000), FgtFixtures.flag(false))), 4);
        assertEquals("FAIL", differ.status());
        assertEquals("CHECKSUM_DIFFERS", differ.reason());
        assertEquals("UNKNOWN", check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), "garbage\n", FgtFixtures.dump(1000), FgtFixtures.flag(false))), 4).status());
    }
    @Test void heartbeatDownFailsAndMonitoredDownFails() {
        assertEquals("FAIL", one(ok().hbDown(), 2).status());
        assertEquals("HEARTBEAT_NOT_UP", one(ok().hbDown(), 2).reason());
        assertEquals("FAIL", one(ok().monDown(), 3).status());
        assertEquals("MONITORED_INTERFACE_DOWN", one(ok().monDown(), 3).reason());
    }
    @Test void missingHeartbeatAndMonitoredSectionsAreUnknown() {
        String text = ok().text();
        String noHb = text.replaceAll("(?s)HBDEV stats:.*?MONDEV stats:", "MONDEV stats:");
        var o = new Observation(FgtFailoverChecks.parseHaStatus(noHb), null, null, null, null, Optional.empty(), Optional.empty(), false);
        assertEquals("UNKNOWN", FgtFailoverChecks.heartbeat(o.ha()).status());
        assertEquals("UNKNOWN", FgtFailoverChecks.monitored(FgtFailoverChecks.parseHaStatus(text.substring(0, text.indexOf("MONDEV stats:")))).status());
    }
    @Test void virtualClusteringAndActiveActiveAreUnsupportedNotPass() {
        assertEquals("UNKNOWN", one(ok().vclusters(2), 1).status());
        assertEquals("UNSUPPORTED_MODE", one(ok().vclusters(2), 1).reason());
        assertEquals("UNSUPPORTED_MODE", one(ok().mode("HA A-A"), 1).reason());
        assertEquals("FAIL", one(ok().mode("Standalone"), 1).status());
    }
    @Test void versionParityIsDerivedFromHealthOkAndBothMembersInSync() {
        var pass = one(ok(), 7);
        assertEquals("PASS", pass.status());
        assertEquals("PARITY_DERIVED_FROM_HA_SYNC", pass.reason());
        assertEquals("DERIVED", pass.evidence().get("evidence"));
        assertEquals("UNKNOWN", one(ok().outOfSync(), 7).status());
        assertEquals("UNKNOWN", one(ok().health("WARNING"), 7).status());
        assertEquals("PASS", one(ok().bare(), 7).status());
    }
    @Test void healthNotOkFails() {
        assertEquals("FAIL", one(ok().health("WARNING"), 1).status());
        assertEquals("HA_HEALTH_NOT_OK", one(ok().health("ERROR"), 1).reason());
    }
    @Test void overrideEnabledFailsCheck8() {
        var r = eval(obs(ok(), FgtFixtures.config("enable", "enable", "300"), FgtFixtures.checksums(true), FgtFixtures.dump(1000), FgtFixtures.flag(false)));
        assertEquals("FAIL", check(r, 8).status());
        assertEquals("OVERRIDE_ENABLED", check(r, 8).reason());
    }
    @Test void check8NeedsNoLatchedFlagAndTreatsTheUptimeMarginAsInformation() {
        var low = check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), FgtFixtures.dump(300), FgtFixtures.flag(false))), 8);
        assertEquals("PASS", low.status(), "uptime semantics are unmeasured: information, never blocking");
        assertEquals(false, low.evidence().get("uptimeAboveMargin"));
        assertEquals("FAIL", check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), FgtFixtures.dump(1000), FgtFixtures.flag(true))), 8).status());
        assertEquals("UNKNOWN", check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), FgtFixtures.dump(1000), "something else\n")), 8).status());
        var noUptime = check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), "no uptime here\n", FgtFixtures.flag(false))), 8);
        assertEquals("PASS", noUptime.status());
        assertEquals("UNKNOWN", noUptime.evidence().get("uptimeAboveMargin"));
        assertEquals("UNKNOWN", check(eval(obs(ok(), FgtFixtures.config("disable", "enable", "300"), FgtFixtures.checksums(true), null, null)), 8).status());
    }
    @Test void sessionPickupOffFailsAndUnreportedIsUnknown() {
        assertEquals("FAIL", FgtFailoverChecks.sessionPickup(FgtFailoverChecks.parseHaConfig(FgtFixtures.config("disable", "disable", "300"))).status());
        assertEquals("UNKNOWN", FgtFailoverChecks.sessionPickup(FgtFailoverChecks.parseHaConfig("config system ha\n    set mode a-p\nend\n")).status());
        assertEquals("UNKNOWN", FgtFailoverChecks.sessionPickup(FgtFailoverChecks.parseHaConfig("nonsense")).status());
        // The nested vcluster override must not be taken for the top-level one.
        assertEquals("disable", FgtFailoverChecks.parseHaConfig(FgtFixtures.config("disable", "enable", "300")).settings().get("override"));
    }
    @Test void identityIsComparedLocallyAndReportedAsRelationshipOnly() {
        var o = obs(ok());
        var mismatch = FgtFailoverChecks.roles(o, Optional.of("FGT60F0000000009"));
        assertEquals("FAIL", mismatch.status());
        assertEquals("IDENTITY_MISMATCH", mismatch.reason());
        assertEquals("MISMATCH", mismatch.evidence().get("identity"));
        var missing = FgtFailoverChecks.roles(o, Optional.empty());
        assertEquals("UNKNOWN", missing.status());
        assertEquals("MISSING", missing.evidence().get("identity"));
        // Case is not folded: an identifier is opaque.
        assertEquals("MISMATCH", FgtFailoverChecks.roles(o, Optional.of(SERIAL_A.toLowerCase())).evidence().get("identity"));
        var secondary = new Observation(o.ha(), null, null, null, null, Optional.empty(), Optional.of(SERIAL_B), true);
        assertEquals("ANSWERED_BY_SECONDARY", FgtFailoverChecks.roles(secondary, Optional.of(SERIAL_A)).reason());
    }
    @Test void sessionsAreNotEvaluatedAndAHealthyInSyncClusterIsReady() {
        var r = eval(obs(ok()));
        assertEquals("PASS", check(r, 7).status(), "parity derived from HA health OK and both members in-sync (PO 2026-11-17)");
        assertEquals("UNKNOWN", check(r, 6).status());
        assertFalse(check(r, 6).blocking());
        assertEquals("READY", FgtFailoverChecks.outcome(r));
    }
    @Test void lastFailoverUsesTheDevicesOwnClock() {
        assertEquals("OLDER_THAN_6H", one(ok(), 9).reason());
        assertEquals("WARN", one(ok().changed("2026-11-17 09:00:00"), 9).status());
        assertEquals("UNKNOWN", one(ok().changed("2026-11-18 09:00:00"), 9).status());
        assertEquals(Optional.of(LocalDateTime.of(2026, 11, 17, 12, 0, 0)), FgtFailoverChecks.systemTime(FgtFixtures.status(SERIAL_A, false)));
    }
    @Test void unparsableOutputIsUnknownNeverPass() {
        for (String text : new String[] {null, "", "garbage\nmore garbage\n", "% Unknown action 0\n"}) {
            var o = new Observation(FgtFailoverChecks.parseHaStatus(text), FgtFailoverChecks.parseHaConfig(text), FgtFailoverChecks.parseChecksums(text),
                FgtFailoverChecks.parseUptimes(text), FgtFailoverChecks.parseFlag(text), Optional.empty(), Optional.empty(), false);
            for (Result r : eval(o)) assertNotEquals("PASS", r.status(), text + " check " + r.check());
            assertEquals("UNKNOWN", FgtFailoverChecks.outcome(eval(o)));
        }
    }
    @Test void evidenceNeverCarriesADeviceValue() {
        String all = eval(obs(ok())).stream().map(r -> r.reason() + r.evidence()).reduce("", String::concat);
        for (String forbidden : List.of("FGT60F", "FGT-SYNTH", "192.0.2.", "wan1", "ha1", "7.4.5", "synth-grp")) assertFalse(all.contains(forbidden), forbidden);
    }
}
