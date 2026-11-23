package com.securityexpert.nexus.ui2.persistence.script;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jooq.DSLContext;
import org.jooq.Record;

import com.securityexpert.nexus.ui2.persistence.AuditedTransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;

/**
 * jOOQ-backed {@link ScriptRepository}. Every mutation runs inside {@link AuditedTransactionBoundary} so
 * {@code trg_audit_script}, {@code trg_audit_script_version} and {@code trg_audit_script_run} capture the actor and action.
 */
public final class JooqScriptRepository implements ScriptRepository {

    private static final String SCRIPT_COLUMNS = "script_id, name, kind, current_version, enabled, schedule, "
            + "timeout_s, egress, egress_device_targets, credential_refs, notify, run_as, "
            + "created_by_actor_fingerprint, created_at, updated_at";

    private static final String VERSION_COLUMNS = "script_id, version, sha256, size_bytes, artefact_ref, key_id, "
            + "wrapped_data_key, secret_findings::text as secret_findings_json, credential_refs, "
            + "egress_device_targets, created_by, created_at";

    private static final String RUN_COLUMNS = "job_id, script_id, version, state, outcome, trigger_kind, "
            + "scheduled_slot, run_as, requested_by, requested_at, started_at, finished_at, duration_ms, "
            + "exit_code, stdout_bytes, stderr_bytes, stdout_truncated, stderr_truncated, "
            + "output_artefact_ref, output_key_id, output_wrapped_data_key, output_sha256, output_size_bytes, "
            + "output_tail, egress_declared, egress_device_targets, credential_refs_used, session_id, failure_reason, "
            + "n.syslog as notify_syslog, n.syslog_code as notify_syslog_code, n.smtp as notify_smtp, "
            + "n.smtp_code as notify_smtp_code, n.notified_at as notified_at";

    /** The run ledger with its V266 notification outcome, when recorded (append-only, at most one row per run). */
    private static final String RUN_FROM = " from script_run left join script_run_notification n using (job_id)";

    /**
     * V266 widened {@code script.notify} with the per-script "mail on failure only" option (contract section 3.5); the
     * API keeps the four-value channel and a separate boolean.
     */
    static final String MAIL_ON_FAILURE_SUFFIX = "_mail_on_failure";

    static String storedNotify(String channel, boolean mailOnFailureOnly) {
        return mailOnFailureOnly && ("smtp".equals(channel) || "both".equals(channel))
                ? channel + MAIL_ON_FAILURE_SUFFIX : channel;
    }

    static String notifyChannel(String stored) {
        return stored != null && stored.endsWith(MAIL_ON_FAILURE_SUFFIX)
                ? stored.substring(0, stored.length() - MAIL_ON_FAILURE_SUFFIX.length()) : stored;
    }

    static boolean mailOnFailureOnly(String stored) {
        return stored != null && stored.endsWith(MAIL_ON_FAILURE_SUFFIX);
    }

    private static final Pattern FINDING_ITEM = Pattern.compile(
            "\\{\\s*\"line\"\\s*:\\s*(\\d+)\\s*,\\s*\"pattern_class\"\\s*:\\s*\"([^\"]+)\"\\s*}"
                    + "|\\{\\s*\"pattern_class\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"line\"\\s*:\\s*(\\d+)\\s*}");

    private final TransactionBoundary transactionBoundary;
    private final AuditedTransactionBoundary auditedTransactionBoundary;

