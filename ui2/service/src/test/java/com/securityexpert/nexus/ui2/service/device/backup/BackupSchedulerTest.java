package com.securityexpert.nexus.ui2.service.device.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.service.api.BackupPolicyController;

class BackupSchedulerTest {

    @Test
    void theSlotIsDueOnceAfterItsTimeAndNeverAgainUntilTheNextOne() {
        String cron = "0 2 * * *";
        Instant before = Instant.parse("2026-09-23T01:59:30Z");
        Instant at = Instant.parse("2026-09-23T02:00:20Z");
        Instant later = Instant.parse("2026-09-23T02:07:00Z");

        assertEquals(Optional.of(Instant.parse("2026-09-22T02:00:00Z")),
                BackupScheduleDue.dueSlot(cron, Optional.empty(), Instant.parse("2026-09-22T05:00:00Z"), 6 * 3600),
                "a never-run schedule runs the slot still inside the catch-up window");
        assertEquals(Optional.empty(), BackupScheduleDue.dueSlot(cron, Optional.empty(), before, 6 * 3600),
                "at 01:59 the previous day's 02:00 is outside the window and today's has not come");
        assertEquals(Optional.empty(), BackupScheduleDue.dueSlot(cron, Optional.of(Instant.parse("2026-09-22T02:00:05Z")), before, 6 * 3600));
        assertEquals(Optional.of(Instant.parse("2026-09-23T02:00:00Z")),
                BackupScheduleDue.dueSlot(cron, Optional.of(Instant.parse("2026-09-22T02:00:05Z")), at, 6 * 3600));
        // Recorded as run at 02:00:20 -> the same slot is not due again seven minutes later.
        assertEquals(Optional.empty(), BackupScheduleDue.dueSlot(cron, Optional.of(at), later, 6 * 3600));
    }

    @Test
    void aSlotOlderThanTheCatchUpWindowIsSkippedNotRunLate() {
        // Service was down for two days: the 02:00 slot 26 hours ago is outside a 6 h window, so nothing fires at 04:00.
        Instant now = Instant.parse("2026-09-24T04:00:00Z");
        assertEquals(Optional.empty(), BackupScheduleDue.dueSlot("0 20 * * *", Optional.empty(), now, 6 * 3600));
        assertEquals(Optional.of(Instant.parse("2026-09-24T02:00:00Z")),
                BackupScheduleDue.dueSlot("0 2 * * *", Optional.empty(), now, 6 * 3600));
    }

    @Test
    void policyValidationRefusesABadCronAndOutOfRangeRetention() {
        assertTrue(BackupPolicyController.validate(new BackupPolicyController.UpdateRequest(true, "0 2 * * *", 14, 4)).isEmpty());
        assertTrue(BackupPolicyController.validate(new BackupPolicyController.UpdateRequest(true, "every night", 14, 4)).isPresent());
        assertTrue(BackupPolicyController.validate(new BackupPolicyController.UpdateRequest(true, "0 2 * * *", 0, 4)).isPresent());
        assertTrue(BackupPolicyController.validate(new BackupPolicyController.UpdateRequest(null, "0 2 * * *", 14, 4)).isPresent());
        assertFalse(BackupScheduleDue.isValidCron("0 2 * *"));
    }
}
