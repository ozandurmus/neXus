package com.securityexpert.nexus.ui2.service.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** A drain hides the missing-consumer alert only while it is recent; an old drain is named STUCK_DRAIN. */
class SystemStatusStuckDrainTest {
    private static final double RECENT = 60;
    private static final double OLD = SystemStatusService.STUCK_DRAIN_SECONDS + 1;

    @Test void aRecentDrainStillSuppressesTheMissingConsumerAlert() {
        assertNull(SystemStatusService.consumerAlert("backup", 3, 0, false, true, RECENT));
        assertNull(SystemStatusService.consumerAlert("policy", 3, 0, false, true, RECENT));
    }

    @Test void aStuckDrainNoLongerSuppressesAndIsNamed() {
        assertEquals("BACKUP_CONSUMER_MISSING", SystemStatusService.consumerAlert("backup", 3, 0, false, true, OLD));
        assertEquals("POLICY_CONSUMER_MISSING", SystemStatusService.consumerAlert("policy", 3, 1, false, true, OLD));
        assertEquals("STUCK_DRAIN", SystemStatusService.consumerAlert("backup", 0, 1, true, true, OLD));
        assertEquals("STUCK_DRAIN", SystemStatusService.consumerAlert("inventory", 5, 0, false, true, OLD));
    }

    @Test void withoutADrainTheOriginalRulesHold() {
        assertEquals("BACKUP_CONSUMER_MISSING", SystemStatusService.consumerAlert("backup", 1, 0, true, false, null));
        assertNull(SystemStatusService.consumerAlert("backup", 0, 0, false, false, null));
        assertNull(SystemStatusService.consumerAlert("backup", 1, 1, true, false, null));
        assertNull(SystemStatusService.consumerAlert("inventory", 1, 0, false, false, null));
    }
}
