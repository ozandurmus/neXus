package com.securityexpert.nexus.ui2.persistence.artefact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.persistence.JooqTransactionBoundary;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/** V207 shape tests over a mocked connection: the digest, the open/update/close statements, the hold statement. */
class JooqCredentialFailureAlertRepositoryTest {
    private static final String REF = "synthetic-backup-credential-ref";
    private static final LocalDate NIGHT = LocalDate.of(2026, 11, 11);

    private final List<String> sql = new ArrayList<>();
    private final List<List<Object>> bindings = new ArrayList<>();

    private JooqCredentialFailureAlertRepository repository(Function<String, Result<Record>> answers) {
        var create = DSL.using(SQLDialect.POSTGRES);
        var dsl = DSL.using(new MockConnection(ctx -> {
            sql.add(ctx.sql());
            bindings.add(List.of(ctx.bindings()));
            Result<Record> result = answers.apply(ctx.sql());
            return new MockResult[] {new MockResult(result == null ? 0 : result.size(), result == null ? create.newResult() : result)};
        }), SQLDialect.POSTGRES);
        return new JooqCredentialFailureAlertRepository(new JooqTransactionBoundary(dsl));
    }

    private static Result<Record> rows(String[] columns, String[]... values) {
        String[][] data = new String[values.length + 1][];
        data[0] = columns;
        System.arraycopy(values, 0, data, 1, values.length);
        return DSL.using(SQLDialect.POSTGRES).fetchFromStringData(data);
    }

    private Function<String, Result<Record>> jobAndOpenAlert(String actor, String key, String failed, String run, String day) {
        return statement -> {
            if (statement.contains("from jobs where job_id")) {
                return rows(new String[] {"submitted_by_actor_fingerprint", "idempotency_key"}, new String[] {actor, key});
            }
            if (statement.contains("from credential_failure_alert") && statement.contains("for update")) {
                return rows(new String[] {"alert_id", "failed_ids", "run_ids", "scheduled_day"},
                        new String[] {"00000000-0000-0000-0000-000000000001", failed, run, day});
            }
            return null;
        };
    }

    @Test
    void theDigestIsTwelveHexAndNeverTheReference() {
        String digest = CredentialRefDigest.of(REF);
        assertEquals(12, digest.length());
        assertTrue(digest.matches("[0-9a-f]{12}"));
        assertFalse(digest.contains("synthetic"));
        assertEquals(digest, CredentialRefDigest.of(REF));
        assertFalse(digest.equals(CredentialRefDigest.of(REF + "2")));
    }

    @Test
    void theFirstFailureOpensAnAlertStoringOnlyTheDigest() {
        var repository = repository(statement -> statement.contains("from jobs where job_id")
                ? rows(new String[] {"submitted_by_actor_fingerprint", "idempotency_key"},
                        new String[] {ScheduledBackupWindowPolicy.ACTOR, "dev-1:cp_gateway_backup:scheduled:2026-11-11"}) : null);
        var recorded = repository.recordAuthenticationFailure(REF, "check_point", "job-1", "dev-1");
        assertTrue(recorded.opened());
        assertTrue(recorded.deviceCountChanged());
        assertEquals(1, recorded.failedDeviceCount());
        assertEquals(1, recorded.runDeviceCount());
        assertEquals(Optional.of(NIGHT), recorded.scheduledDay());
        int insert = indexOf("insert into credential_failure_alert");
        assertTrue(bindings.get(insert).contains(CredentialRefDigest.of(REF)));
        assertTrue(bindings.stream().flatMap(List::stream).noneMatch(b -> String.valueOf(b).contains(REF)),
                "the reference itself is never bound anywhere");
        assertTrue(sql.stream().anyMatch(s -> s.toLowerCase().contains("set local app.actor_fingerprint")), "audited");
    }

    @Test
    void aSecondDistinctDeviceInTheSameNightUpdatesTheCountsAndReportsTwoRunDevices() {
        var repository = repository(jobAndOpenAlert(ScheduledBackupWindowPolicy.ACTOR,
                "dev-2:cp_gateway_backup:scheduled:2026-11-11", "dev-1", "dev-1", "2026-11-11"));
        var recorded = repository.recordAuthenticationFailure(REF, "check_point", "job-2", "dev-2");
        assertFalse(recorded.opened());
        assertTrue(recorded.deviceCountChanged());
        assertEquals(2, recorded.failedDeviceCount());
        assertEquals(2, recorded.runDeviceCount());
        List<Object> update = bindings.get(indexOf("update credential_failure_alert"));
        assertEquals(2, update.get(0), "failed_device_count");
        assertEquals("{dev-1,dev-2}", update.get(1));
        assertTrue((Boolean) update.get(5), "changed_at moves because the device count grew");
    }