    public JooqScriptRepository(TransactionBoundary transactionBoundary) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.auditedTransactionBoundary = new AuditedTransactionBoundary(transactionBoundary);
    }

    @Override
    public List<ScriptRecord> findAll() {
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + SCRIPT_COLUMNS + " from script order by name asc, script_id asc")
                .stream()
                .map(JooqScriptRepository::toScriptRecord)
                .toList());
    }

    @Override
    public Optional<ScriptRecord> findById(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> fetchScriptById(dsl, scriptId));
    }

    @Override
    public Optional<ScriptRecord> findByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + SCRIPT_COLUMNS + " from script where name = {0}", name.strip())
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toScriptRecord));
    }

    @Override
    public List<ScriptVersionRecord> findVersions(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + VERSION_COLUMNS + " from script_version where script_id = {0} order by version desc",
                        scriptId)
                .stream()
                .map(JooqScriptRepository::toVersionRecord)
                .toList());
    }

    @Override
    public Optional<ScriptVersionRecord> findVersion(String scriptId, int version) {
        if (scriptId == null || scriptId.isBlank() || version < 1) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + VERSION_COLUMNS + " from script_version where script_id = {0} and version = {1}",
                        scriptId, version)
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toVersionRecord));
    }

    @Override
    public ScriptRecord create(ScriptRecord script, ScriptVersionRecord initialVersion, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            Timestamp createdAt = Timestamp.from(script.createdAt());
            Timestamp updatedAt = Timestamp.from(script.updatedAt());
            dsl.execute("insert into script(script_id, name, kind, current_version, enabled, schedule, timeout_s, "
                    + "egress, egress_device_targets, credential_refs, notify, run_as, "
                    + "created_by_actor_fingerprint, created_at, updated_at) "
                    + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}::text[], {8}::text[], {9}::text[], "
                    + "{10}, {11}, {12}, {13}, {14})",
                    script.scriptId(),
                    script.name(),
                    script.kind(),
                    script.currentVersion(),
                    script.enabled(),
                    script.schedule().orElse(null),
                    script.timeoutSeconds(),
                    script.egress().toArray(String[]::new),
                    script.egressDeviceTargets().toArray(String[]::new),
                    script.credentialRefs().toArray(String[]::new),
                    storedNotify(script.notifyMode(), script.notifyMailOnFailureOnly()),
                    script.runAs(),
                    script.createdByActorFingerprint(),
                    createdAt,
                    updatedAt);
            insertVersionRow(dsl, initialVersion);
            return fetchScriptById(dsl, script.scriptId()).orElseThrow();
        });
    }

    @Override
    public ScriptRecord appendVersion(String scriptId, ScriptVersionRecord version, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            dsl.execute("select 1 from script where script_id = {0} for update", scriptId);
            insertVersionRow(dsl, version);
            Timestamp now = Timestamp.from(version.createdAt());
            dsl.execute("update script set current_version = {1}, credential_refs = {2}::text[], "
                    + "egress_device_targets = {3}::text[], updated_at = {4} where script_id = {0}",
                    scriptId,
                    version.version(),
                    version.credentialRefs().toArray(String[]::new),
                    version.egressDeviceTargets().toArray(String[]::new),
                    now);
            return fetchScriptById(dsl, scriptId).orElseThrow();
        });
    }

    @Override
    public Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            String runAs,
            String actorFingerprint,
            String actionId) {
        return updateSettings(scriptId, enabled, schedule, timeoutSeconds, egress, egressDeviceTargets, credentialRefs,
                notifyMode, false, runAs, actorFingerprint, actionId);
    }

    @Override
    public Optional<ScriptRecord> updateSettings(
            String scriptId,
            boolean enabled,
            Optional<String> schedule,
            int timeoutSeconds,
            List<String> egress,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            String notifyMode,
            boolean notifyMailOnFailureOnly,
            String runAs,
            String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            int updated = dsl.execute("update script set enabled = {1}, schedule = {2}, timeout_s = {3}, "
                    + "egress = {4}::text[], egress_device_targets = {5}::text[], credential_refs = {6}::text[], "
                    + "notify = {7}, run_as = {8}, updated_at = {9} where script_id = {0}",
                    scriptId,
                    enabled,
                    schedule.orElse(null),
                    timeoutSeconds,
                    egress.toArray(String[]::new),
                    egressDeviceTargets.toArray(String[]::new),
                    credentialRefs.toArray(String[]::new),
                    storedNotify(notifyMode, notifyMailOnFailureOnly),
                    runAs,
                    Timestamp.from(Instant.now()));
            if (updated == 0) {
                return Optional.empty();
            }
            return fetchScriptById(dsl, scriptId);
        });
    }

    @Override
    public Optional<ScriptRecord> setEnabled(String scriptId, boolean enabled, String actorFingerprint,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, actionId, dsl -> {
            int updated = dsl.execute("update script set enabled = {1}, updated_at = {2} where script_id = {0}",
                    scriptId, enabled, Timestamp.from(Instant.now()));
            if (updated == 0) {
                return Optional.empty();
            }
            return fetchScriptById(dsl, scriptId);
        });
    }

    @Override
    public List<String> findEnrolledDeviceIdsByEndpointHosts(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) {
            return List.of();
        }
        Set<String> lowered = new LinkedHashSet<>();
        for (String h : hosts) {
            if (h != null && !h.isBlank()) {
                lowered.add(h.strip().toLowerCase(Locale.ROOT));
            }
        }
        if (lowered.isEmpty()) {
            return List.of();
        }
        return transactionBoundary.inTransaction(dsl -> {
            Set<String> matched = new LinkedHashSet<>();
            for (String host : lowered) {
                List<String> ids = dsl.fetch("select distinct d.device_id, d.created_at from devices d "
                        + "join endpoints e on e.device_id = d.device_id "
                        + "where d.enrollment_state <> 'DRAFT' "
                        + "and (lower(btrim(e.address_ref)) = {0} or lower(btrim(split_part(e.address_ref, ':', 1))) = {0}) "
                        + "order by d.created_at asc, d.device_id asc",
                        host).getValues("device_id", String.class);
                matched.addAll(ids);
            }
            return List.copyOf(matched);
        });
    }

    @Override
    public Optional<Instant> findLastScheduledSlot(String scriptId) {
        if (scriptId == null || scriptId.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select last_scheduled_slot from script where script_id = {0}", scriptId.strip())
                .stream()
                .findFirst()
                .flatMap(row -> Optional.ofNullable(row.get("last_scheduled_slot", Timestamp.class))
                        .map(Timestamp::toInstant)));
    }

    @Override
    public Optional<ScriptRunRecord> admitRun(
            String jobId,
            String scriptId,
            int version,
            String triggerKind,
            Optional<Instant> scheduledSlot,
            String runAs,
            List<String> egressDeclared,
            List<String> egressDeviceTargets,
            List<String> credentialRefs,
            Instant requestedAt,
            String requestedBy,
            String actionId) {
        return auditedTransactionBoundary.inTransaction(requestedBy, actionId, dsl -> {
            Timestamp nowTs = Timestamp.from(requestedAt);
            if (scheduledSlot.isPresent()) {
                Timestamp slotTs = Timestamp.from(scheduledSlot.get());
                int claimed = dsl.execute(
                        "update script set last_scheduled_slot = {1}, updated_at = {2} "
                                + "where script_id = {0} and enabled = true "
                                + "and (last_scheduled_slot is null or last_scheduled_slot < {1})",
                        scriptId, slotTs, nowTs);
                if (claimed == 0) {
                    return Optional.empty();
                }
            }
            String targetRef = ScriptExecutionJob.formatTargetRef(scriptId, version);
            String idempotencyKey = scheduledSlot
                    .map(slot -> "script_schedule:" + scriptId + ":" + slot)
                    .orElse("script_run:" + jobId);
            dsl.execute(
                    "insert into jobs(job_id, job_type, capability_id, target_kind, target_ref, "
                            + "submitted_by_actor_fingerprint, submitted_at, idempotency_key, action_class, state) "
                            + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}, 'read', 'REQUESTED')",
                    jobId,
                    ScriptExecutionJob.CAPABILITY,
                    ScriptExecutionJob.CAPABILITY,
                    ScriptExecutionJob.TARGET_KIND,
                    targetRef,
                    requestedBy,
                    nowTs,
                    idempotencyKey);
            dsl.execute(
                    "insert into script_run(job_id, script_id, version, state, trigger_kind, scheduled_slot, "
                            + "run_as, requested_by, requested_at, egress_declared, egress_device_targets, "
                            + "credential_refs_used) "
                            + "values ({0}, {1}, {2}, 'REQUESTED', {3}, {4}, {5}, {6}, {7}, {8}::text[], {9}::text[], {10}::text[])",
                    jobId,
                    scriptId,
                    version,
                    triggerKind,
                    scheduledSlot.map(Timestamp::from).orElse(null),
                    runAs,
                    requestedBy,
                    nowTs,
                    (egressDeclared == null ? List.<String>of() : egressDeclared).toArray(String[]::new),
                    (egressDeviceTargets == null ? List.<String>of() : egressDeviceTargets).toArray(String[]::new),
                    (credentialRefs == null ? List.<String>of() : credentialRefs).toArray(String[]::new));
            return fetchRunByJobId(dsl, jobId);
        });
    }

    @Override
    public Optional<ScriptRunRecord> findRunByJobId(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> fetchRunByJobId(dsl, jobId.strip()));
    }

    @Override
    public List<ScriptRunRecord> findRunsByScriptId(String scriptId, int limit) {
        if (scriptId == null || scriptId.isBlank() || limit <= 0) {
            return List.of();
        }
        int bounded = Math.min(limit, 500);
        return transactionBoundary.inTransaction(dsl -> dsl
                .fetch("select " + RUN_COLUMNS + RUN_FROM + " where script_id = {0} "
                        + "order by requested_at desc, job_id desc limit {1}", scriptId.strip(), bounded)
                .stream()
                .map(JooqScriptRepository::toRunRecord)
                .toList());
    }

    @Override
    public Optional<RunLease> findRunLease(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                        "select j.job_id, j.state as job_state, j.lease_epoch, j.lease_worker_id, r.state as run_state, "
                                + "r.dispatch_epoch, r.session_id from jobs j join script_run r on r.job_id = j.job_id "
                                + "where j.job_id = {0} and j.capability_id = {1}",
                        jobId.strip(), ScriptExecutionJob.CAPABILITY)
                .stream()
                .findFirst()
                .map(row -> new RunLease(
                        row.get("job_id", String.class),
                        row.get("job_state", String.class),
                        row.get("lease_epoch", Long.class) == null ? -1L : row.get("lease_epoch", Long.class),
                        row.get("lease_worker_id", String.class),
                        row.get("run_state", String.class),
                        Optional.ofNullable(row.get("dispatch_epoch", Long.class)),
                        Optional.ofNullable(row.get("session_id", String.class)))));
    }

    @Override
    public boolean beginDispatch(
            String jobId,
            long epoch,
            String sessionId,
            List<String> credentialRefsUsed,
            Instant startedAt,
            String actorFingerprint) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, ScriptExecutionJob.CAPABILITY, dsl -> {
            int updated = dsl.execute(
                    "update script_run set state = 'RUNNING', session_id = {1}, dispatch_epoch = {2}, "
                            + "credential_refs_used = {3}::text[], started_at = {4} "
                            + "where job_id = {0} and state in ('REQUESTED', 'RUNNING') "
                            + "and (dispatch_epoch is null or dispatch_epoch < {2})",
                    jobId,
                    sessionId,
                    epoch,
                    (credentialRefsUsed == null ? List.<String>of() : credentialRefsUsed).toArray(String[]::new),
                    Timestamp.from(startedAt));
            return updated == 1;
        });
    }

    @Override
    public boolean runnerAvailable() {
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                        "select 1 from module_runtime_control where module = {0} and owner_instance is not null "
                                + "and owner_heartbeat_at > now() - interval '60 seconds'", ScriptExecutionJob.MODULE)
                .isNotEmpty());
    }

    @Override
    public void auditOutputDownload(String jobId, String actorFingerprint) {
        auditedTransactionBoundary.inTransaction(actorFingerprint, "script_output_download", jobId,
                dsl -> dsl.fetch("select audit_script_output_download({0})", jobId));
    }

    @Override
    public List<ExpiredRunOutput> findRunsWithOutputOlderThan(Instant cutoff, int limit) {
        if (cutoff == null || limit <= 0) {
            return List.of();
        }
        int bounded = Math.min(limit, 500);
        Timestamp cutoffTs = Timestamp.from(cutoff);
        return transactionBoundary.inTransaction(dsl -> dsl.fetch(
                        "select job_id, output_artefact_ref from script_run "
                                + "where state in ('COMPLETED', 'FAILED', 'TIMED_OUT', 'CANCELLED') "
                                + "and coalesce(finished_at, requested_at) < {0} "
                                + "and (output_artefact_ref is not null or output_tail is not null or output_wrapped_data_key is not null) "
                                + "order by coalesce(finished_at, requested_at) asc, job_id asc limit {1}",
                        cutoffTs, bounded)
                .stream()
                .map(row -> new ExpiredRunOutput(
                        row.get("job_id", String.class),
                        Optional.ofNullable(row.get("output_artefact_ref", String.class))))
                .toList());
    }

    @Override
    public boolean clearExpiredRunOutput(String jobId, String actorFingerprint) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        return auditedTransactionBoundary.inTransaction(actorFingerprint, "script_output_retention_prune", dsl -> {
            int updated = dsl.execute(
                    "update script_run set output_artefact_ref = null, output_key_id = null, "
                            + "output_wrapped_data_key = null, output_tail = null "
                            + "where job_id = {0} and (output_artefact_ref is not null or output_tail is not null "
                            + "or output_wrapped_data_key is not null)",
                    jobId.strip());
            return updated == 1;
        });
    }

    @Override
    public boolean completeRun(
            String jobId,
            String state,
            String outcome,
            Instant finishedAt,
            long durationMs,
            Integer exitCode,
            int stdoutBytes,
            int stderrBytes,
            boolean stdoutTruncated,
            boolean stderrTruncated,
            Optional<String> outputArtefactRef,
            Optional<String> outputKeyId,
            byte[] outputWrappedDataKey,
            Optional<String> outputSha256,
            Optional<Long> outputSizeBytes,
            Optional<String> outputTail,
            List<String> egressDeviceTargets,
            List<String> credentialRefsUsed,
            Optional<String> failureReason,
            String actorFingerprint) {
        return auditedTransactionBoundary.inTransaction(actorFingerprint, ScriptExecutionJob.CAPABILITY, dsl -> {
            int updated = dsl.execute(
                    "update script_run set state = {1}, outcome = {2}, finished_at = {3}, duration_ms = {4}, "
                            + "exit_code = {5}, stdout_bytes = {6}, stderr_bytes = {7}, stdout_truncated = {8}, "
                            + "stderr_truncated = {9}, output_artefact_ref = {10}, output_key_id = {11}, "
                            + "output_wrapped_data_key = {12}, output_sha256 = {13}, output_size_bytes = {14}, "
                            + "output_tail = {15}, egress_device_targets = {16}::text[], "
                            + "credential_refs_used = {17}::text[], failure_reason = {18} "
                            + "where job_id = {0} and state in ('REQUESTED', 'RUNNING')",
                    jobId,
                    state,
                    outcome,
                    Timestamp.from(finishedAt),
                    Math.max(0L, durationMs),
                    exitCode,
                    stdoutBytes,
                    stderrBytes,
                    stdoutTruncated,
                    stderrTruncated,
                    outputArtefactRef.orElse(null),
                    outputKeyId.orElse(null),
                    outputWrappedDataKey,
                    outputSha256.orElse(null),
                    outputSizeBytes.orElse(null),
                    outputTail.orElse(null),
                    (egressDeviceTargets == null ? List.<String>of() : egressDeviceTargets).toArray(String[]::new),
                    (credentialRefsUsed == null ? List.<String>of() : credentialRefsUsed).toArray(String[]::new),
                    failureReason.orElse(null));
            return updated == 1;
        });
    }

    private static void insertVersionRow(DSLContext dsl, ScriptVersionRecord v) {
        dsl.execute("insert into script_version(script_id, version, sha256, size_bytes, artefact_ref, key_id, "
                + "wrapped_data_key, secret_findings, credential_refs, egress_device_targets, created_by, created_at) "
                + "values ({0}, {1}, {2}, {3}, {4}, {5}, {6}, {7}::jsonb, {8}::text[], {9}::text[], {10}, {11})",
                v.scriptId(),
                v.version(),
                v.sha256(),
                v.sizeBytes(),
                v.artefactRef(),
                v.keyId(),
                v.wrappedDataKey(),
                toSecretFindingsJson(v.secretFindings()),
                v.credentialRefs().toArray(String[]::new),
                v.egressDeviceTargets().toArray(String[]::new),
                v.createdBy(),
                Timestamp.from(v.createdAt()));
    }

    private static Optional<ScriptRecord> fetchScriptById(DSLContext dsl, String scriptId) {
        return dsl.fetch("select " + SCRIPT_COLUMNS + " from script where script_id = {0}", scriptId)
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toScriptRecord);
    }

    private static Optional<ScriptRunRecord> fetchRunByJobId(DSLContext dsl, String jobId) {
        return dsl.fetch("select " + RUN_COLUMNS + RUN_FROM + " where job_id = {0}", jobId)
                .stream()
                .findFirst()
                .map(JooqScriptRepository::toRunRecord);
    }

    private static ScriptRunRecord toRunRecord(Record row) {
        return new ScriptRunRecord(
                row.get("job_id", String.class),
                row.get("script_id", String.class),
                row.get("version", Integer.class),
                row.get("state", String.class),
                Optional.ofNullable(row.get("outcome", String.class)),
                row.get("trigger_kind", String.class),
                Optional.ofNullable(row.get("scheduled_slot", Timestamp.class)).map(Timestamp::toInstant),
                row.get("run_as", String.class),
                row.get("requested_by", String.class),
                row.get("requested_at", Timestamp.class).toInstant(),
                Optional.ofNullable(row.get("started_at", Timestamp.class)).map(Timestamp::toInstant),
                Optional.ofNullable(row.get("finished_at", Timestamp.class)).map(Timestamp::toInstant),
                Optional.ofNullable(row.get("duration_ms", Long.class)),
                Optional.ofNullable(row.get("exit_code", Integer.class)),
                row.get("stdout_bytes", Integer.class) == null ? 0 : row.get("stdout_bytes", Integer.class),
                row.get("stderr_bytes", Integer.class) == null ? 0 : row.get("stderr_bytes", Integer.class),
                Boolean.TRUE.equals(row.get("stdout_truncated", Boolean.class)),
                Boolean.TRUE.equals(row.get("stderr_truncated", Boolean.class)),
                Optional.ofNullable(row.get("output_artefact_ref", String.class)),
                Optional.ofNullable(row.get("output_key_id", String.class)),
                row.get("output_wrapped_data_key", byte[].class),
                Optional.ofNullable(row.get("output_sha256", String.class)),
                Optional.ofNullable(row.get("output_size_bytes", Long.class)),
                Optional.ofNullable(row.get("output_tail", String.class)),
                stringList(row.get("egress_declared", String[].class)),
                stringList(row.get("egress_device_targets", String[].class)),
                stringList(row.get("credential_refs_used", String[].class)),
                Optional.ofNullable(row.get("session_id", String.class)),
                Optional.ofNullable(row.get("failure_reason", String.class)),
                toNotification(row));
    }

    private static Optional<ScriptRunNotification> toNotification(Record row) {
        Timestamp notifiedAt = row.field("notified_at") == null ? null : row.get("notified_at", Timestamp.class);
        String syslog = row.field("notify_syslog") == null ? null : row.get("notify_syslog", String.class);
        String smtp = row.field("notify_smtp") == null ? null : row.get("notify_smtp", String.class);
        if (notifiedAt == null || syslog == null || smtp == null) {
            return Optional.empty();
        }
        return Optional.of(new ScriptRunNotification(
                syslog,
                Optional.ofNullable(row.get("notify_syslog_code", String.class)),
                smtp,
                Optional.ofNullable(row.get("notify_smtp_code", String.class)),
                notifiedAt.toInstant()));
    }

    private static ScriptRecord toScriptRecord(Record row) {
        return new ScriptRecord(
                row.get("script_id", String.class),
                row.get("name", String.class),
                row.get("kind", String.class),
                row.get("current_version", Integer.class),
                Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
                Optional.ofNullable(row.get("schedule", String.class)),
                row.get("timeout_s", Integer.class),
                stringList(row.get("egress", String[].class)),
                stringList(row.get("egress_device_targets", String[].class)),
                stringList(row.get("credential_refs", String[].class)),
                notifyChannel(row.get("notify", String.class)),
                row.get("run_as", String.class),
                row.get("created_by_actor_fingerprint", String.class),
                row.get("created_at", Timestamp.class).toInstant(),
                row.get("updated_at", Timestamp.class).toInstant(),
                mailOnFailureOnly(row.get("notify", String.class)));
    }

    private static ScriptVersionRecord toVersionRecord(Record row) {
        return new ScriptVersionRecord(
                row.get("script_id", String.class),
                row.get("version", Integer.class),
                row.get("sha256", String.class),
                row.get("size_bytes", Long.class),
                row.get("artefact_ref", String.class),
                row.get("key_id", String.class),
                row.get("wrapped_data_key", byte[].class),
                parseSecretFindings(row.get("secret_findings_json", String.class)),
                stringList(row.get("credential_refs", String[].class)),
                stringList(row.get("egress_device_targets", String[].class)),
                row.get("created_by", String.class),
                row.get("created_at", Timestamp.class).toInstant());
    }

    private static List<String> stringList(String[] values) {
        return values == null ? List.of() : List.copyOf(Arrays.asList(values));
    }

    public static String toSecretFindingsJson(List<ScriptSecretFinding> findings) {
        List<ScriptSecretFinding> list = findings == null ? List.of() : findings;
        StringBuilder linesOut = new StringBuilder("[");
        StringBuilder itemsOut = new StringBuilder("[");
        List<Integer> uniqueLines = list.stream().map(ScriptSecretFinding::line).distinct().sorted().toList();
        for (int i = 0; i < uniqueLines.size(); i++) {
            if (i > 0) linesOut.append(',');
            linesOut.append(uniqueLines.get(i));
        }
        linesOut.append(']');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) itemsOut.append(',');
            ScriptSecretFinding f = list.get(i);
            itemsOut.append("{\"line\":").append(f.line())
                    .append(",\"pattern_class\":\"").append(escapeJson(f.patternClass())).append("\"}");
        }
        itemsOut.append(']');
        return "{\"count\":" + list.size() + ",\"line_numbers\":" + linesOut + ",\"findings\":" + itemsOut + "}";
    }

    public static List<ScriptSecretFinding> parseSecretFindings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<ScriptSecretFinding> out = new ArrayList<>();
        Matcher m = FINDING_ITEM.matcher(json);
        while (m.find()) {
            if (m.group(1) != null) {
                out.add(new ScriptSecretFinding(Integer.parseInt(m.group(1)), m.group(2)));
            } else {
                out.add(new ScriptSecretFinding(Integer.parseInt(m.group(4)), m.group(3)));
            }
        }
        return List.copyOf(out);
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
