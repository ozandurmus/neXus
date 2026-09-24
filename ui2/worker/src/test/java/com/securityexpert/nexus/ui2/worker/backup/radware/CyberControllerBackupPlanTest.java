package com.securityexpert.nexus.ui2.worker.backup.radware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CyberControllerBackupPlanTest {

    /** The measured `system backup config list` shape (10.13.0-3, 2026-09-24), names made up. */
    private static final String LISTING = """
            Name                                                             Size(K)  Date                    Version      Description
            _06200056                                                          36342  20/06/26 00:56          10.13.0-3-5  Scheduler-gene...
            nexus-0123456789abcdef                                             36136  24/09/26 12:55          10.13.0-3-5
            """;

    @Test
    void ourRowIsFoundByExactNameOnly() {
        assertEquals(Optional.of(36136L), CyberControllerBackupPlan.listedSizeKb(LISTING, "nexus-0123456789abcdef"));
        assertEquals(Optional.empty(), CyberControllerBackupPlan.listedSizeKb(LISTING, "nexus-0123456789abcde"));
        assertEquals(Optional.empty(), CyberControllerBackupPlan.listedSizeKb(LISTING, "_0620"));
    }

    @Test
    void theNameIsAlwaysOursAndTheTargetIsInsideTheChroot() {
        assertEquals("nexus-0123456789abcdef", CyberControllerBackupPlan.name("0123456789abcdef"));
        assertThrows(IllegalArgumentException.class, () -> CyberControllerBackupPlan.name("x; rm -rf /"));
        assertEquals("sftp://nexus-cc@192.0.2.10:/in/0123456789abcdef.tgz",
                CyberControllerBackupPlan.exportTarget("nexus-cc", "192.0.2.10", "0123456789abcdef"));
    }
}
