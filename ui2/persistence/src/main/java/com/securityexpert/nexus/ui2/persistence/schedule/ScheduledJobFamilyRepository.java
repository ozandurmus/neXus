package com.securityexpert.nexus.ui2.persistence.schedule;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * The {@code scheduled_job_family} rows (V203): one on/off per scheduled job family, read by that family's scheduler at
 * the start of every tick. A change is one {@link AuditedTransactionBoundary} transaction under {@link #SET_ACTION}, so
 * the audit trigger records the actor and the old and new values; the same transaction cancels the family's queued
 * (REQUESTED, never leased) jobs when it is switched off, and closes the discovery runs that pointed at them, so a family
 * switched off mid-window leaves nothing behind (2026-11-03: five discovery runs left REQUESTED stopped discovery and
 * policy refresh for thirteen days).
 */
public final class ScheduledJobFamilyRepository {

    public static final String SET_ACTION = "scheduled_job_family_set";
    public static final String SWEEP_ACTION = "scheduled_job_family_sweep";
    /** {@code jobs.terminal_reason} and {@code discovery_run.outcome_summary} key for work a switched-off family left queued. */
    public static final String SWITCHED_OFF_REASON = "SCHEDULED_FAMILY_SWITCHED_OFF";

    public record Row(String familyKey, boolean enabled, String reason, String changedBy, Instant changedAt) {
    }

    public record Change(Row row, int cancelledJobs, int closedDiscoveryRuns) {
    }

    private final TransactionBoundary transactions;
    private final AuditedTransactionBoundary audited;

    public ScheduledJobFamilyRepository(TransactionBoundary transactions) {
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.audited = new AuditedTransactionBoundary(transactions);
    }

    /** Every stored row, keyed by family; a family without a row is absent (callers treat it as off). */
    public Map<String, Row> readAll() {
        return transactions.inTransaction(dsl -> {
            Map<String, Row> rows = new LinkedHashMap<>();
            for (Record r : dsl.fetch("select family_key, enabled, reason, changed_by, changed_at from scheduled_job_family "
                    + "order by family_key")) {
                Row row = row(r);
                rows.put(row.familyKey(), row);
            }
            return rows;
        });
    }

    /** The stored flag for one family; empty when the row does not exist. */
    public Optional<Boolean> enabled(String familyKey) {
        return transactions.inTransaction(dsl -> dsl.fetchOptional(
                "select enabled from scheduled_job_family where family_key = {0}", familyKey)
                .map(r -> Boolean.TRUE.equals(r.get("enabled", Boolean.class))));
    }

    /** The newest {@code submitted_at} per submitting actor, for the given actors (V203 index). */
    public Map<String, Instant> lastSubmitted(Collection<String> actors) {
        if (actors.isEmpty()) return Map.of();
        return transactions.inTransaction(dsl -> {
            Map<String, Instant> last = new LinkedHashMap<>();
            for (String actor : actors) {
                dsl.fetchOptional("select max(submitted_at) as last from jobs where submitted_by_actor_fingerprint = {0}", actor)
                        .map(r -> r.get("last", OffsetDateTime.class))
                        .ifPresent(t -> last.put(actor, t.toInstant()));
            }
            return last;
        });
    }

    /**
     * Stores the family's flag and, when it is switched off, cancels its queued jobs and closes the discovery runs that
     * pointed at them. Empty when the family has no row (an unknown family never reaches here: the table's CHECK and the
     * service's closed list agree).
     */
    public Optional<Change> set(String familyKey, boolean enabled, String reason, String actor, List<String> familyActors) {
        Objects.requireNonNull(reason, "reason");
        return audited.inTransaction(actor, SET_ACTION, dsl -> {
            int updated = dsl.execute("update scheduled_job_family set enabled = {0}, reason = {1}, changed_by = {2}, "
                    + "changed_at = now() where family_key = {3}", enabled, reason, actor, familyKey);
            if (updated != 1) return Optional.<Change>empty();
            int cancelled = 0;
            int closed = 0;
            if (!enabled) {
                Cancelled jobs = cancelQueued(dsl, familyActors);
                cancelled = jobs.jobIds().size();
                closed = closeDiscoveryRuns(dsl, jobs);
            }
            Row row = row(dsl.fetchOne("select family_key, enabled, reason, changed_by, changed_at from scheduled_job_family "
                    + "where family_key = {0}", familyKey));
            return Optional.of(new Change(row, cancelled, closed));
        });
    }

    /**
     * The safety net behind {@link #set}: a tick that read "on" just before the switch could still submit after it. For
     * each family in {@code familyActors} it re-reads the switch under {@code FOR SHARE} in the cancelling transaction,
     * so a family switched back on in the meantime is never swept (Argon review 2026-11-10, item 3); a family without a
     * row counts as off, as in the schedulers. Writes nothing when no switched-off family has a queued job.
     *
     * @param familyActors family key to the system actors its scheduler submits as
     * @return cancelled jobs and closed discovery runs
     */
    public Change sweep(String sweepActor, Map<String, List<String>> familyActors) {
        if (familyActors.isEmpty()) return new Change(null, 0, 0);
        List<String> allActors = familyActors.values().stream().flatMap(List::stream).toList();
        boolean any = transactions.inTransaction(dsl -> Boolean.TRUE.equals(dsl.fetchOne(
                "select exists(select 1 from jobs where state = 'REQUESTED' and lease_worker_id is null "
                        + "and submitted_by_actor_fingerprint = any(string_to_array({0}, ','))) as queued",
                String.join(",", allActors)).get("queued", Boolean.class)));
        if (!any) return new Change(null, 0, 0);
        return audited.inTransaction(sweepActor, SWEEP_ACTION, dsl -> {
            // Held until commit: a concurrent switch-on waits for this sweep, and a scheduler reads the committed value.
            java.util.Set<String> stillOn = new java.util.HashSet<>(dsl.fetch(
                    "select family_key from scheduled_job_family where family_key = any(string_to_array({0}, ',')) "
                            + "and enabled for share", String.join(",", familyActors.keySet()))
                    .map(r -> r.get("family_key", String.class)));
            List<String> offActors = familyActors.entrySet().stream().filter(e -> !stillOn.contains(e.getKey()))
                    .flatMap(e -> e.getValue().stream()).toList();
            Cancelled jobs = cancelQueued(dsl, offActors);
            return new Change(null, jobs.jobIds().size(), closeDiscoveryRuns(dsl, jobs));
        });
    }

    /** The cancelled jobs, and the discovery runs they were admitted for (their {@code target_ref}). */
    private record Cancelled(List<String> jobIds, List<String> discoveryRunIds) {
    }

    /**
     * Cancels the queued, never-leased jobs of {@code actors}. Each keeps its idempotency key with a suffix, so the same
     * key is free again: a manual run, or the family switched back on inside the same window, is admitted instead of
     * being deduplicated onto the cancelled job (Argon review 2026-11-10, item 1).
     */
    private static Cancelled cancelQueued(DSLContext dsl, List<String> actors) {
        if (actors.isEmpty()) return new Cancelled(List.of(), List.of());
        List<String> jobIds = new java.util.ArrayList<>();
        List<String> runIds = new java.util.ArrayList<>();
        for (Record r : dsl.fetch("update jobs set cancel_requested = true, state = 'CANCELLED', outcome = 'CANCELLED', "
                        + "terminal_reason = {0}, finished_at = now(), "
                        + "idempotency_key = case when idempotency_key is null then null "
                        + "else idempotency_key || ':switched-off:' || job_id end "
                        + "where state = 'REQUESTED' and lease_worker_id is null "
                        + "and submitted_by_actor_fingerprint = any(string_to_array({1}, ',')) "
                        + "returning job_id, target_kind, target_ref",
                        SWITCHED_OFF_REASON, String.join(",", actors))) {
            jobIds.add(r.get("job_id", String.class));
            if ("discovery_run".equals(r.get("target_kind", String.class)) && r.get("target_ref", String.class) != null)
                runIds.add(r.get("target_ref", String.class));
        }
        return new Cancelled(jobIds, runIds);
    }

    /**
     * Closes the open discovery runs of the cancelled jobs, matched by the run's {@code job_id} or by the job's own target
     * (a run whose {@code job_id} is not written yet: {@code DiscoveryRunService} stores it in a later transaction).
     */
    private static int closeDiscoveryRuns(DSLContext dsl, Cancelled jobs) {
        if (jobs.jobIds().isEmpty()) return 0;
        return dsl.execute("update discovery_run set state = 'FAILED', finished_at = now(), "
                + "outcome_summary = jsonb_build_object({0}::text, 1) where finished_at is null "
                + "and (job_id = any(string_to_array({1}, ',')) or run_id = any(string_to_array({2}, ',')))",
                "failure_reason_class:" + SWITCHED_OFF_REASON, String.join(",", jobs.jobIds()),
                String.join(",", jobs.discoveryRunIds()));
    }

    private static Row row(Record r) {
        OffsetDateTime changedAt = r.get("changed_at", OffsetDateTime.class);
        return new Row(r.get("family_key", String.class), Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
                r.get("reason", String.class), r.get("changed_by", String.class),
                changedAt == null ? null : changedAt.toInstant());
    }
}
