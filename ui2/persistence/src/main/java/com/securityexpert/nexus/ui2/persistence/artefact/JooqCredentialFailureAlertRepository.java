package com.securityexpert.nexus.ui2.persistence.artefact;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.ScheduledBackupWindowPolicy;

/** {@link CredentialFailureAlertRepository} over {@code credential_failure_alert} (V207). */
public final class JooqCredentialFailureAlertRepository implements CredentialFailureAlertRepository {

    private static final Pattern SCHEDULED_KEY = Pattern.compile(":scheduled:(\\d{4}-\\d{2}-\\d{2})$");
    private static final String CHECK_POINT = "check_point";

    private final AuditedTransactionBoundary audited;

    public JooqCredentialFailureAlertRepository(TransactionBoundary transactionBoundary) {
        this.audited = new AuditedTransactionBoundary(Objects.requireNonNull(transactionBoundary, "transactionBoundary"));
    }

    /** The day of the scheduled fleet run a job belongs to: submitted by the scheduler, key ending {@code :scheduled:<date>}. */
    static Optional<LocalDate> scheduledDay(String actor, String idempotencyKey) {
        if (!ScheduledBackupWindowPolicy.ACTOR.equals(actor) || idempotencyKey == null) {
            return Optional.empty();
        }
        Matcher m = SCHEDULED_KEY.matcher(idempotencyKey);
        return m.find() ? Optional.of(LocalDate.parse(m.group(1))) : Optional.empty();
    }

    private static Set<String> ids(Record row, String column) {
        String joined = row.get(column, String.class);
        Set<String> out = new LinkedHashSet<>();
        if (joined != null && !joined.isEmpty()) {
            for (String id : joined.split(",")) {
                out.add(id);
            }
        }
        return out;
    }

    private static String array(Set<String> ids) {
        return "{" + String.join(",", ids) + "}";
    }

    @Override
    public Recorded recordAuthenticationFailure(String credentialRef, String vendor, String jobId, String deviceId) {
        String digest = CredentialRefDigest.of(credentialRef);
        Objects.requireNonNull(deviceId, "deviceId");
        return audited.inTransaction(ACTOR, ACTION_UPDATED, dsl -> {
            dsl.execute("select pg_advisory_xact_lock(hashtext('credential_failure_alert:' || {0}))", digest);
            Optional<LocalDate> day = jobId == null ? Optional.empty() : dsl.fetchOptional(
                    "select submitted_by_actor_fingerprint, idempotency_key from jobs where job_id = {0}", jobId)
                    .flatMap(r -> scheduledDay(r.get("submitted_by_actor_fingerprint", String.class),
                            r.get("idempotency_key", String.class)));
            Optional<Record> open = dsl.fetchOptional(
                    "select alert_id, array_to_string(failed_device_ids, ',') as failed_ids, "
                            + "array_to_string(run_device_ids, ',') as run_ids, scheduled_day from credential_failure_alert "
                            + "where credential_ref_digest = {0} and closed_at is null for update", digest);
            if (open.isEmpty()) {
                Set<String> failed = new LinkedHashSet<>(List.of(deviceId));
                Set<String> run = day.isPresent() ? new LinkedHashSet<>(List.of(deviceId)) : new LinkedHashSet<>();
                dsl.execute("insert into credential_failure_alert(alert_id, credential_ref_digest, vendor, "
                        + "first_failure_at, last_failure_at, failed_job_count, failed_device_count, failed_device_ids, "
                        + "run_device_ids, last_job_id, scheduled_day, changed_at) values ({0}, {1}, {2}, now(), now(), 1, 1, "
                        + "{3}::text[], {4}::text[], {5}, {6}::date, now())",
                        UUID.randomUUID(), digest, vendor, array(failed), array(run), jobId, day.map(LocalDate::toString).orElse(null));
                return new Recorded(true, true, 1, run.size(), day);
            }
            Record row = open.get();
            Set<String> failed = ids(row, "failed_ids");
            boolean grew = failed.add(deviceId);
            // A new scheduled night starts a new run set; a manual or canary failure leaves the run set alone.
            Set<String> run = ids(row, "run_ids");
            Optional<LocalDate> storedDay = Optional.ofNullable(row.get("scheduled_day", java.sql.Date.class))
                    .map(java.sql.Date::toLocalDate);
            Optional<LocalDate> effectiveDay = day.isPresent() ? day : storedDay;
            if (day.isPresent()) {
                if (!day.equals(storedDay)) {
                    run.clear();
                }
                run.add(deviceId);
            }
            dsl.execute("update credential_failure_alert set last_failure_at = now(), "
                    + "failed_job_count = failed_job_count + 1, failed_device_count = {0}, failed_device_ids = {1}::text[], "
                    + "run_device_ids = {2}::text[], last_job_id = {3}, scheduled_day = {4}::date, "
                    + "changed_at = case when {5} then now() else changed_at end where alert_id = {6}",
                    failed.size(), array(failed), array(run), jobId, effectiveDay.map(LocalDate::toString).orElse(null),
                    grew, row.get("alert_id", UUID.class));
            return new Recorded(false, grew, failed.size(), day.isPresent() ? run.size() : 0, day);
        });
    }

    @Override
    public boolean closeOnSuccess(String credentialRef) {
        String digest = CredentialRefDigest.of(credentialRef);
        return audited.inTransaction(ACTOR, ACTION_CLOSED, dsl -> dsl.execute(
                "update credential_failure_alert set closed_at = now(), closed_reason = {0} "
                        + "where credential_ref_digest = {1} and closed_at is null", CLOSED_AUTH_SUCCEEDED, digest) > 0);
    }

    @Override
    public boolean hasOpenAlert(String credentialRef) {
        String digest = CredentialRefDigest.of(credentialRef);
        return audited.inTransaction(ACTOR, ACTION_UPDATED, dsl -> dsl.fetchOptional(
                "select 1 from credential_failure_alert where credential_ref_digest = {0} and closed_at is null", digest)
                .isPresent());
    }

    @Override
    public int holdScheduledBackups(String credentialRef, String vendor, LocalDate day) {
        Objects.requireNonNull(day, "day");
        if (credentialRef == null || credentialRef.isBlank()) {
            return 0;
        }
        boolean checkPoint = CHECK_POINT.equals(vendor);
        List<Object> binds = new ArrayList<>();
        // The Check Point backup credential is one environment value shared by every Check Point device; any other
        // vendor's reference is the device's own credential_reference_id.
        String deviceMatch = checkPoint ? "d.vendor_hint = 'check_point'" : "d.vendor_hint = {4} and d.credential_reference_id = {5}";
        String sql = "update jobs j set state = 'CANCELLED', outcome = 'CANCELLED', terminal_reason = {0}, finished_at = now() "
                + "from devices d where d.device_id = j.target_device_id and j.state = 'REQUESTED' "
                + "and j.submitted_by_actor_fingerprint = {1} and j.idempotency_key like {2} "
                + "and j.job_type = any(string_to_array({3}, ',')) and " + deviceMatch;
        binds.add(HOLD_REASON);
        binds.add(ScheduledBackupWindowPolicy.ACTOR);
        binds.add("%:scheduled:" + day);
        binds.add(String.join(",", ScheduledBackupWindowPolicy.FLEET_JOB_TYPES));
        if (!checkPoint) {
            binds.add(vendor);
            binds.add(credentialRef);
        }
        return audited.inTransaction(ACTOR, ACTION_HOLD, dsl -> dsl.execute(sql, binds.toArray()));
    }
}
