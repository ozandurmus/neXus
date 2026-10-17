package com.securityexpert.nexus.ui2.worker.policy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.jobs.policy.PolicyCompletenessGates;
import com.securityexpert.nexus.ui2.jobs.transport.ExecResult;
import com.securityexpert.nexus.ui2.jobs.transport.ExecSpec;
import com.securityexpert.nexus.ui2.jobs.transport.TransportSession;
import com.securityexpert.nexus.ui2.persistence.discovery.DiscoveryRun;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyChangeEvidenceRepository.Evidence;
import com.securityexpert.nexus.ui2.persistence.policy.PolicyCollectionRepository;
import com.securityexpert.nexus.ui2.platform.HostnameFingerprint;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.policy.PolicySnapshot;
import com.securityexpert.nexus.ui2.worker.discovery.cp.MgmtCliCommands;
import com.securityexpert.nexus.ui2.worker.transcript.JobTranscriptScope;

/**
 * Check Point change evidence for one changed domain (PO approval 2026-10-18; plan
 * docs/design/POLICY_COLLECTION_COMPLETENESS_PLAN.md section 6, commands C-1 and C-2): {@code show-changes} between the
 * previous and the current published-session time returns a task id, and {@code show-task} returns, per published session,
 * the added, modified and deleted objects. Only uid, type, operation, counts, the publish time and the admin identity (an
 * HMAC, or masked) are kept: object names and user names are dropped in memory (field 9 of C-2).
 *
 * <ul>
 *   <li><b>Off by default (partial-first).</b> It reads only the one domain named by
 *       {@code UI2_POLICY_CP_SHOW_CHANGES_TRIAL_DOMAIN}, and only when the published-session marker changed (the plan's
 *       frequency rule), at most once per domain per six hours for automatic jobs through the existing
 *       {@code policy_collection_domain} throttle.</li>
 *   <li><b>Window.</b> It runs inside a policy job admitted through {@link JobWindowPolicy} and refuses to start when the
 *       job started outside the window.</li>
 *   <li><b>Bounds.</b> At most {@value PolicyCompletenessGates#CP_SHOW_CHANGES_MAX_OFFSETS} offsets of
 *       {@code limit 10} sessions; {@code show-task} polled every 5 s, at most 24 polls per task; 60 s per command, no
 *       retry; one extra trusted MDS session for the whole read (the same connect path as the collector), serial.</li>
 *   <li><b>Fail closed.</b> An error, a missing task id, a failed or unfinished task or an unknown shape is {@code UNKNOWN}:
 *       the domain was already collected fully (as before), the revision is recorded without a change list, and no
 *       evidence is stored. The reader never throws for those.</li>
 * </ul>
 *
 * <p><b>UNKNOWN (plan section 8), not resolved here:</b> whether {@code show-changes} opens a session or lock on the MDS
 * (the plan asks to measure once; this reader only records the outcome); whether a modified rule's diff names its layer
 * (nothing here depends on it: the whole changed domain is re-read as before); the exact API version on the MDS; the
 * maximum {@code limit}. The response shape (tasks, task-details, changes, a session with its uid, user name and publish
 * time, and the operations added, modified and deleted) is the one the plan quotes from the vendor reference and is
 * unmeasured: where the session is placed in the answer is searched tolerantly, and an object or session that does not fit
 * the expected shape is counted but not attributed.</p>
 */
final class CpShowChangesReader {
    @FunctionalInterface interface Pause { void sleep(Duration duration) throws InterruptedException; }

    /** Everything the reader may spend on one domain, beyond the per-call timeouts. */
    static final Duration BUDGET = Duration.ofMinutes(15);
    private static final int MAX_OBJECTS_PER_SESSION = 200;
    private static final Pattern SESSION_UID = Pattern.compile("[0-9A-Za-z-]{1,64}");
    private static final Pattern OBJECT_UID = Pattern.compile("[0-9A-Za-z-]{8,64}");
    private static final Pattern OBJECT_TYPE = Pattern.compile("[a-z0-9][a-z0-9-]{0,47}");
    private static final Pattern API_CODE = Pattern.compile("[a-z0-9_]{1,64}");
    private static final DateTimeFormatter SQL_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DOMAIN_ADMIN = "policy-change-admin-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** {@code outcome}: OFF, COLLECTED, NO_CHANGES or UNKNOWN (with a fixed reason). Counts are session sums. */
    record Result(String outcome, String reason, int sessions, int added, int modified, int deleted, int polls, int unattributed) {
        static Result off() { return new Result("OFF", null, 0, 0, 0, 0, 0, 0); }
        static Result unknown(String reason, int polls) { return new Result("UNKNOWN", reason, 0, 0, 0, 0, polls, 0); }
        boolean measured() { return outcome.equals("COLLECTED") || outcome.equals("NO_CHANGES"); }
        /** The safe telemetry of plan section 6 (C-1: outcome, seconds; C-2: outcome, added, modified, deleted, polls). */
        String note(long seconds) {
            return "CP_SHOW_CHANGES outcome=" + outcome + (reason == null ? "" : " reason=" + reason) + " sessions=" + sessions
                    + " added=" + added + " modified=" + modified + " deleted=" + deleted + " polls=" + polls + " seconds=" + seconds;
        }
    }

