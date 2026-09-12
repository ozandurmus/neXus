package com.securityexpert.nexus.ui2.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Contract §8 test 1 / C4 §7 test 1: closed step-kind enforcement. */
class StepKindTest {

    @Test
    void allNineClosedSetMembersParse() {
        assertEquals(StepKind.CONNECT, StepKind.fromSpecValue("connect"));
        assertEquals(StepKind.EXEC, StepKind.fromSpecValue("exec"));
        assertEquals(StepKind.POLL, StepKind.fromSpecValue("poll"));
        assertEquals(StepKind.SFTP_GET, StepKind.fromSpecValue("sftp_get"));
        assertEquals(StepKind.SFTP_GET, StepKind.fromSpecValue("scp_get"));
        assertEquals(StepKind.SFTP_PUT, StepKind.fromSpecValue("sftp_put"));
        assertEquals(StepKind.RESTORE_PUSH, StepKind.fromSpecValue("restore_push"));
        assertEquals(StepKind.XML_API_CALL, StepKind.fromSpecValue("xml_api_call"));
        assertEquals(StepKind.VERIFY, StepKind.fromSpecValue("verify"));
        assertEquals(StepKind.DISCONNECT, StepKind.fromSpecValue("disconnect"));
        assertEquals(9, StepKind.values().length, "the closed set has exactly nine members (C4 §2.3)");
    }

    @Test
    void anUnrecognizedKindFailsClosedRatherThanBeingSilentlyAccepted() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StepKind.fromSpecValue("show_backup_status"));
        assertEquals(true, e.getMessage().contains("STEP_KIND_NOT_IN_CLOSED_SET"));
    }

    @Test
    void aNullKindFailsClosedTooNeverDefaultingToAKnownKind() {
        assertThrows(IllegalArgumentException.class, () -> StepKind.fromSpecValue(null));
    }
}
