package com.securityexpert.nexus.ui2.service.logdetect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.logdetect.HardwareMarkers.Hit;

/** The closed marker list of layer A on masked synthetic lines (FW-TANGO-04 style names only). */
class HardwareMarkersTest {
    private final HardwareMarkers markers = new HardwareMarkers(new LogKeyToken("synthetic-test-key-synthetic-test-key".getBytes()));

    private static final String SLOT = "CPU_SrcID#1_MC#0_Chan#2_DIMM#0";

    private List<Hit> hits(String line) {
        return markers.match(line);
    }

    @Test
    void edacCorrectedErrorCarriesTheClosedSlotLabelAsItsKey() {
        var hits = hits("Nov 24 01:02:03 2026 FW-TANGO-04 kernel: EDAC MC0: 1 CE memory read error on " + SLOT
                + " (channel:2 slot:0 page:0x1 offset:0x0 grain:32 syndrome:0x0 - area:DRAM err_code:0001:0090)");
        assertEquals(1, hits.size());
        assertEquals("hw.edac.ce", hits.get(0).marker().id());
        assertEquals(SLOT, hits.get(0).keyToken());
        assertEquals(0.8, hits.get(0).marker().weight());
    }

    @Test
    void uncorrectedErrorIsAnotherMarkerWithWeightOne() {
        var hits = hits("Nov 24 01:02:03 2026 FW-TANGO-04 kernel: EDAC MC0: 1 UE memory read error on " + SLOT + " (channel:2)");
        assertEquals("hw.edac.ue", hits.get(0).marker().id());
        assertEquals(1.0, hits.get(0).marker().weight());
        assertFalse(hits.get(0).marker().suppressible(), "an S = 1.0 marker cannot be suppressed by rule");
    }

    @Test
    void anUnknownSlotWordBecomesAKeyedOpaqueTokenNeverTheWord() {
        var hits = hits("Nov 24 01:02:03 2026 FW-TANGO-04 kernel: EDAC MC0: 1 CE memory read error on some-label-with-name (x)");
        String key = hits.get(0).keyToken();
        assertTrue(key.matches("k-[0-9a-f]{12}"), key);
        assertEquals(key, hits("Nov 24 05:00:00 2026 FW-TANGO-04 kernel: EDAC MC0: 3 CE memory read error on some-label-with-name (y)")
                .get(0).keyToken(), "equality only: the same word gives the same token");
    }

    @Test
    void machineCheckPanicOopsOomIoAndReadOnlyAreRecognised() {
        assertEquals("hw.mce", hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: [Hardware Error]: Machine check events logged").get(0).marker().id());
        assertEquals("kern.panic", hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: Kernel panic - not syncing: Fatal exception").get(0).marker().id());
        assertEquals("kern.oops", hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: BUG: unable to handle kernel paging request at 0x0").get(0).marker().id());
        var oom = hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: Out of memory: Kill process 4242 (fwk_wd) score 900 or sacrifice child").get(0);
        assertEquals("kern.oom", oom.marker().id());
        assertTrue(oom.keyToken().matches("k-[0-9a-f]{12}"), "the process name is always a keyed opaque token: " + oom.keyToken());
        assertFalse(oom.keyToken().contains("fwk_wd"), "never the name itself");
        var io = hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: blk_update_request: I/O error, dev sda, sector 123").get(0);
        assertEquals("io.error", io.marker().id());
        assertEquals("sda", io.keyToken());
        assertEquals("fs.readonly", hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: EXT4-fs (dm-0): Remounting filesystem read-only").get(0).marker().id());
    }

    @Test
    void everyKeyFitsTheStoredCharacterSetWhateverTheProcessOrDeviceWord() {
        java.util.regex.Pattern stored = java.util.regex.Pattern.compile("[A-Za-z0-9#_.:-]{0,64}"); // the V272 key_token CHECK
        for (String process : new String[] {"g++", "c++filt", "a:b", "naïve", "weird'name", "a".repeat(64), "{}[]", "-", "k-0123456789ab"}) {
            var hits = hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: Out of memory: Killed process 4242 (" + process + ") score 900");
            assertEquals("kern.oom", hits.get(0).marker().id(), process);
            assertTrue(stored.matcher(hits.get(0).keyToken()).matches(), "process word breaks the stored character set: " + process.length());
            assertTrue(hits.get(0).keyToken().matches("k-[0-9a-f]{12}"), process);
        }
        for (String slot : new String[] {"a+b", "some label", "ü", "x".repeat(80)}) {
            var hits = hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: EDAC MC0: 1 CE memory read error on " + slot + " (x)");
            assertTrue(stored.matcher(hits.get(0).keyToken()).matches(), "slot word breaks the stored character set");
        }
        assertEquals("", hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: Out of memory: Killed process 4242 and no name").get(0).keyToken());
    }

    @Test
    void aProcessNameWithoutAKeyedTokenCollapsesToOtherNeverToTheName() {
        var plain = new HardwareMarkers(null);
        var hits = plain.match("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: Out of memory: Killed process 4242 (g++) score 900");
        assertEquals("OTHER", hits.get(0).keyToken());
    }

    @Test
    void theBootBannerIsAnAnchorNotASeverityMarker() {
        var hit = hits("Nov 24 01:00:05 2026 FW-TANGO-04 kernel: Linux version 3.10.0 (builder@host) (gcc version 4.8.5) #1 SMP").get(0);
        assertEquals("kern.boot", hit.marker().id());
        assertTrue(hit.marker().anchor());
        assertEquals(0.15, hit.marker().weight());
    }

    @Test
    void ordinaryLinesAndNearMissesMatchNothing() {
        assertTrue(hits("Nov 24 01:00:00 2026 FW-TANGO-04 sshd[123]: Accepted publickey for USR-0123456789 from 192.0.2.7").isEmpty());
        assertTrue(hits("Nov 24 01:00:00 2026 FW-TANGO-04 kernel: EDAC MC0: initialised, no errors").isEmpty());
        assertTrue(hits("Nov 24 01:00:00 2026 FW-TANGO-04 cpd[55]: certificate error rate low").isEmpty());
        assertTrue(hits("").isEmpty());
        assertTrue(hits(null).isEmpty());
    }

    @Test
    void everyMarkerStartsProvisionalAndTheListIsVersioned() {
        for (var m : HardwareMarkers.LIST) assertEquals(LogProvenance.PROVISIONAL, m.provenance(), m.id());
        assertEquals(9, HardwareMarkers.LIST.size());
        assertFalse(HardwareMarkers.LIST_VERSION.isBlank());
    }

    @Test
    void hardwareExemptionFollowsDesignSection34() {
        assertTrue(HardwareMarkers.byId("hw.edac.ue").orElseThrow().exemptFromChronic());
        assertFalse(HardwareMarkers.byId("hw.edac.ce").orElseThrow().exemptFromChronic());
        assertTrue(HardwareMarkers.byId("hw.edac.ce").orElseThrow().exemptFromFleetBackground());
        assertFalse(HardwareMarkers.byId("kern.oops").orElseThrow().exemptFromFleetBackground());
    }
}