    private static final class Unknown extends RuntimeException {
        final String code;
        Unknown(String code) { super(code, null, false, false); this.code = code; }
    }

    private static final class Session {
        final String uid; String adminRef = "UNKNOWN"; String changeTime; int added, modified, deleted;
        final ArrayNode objects = JSON.createArrayNode(); boolean truncated;
        Session(String uid) { this.uid = uid; }
    }

    private final CheckPointPolicyCollector collector;
    private final GateRegistryPort gates;
    private final PolicyChangeEvidenceRepository evidence;
    private final PolicyCollectionRepository policies;
    private final PolicyCompletenessSettings settings;
    private final JobWindowPolicy windows;
    private final HostnameFingerprint fingerprint;
    private final Pause pause;

    CpShowChangesReader(CheckPointPolicyCollector collector, GateRegistryPort gates, PolicyChangeEvidenceRepository evidence,
            PolicyCollectionRepository policies, PolicyCompletenessSettings settings, JobWindowPolicy windows,
            HostnameFingerprint fingerprint, Pause pause) {
        this.collector = collector; this.gates = gates; this.evidence = evidence; this.policies = policies;
        this.settings = settings; this.windows = windows; this.fingerprint = fingerprint; this.pause = pause;
    }

    /**
     * @param previousMarker the earlier published-session marker ({uid, publish_time, posix}), JSON
     * @param currentMarker the marker this run read, JSON; the two must differ (the caller only asks for a changed domain)
     */
    Result read(DiscoveryRun run, PolicyCollectionRepository.Request request, String container, String domainName,
            String previousMarker, String currentMarker, Instant jobStart, BooleanSupplier lease) {
        if (!settings.cpShowChangesTargets(container)) return Result.off();
        if (!windows.isOpen(jobStart)) return Result.unknown("OUTSIDE_JOB_WINDOW", 0);
        if (!PolicyCompletenessGates.cpShowChangesEnabled(gates)) return Result.unknown("POLICY_GATE_UNAVAILABLE", 0);
        String from, to;
        try {
            from = publishTime(previousMarker);
            to = publishTime(currentMarker);
            if (!OffsetDateTime.parse(normalize(from)).isBefore(OffsetDateTime.parse(normalize(to)))) return Result.unknown("WINDOW_NOT_FORWARD", 0);
        } catch (RuntimeException unreadable) {
            return Result.unknown("MARKER_UNREADABLE", 0);
        }
        if (!policies.beginDomain(request.sourceId(), PolicySnapshot.ref("cp-show-changes", container), request.throttledAutomatic()))
            return Result.unknown("THROTTLED", 0);
        long started = System.nanoTime();
        long deadline = started + BUDGET.toNanos();
        int[] polls = {0};
        TransportSession session = null;
        Result result;
        try {
            session = collector.openSession(run, deadline, lease);
            Map<String, Session> sessions = new LinkedHashMap<>();
            int unattributed = 0;
            for (int page = 0; page < PolicyCompletenessGates.CP_SHOW_CHANGES_MAX_OFFSETS; page++) {
                int offset = page * PolicyCompletenessGates.CP_SHOW_CHANGES_LIMIT;
                JsonNode started1 = exec(session, PolicyCompletenessGates.C1_SHOW_CHANGES,
                        MgmtCliCommands.showChanges(domainName, from, to, offset), deadline, lease);
                String taskId = started1.path("task-id").asText("");
                if (!PolicyCompletenessGates.CP_TASK_ID.matcher(taskId).matches()) throw new Unknown("TASK_ID_MISSING");
                JsonNode task = waitForTask(session, domainName, taskId, deadline, lease, polls);
                int[] pageStats = {0, 0};
                unattributed += absorb(task, sessions, pageStats);
                if (pageStats[0] < PolicyCompletenessGates.CP_SHOW_CHANGES_LIMIT) break;
            }
            int added = 0, modified = 0, deleted = 0;
            List<Evidence> rows = new ArrayList<>();
            for (Session s : sessions.values()) {
                added += s.added; modified += s.modified; deleted += s.deleted;
                rows.add(new Evidence("check_point", PolicyChangeEvidenceRepository.CP_SHOW_CHANGES, container, s.uid,
                        s.changeTime, s.changeTime == null ? null : "UTC", s.adminRef, null, null, null, 0, s.added, s.modified,
                        s.deleted, s.objects.toString(), s.truncated));
            }
            if (!rows.isEmpty()) {
                try { evidence.save(request.sourceId(), request.jobId(), rows, java.util.Optional.empty()); }
                catch (RuntimeException notStored) {
                    com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(notStored);
                    throw new Unknown("EVIDENCE_NOT_STORED");
                }
            }
            result = new Result(rows.isEmpty() && unattributed == 0 ? "NO_CHANGES" : "COLLECTED", null, sessions.size(), added,
                    modified, deleted, polls[0], unattributed);
        } catch (Unknown unknown) {
            result = Result.unknown(unknown.code, polls[0]);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            result = Result.unknown("INTERRUPTED", polls[0]);
        } catch (RuntimeException failure) {
            com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(failure);
            String reason = PolicyCollectionTrace.reason(failure);
            if (reason.endsWith(": LEASE_LOST") || reason.endsWith(": CANCELLED")) throw failure;
            result = Result.unknown("CP_SESSION_UNAVAILABLE", polls[0]);
        } finally {
            if (session != null) {
                TransportSession owned = session;
                try { JobTranscriptScope.withoutRecording(() -> { collector.transport.disconnect(owned); return null; }); }
                catch (RuntimeException closeFailed) {
                    com.securityexpert.nexus.ui2.worker.transport.EndpointRuntime.rethrow(closeFailed);
                }
            }
        }
        JobTranscriptScope.add("job", "note", result.note((System.nanoTime() - started) / 1_000_000_000L));
        return result;
    }

