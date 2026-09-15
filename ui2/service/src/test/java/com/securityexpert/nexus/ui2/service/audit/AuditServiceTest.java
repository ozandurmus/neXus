package com.securityexpert.nexus.ui2.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.audit.AuditLogRow;
import com.securityexpert.nexus.ui2.persistence.audit.AuditRedactionPolicyRow;

/**
 * `UI2_0_B1_08_AUDIT_LOGS_SCREEN_CONTRACT.md` §8 tests 1-4, 7 and 9, proved
 * at the service layer with an in-memory {@link FakeAuditLogRepository} --
 * the presentation-boundary logic these tests exercise
 * ({@link AuditFieldProjector} wiring, §4.1 filter validation, §5.3
 * pagination) needs no real PostgreSQL server; the real-database half of
 * the same claims (that the trigger/redaction machinery upstream of this
 * repository actually produces these snapshots) is
 * {@code integration-tests}' job.
 */
class AuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T10:00:00Z");

    private AuditListRequestBuilder ownRequest(FakeAuditLogRepository repo, String caller) {
        return new AuditListRequestBuilder(repo, false, caller);
    }

    private AuditListRequestBuilder allRequest(FakeAuditLogRepository repo) {
        return new AuditListRequestBuilder(repo, true, "irrelevant-for-read-all");
    }

    @Test
    void rejectsUnknownTableName() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome outcome = service.list(new AuditService.ListRequest(true, "actor-1",
                Optional.of("not_a_real_table"), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50));

        assertEquals(new AuditService.ListOutcome.ValidationFailed(AuditService.REASON_UNKNOWN_TABLE_NAME), outcome);
    }

    @Test
    void rejectsUnknownOperation() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome outcome = service.list(new AuditService.ListRequest(true, "actor-1",
                Optional.empty(), Optional.empty(), Optional.of("TRUNCATE"), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50));

        assertEquals(new AuditService.ListOutcome.ValidationFailed(AuditService.REASON_UNKNOWN_OPERATION), outcome);
    }

    @Test
    void rejectsOccurredToBeforeOccurredFrom() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome outcome = service.list(new AuditService.ListRequest(true, "actor-1",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(NOW), Optional.of(NOW.minusSeconds(60)),
                Optional.empty(), 50));

        assertEquals(new AuditService.ListOutcome.ValidationFailed(AuditService.REASON_OCCURRED_TO_BEFORE_FROM), outcome);
    }

    /** Contract §5.2: never silently narrowed -- refused outright. */
    @Test
    void rejectsActorFingerprintFilterForOwnScope() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome outcome = service.list(new AuditService.ListRequest(false, "actor-1",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("actor-2"),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), 50));

        assertEquals(new AuditService.ListOutcome.ScopeRefused(
                AuditService.REASON_ACTOR_FINGERPRINT_NOT_ACCEPTED_FOR_READ_OWN), outcome);
    }

    @Test
    void ownScopeOnlyReturnsCallersRows() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(row(1, "devices", "dev-1", "actor-1"));
        repo.rows.add(row(2, "devices", "dev-2", "actor-2"));
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome.Ok outcome = (AuditService.ListOutcome.Ok) service.list(
                ownRequest(repo, "actor-1").build());

        assertEquals(1, outcome.entries().size());
        assertEquals("dev-1", outcome.entries().get(0).rowPk());
    }

    @Test
    void allScopeReturnsEveryRow() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(row(1, "devices", "dev-1", "actor-1"));
        repo.rows.add(row(2, "devices", "dev-2", "actor-2"));
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome.Ok outcome = (AuditService.ListOutcome.Ok) service.list(allRequest(repo).build());

        assertEquals(2, outcome.entries().size());
    }

    /** Contract §4.1/AC-7: a larger request is capped, never refused, and the repository never sees more than {@code MAX_LIMIT}. */
    @Test
    void requestedLimitIsCappedBeforeReachingTheRepository() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        AuditService service = new AuditService(repo);

        service.list(allRequest(repo).limit(100_000).build());

        assertEquals(AuditService.MAX_LIMIT, repo.lastRequestedLimit,
                "the capped page size, not the raw request -- fetching one extra row to compute next_cursor "
                        + "is the repository's own internal concern, not part of this interface's contract");
    }

    @Test
    void keysetPaginationReturnsNextCursorWhenMoreRowsExist() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(row(1, "devices", "dev-1", "actor-1"));
        repo.rows.add(row(2, "devices", "dev-2", "actor-1"));
        repo.rows.add(row(3, "devices", "dev-3", "actor-1"));
        AuditService service = new AuditService(repo);

        AuditService.ListOutcome.Ok firstPage =
                (AuditService.ListOutcome.Ok) service.list(allRequest(repo).limit(2).build());
        assertEquals(2, firstPage.entries().size());
        assertEquals(List.of(3L, 2L), firstPage.entries().stream().map(AuditEntrySummary::auditId).toList());
        assertTrue(firstPage.nextCursor().isPresent());

        AuditService.ListOutcome.Ok secondPage = (AuditService.ListOutcome.Ok) service.list(
                allRequest(repo).limit(2).cursor(firstPage.nextCursor().get()).build());
        assertEquals(1, secondPage.entries().size());
        assertEquals(1L, secondPage.entries().get(0).auditId());
        assertFalse(secondPage.nextCursor().isPresent(), "no response may claim more rows exist than it has");
    }

    /** Contract §3.2/§8 test 3: REDACTED, NULL and ABSENT never render identically. */
    @Test
    void redactedNullAndAbsentAreDistinguishable() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.policy.add(new AuditRedactionPolicyRow("endpoints", "address_ref", 2, "management-path reference"));
        repo.rows.add(rowWithSnapshot(1, "endpoints", "ep-redacted", "actor-1", "INSERT", null,
                "{\"endpoint_id\":\"ep-redacted\",\"device_id\":\"dev-1\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\",\"address_ref\":\"secret-address\"}"));
        repo.rows.add(rowWithSnapshot(2, "endpoints", "ep-null", "actor-1", "INSERT", null,
                "{\"endpoint_id\":\"ep-null\",\"device_id\":\"dev-1\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\",\"address_ref\":null}"));
        repo.rows.add(rowWithSnapshot(3, "endpoints", "ep-absent", "actor-1", "INSERT", null,
                "{\"endpoint_id\":\"ep-absent\",\"device_id\":\"dev-1\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\"}"));
        AuditService service = new AuditService(repo);

        AuditFieldProjection redacted = detailOf(service, 1).afterFields().get("address_ref");
        AuditFieldProjection nullField = detailOf(service, 2).afterFields().get("address_ref");
        AuditFieldProjection absent = detailOf(service, 3).afterFields().get("address_ref");

        assertEquals(AuditFieldState.REDACTED, redacted.state());
        assertEquals("management-path reference", redacted.reason());
        assertEquals(AuditFieldState.NULL, nullField.state());
        assertEquals(AuditFieldState.ABSENT, absent.state());
    }

    /** Contract §3.3/§8 test 2: an unclassified key refuses the whole snapshot's PRESENT fields, not just its own. */
    @Test
    void unclassifiedColumnWithholdsEveryPresentFieldOfTheSameSnapshot() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(rowWithSnapshot(1, "endpoints", "ep-1", "actor-1", "INSERT", null,
                "{\"endpoint_id\":\"ep-1\",\"device_id\":\"dev-1\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\",\"mystery_column\":\"new-value\"}"));
        AuditService service = new AuditService(repo);

        AuditEntryDetail detail = detailOf(service, 1);

        assertEquals(AuditFieldState.UNCLASSIFIED, detail.afterFields().get("device_id").state(),
                "a PRESENT-eligible key must be withheld once any key in the same snapshot is unclassified");
        AuditFieldProjection mystery = detail.afterFields().get("mystery_column");
        assertEquals(AuditFieldState.UNCLASSIFIED, mystery.state());
        assertEquals("endpoints", mystery.tableName());
        assertEquals("mystery_column", mystery.columnName());
    }

    /** Contract §3.2/§8 test 5: CHANGED/UNCHANGED/NOT_EVALUABLE, computed server-side, only for UPDATE. */
    @Test
    void changeStatesAreComputedOnlyForUpdateRows() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(rowWithSnapshot(1, "endpoints", "ep-1", "actor-1", "UPDATE",
                "{\"endpoint_id\":\"ep-1\",\"device_id\":\"dev-1\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\"}",
                "{\"endpoint_id\":\"ep-1\",\"device_id\":\"dev-2\",\"transport_kind\":\"ssh_exec\","
                        + "\"created_at\":\"2026-01-01T00:00:00Z\"}"));
        AuditService service = new AuditService(repo);

        AuditEntryDetail detail = detailOf(service, 1);

        assertEquals(AuditChangeState.CHANGED, detail.changeStates().get("device_id"));
        assertEquals(AuditChangeState.UNCHANGED, detail.changeStates().get("transport_kind"));
    }

    @Test
    void outOfScopeDetailIsNotFoundNeverForbidden() {
        FakeAuditLogRepository repo = new FakeAuditLogRepository();
        repo.rows.add(row(1, "devices", "dev-1", "actor-2"));
        AuditService service = new AuditService(repo);

        AuditService.DetailOutcome outcome = service.get(false, "actor-1", 1);

        assertEquals(new AuditService.DetailOutcome.NotFound(), outcome);
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private static AuditEntryDetail detailOf(AuditService service, long auditId) {
        return ((AuditService.DetailOutcome.Ok) service.get(true, "irrelevant", auditId)).detail();
    }

    private static AuditLogRow row(long auditId, String tableName, String rowPk, String actorFingerprint) {
        return new AuditLogRow(auditId, tableName, rowPk, "INSERT", actorFingerprint, "test.action", NOW,
                Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static AuditLogRow rowWithSnapshot(long auditId, String tableName, String rowPk, String actorFingerprint,
            String operation, String beforeJson, String afterJson) {
        return new AuditLogRow(auditId, tableName, rowPk, operation, actorFingerprint, "test.action", NOW,
                Optional.empty(), Optional.ofNullable(beforeJson), Optional.ofNullable(afterJson));
    }

    private static final class AuditListRequestBuilder {
        private final FakeAuditLogRepository repo;
        private final boolean scopeAll;
        private final String caller;
        private int limit = 50;
        private Optional<Long> cursor = Optional.empty();

        AuditListRequestBuilder(FakeAuditLogRepository repo, boolean scopeAll, String caller) {
            this.repo = repo;
            this.scopeAll = scopeAll;
            this.caller = caller;
        }

        AuditListRequestBuilder limit(int limit) {
            this.limit = limit;
            return this;
        }

        AuditListRequestBuilder cursor(long cursor) {
            this.cursor = Optional.of(cursor);
            return this;
        }

        AuditService.ListRequest build() {
            return new AuditService.ListRequest(scopeAll, caller, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), cursor, limit);
        }
    }
}
