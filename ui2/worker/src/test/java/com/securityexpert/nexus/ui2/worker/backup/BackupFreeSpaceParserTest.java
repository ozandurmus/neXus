package com.securityexpert.nexus.ui2.worker.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/** Measured live on the first real Check Point backup (2026-09-22): "show diskspace" answered a
 * one-line CLI error with exit 0 and its error code was read as "329 KB free", refusing a backup on
 * a device with gigabytes to spare. */
class BackupFreeSpaceParserTest {

    @Test
    void aCliErrorIsNeverAFreeSpaceValue() {
        assertEquals(Optional.empty(),
                BackupCapabilityExecutor.parseFreeSpaceBytes("CLINFR0329  Invalid command:'show diskspace'.\n"));
        assertEquals(Optional.empty(),
                BackupCapabilityExecutor.parseDfAvailableBytes("bash: df: command not found\n"));
    }

    @Test
    void dfPosixOutputYieldsTheAvailableColumnInBytes() {
        String df = "Filesystem     1024-blocks     Used Available Capacity Mounted on\n"
                + "/dev/mapper/vg_splat-lv_log    20961280  5124096  15837184      25% /var/log\n";
        assertEquals(Optional.of(15837184L * 1024L), BackupCapabilityExecutor.parseDfAvailableBytes(df));
    }

    @Test
    void showDiskspaceStillParsesAPlainKilobyteFigure() {
        assertEquals(Optional.of(1000000L * 1024L), BackupCapabilityExecutor.parseFreeSpaceBytes("1000000\n"));
    }

    @Test
    void maskedShapeCarriesStructureOnly() {
        String shape = BackupCapabilityExecutor.maskedShape("CLINFR0329  Invalid command:'show diskspace'.");
        assertTrue(shape.contains("CLINFR#"), shape);
        assertTrue(!shape.contains("0329"), shape);
    }
}