    /** C-2 until the task finishes: every 5 s, at most 24 polls. */
    private JsonNode waitForTask(TransportSession session, String domain, String taskId, long deadline, BooleanSupplier lease,
            int[] polls) throws InterruptedException {
        for (int poll = 1; poll <= PolicyCompletenessGates.CP_SHOW_TASK_MAX_POLLS; poll++) {
            pause.sleep(Duration.ofSeconds(PolicyCompletenessGates.CP_SHOW_TASK_POLL_SECONDS));
            polls[0]++;
            JsonNode root = exec(session, PolicyCompletenessGates.C2_SHOW_TASK, MgmtCliCommands.showTask(domain, taskId), deadline, lease);
            JsonNode task = root.path("tasks").path(0);
            String status = task.path("status").asText(root.path("status").asText("")).toLowerCase(java.util.Locale.ROOT);
            if (status.equals("succeeded")) return task;
            if (status.equals("failed") || status.equals("partially succeeded")) throw new Unknown("TASK_" + status.toUpperCase(java.util.Locale.ROOT).replace(' ', '_'));
        }
        throw new Unknown("TASK_NOT_FINISHED");
    }

    /** One gated, bounded exec; the answer must be a JSON object that is not an API error. Never recorded in a transcript. */
    private JsonNode exec(TransportSession session, PolicyCompletenessGates.Command gate, String command, long deadline,
            BooleanSupplier lease) {
        com.securityexpert.nexus.ui2.worker.JobCancellationScope.check();
        if (!lease.getAsBoolean()) throw PolicyCollectionTrace.failure("LEASE_LOST");
        if (!PolicyCompletenessGates.enabled(gates, gate)) throw new Unknown("POLICY_GATE_UNAVAILABLE");
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new Unknown("READER_BUDGET_EXHAUSTED");
        var result = JobTranscriptScope.withoutRecording(() -> collector.transport.execInteractive(session, new ExecSpec(command, false, 0),
                Duration.ofNanos(Math.min(Duration.ofSeconds(gate.timeoutS()).toNanos(), remaining))));
        if (result instanceof ExecResult.TimedOut) throw new Unknown("TIMEOUT");
        if (!(result instanceof ExecResult.Completed completed)) throw new Unknown("CHANNEL_FAILED");
        JsonNode root;
        try {
            String output = completed.output();
            int open = output.indexOf('{'), close = output.lastIndexOf('}');
            if (open < 0 || close < open) throw new Unknown("RESPONSE_NOT_JSON");
            root = JSON.readTree(output.substring(open, close + 1).getBytes(StandardCharsets.UTF_8));
        } catch (java.io.IOException invalid) {
            throw new Unknown("RESPONSE_NOT_JSON");
        }
        if (root == null || !root.isObject()) throw new Unknown("RESPONSE_NOT_JSON");
        if (root.has("code") && !root.has("tasks") && !root.has("task-id")) {
            String code = root.path("code").asText("");
            throw new Unknown("API_ERROR_" + (API_CODE.matcher(code).matches() ? code.toUpperCase(java.util.Locale.ROOT) : "UNKNOWN"));
        }
        if (completed.exitStatus() != 0) throw new Unknown("EXIT_" + completed.exitStatus());
        return root;
    }

