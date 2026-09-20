package com.securityexpert.nexus.ui2.service.failover;

import com.securityexpert.nexus.ui2.jobs.failover.schedule.FailoverScheduleStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CF-P0.7: the class previously claimed a storage-enforced {@code UNIQUE(grant_id)} while
 * consuming grants against a plain heap map. These tests prove the single-use invariant in both
 * modes this class now supports: the lightweight in-memory fallback (no {@link JdbcTemplate}
 * wired, used by unit tests such as {@link FailoverScheduleServiceTest}) and the durable path
 * (a real database round trip is a Testcontainers-dependent test this environment cannot run --
 * see {@code LocalMechanismTest}'s equivalent note -- so the durable path is proved here by
 * mocking {@link JdbcTemplate} and asserting the {@link DuplicateKeyException}-to-single-use-
 * invariant translation, which is exactly the logic a real unique-constraint violation exercises).
 */
class FailoverScheduleLedgerTest {

    @Test
    @DisplayName("In-memory fallback: a grant can only be consumed once")
    void inMemoryGrantConsumptionRejectsDuplicates() {
        FailoverScheduleLedger ledger = new FailoverScheduleLedger();

        ledger.consumeGrant("grant-1", "sched-1", "att-1");
        assertTrue(ledger.isGrantConsumed("grant-1"));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ledger.consumeGrant("grant-1", "sched-2", "att-2"));
        assertTrue(ex.getMessage().contains("single-use grant invariant"));
    }

    @Test
    @DisplayName("In-memory fallback: hash chain stays valid across several transitions, with a NOT NULL attempt_id substitute for null attempts")
    void inMemoryChainIntegrityHoldsAcrossTransitions() {
        FailoverScheduleLedger ledger = new FailoverScheduleLedger();

        ledger.recordTransition("sched-1", null, FailoverScheduleStatus.SCHEDULED, null, "alice", "created");
        ledger.recordTransition("sched-1", FailoverScheduleStatus.SCHEDULED, FailoverScheduleStatus.CLAIMED_VERIFYING, "att-1", "CRON", "claimed");
        ledger.recordTransition("sched-1", FailoverScheduleStatus.CLAIMED_VERIFYING, FailoverScheduleStatus.COMPLETED, "att-1", "CRON", "done");

        assertTrue(ledger.verifyChainIntegrity());
        List<FailoverScheduleLedger.TransitionEntry> entries = ledger.getTransitionsForSchedule("sched-1");
        assertEquals(3, entries.size());
        assertEquals("NONE", entries.get(0).attemptId(), "a null attemptId must be substituted, never stored as null (attempt_id is NOT NULL)");
        assertEquals("att-1", entries.get(1).attemptId());
    }

    @Test
    @DisplayName("Durable mode: a unique-constraint violation on failover_grant_consumption is translated to the single-use grant invariant")
    void durableGrantConsumptionTranslatesDuplicateKeyException() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.update(anyString(), any(), any(), any(), any()))
            .thenThrow(new DuplicateKeyException("duplicate key value violates unique constraint \"failover_grant_consumption_pkey\""));

        FailoverScheduleLedger ledger = new FailoverScheduleLedger(jdbcTemplate);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> ledger.consumeGrant("grant-1", "sched-1", "att-1"));
        assertTrue(ex.getMessage().contains("single-use grant invariant"));
        assertInstanceOf(DuplicateKeyException.class, ex.getCause());
    }

    @Test
    @DisplayName("Durable mode: isGrantConsumed reflects a COUNT(*) query against failover_grant_consumption")
    void durableIsGrantConsumedQueriesCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("grant-1"))).thenReturn(1);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq("grant-2"))).thenReturn(0);

        FailoverScheduleLedger ledger = new FailoverScheduleLedger(jdbcTemplate);

        assertTrue(ledger.isGrantConsumed("grant-1"));
        assertFalse(ledger.isGrantConsumed("grant-2"));
    }

    @Test
    @DisplayName("Durable mode: recordTransition reads the current chain tip and inserts the next entry")
    void durableRecordTransitionReadsTipAndInserts() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(Map.of("entry_index", 4L, "entry_hash", "prev-hash-value")));

        FailoverScheduleLedger ledger = new FailoverScheduleLedger(jdbcTemplate);
        FailoverScheduleLedger.TransitionEntry entry = ledger.recordTransition(
            "sched-1", FailoverScheduleStatus.SCHEDULED, FailoverScheduleStatus.CANCELLED, null, "alice", "cancelled");

        assertEquals(5L, entry.index());
        assertEquals("prev-hash-value", entry.prevHash());
        assertEquals("NONE", entry.attemptId());
    }
}
