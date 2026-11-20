package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.IOException;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.platform.ScriptOutputMasking;
import com.securityexpert.nexus.ui2.platform.WorkerActor;

/**
 * Runs one claimed {@code script_run} job as a short-lived Kubernetes Job ({@code SCRIPT_EXECUTION_CONTRACT.md}
 * Amendment 2026-11-18 (b)). The broker itself never executes a script and never holds a store key.
 *
 * <ol>
 *   <li>{@code CLAIMED -> EXECUTING}, then {@code start} on the service: body, the version's declared credentials and
 *       the per-run session, released once for this lease epoch.</li>
 *   <li>Resolve the declared egress now, create the run's NetworkPolicy, the seed Secrets and the Job (in that order,
 *       so the pod never exists without its policy).</li>
 *   <li>Wait for the Job to end, the broker's own deadline ({@code timeout_s} + {@link #STARTUP_GRACE}), or a lost
 *       lease. A lost lease deletes everything at once, revokes the session through {@code abort}, and reports nothing
 *       under the lease it no longer holds (review finding 9).</li>
 *   <li>Read the pod's exit code and log, split the streams, mask everything <em>before</em> truncation, delete the
 *       Job, its pod, the Secrets and the NetworkPolicy (always), report to the service, close the job.</li>
 * </ol>
 */
public final class ScriptRunJobExecutor {

    private static final Logger LOG = Logger.getLogger(ScriptRunJobExecutor.class.getName());

    public static final String RUNNER_ACTOR = WorkerActor.RESERVED_ACTOR_FINGERPRINT;
    public static final String DEFAULT_SERVICE_URL = "http://ui2-service:8080";
    private static final String STEP_KIND = "SCRIPT_RUN";
    /** Image pull, init step and log collection beyond the script's own {@code timeout_s}. */
    static final Duration STARTUP_GRACE = Duration.ofSeconds(180);

    private final JobLeaseRepository leases;
    private final JobStepAttemptRepository attempts;
    private final ScriptRunnerServiceClient service;
    private final ScriptRunKubernetes kubernetes;
    private final ScriptRunPodSpec.HostResolver resolver;
    private final String execImage;
    private final String cliServiceUrl;
    private final Clock clock;
    private final Duration pollInterval;
    private final Sleeper sleeper;

    @FunctionalInterface
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    public ScriptRunJobExecutor(
            JobLeaseRepository leases,
            JobStepAttemptRepository attempts,
            ScriptRunnerServiceClient service,
            ScriptRunKubernetes kubernetes,
            ScriptRunPodSpec.HostResolver resolver,
            String execImage,
            String cliServiceUrl,
            Clock clock,
            Duration pollInterval,
            Sleeper sleeper) {
        this.leases = Objects.requireNonNull(leases, "leases");
        this.attempts = Objects.requireNonNull(attempts, "attempts");
        this.service = Objects.requireNonNull(service, "service");
        this.kubernetes = Objects.requireNonNull(kubernetes, "kubernetes");
        this.resolver = resolver == null ? host -> List.of(InetAddress.getAllByName(host)) : resolver;
        this.execImage = Objects.requireNonNull(execImage, "execImage");
        this.cliServiceUrl = cliServiceUrl == null || cliServiceUrl.isBlank() ? DEFAULT_SERVICE_URL : cliServiceUrl;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
        this.sleeper = sleeper == null ? d -> Thread.sleep(d.toMillis()) : sleeper;
    }

    /** What happened to one run, as reported to the service. */
    record RunResult(String outcome, Integer exitCode, String failureReason, String stdout, String stderr, boolean cut) {
    }

    /**
     * Sweeps any orphaned {@code script-run-*} Jobs, seed Secrets and NetworkPolicies left behind in {@code ui2} by a
     * crashed or restarted broker instance. Returns the number of orphaned Jobs swept.
     */
    @SuppressWarnings("unchecked")
    public int sweepOrphans() {
        List<Map<String, Object>> jobs;
        try {
            jobs = kubernetes.listJobs();
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_orphan_sweep_list_failed: error={0}", e.getClass().getSimpleName());
            return 0;
        }
        int swept = 0;
        for (Map<String, Object> job : jobs) {
            if (!(job.get("metadata") instanceof Map<?, ?> meta)
                    || !(meta.get("name") instanceof String jobName)
                    || !ScriptRunKubernetes.NAME.matcher(jobName).matches()) {
                continue;
            }
            int secrets = Math.max(1, countSeedSecrets(job));
            deleteAll(jobName, secrets);
            swept++;
        }
        return swept;
    }