    /**
     * Adds the sessions of one finished task to {@code sessions}. {@code stats[0]} counts the sessions the page held (the
     * paging signal). Returns the number of changes that could not be attributed to a session uid (counted, not stored).
     */
    int absorb(JsonNode task, Map<String, Session> sessions, int[] stats) {
        int unattributed = 0;
        java.util.Set<String> pageSessions = new java.util.HashSet<>();
        for (JsonNode detail : task.path("task-details")) {
            for (JsonNode change : detail.path("changes")) {
                JsonNode sessionNode = change.path("session").isObject() ? change.path("session") : detail.path("session");
                String uid = sessionNode.path("session-uid").asText(sessionNode.path("uid").asText(""));
                JsonNode operations = change.path("operations");
                if (!SESSION_UID.matcher(uid).matches()) { unattributed++; continue; }
                pageSessions.add(uid);
                Session s = sessions.computeIfAbsent(uid, Session::new);
                String user = sessionNode.path("user-name").asText("");
                if (s.adminRef.equals("UNKNOWN") && !user.isBlank()) s.adminRef = adminRef(user);
                if (s.changeTime == null) s.changeTime = utc(sessionNode.path("publish-time"));
                for (JsonNode o : operations.path("added-objects")) { s.added++; keep(s, o, "ADDED"); }
                for (JsonNode o : operations.path("modified-objects")) {
                    s.modified++; keep(s, o.has("new-object") ? o.path("new-object") : o.path("old-object"), "MODIFIED");
                }
                for (JsonNode o : operations.path("deleted-objects")) { s.deleted++; keep(s, o, "DELETED"); }
            }
        }
        stats[0] += pageSessions.size();
        return unattributed;
    }

    private void keep(Session s, JsonNode object, String operation) {
        String uid = object.path("uid").asText(""), type = object.path("type").asText("");
        if (!OBJECT_UID.matcher(uid).matches() || !OBJECT_TYPE.matcher(type).matches()) return;
        if (s.objects.size() >= MAX_OBJECTS_PER_SESSION) { s.truncated = true; return; }
        s.objects.addObject().put("uid", uid).put("type", type).put("op", operation);
    }

    private String adminRef(String user) {
        byte[] bytes = user.strip().getBytes(StandardCharsets.UTF_8);
        return fingerprint != null ? fingerprint.ofContent(DOMAIN_ADMIN, bytes, 0, bytes.length) : "masked:" + Math.min(user.strip().length(), 999);
    }

    /** The publish time as UTC wall-clock text, from {posix (ms), iso-8601} or an ISO string; null (UNKNOWN) otherwise. */
    static String utc(JsonNode time) {
        try {
            Instant at;
            if (time.isObject() && time.path("posix").isIntegralNumber()) at = Instant.ofEpochMilli(time.path("posix").longValue());
            else {
                String iso = time.isObject() ? time.path("iso-8601").asText("") : time.asText("");
                if (iso.isBlank()) return null;
                at = OffsetDateTime.parse(normalize(iso)).toInstant();
            }
            return at.atOffset(ZoneOffset.UTC).format(SQL_TIME);
        } catch (RuntimeException unreadable) { return null; }
    }

    private static String publishTime(String markerJson) {
        try {
            String value = JSON.readTree(markerJson).path("publish_time").asText("");
            if (!PolicyCompletenessGates.CP_ISO_TIME.matcher(value).matches()) throw new IllegalArgumentException("publish_time");
            return value;
        } catch (java.io.IOException | NullPointerException invalid) { throw new IllegalArgumentException("marker"); }
    }

    /** CP ISO dates may use an offset without a colon and minute precision (as CpDomainReuse reads them). */
    private static String normalize(String iso) {
        String withSeconds = iso.matches(".*T[0-9]{2}:[0-9]{2}(Z|[+-].*)?") ? iso.replaceFirst("(T[0-9]{2}:[0-9]{2})", "$1:00") : iso;
        return withSeconds.replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2");
    }
}
