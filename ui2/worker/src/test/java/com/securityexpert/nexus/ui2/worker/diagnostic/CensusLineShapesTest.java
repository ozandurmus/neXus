package com.securityexpert.nexus.ui2.worker.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.worker.diagnostic.CensusLineShapes.Marker;
import com.securityexpert.nexus.ui2.worker.diagnostic.CensusLineShapes.Severity;

/** Header parse, severity class, marker set and normalisation over masked synthetic lines. */
class CensusLineShapesTest {

    static final String EDAC = "Nov 24 13:31:44 2026 FW-SIERRA-06-M2 kernel: EDAC MC0: 9 CE memory read error on "
            + "CPU_SrcID#0_Ha#0_Chan#1_DIMM#0 (channel:1 slot:0 page:0x4a1f4 offset:0x0 grain:32)";

    @Test
    void gaiaMessagesHeaderIsStrippedAndPriorityGivesTheSeverity() {
        var p = CensusLineShapes.parse("cp_messages", "<11>Nov 24 10:00:00 2026 FW-SIERRA-06-M2 sshd[812]: error: key exchange failed");
        assertNotNull(p);
        assertEquals("sshd[812]: error: key exchange failed", p.message());
        assertEquals(Severity.ERROR, p.severity()); // 11 % 8 = 3
        assertEquals(Severity.WARNING, CensusLineShapes.parse("cp_messages", "<4>Nov 24 10:00:00 2026 H-1 x: y").severity());
        assertEquals(Severity.CRITICAL, CensusLineShapes.parse("cp_messages", "<2>Nov 24 10:00:00 2026 H-1 x: y").severity());
        assertEquals(Severity.INFO, CensusLineShapes.parse("cp_messages", "<6>Nov 24 10:00:00 2026 H-1 x: y").severity());
    }

    @Test
    void facilityLevelTokenGivesTheSeverityAndNoLevelMeansUnknown() {
        var p = CensusLineShapes.parse("cp_messages", "Nov 24 10:00:00 2026 FW-SIERRA-06-M2 kern.crit kernel: Out of memory: Kill process 1");
        assertEquals(Severity.CRITICAL, p.severity());
        assertTrue(p.message().startsWith("kernel:"), p.message());
        assertEquals(Severity.UNKNOWN, CensusLineShapes.parse("cp_messages", EDAC).severity());
    }

    @Test
    void headerlessLineIsUnparsed() {
        assertNull(CensusLineShapes.parse("cp_messages", "    continuation of a stack trace"));
        assertNull(CensusLineShapes.parse("cp_fwd_elg", "plain text without a header"));
        assertNull(CensusLineShapes.parse("pan_system", "no key value pairs"));
    }

    @Test
    void elgHeaderAndLeadingLevelToken() {
        var p = CensusLineShapes.parse("cp_cpd_elg", "[cpd 4021 3455012608]@FW-SIERRA-06-M1[24 Nov 10:00:00] ERROR: lost connection to peer");
        assertNotNull(p);
        assertEquals("cpd: ERROR: lost connection to peer", p.message());
        assertEquals(Severity.ERROR, p.severity());
        assertEquals(Severity.WARNING, CensusLineShapes.parse("cp_fwd_elg", "[fwd 1 2]@H-1[24 Nov 10:00:00] [Warning] queue high").severity());
        assertEquals(Severity.UNKNOWN, CensusLineShapes.parse("cp_fwd_elg", "[fwd 1 2]@H-1[24 Nov 10:00:00] connection opened").severity());
    }

    @Test
    void fwkLevelFieldIsRead() {
        var p = CensusLineShapes.parse("cp_fwk_elg", "[24 Nov 10:00:00][fw4_0];[cpu_1];[conn-a] [Error]: policy install failed");
        assertNotNull(p);
        assertEquals(Severity.ERROR, p.severity());
        assertEquals("fwk: policy install failed", p.message());
    }

    @Test
    void panSeverityMapsToTheClasses() {
        String base = "receive_time=2026/11/24 03:22:10 severity=%s type=system subtype=hw eventid=fan-failed module=general "
                + "object=fan-tray-2 opaque=\"Fan tray 2: fan 3 failed (0 RPM)\"";
        assertEquals(Severity.CRITICAL, CensusLineShapes.parse("pan_system", base.formatted("critical")).severity());
        assertEquals(Severity.ERROR, CensusLineShapes.parse("pan_system", base.formatted("high")).severity());
        assertEquals(Severity.WARNING, CensusLineShapes.parse("pan_system", base.formatted("medium")).severity());
        assertEquals(Severity.INFO, CensusLineShapes.parse("pan_system", base.formatted("informational")).severity());
        assertEquals("hw/fan-failed: Fan tray 2: fan 3 failed (0 RPM)", CensusLineShapes.parse("pan_system", base.formatted("high")).message());
    }

    @Test
    void closedMarkerSet() {
        assertEquals(Marker.EDAC_CE, CensusLineShapes.marker(EDAC));
        assertEquals(Marker.EDAC_UE, CensusLineShapes.marker("kernel: EDAC MC0: 1 UE memory read error on DIMM#0"));
        assertEquals(Marker.MCE_HARDWARE_ERROR, CensusLineShapes.marker("kernel: mce: [Hardware Error]: Machine check events logged"));
        assertEquals(Marker.KERNEL_PANIC, CensusLineShapes.marker("kernel: Kernel panic - not syncing: Fatal exception"));
        assertEquals(Marker.IO_ERROR, CensusLineShapes.marker("kernel: Buffer I/O error on device dm-3, logical block 99"));
        assertEquals(Marker.READONLY_REMOUNT, CensusLineShapes.marker("kernel: EXT4-fs (dm-0): Remounting filesystem read-only"));
        assertEquals(Marker.OOM_KILLER, CensusLineShapes.marker("kernel: Out of memory: Kill process 31 (fwk) score 900"));
        assertEquals(Marker.BOOT, CensusLineShapes.marker("kernel: Linux version 3.10.0-1160cp (builder) #1 SMP"));
        assertNull(CensusLineShapes.marker("sshd[1]: Accepted publickey for admin"));
        assertNull(CensusLineShapes.marker("kernel: EDAC MC0: scrub completed")); // EDAC alone is not a CE/UE
    }

    @Test
    void normalizeCollapsesNumbersHexAndMaskedTokens() {
        String n = CensusLineShapes.normalize("kernel: EDAC MC0: 9 CE memory read error on CPU_SrcID#0_Ha#0_Chan#1_DIMM#0 "
                + "(channel:1 slot:0 page:0x4a1f4 offset:0x0 grain:32)");
        assertEquals("kernel: EDAC MC<*>: <*> CE memory read error on CPU_SrcID#<*>_Ha#<*>_Chan#<*>_DIMM#<*> "
                + "(channel:<*> slot:<*> page:<*> offset:<*> grain:<*>)", n);
        String masked = CensusLineShapes.normalize("auth: user USR-6E19B2D0A4 from 198.51.100.9:5022 on FW-TANGO-04 via h-3fa21b9c04.example.com id 3f2a9c01-aaaa-bbbb-cccc-0123456789ab");
        assertEquals("auth: user <*> from <*> on <*> via <*> id <*>", masked);
        assertFalse(CensusLineShapes.normalize("link 00:50:56:ab:cd:ef up on 2001:db8:1::5").contains("00:50"));
    }
}