    @SuppressWarnings("unchecked")
    private static int countSeedSecrets(Map<String, Object> job) {
        if (!(job.get("spec") instanceof Map<?, ?> spec)
                || !(spec.get("template") instanceof Map<?, ?> template)
                || !(template.get("spec") instanceof Map<?, ?> podSpec)
                || !(podSpec.get("volumes") instanceof List<?> volumes)) {
            return 1;
        }
        for (Object v : volumes) {
            if (v instanceof Map<?, ?> vol
                    && "seed".equals(vol.get("name"))
                    && vol.get("projected") instanceof Map<?, ?> proj
                    && proj.get("sources") instanceof List<?> sources) {
                return Math.max(1, sources.size());
            }
        }
        return 1;
    }

    public void execute(ClaimedJob claimed, BooleanSupplier leaseLost) {
        Objects.requireNonNull(claimed, "claimed");
        String jobId = claimed.jobId();
        long epoch = claimed.leaseEpoch();
        if (!leases.transitionState(jobId, epoch, JobState.CLAIMED, JobState.EXECUTING, RUNNER_ACTOR, "script_run_running")) {
            LOG.log(Level.WARNING, "script_run_claim_lost_before_executing: job_id={0}", jobId);
            return;
        }
        String attemptId = attempts.insertPreContact(jobId, epoch, 0, STEP_KIND, "read", 1);
        Instant startedAt = clock.instant();

        ScriptRunnerServiceClient.RunBundle bundle;
        try {
            bundle = service.start(jobId, epoch);
        } catch (ScriptRunnerServiceClient.Refused refused) {
            close(jobId, epoch, attemptId, startedAt, new RunResult("FAILURE", null, "START_REFUSED:" + refused.code(),
                    "", "", false), List.of());
            return;
        } catch (IOException | RuntimeException e) {
            close(jobId, epoch, attemptId, startedAt, new RunResult("FAILURE", null, "START_FAILED", "", "", false),
                    List.of());
            return;
        }

        List<String> maskValues = new ArrayList<>();
        for (ScriptRunnerServiceClient.Credential c : bundle.credentials()) {
            maskValues.add(c.secret());
            maskValues.add(c.username());
        }
        maskValues.add(bundle.sessionCookie());
        maskValues.add(bundle.sessionCsrf());

        String jobName = ScriptRunPodSpec.jobName(jobId, epoch);
        List<Map<String, Object>> secrets = ScriptRunPodSpec.secrets(jobName, bundle, cliServiceUrl);
        RunResult result;
        try {
            Optional<RunResult> ran = runPod(jobName, bundle, secrets, leaseLost);
            if (ran.isEmpty()) {
                // Lease lost: nothing is reported under a lease the broker no longer holds.
                deleteAll(jobName, secrets.size());
                try {
                    service.abort(jobId, epoch);
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "script_run_abort_failed: job_id={0}", jobId);
                }
                LOG.log(Level.WARNING, "script_run_lease_lost_run_killed: job_id={0}", jobId);
                return;
            }
            result = ran.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result = new RunResult("FAILURE", null, "INTERRUPTED", "", "", false);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_execution_failed: job_id={0} error={1}",
                    new Object[] {jobId, e.getClass().getSimpleName()});
            result = new RunResult("FAILURE", null, "EXECUTION_ERROR:" + e.getClass().getSimpleName(), "", "", false);
        } finally {
            deleteAll(jobName, secrets.size());
        }
        close(jobId, epoch, attemptId, startedAt, result, maskValues);
    }

    /** Empty when the lease was lost while the pod ran. */
    Optional<RunResult> runPod(String jobName, ScriptRunnerServiceClient.RunBundle bundle,
            List<Map<String, Object>> secrets, BooleanSupplier leaseLost) throws IOException, InterruptedException {
        List<ScriptRunPodSpec.ResolvedEgress> egress = ScriptRunPodSpec.resolveEgress(bundle.egress(), resolver);
        StringBuilder notes = new StringBuilder();
        for (ScriptRunPodSpec.ResolvedEgress e : egress) {
            if (e.blockedProtectedAddress() && e.addresses().isEmpty()) {
                notes.append("[neXus] declared egress ").append(e.host()).append(':').append(e.port())
                        .append(" resolved to a protected cluster/internal address; no path to it was allowed\n");
            } else if (e.addresses().isEmpty()) {
                notes.append("[neXus] declared egress ").append(e.host()).append(':').append(e.port())
                        .append(" did not resolve at run start; no path to it was allowed\n");
            } else if (e.blockedProtectedAddress()) {
                notes.append("[neXus] declared egress ").append(e.host()).append(':').append(e.port())
                        .append(" had protected cluster/internal address(es) filtered out\n");
            }
        }
        // Objects of an earlier epoch of the same run (a broker restart) are removed first.
        deleteAll(jobName, secrets.size());
        kubernetes.create(KubernetesApi.Kind.NETWORK_POLICY, ScriptRunPodSpec.networkPolicy(jobName, egress));
        Map<String, Object> jobSpec = ScriptRunPodSpec.job(jobName, bundle, execImage, secrets.size(),
                resolveServiceHostAliases());
        kubernetes.create(KubernetesApi.Kind.JOB, jobSpec);
        String jobUid = resolveJobUid(jobSpec, jobName);
        for (Map<String, Object> secret : secrets) {
            ScriptRunPodSpec.withJobOwner(secret, jobName, jobUid);
            kubernetes.create(KubernetesApi.Kind.SECRET, secret);
        }

        Instant brokerDeadline = clock.instant().plusSeconds(bundle.timeoutSeconds()).plus(STARTUP_GRACE);
        String terminal = null;
        while (terminal == null) {
            if (leaseLost.getAsBoolean()) {
                return Optional.empty();
            }
            Optional<Map<String, Object>> job = kubernetes.get(KubernetesApi.Kind.JOB, jobName);
            terminal = job.map(ScriptRunJobExecutor::terminalCondition).orElse(null);
            if (terminal == null && !clock.instant().isBefore(brokerDeadline)) {
                terminal = "BrokerDeadline";
            }
            if (terminal == null) {
                sleeper.sleep(pollInterval);
            }
        }
        Integer exitCode = null;
        String podReason = null;
        ScriptRunLog.Split split = new ScriptRunLog.Split("", "", false);
        List<Map<String, Object>> pods = kubernetes.podsOfJob(jobName);
        if (!pods.isEmpty()) {
            Map<String, Object> pod = pods.get(0);
            exitCode = scriptExitCode(pod);
            podReason = podReason(pod);
            String podName = (String) ((Map<?, ?>) pod.get("metadata")).get("name");
            try {
                KubernetesApi.LogRead log = kubernetes.podLog(podName, ScriptRunPodSpec.SCRIPT_CONTAINER,
                        ScriptRunLog.READ_LIMIT_BYTES);
                split = ScriptRunLog.split(log.bytes(), log.limitReached());
            } catch (IOException e) {
                notes.append("[neXus] the script's output could not be read\n");
            }
        }
        String stderr = notes + split.stderr();
        if ("DeadlineExceeded".equals(terminal) || "BrokerDeadline".equals(terminal)) {
            return Optional.of(new RunResult("TIMED_OUT", exitCode, "TIMED_OUT", split.stdout(), stderr, split.cut()));
        }
        if ("Complete".equals(terminal) && exitCode != null && exitCode == 0) {
            return Optional.of(new RunResult("SUCCESS", 0, null, split.stdout(), stderr, split.cut()));
        }
        String reason = exitCode != null ? "EXIT_CODE_" + exitCode
                : "POD_FAILED" + (podReason == null ? "" : ":" + podReason);
        return Optional.of(new RunResult("FAILURE", exitCode, reason, split.stdout(), stderr, split.cut()));
    }

    /** {@code Complete}, the Failed condition's reason (e.g. {@code DeadlineExceeded}), or null while running. */
    @SuppressWarnings("unchecked")
    static String terminalCondition(Map<String, Object> job) {
        Object status = job.get("status");
        if (!(status instanceof Map<?, ?> s) || !(s.get("conditions") instanceof List<?> conditions)) {
            return null;
        }
        for (Object c : conditions) {
            Map<String, Object> condition = (Map<String, Object>) c;
            if (!"True".equals(condition.get("status"))) {
                continue;
            }
            if ("Complete".equals(condition.get("type"))) {
                return "Complete";
            }
            if ("Failed".equals(condition.get("type"))) {
                Object reason = condition.get("reason");
                return reason == null ? "Failed" : reason.toString();
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static Integer scriptExitCode(Map<String, Object> pod) {
        Object status = pod.get("status");
        if (!(status instanceof Map<?, ?> s) || !(s.get("containerStatuses") instanceof List<?> list)) {
            return null;
        }
        for (Object o : list) {
            Map<String, Object> cs = (Map<String, Object>) o;
            if (!ScriptRunPodSpec.SCRIPT_CONTAINER.equals(cs.get("name"))) {
                continue;
            }
            if (cs.get("state") instanceof Map<?, ?> state && state.get("terminated") instanceof Map<?, ?> t
                    && t.get("exitCode") instanceof Number n) {
                return n.intValue();
            }
        }
        return null;
    }

    static String podReason(Map<String, Object> pod) {
        if (pod.get("status") instanceof Map<?, ?> s && s.get("reason") instanceof String r && r.matches("[A-Za-z]{1,40}")) {
            return r;
        }
        return null;
    }

    private Map<String, List<String>> resolveServiceHostAliases() {
        try {
            String host = java.net.URI.create(cliServiceUrl).getHost();
            if (host == null || host.isBlank()) {
                return Map.of();
            }
            if (host.startsWith("[") && host.endsWith("]")) {
                return Map.of();
            }
            List<String> ips = new ArrayList<>();
            for (InetAddress addr : resolver.resolve(host)) {
                String ip = addr.getHostAddress();
                int pct = ip.indexOf('%');
                ips.add(pct > 0 ? ip.substring(0, pct) : ip);
            }
            return ips.isEmpty() ? Map.of() : Map.of(host, ips);
        } catch (IOException | RuntimeException ignored) {
            return Map.of();
        }
    }

    private String resolveJobUid(Map<String, Object> createdJob, String jobName) throws IOException {
        if (createdJob.get("metadata") instanceof Map<?, ?> meta
                && meta.get("uid") instanceof String uid
                && !uid.isBlank()) {
            return uid;
        }
        Optional<Map<String, Object>> fetched = kubernetes.get(KubernetesApi.Kind.JOB, jobName);
        if (fetched.isPresent()
                && fetched.get().get("metadata") instanceof Map<?, ?> meta
                && meta.get("uid") instanceof String uid
                && !uid.isBlank()) {
            return uid;
        }
        throw new IOException("kubernetes job " + jobName + " has no metadata.uid");
    }

    private void deleteAll(String jobName, int secretCount) {
        try {
            kubernetes.delete(KubernetesApi.Kind.JOB, jobName);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_delete_failed: kind=job name={0}", jobName);
        }
        for (int i = 0; i < secretCount; i++) {
            try {
                kubernetes.delete(KubernetesApi.Kind.SECRET, ScriptRunPodSpec.secretName(jobName, i));
            } catch (IOException | RuntimeException e) {
                LOG.log(Level.WARNING, "script_run_delete_failed: kind=secret name={0}", jobName);
            }
        }
        try {
            kubernetes.delete(KubernetesApi.Kind.NETWORK_POLICY, jobName);
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_delete_failed: kind=networkpolicy name={0}", jobName);
        }
    }

    private void close(String jobId, long epoch, String attemptId, Instant startedAt, RunResult result,
            List<String> maskValues) {
        ScriptOutputMasking.MaskedRunOutput masked = ScriptOutputMasking.maskThenTruncate(
                result.stdout(), result.stderr(), result.cut(), result.cut(), maskValues);
        long durationMs = Math.max(0L, Duration.between(startedAt, clock.instant()).toMillis());
        try {
            service.finish(jobId, epoch, new ScriptRunnerServiceClient.Finish(result.outcome(), result.exitCode(),
                    durationMs, masked.maskedStdout(), masked.maskedStderr(), masked.stdoutTruncated(),
                    masked.stderrTruncated(), result.failureReason()));
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "script_run_finish_not_recorded: job_id={0}", jobId);
        }
        boolean success = "SUCCESS".equals(result.outcome());
        if (attemptId != null) {
            attempts.writeOutcome(attemptId, epoch, success ? "MATCHED" : "EXPECTATION_UNMET",
                    success ? null : safeReason(result.failureReason()), success,
                    (long) masked.stdoutBytes() + masked.stderrBytes(), 0L, null);
        }
        leases.transitionState(jobId, epoch, JobState.EXECUTING, success ? JobState.COMPLETED : JobState.FAILED,
                RUNNER_ACTOR, success ? "script_run_completed" : "script_run_failed",
                success ? "COMPLETED" : safeReason(result.failureReason()));
    }

    private static String safeReason(String reason) {
        if (reason == null) {
            return "FAILED";
        }
        return reason.replaceAll("[^A-Za-z0-9_:.-]", "_");
    }
}