    @Test
    void theSameDeviceAgainChangesNoCountAndDoesNotMoveChangedAt() {
        var repository = repository(jobAndOpenAlert(ScheduledBackupWindowPolicy.ACTOR,
                "dev-1:cp_gateway_backup:scheduled:2026-11-11", "dev-1", "dev-1", "2026-11-11"));
        var recorded = repository.recordAuthenticationFailure(REF, "check_point", "job-3", "dev-1");
        assertFalse(recorded.deviceCountChanged());
        assertEquals(1, recorded.failedDeviceCount());
        assertEquals(1, recorded.runDeviceCount());
        assertEquals(false, bindings.get(indexOf("update credential_failure_alert")).get(5));
    }

    @Test
    void aNewScheduledNightStartsANewRunSetAndAManualFailureLeavesItAlone() {
        var next = repository(jobAndOpenAlert(ScheduledBackupWindowPolicy.ACTOR,
                "dev-7:cp_gateway_backup:scheduled:2026-11-12", "dev-1,dev-2", "dev-1,dev-2", "2026-11-11"));
        var recorded = next.recordAuthenticationFailure(REF, "check_point", "job-7", "dev-7");
        assertEquals(3, recorded.failedDeviceCount());
        assertEquals(1, recorded.runDeviceCount(), "the earlier night's devices do not count toward tonight's hold");
        assertEquals(Optional.of(LocalDate.of(2026, 11, 12)), recorded.scheduledDay());

        sql.clear();
        bindings.clear();
        var manual = repository(jobAndOpenAlert("operator-fp", "manual-key-1", "dev-1", "dev-1", "2026-11-11"));
        var manualRecorded = manual.recordAuthenticationFailure(REF, "check_point", "job-m", "dev-9");
        assertEquals(0, manualRecorded.runDeviceCount());
        assertEquals(Optional.empty(), manualRecorded.scheduledDay());
    }

    @Test
    void theSchedulerOnlyMarksAScheduledNight() {
        assertEquals(Optional.of(NIGHT), JooqCredentialFailureAlertRepository.scheduledDay(
                ScheduledBackupWindowPolicy.ACTOR, "dev-1:cp_gateway_backup:scheduled:2026-11-11"));
        assertEquals(Optional.empty(), JooqCredentialFailureAlertRepository.scheduledDay("operator-fp",
                "dev-1:cp_gateway_backup:scheduled:2026-11-11"));
        assertEquals(Optional.empty(), JooqCredentialFailureAlertRepository.scheduledDay(
                ScheduledBackupWindowPolicy.ACTOR, "dev-1:cp_gateway_backup:123456"));
    }

    @Test
    void closeOnSuccessClosesOnlyTheOpenAlertOfThatCredential() {
        repository(statement -> null).closeOnSuccess(REF);
        int update = indexOf("update credential_failure_alert");
        assertTrue(sql.get(update).contains("closed_at is null"));
        assertEquals(List.of("auth_succeeded", CredentialRefDigest.of(REF)), bindings.get(update));
    }

    @Test
    void holdCancelsOnlyRequestedScheduledBackupsOfThatNightForCheckPointDevices() {
        repository(statement -> null).holdScheduledBackups(REF, "check_point", NIGHT);
        int update = indexOf("update jobs j set state = 'CANCELLED'");
        String statement = sql.get(update);
        assertTrue(statement.contains("j.state = 'REQUESTED'"));
        assertTrue(statement.contains("d.vendor_hint = 'check_point'"));
        assertFalse(statement.contains("credential_reference_id"));
        assertEquals("credential_auth_failed_hold", bindings.get(update).get(0));
        assertEquals(ScheduledBackupWindowPolicy.ACTOR, bindings.get(update).get(1));
        assertEquals("%:scheduled:2026-11-11", bindings.get(update).get(2));
        assertTrue(String.valueOf(bindings.get(update).get(3)).contains("cp_gateway_backup"));
    }

    @Test
    void holdForPaloAltoMatchesTheDevicesOwnCredentialReference() {
        repository(statement -> null).holdScheduledBackups("pan-cred-1", "palo_alto", NIGHT);
        int update = indexOf("update jobs j set state = 'CANCELLED'");
        assertTrue(sql.get(update).contains("d.credential_reference_id = "));
        assertTrue(bindings.get(update).contains("pan-cred-1"));
        assertTrue(bindings.get(update).contains("palo_alto"));
    }

    private int indexOf(String fragment) {
        for (int i = 0; i < sql.size(); i++) {
            if (sql.get(i).contains(fragment)) {
                return i;
            }
        }
        throw new AssertionError("no statement containing: " + fragment + " in " + sql);
    }
}
