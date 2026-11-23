package com.securityexpert.nexus.ui2.worker.scriptrunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;

/**
 * The script-run broker against fake Kubernetes and service doubles ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment
 * 2026-11-18 (b); review {@code script-s2-review-1118.md} findings 1, 4, 5, 9, 12). Secret-shaped values are built at
 * run time.
 */
class ScriptRunBrokerTest {

    private static final String IMAGE = "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "e".repeat(64);
    private static final String JOB_ID = "6f1c2d3e-0000-4000-8000-00000000abcd";

    @TempDir
    Path temp;

    private static String secretValue() {
        return "S3cr" + "etVal" + "ue!" + Long.toHexString(Double.doubleToLongBits(Math.PI));
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Fakes
    // ---------------------------------------------------------------------------------------------------------------

    static final class FakeKubernetes implements KubernetesApi {
        final Map<String, Map<String, Object>> objects = new LinkedHashMap<>();
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> created = new ArrayList<>();
        final List<Map<String, Object>> everCreated = new ArrayList<>();
        /** Job status returned once the Job exists: null = still running. */
        String terminal = "Complete";
        int pollsBeforeTerminal = 1;
        Integer exitCode = 0;
        byte[] log = new byte[0];
        boolean logLimitReached;
        private int polls;

        private static String key(Kind kind, String name) {
            return kind + "/" + name;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void create(Kind kind, Map<String, Object> object) {
            Map<String, Object> meta = (Map<String, Object>) object.get("metadata");
            String name = (String) meta.get("name");
            try {
                meta.putIfAbsent("uid", "uid-" + name);
            } catch (UnsupportedOperationException ignored) {
            }
            calls.add("create " + key(kind, name));
            created.add(key(kind, name));
            objects.put(key(kind, name), object);
            everCreated.add(object);
        }

        @Override
        public Optional<Map<String, Object>> get(Kind kind, String name) {
            calls.add("get " + key(kind, name));
            Map<String, Object> object = objects.get(key(kind, name));
            if (object == null) {
                return Optional.empty();
            }
            if (kind == Kind.JOB && terminal != null && ++polls >= pollsBeforeTerminal) {
                Map<String, Object> copy = new LinkedHashMap<>(object);
                Map<String, Object> condition = new LinkedHashMap<>();
                condition.put("type", "Complete".equals(terminal) ? "Complete" : "Failed");
                condition.put("status", "True");
                if (!"Complete".equals(terminal)) {
                    condition.put("reason", terminal);
                }
                copy.put("status", Map.of("conditions", List.of(condition)));
                return Optional.of(copy);
            }
            return Optional.of(object);
        }

        @Override
        public List<Map<String, Object>> list(Kind kind, String labelSelector) {
            calls.add("list " + kind + " " + labelSelector);
            if (kind == Kind.JOB) {
                return objects.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("JOB/"))
                        .map(Map.Entry::getValue)
                        .toList();
            }
            String jobName = labelSelector.substring("job-name=".length());
            if (!objects.containsKey(key(Kind.JOB, jobName))) {
                return List.of();
            }
            Map<String, Object> terminated = new LinkedHashMap<>();
            if (exitCode != null) {
                terminated.put("exitCode", exitCode);
            }
            return List.of(Map.of(
                    "metadata", Map.of("name", jobName + "-x7k2p"),
                    "status", Map.of("containerStatuses", List.of(Map.of(
                            "name", "script", "state", Map.of("terminated", terminated))))));
        }

        @Override
        public void delete(Kind kind, String name) {
            calls.add("delete " + key(kind, name));
            objects.remove(key(kind, name));
        }

        @Override
        public LogRead podLog(String podName, String container, long limitBytes) {
            calls.add("log " + podName + " " + container + " " + limitBytes);
            return new LogRead(log, logLimitReached);
        }

        long remaining() {
            return objects.size();
        }
    }

    static final class FakeService implements ScriptRunnerServiceClient {
        RunBundle bundle;
        Refused startRefusal;
        final List<Finish> finishes = new ArrayList<>();
        final List<String> aborts = new ArrayList<>();

        @Override
        public RunBundle start(String jobId, long leaseEpoch) throws IOException {
            if (startRefusal != null) {
                throw startRefusal;
            }
            return bundle;
        }

        @Override
        public void finish(String jobId, long leaseEpoch, Finish finish) {
            finishes.add(finish);
        }

        @Override
        public void abort(String jobId, long leaseEpoch) {
            aborts.add(jobId + "@" + leaseEpoch);
        }
    }

    static class Leases implements JobLeaseRepository {
        final Map<String, JobState> states = new LinkedHashMap<>();
        final List<JobState> transitions = new ArrayList<>();

        @Override
        public Optional<ClaimedJob> claimNext(String workerId, List<String> ids, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState from, JobState to, String actor,
                String actionId) {
            states.put(jobId, to);
            transitions.add(to);
            return true;
        }

        @Override
        public List<ClaimedJob> findExpiredWithNoAttempt() {
            return List.of();
        }

        @Override
        public List<ClaimedJob> findExpiredAllBoundaryNo() {
            return List.of();
        }

        @Override
        public List<ClaimedJob> findExpiredUnconfirmedYes() {
            return List.of();
        }
    }

    static final class Attempts implements JobStepAttemptRepository {
        final List<String> outcomes = new ArrayList<>();

        @Override
        public String insertPreContact(String jobId, long epoch, int stepIndex, String kind, String cls, int n) {
            return "attempt-" + jobId;
        }

        @Override
        public boolean markBoundaryCrossed(String attemptId, long epoch) {
            return true;
        }

        @Override
        public boolean writeOutcome(String attemptId, long epoch, String outcome, String errorClass, long bytes,
                long lines, String sha) {
            outcomes.add(outcome);
            return true;
        }

        @Override
        public Optional<StepAttempt> find(String attemptId) {
            return Optional.empty();
        }

        @Override
        public List<StepAttempt> findByJobAndStep(String jobId, int stepIndex) {
            return List.of();
        }
    }

    final FakeKubernetes k8s = new FakeKubernetes();
    final FakeService service = new FakeService();
    final Leases leases = new Leases();
    final Attempts attempts = new Attempts();
    final List<String> resolved = new ArrayList<>();
    final Map<String, List<InetAddress>> dns = new LinkedHashMap<>();
    Instant now = Instant.parse("2026-11-18T10:00:00Z");

    private ScriptRunJobExecutor executor() {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new ScriptRunJobExecutor(leases, attempts, service, new ScriptRunKubernetes(k8s),
                host -> {
                    resolved.add(host);
                    List<InetAddress> a = dns.get(host);
                    if (a == null) {
                        throw new java.net.UnknownHostException("synthetic");
                    }
                    return a;
                },
                IMAGE, null, clock, Duration.ofSeconds(2), d -> now = now.plus(d));
    }

    private static ScriptRunnerServiceClient.RunBundle bundle(String jobId, String kind, byte[] body, List<String> egress,
            List<ScriptRunnerServiceClient.Credential> creds, int timeout) {
        return new ScriptRunnerServiceClient.RunBundle(jobId, "script-1", 3, kind, timeout, egress, body, creds,
                "c00k" + "1e" + "a".repeat(60), "csrf" + "b".repeat(44), List.of());
    }

    static String escapedForMessage(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        return escaped.length() > 400 ? escaped.substring(0, 400) + "...(+" + (escaped.length() - 400) + " chars)" : escaped;
    }

    @Test
    void escapedForMessageShowsControlCharactersAndTruncates() {
        assertEquals("a\\nb\\rc\\td", escapedForMessage("a\nb\rc\td"));
        String longValue = "x".repeat(450);
        assertEquals("x".repeat(400) + "...(+50 chars)", escapedForMessage(longValue));
    }

    private static byte[] tagged(String... lines) {
        return String.join("\n", lines).concat("\n").getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object node, Object... path) {
        Object cur = node;
        for (Object p : path) {
            cur = p instanceof Integer i ? ((List<Object>) cur).get(i) : ((Map<String, Object>) cur).get(p);
        }
        return (T) cur;
    }

    private Map<String, Object> created(KubernetesApi.Kind kind) {
        for (Map<String, Object> o : k8s.everCreated) {
            String apiKind = (String) o.get("kind");
            if (kind == KubernetesApi.Kind.JOB && "Job".equals(apiKind)
                    || kind == KubernetesApi.Kind.NETWORK_POLICY && "NetworkPolicy".equals(apiKind)
                    || kind == KubernetesApi.Kind.SECRET && "Secret".equals(apiKind)) {
                return o;
            }
        }
        throw new AssertionError("not created: " + kind);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    void happyPathRunsInItsOwnJobMasksOutputDeletesEverythingAndCompletes() throws Exception {
        String secret = secretValue();
        String user = "svc_" + "reader";
        service.bundle = bundle(JOB_ID, "py", "print('x')\n".getBytes(StandardCharsets.UTF_8), List.of(),
                List.of(new ScriptRunnerServiceClient.Credential("cred-ref-1", "VAULT", user, secret)), 60);
        k8s.log = tagged("O|token=" + secret, "O|b64=" + Base64.getEncoder().encodeToString((secret + "\n").getBytes()),
                "E|user " + user + " ok", "O|done");

        executor().execute(new ClaimedJob(JOB_ID, 4L), () -> false);

        // Order: the policy exists before the pod can start; the Job is created before the seed Secret so the Secret
        // carries an ownerReference to the Job UID for Kubernetes garbage collection (P1-6).
        String name = ScriptRunPodSpec.jobName(JOB_ID, 4L);
        List<String> creates = k8s.created;
        assertEquals("NETWORK_POLICY/" + name, creates.get(0));
        assertEquals("JOB/" + name, creates.get(1));
        assertEquals("SECRET/" + name + "-s0", creates.get(2));
        Map<String, Object> createdSecret = created(KubernetesApi.Kind.SECRET);
        assertEquals(name, at(createdSecret, "metadata", "ownerReferences", 0, "name"));
        assertEquals("uid-" + name, at(createdSecret, "metadata", "ownerReferences", 0, "uid"));
        assertEquals(0, k8s.remaining(), "Job, Secrets and NetworkPolicy are deleted at run end");

        assertEquals(1, service.finishes.size());
        ScriptRunnerServiceClient.Finish finish = service.finishes.get(0);
        assertEquals("SUCCESS", finish.outcome());
        assertEquals(0, finish.exitCode());
        assertFalse(finish.stdout().contains(secret));
        assertFalse(finish.stdout().contains(Base64.getEncoder().encodeToString((secret + "\n").getBytes())));
        assertFalse(finish.stderr().contains(user), "the username is masked too");
        assertTrue(finish.stdout().contains("done"));
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), leases.transitions);
        assertEquals(List.of("MATCHED"), attempts.outcomes);
    }

    @Test
    void executionPodHasNoSecretEnvNoTokenNoStoreMountsAndItsOwnUid() throws Exception {
        service.bundle = bundle(JOB_ID, "sh", "echo hi\n".getBytes(StandardCharsets.UTF_8), List.of(),
                List.of(new ScriptRunnerServiceClient.Credential("r1", "A", "u", secretValue())), 120);
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        Map<String, Object> job = created(KubernetesApi.Kind.JOB);
        String json = new ObjectMapper().writeValueAsString(job);
        // Nothing reaches the pod's environment from a Secret: an env-read attempt (/proc/*/environ) finds no key.
        assertFalse(json.contains("secretKeyRef"));
        assertFalse(json.contains("envFrom"));
        assertFalse(json.contains("valueFrom"));
        assertFalse(json.contains(secretValue()), "credential values travel only in the seed Secret, never the Job");
        assertFalse(json.contains("hostPath"));
        assertFalse(json.contains("persistentVolumeClaim"));

        Map<String, Object> pod = at(job, "spec", "template", "spec");
        assertEquals(false, pod.get("automountServiceAccountToken"));
        assertEquals(false, pod.get("enableServiceLinks"));
        assertEquals(ScriptRunPodSpec.SERVICE_ACCOUNT, pod.get("serviceAccountName"));
        assertEquals(120, pod.get("activeDeadlineSeconds"));
        assertEquals((Object) 120, at(job, "spec", "activeDeadlineSeconds"));
        assertEquals((Object) 0, at(job, "spec", "backoffLimit"));
        assertEquals("Never", pod.get("restartPolicy"));
        assertEquals((Object) ScriptRunPodSpec.RUN_UID, at(pod, "securityContext", "runAsUser"));
        assertNotEquals(185L, ScriptRunPodSpec.RUN_UID, "never the broker's / service image uid");

        Map<String, Object> main = at(pod, "containers", 0);
        assertEquals(true, at(main, "securityContext", "readOnlyRootFilesystem"));
        assertEquals(false, at(main, "securityContext", "allowPrivilegeEscalation"));
        assertEquals(List.of("ALL"), at(main, "securityContext", "capabilities", "drop"));
        List<Map<String, Object>> env = at(main, "env");
        for (Map<String, Object> e : env) {
            assertTrue(ScriptRunPodSpec.ALLOWED_ENV.contains((String) e.get("name")), "env " + e.get("name"));
            assertTrue(e.containsKey("value") && !e.containsKey("valueFrom"));
        }
        List<Map<String, Object>> mainMounts = at(main, "volumeMounts");
        assertTrue(mainMounts.stream().noneMatch(m -> "seed".equals(m.get("name"))), "only the init step mounts the seed");
        List<Map<String, Object>> volumes = at(pod, "volumes");
        for (Map<String, Object> v : volumes) {
            assertTrue(v.containsKey("emptyDir") || v.containsKey("projected"), "volume " + v.get("name"));
        }
        Map<String, Object> init = at(pod, "initContainers", 0);
        String seed = at(init, "command", 2);
        assertTrue(seed.contains("chmod 0400 /scratch/$f"));
        assertTrue(seed.contains("chmod 0400 /scratch/work/script.sh"));
    }

    @Test
    void networkPolicyAllowsOnlyDnsServiceAndEachDeclaredHostPortAsResolvedNow() throws Exception {
        dns.put("api.example.test", List.of(InetAddress.getByName("192.0.2.10"), InetAddress.getByName("192.0.2.11")));
        dns.put("198.51.100.7", List.of(InetAddress.getByName("198.51.100.7")));
        service.bundle = bundle(JOB_ID, "sh", "true\n".getBytes(), List.of("api.example.test:443", "198.51.100.7:8443",
                "gone.example.test:22"), List.of(), 30);
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);

        Map<String, Object> np = created(KubernetesApi.Kind.NETWORK_POLICY);
        String name = ScriptRunPodSpec.jobName(JOB_ID);
        assertEquals(Map.of(ScriptRunPodSpec.RUN_LABEL, name), at(np, "spec", "podSelector", "matchLabels"));
        assertEquals(List.of("Egress"), at(np, "spec", "policyTypes"));
        List<Map<String, Object>> rules = at(np, "spec", "egress");
        assertEquals(4, rules.size(), "DNS, ui2-service:8080, two resolved declarations; the unresolved one allows nothing");
        assertEquals("kube-dns", at(rules.get(0), "to", 0, "podSelector", "matchLabels", "k8s-app"));
        assertEquals((Object) 8080, at(rules.get(1), "ports", 0, "port"));
        assertEquals("192.0.2.10/32", at(rules.get(2), "to", 0, "ipBlock", "cidr"));
        assertEquals("192.0.2.11/32", at(rules.get(2), "to", 1, "ipBlock", "cidr"));
        assertEquals((Object) 443, at(rules.get(2), "ports", 0, "port"));
        assertEquals("198.51.100.7/32", at(rules.get(3), "to", 0, "ipBlock", "cidr"));
        assertEquals((Object) 8443, at(rules.get(3), "ports", 0, "port"));
        String json = new ObjectMapper().writeValueAsString(np);
        assertFalse(json.contains("0.0.0.0/0"), "never an open egress");
        assertTrue(service.finishes.get(0).stderr().contains("gone.example.test:22 did not resolve"));

        // Hostnames are resolved again for the next run: a changed address changes the policy.
        int before = resolved.size();
        dns.put("api.example.test", List.of(InetAddress.getByName("192.0.2.99")));
        k8s.everCreated.clear();
        executor().execute(new ClaimedJob(JOB_ID, 2L), () -> false);
        assertTrue(resolved.size() > before);
        Map<String, Object> np2 = created(KubernetesApi.Kind.NETWORK_POLICY);
        assertEquals("192.0.2.99/32", at(np2, "spec", "egress", 2, "to", 0, "ipBlock", "cidr"));
    }

    @Test
    void deadlineExceededIsTimedOutAndEverythingIsDeletedSoForkedChildrenDieWithThePod() throws Exception {
        service.bundle = bundle(JOB_ID, "sh", "sleep 999 &\nsleep 999\n".getBytes(), List.of(), List.of(), 5);
        k8s.terminal = "DeadlineExceeded";
        k8s.exitCode = null;
        k8s.log = tagged("O|started");
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertEquals("TIMED_OUT", service.finishes.get(0).outcome());
        assertEquals(JobState.FAILED, leases.states.get(JOB_ID));
        assertEquals(0, k8s.remaining());
        // The pod's own deadline kills every process in it (a forked grandchild included): the Job carries it.
        assertEquals((Object) 5, at(created(KubernetesApi.Kind.JOB), "spec", "activeDeadlineSeconds"));
        assertEquals((Object) 5, at(created(KubernetesApi.Kind.JOB), "spec", "template", "spec", "activeDeadlineSeconds"));
    }

    @Test
    void brokerDeadlineStopsAPodThatNeverEnds() throws Exception {
        service.bundle = bundle(JOB_ID, "sh", "true\n".getBytes(), List.of(), List.of(), 10);
        k8s.terminal = null; // never reports an end
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertEquals("TIMED_OUT", service.finishes.get(0).outcome());
        assertEquals(0, k8s.remaining());
    }

    @Test
    void lostLeaseKillsTheRunRevokesTheSessionAndReportsNothing() throws Exception {
        service.bundle = bundle(JOB_ID, "sh", "sleep 100\n".getBytes(), List.of(), List.of(), 100);
        k8s.terminal = null;
        AtomicInteger polls = new AtomicInteger();
        BooleanSupplier lost = () -> polls.incrementAndGet() > 2;
        executor().execute(new ClaimedJob(JOB_ID, 7L), lost);
        assertEquals(0, k8s.remaining(), "the Job is deleted at once");
        assertEquals(List.of(JOB_ID + "@7"), service.aborts);
        assertTrue(service.finishes.isEmpty());
        assertEquals(List.of(JobState.EXECUTING), leases.transitions, "no outcome under a lease it no longer holds");
    }

    @Test
    void claimLoopTurnsAFailedLeaseRenewalIntoAKilledRun() {
        service.bundle = bundle(JOB_ID, "sh", "sleep 100\n".getBytes(), List.of(), List.of(), 100);
        k8s.terminal = null;
        AtomicBoolean claimed = new AtomicBoolean();
        JobLeaseRepository lossy = new Leases() {
            @Override
            public Optional<ClaimedJob> claimNext(String workerId, List<String> ids, Duration d) {
                assertEquals(List.of("script_run"), ids);
                return claimed.compareAndSet(false, true) ? Optional.of(new ClaimedJob(JOB_ID, 9L)) : Optional.empty();
            }

            @Override
            public boolean heartbeat(String jobId, long epoch, Duration d) {
                return false;
            }
        };
        ScriptRunJobExecutor realTime = new ScriptRunJobExecutor(lossy, attempts, service, new ScriptRunKubernetes(k8s),
                host -> List.of(), IMAGE, null, Clock.systemUTC(), Duration.ofMillis(20), null);
        ScriptRunnerClaimLoop loop = new ScriptRunnerClaimLoop("script_runner-t", lossy, realTime, Duration.ofSeconds(30),
                Duration.ofMillis(20), Duration.ofMillis(10), () -> false);
        assertTrue(loop.runSingleIteration());
        assertEquals(List.of(JOB_ID + "@9"), service.aborts);
        assertEquals(0, k8s.remaining());
    }

    @Test
    void consecutiveRunsShareNothingEachGetsFreshObjectsAndLeavesNone() throws Exception {
        String second = "7a1c2d3e-0000-4000-8000-00000000abce";
        service.bundle = bundle(JOB_ID, "sh", "nohup sleep 999 &\n".getBytes(), List.of(), List.of(), 30);
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertEquals(0, k8s.remaining());
        service.bundle = bundle(second, "sh", "ls /scratch\n".getBytes(), List.of(), List.of(), 30);
        executor().execute(new ClaimedJob(second, 1L), () -> false);
        assertEquals(0, k8s.remaining(), "a background process of run 1 died with its pod; run 2 had its own pod");
        long jobs = k8s.created.stream().filter(c -> c.startsWith("JOB/")).distinct().count();
        assertEquals(2, jobs);
        assertTrue(k8s.calls.contains("delete JOB/" + ScriptRunPodSpec.jobName(JOB_ID)));
        assertTrue(k8s.calls.contains("delete SECRET/" + ScriptRunPodSpec.jobName(JOB_ID) + "-s0"));
    }

    @Test
    void refusedStartCreatesNothingAndClosesTheRunAsFailure() throws Exception {
        service.startRefusal = new ScriptRunnerServiceClient.Refused(409, "SCRIPT_RUN_LEASE_MISMATCH");
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertTrue(k8s.created.isEmpty());
        assertEquals("FAILURE", service.finishes.get(0).outcome());
        assertEquals("START_REFUSED:SCRIPT_RUN_LEASE_MISMATCH", service.finishes.get(0).failureReason());
        assertEquals(JobState.FAILED, leases.states.get(JOB_ID));
    }

    @Test
    void nonZeroExitIsFailureWithItsExitCode() throws Exception {
        service.bundle = bundle(JOB_ID, "sh", "exit 3\n".getBytes(), List.of(), List.of(), 30);
        k8s.terminal = "BackoffLimitExceeded";
        k8s.exitCode = 3;
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertEquals("FAILURE", service.finishes.get(0).outcome());
        assertEquals("EXIT_CODE_3", service.finishes.get(0).failureReason());
        assertEquals(3, service.finishes.get(0).exitCode());
    }

    @Test
    void largeBodiesAreSplitOverSeveralSeedSecretsAndRejoinedByTheInitStep() throws Exception {
        byte[] body = new byte[ScriptRunPodSpec.BODY_CHUNK_BYTES * 2 + 17];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i % 251);
        }
        service.bundle = bundle(JOB_ID, "jar", body, List.of(), List.of(), 30);
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        long secrets = k8s.created.stream().filter(c -> c.startsWith("SECRET/")).count();
        assertEquals(3, secrets);
        List<Object> sources = at(created(KubernetesApi.Kind.JOB), "spec", "template", "spec", "volumes", 2, "projected",
                "sources");
        assertEquals(3, sources.size());
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (Map<String, Object> o : k8s.everCreated) {
            if ("Secret".equals(o.get("kind"))) {
                Map<String, String> data = at(o, "data");
                data.entrySet().stream().filter(e -> e.getKey().startsWith("body.")).sorted(Map.Entry.comparingByKey())
                        .forEach(e -> joined.writeBytes(Base64.getDecoder().decode(e.getValue())));
            }
        }
        assertTrue(java.util.Arrays.equals(body, joined.toByteArray()));
    }

    @Test
    void credentialsEnvIsShellQuotedAndCredentialsJsonEscaped() {
        String tricky = "a'b\"c\\d";
        String env = ScriptRunPodSpec.credentialsEnv(List.of(new ScriptRunnerServiceClient.Credential("r", "VAULT", "u", tricky)));
        assertEquals("NEXUS_CRED_VAULT_USERNAME='u'\nNEXUS_CRED_VAULT_SECRET='a'\\''b\"c\\d'\n", env);
        String json = ScriptRunPodSpec.credentialsJson(List.of(new ScriptRunnerServiceClient.Credential("r", "VAULT", "u", tricky)));
        assertTrue(json.contains("\"secret\":\"a'b\\\"c\\\\d\""));
    }

    @Test
    void namePrefixDisciplineRefusesEverythingOutsideScriptRun() {
        ScriptRunKubernetes guard = new ScriptRunKubernetes(k8s);
        Map<String, Object> labels = Map.of("app.kubernetes.io/component", "script-run");
        for (String bad : List.of("ui2-service", "ui2-db", "script-runner", "script-run-", "script-run-A", "x-script-run-1",
                "script-run-" + "a".repeat(60))) {
            Map<String, Object> obj = Map.of("metadata", Map.of("name", bad, "namespace", "ui2", "labels", labels));
            assertThrows(IllegalArgumentException.class, () -> guard.create(KubernetesApi.Kind.SECRET, obj), bad);
            assertThrows(IllegalArgumentException.class, () -> guard.delete(KubernetesApi.Kind.SECRET, bad), bad);
            assertThrows(IllegalArgumentException.class, () -> guard.get(KubernetesApi.Kind.JOB, bad), bad);
        }
        Map<String, Object> otherNs = Map.of("metadata", Map.of("name", "script-run-1", "namespace", "kube-system",
                "labels", labels));
        assertThrows(IllegalArgumentException.class, () -> guard.create(KubernetesApi.Kind.SECRET, otherNs));
        Map<String, Object> unlabelled = Map.of("metadata", Map.of("name", "script-run-1", "namespace", "ui2"));
        assertThrows(IllegalArgumentException.class, () -> guard.create(KubernetesApi.Kind.SECRET, unlabelled));
        Map<String, Object> pod = Map.of("metadata", Map.of("name", "script-run-1", "namespace", "ui2", "labels", labels));
        assertThrows(IllegalArgumentException.class, () -> guard.create(KubernetesApi.Kind.POD, pod));
        assertThrows(IllegalArgumentException.class, () -> guard.delete(KubernetesApi.Kind.POD, "script-run-1"));
        assertThrows(IllegalArgumentException.class, () -> guard.podLog("ui2-service-abc", "script", 10));
        assertTrue(k8s.calls.isEmpty(), "nothing reached the API");
    }

    @Test
    void logSplitDropsAPartialLastLineAtTheReadLimitAndKeepsTagsUnforgeable() {
        ScriptRunLog.Split split = ScriptRunLog.split(
                "O|one\nO|E|forged\nE|err\nshell noise\nO|partial-secr".getBytes(StandardCharsets.UTF_8), true);
        assertEquals("one\nE|forged\n", split.stdout());
        assertEquals("err\nshell noise\n", split.stderr());
        assertTrue(split.cut());
        assertNull(ScriptRunJobExecutor.terminalCondition(Map.of("status", Map.of())));
    }

    @Test
    void inClusterApiSendsBackgroundPropagationOnDeleteAndLimitBytesOnLogs() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        List<String> seen = new CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                    + exchange.getRequestHeaders().getFirst("Authorization") + " " + body);
            byte[] answer = exchange.getRequestURI().getPath().endsWith("/log") ? "O|x\n".getBytes() : "{}".getBytes();
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        try {
            Path token = temp.resolve("token");
            Files.writeString(token, "synthetic-sa-token\n");
            InClusterKubernetesApi api = new InClusterKubernetesApi(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "ui2", token, javax.net.ssl.SSLContext.getDefault());
            api.delete(KubernetesApi.Kind.JOB, "script-run-1");
            api.podLog("script-run-1-abcde", "script", 4096);
            api.create(KubernetesApi.Kind.NETWORK_POLICY, Map.of("kind", "NetworkPolicy"));
        } finally {
            server.stop(0);
        }
        assertTrue(seen.get(0).startsWith("DELETE /apis/batch/v1/namespaces/ui2/jobs/script-run-1 Bearer synthetic-sa-token"));
        assertTrue(seen.get(0).contains("\"propagationPolicy\":\"Background\""));
        assertTrue(seen.get(1).contains("/api/v1/namespaces/ui2/pods/script-run-1-abcde/log?container=script&limitBytes=4096"));
        assertTrue(seen.get(2).startsWith("POST /apis/networking.k8s.io/v1/namespaces/ui2/networkpolicies"));
    }

    @Test
    void networkPolicyBlocksClusterPodServiceCidrsNodeIpsRegistryDatabaseAndAllowsOnlyKubeDnsWhenEgressEmpty()
            throws Exception {
        // 1. With egress = [], NetworkPolicy omits kube-dns completely (only ui2-service:8080 exists, with ui2-service
        // resolved by the broker and injected into podSpec.hostAliases so zero DNS egress is possible).
        dns.put("ui2-service", List.of(InetAddress.getByName("10.43.0.55")));
        service.bundle = bundle(JOB_ID, "sh", "true\n".getBytes(), List.of(), List.of(), 30);
        executor().execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        Map<String, Object> emptyNp = created(KubernetesApi.Kind.NETWORK_POLICY);
        List<Map<String, Object>> emptyRules = at(emptyNp, "spec", "egress");
        assertEquals(1, emptyRules.size(), "with egress = [], only ui2-service:8080 exists (no kube-dns rule)");
        assertEquals("service", at(emptyRules.get(0), "to", 0, "podSelector", "matchLabels",
                "app.kubernetes.io/component"));
        assertEquals((Object) 8080, at(emptyRules.get(0), "ports", 0, "port"));
        String emptyNpJson = new ObjectMapper().writeValueAsString(emptyNp);
        assertFalse(emptyNpJson.contains("kube-dns"), "no DNS egress when egress = []");
        assertFalse(emptyNpJson.contains("ipBlock"));
        Map<String, Object> emptyJob = created(KubernetesApi.Kind.JOB);
        assertEquals("10.43.0.55", at(emptyJob, "spec", "template", "spec", "hostAliases", 0, "ip"));
        assertEquals(List.of("ui2-service"), at(emptyJob, "spec", "template", "spec", "hostAliases", 0, "hostnames"));

        // 2. Protected targets (pod/service CIDRs, registry, database, loopback, link-local, node IPs) are never allowed,
        // even when returned via DNS resolution; declared device egress (192.0.2.10:22) is still allowed.
        k8s.everCreated.clear();
        service.finishes.clear();
        dns.put("rebind-pod.example.test", List.of(InetAddress.getByName("10.42.1.25")));
        dns.put("rebind-svc.example.test", List.of(InetAddress.getByName("10.43.0.10"), InetAddress.getByName("10.96.0.10")));
        dns.put("rebind-meta.example.test", List.of(InetAddress.getByName("169.254.169.254"), InetAddress.getByName("127.0.0.1")));
        dns.put("192.0.2.10", List.of(InetAddress.getByName("192.0.2.10")));
        service.bundle = bundle(JOB_ID, "sh", "true\n".getBytes(), List.of(
                "registry.kube-system.svc.cluster.local:5000",
                "ui2-db:5432",
                "ui2-service-internal.ui2.svc.cluster.local:8086",
                "rebind-pod.example.test:443",
                "rebind-svc.example.test:5432",
                "rebind-meta.example.test:80",
                "192.0.2.10:22"), List.of(), 30);
        executor().execute(new ClaimedJob(JOB_ID, 2L), () -> false);

        Map<String, Object> np = created(KubernetesApi.Kind.NETWORK_POLICY);
        List<Map<String, Object>> rules = at(np, "spec", "egress");
        assertEquals(3, rules.size(), "only kube-dns, ui2-service:8080, and the valid device egress 192.0.2.10:22");
        assertEquals("192.0.2.10/32", at(rules.get(2), "to", 0, "ipBlock", "cidr"));
        assertEquals((Object) 22, at(rules.get(2), "ports", 0, "port"));
        String npJson = new ObjectMapper().writeValueAsString(np);
        for (String forbidden : List.of("10.42.", "10.43.", "10.96.", "169.254.", "127.0.0.1")) {
            assertFalse(npJson.contains(forbidden), "must never allow " + forbidden);
        }
        String stderr = service.finishes.get(0).stderr();
        assertTrue(stderr.contains("registry.kube-system.svc.cluster.local:5000 resolved to a protected"));
        assertTrue(stderr.contains("ui2-db:5432 resolved to a protected"));
        assertTrue(stderr.contains("rebind-pod.example.test:443 resolved to a protected"));

        // 3. Node IPs (Downward API UI2_NODE_IPS = status.hostIP) and DNS-named UI2_DB_HOST resolved via HostResolver
        // are blocked even when declared by raw IP or alias hostname (OPEN-P1-2).
        Map<String, String> env = Map.of(
                "UI2_DB_HOST", "ext-pg.db.example.test",
                "UI2_NODE_IPS", "198.51.100.50",
                "KUBERNETES_SERVICE_HOST", "198.51.100.1");
        Map<String, List<InetAddress>> dbAndNodeDns = Map.of(
                "ext-pg.db.example.test", List.of(InetAddress.getByName("198.51.100.200")),
                "db-alias.example.test", List.of(InetAddress.getByName("198.51.100.200")),
                "node-alias.example.test", List.of(InetAddress.getByName("198.51.100.50")),
                "198.51.100.200", List.of(InetAddress.getByName("198.51.100.200")),
                "198.51.100.50", List.of(InetAddress.getByName("198.51.100.50")),
                "192.0.2.10", List.of(InetAddress.getByName("192.0.2.10")));
        List<ScriptRunPodSpec.ResolvedEgress> resolvedEgress = ScriptRunPodSpec.resolveEgress(
                List.of("198.51.100.200:5432", "db-alias.example.test:5432", "198.51.100.50:10250",
                        "node-alias.example.test:6443", "192.0.2.10:443"),
                host -> {
                    List<InetAddress> addrs = dbAndNodeDns.get(host);
                    if (addrs == null) {
                        throw new java.net.UnknownHostException(host);
                    }
                    return addrs;
                },
                env,
                java.util.Set.of());
        assertEquals(5, resolvedEgress.size());
        for (int i = 0; i < 4; i++) {
            assertTrue(resolvedEgress.get(i).addresses().isEmpty(), "must block " + resolvedEgress.get(i).host());
            assertTrue(resolvedEgress.get(i).blockedProtectedAddress(), "must mark blocked " + resolvedEgress.get(i).host());
        }
        assertEquals(List.of("192.0.2.10/32"), resolvedEgress.get(4).addresses());
        assertFalse(resolvedEgress.get(4).blockedProtectedAddress());
    }

    @Test
    void orphanSweepDeletesStrandedJobsSecretsAndNetworkPoliciesAndCreateRetriesIdempotentlyOn409() throws Exception {
        // 1. Simulate a crashed broker that left an orphaned Job, two seed Secrets, and a NetworkPolicy in the cluster.
        String orphanName = ScriptRunPodSpec.jobName("99999999-0000-4000-8000-000000000099");
        ScriptRunnerServiceClient.RunBundle orphanBundle = bundle(
                "99999999-0000-4000-8000-000000000099", "sh", "echo x\n".getBytes(), List.of(), List.of(), 30);
        k8s.create(KubernetesApi.Kind.NETWORK_POLICY, ScriptRunPodSpec.networkPolicy(orphanName, List.of()));
        k8s.create(KubernetesApi.Kind.JOB, ScriptRunPodSpec.job(orphanName, orphanBundle, IMAGE, 2));
        k8s.create(KubernetesApi.Kind.SECRET, Map.of("metadata", Map.of(
                "name", ScriptRunPodSpec.secretName(orphanName, 0), "namespace", "ui2",
                "labels", Map.of("app.kubernetes.io/component", "script-run"))));
        k8s.create(KubernetesApi.Kind.SECRET, Map.of("metadata", Map.of(
                "name", ScriptRunPodSpec.secretName(orphanName, 1), "namespace", "ui2",
                "labels", Map.of("app.kubernetes.io/component", "script-run"))));
        assertEquals(4, k8s.remaining());

        assertEquals(1, executor().sweepOrphans());
        assertEquals(0, k8s.remaining(), "sweepOrphans removes the orphaned Job, both seed Secrets and the NetworkPolicy");

        // 2. Idempotent create on lease-requeue: when Kubernetes returns 409 Conflict because the previous epoch's Job
        // is still terminating, InClusterKubernetesApi deletes it and retries POST until 201 Created succeeds.
        AtomicInteger posts = new AtomicInteger();
        List<String> seen = new CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            seen.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            if ("POST".equals(exchange.getRequestMethod())) {
                int call = posts.incrementAndGet();
                if (call == 1) {
                    byte[] conflict = "{\"reason\":\"AlreadyExists\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(409, conflict.length);
                    exchange.getResponseBody().write(conflict);
                } else {
                    byte[] created = "{\"metadata\":{\"name\":\"script-run-1\",\"uid\":\"job-uid-123\"}}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(201, created.length);
                    exchange.getResponseBody().write(created);
                }
            } else {
                byte[] ok = "{}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                exchange.getResponseBody().write(ok);
            }
            exchange.close();
        });
        server.start();
        try {
            Path token = temp.resolve("token-409");
            Files.writeString(token, "synthetic-sa-token\n");
            InClusterKubernetesApi api = new InClusterKubernetesApi(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "ui2", token,
                    javax.net.ssl.SSLContext.getDefault());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("name", "script-run-1");
            Map<String, Object> job = new LinkedHashMap<>();
            job.put("kind", "Job");
            job.put("metadata", meta);
            api.create(KubernetesApi.Kind.JOB, job);
            assertEquals("job-uid-123", meta.get("uid"), "created Job UID is captured for Secret ownerReferences");
        } finally {
            server.stop(0);
        }
        assertEquals(List.of(
                "POST /apis/batch/v1/namespaces/ui2/jobs",
                "DELETE /apis/batch/v1/namespaces/ui2/jobs/script-run-1",
                "POST /apis/batch/v1/namespaces/ui2/jobs"), seen);
    }

    @Test
    void claimLoopKillsRunWhenDatabaseHeartbeatThrowsPastLeaseExpiry() {
        service.bundle = bundle(JOB_ID, "sh", "sleep 100\n".getBytes(), List.of(), List.of(), 100);
        k8s.terminal = null;
        AtomicBoolean claimed = new AtomicBoolean();
        JobLeaseRepository brokenDb = new Leases() {
            @Override
            public Optional<ClaimedJob> claimNext(String workerId, List<String> ids, Duration d) {
                return claimed.compareAndSet(false, true) ? Optional.of(new ClaimedJob(JOB_ID, 11L)) : Optional.empty();
            }

            @Override
            public boolean heartbeat(String jobId, long epoch, Duration d) {
                throw new RuntimeException("simulated JDBC outage");
            }
        };
        Clock advancingClock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        ScriptRunJobExecutor exec = new ScriptRunJobExecutor(brokenDb, attempts, service, new ScriptRunKubernetes(k8s),
                host -> List.of(), IMAGE, null, advancingClock, Duration.ofSeconds(10), d -> now = now.plus(d));
        ScriptRunnerClaimLoop loop = new ScriptRunnerClaimLoop("script_runner-t", brokenDb, exec,
                Duration.ofSeconds(25), Duration.ofMillis(10), Duration.ofMillis(10), () -> false, advancingClock);
        assertTrue(loop.runSingleIteration());
        assertEquals(List.of(JOB_ID + "@11"), service.aborts);
        assertTrue(service.finishes.isEmpty(), "run is aborted locally once leaseValidUntil passes during a DB outage");
        assertEquals(0, k8s.remaining());
    }

    @Test
    void launcherCapturesStdoutAndStderrClosesFd3InChildAndPreservesExitCode() throws Exception {
        // NEW-P0-1: 2> >(sed ... >&3) must be evaluated BEFORE 3>&- so the stderr process substitution inherits open fd 3,
        // while the child command itself sees fd 3 closed and parent closes fd 3 before waiting.
        String launcher = ScriptRunPodSpec.LAUNCHER;
        int stderrRedir = launcher.indexOf("2> >(");
        int closeFd3OnChild = launcher.indexOf("3>&-");
        assertTrue(stderrRedir > 0 && closeFd3OnChild > stderrRedir,
                "2> >(... >&3) must precede 3>&- on the child command line");
        assertTrue(launcher.contains("exec 3>&-\nwait"), "parent shell must close fd 3 before wait");

        Path launcherFile = temp.resolve("run.sh");
        Files.writeString(launcherFile, launcher, StandardCharsets.UTF_8);
        Path childScript = temp.resolve("child.sh");
        Files.writeString(childScript, """
                #!/bin/bash
                echo "stdout-line-1"
                echo "stderr-line-1" >&2
                echo "stdout-line-2"
                echo "stderr-line-2" >&2
                if (echo "O|forged-on-fd3" >&3); then
                    exit 99
                fi
                exit "${1:-0}"
                """, StandardCharsets.UTF_8);

        Process p0 = new ProcessBuilder("/bin/bash", launcherFile.toString(), "/bin/bash", childScript.toString(), "0")
                .redirectErrorStream(true)
                .start();
        byte[] raw0 = p0.getInputStream().readAllBytes();
        int rc0 = p0.waitFor();
        assertEquals(0, rc0, "script writing to stderr must not die with SIGPIPE (141) or leak fd 3 (99)");
        ScriptRunLog.Split split0 = ScriptRunLog.split(raw0, false);
        assertEquals("stdout-line-1\nstdout-line-2\n", split0.stdout(),
                () -> "stdout was: " + escapedForMessage(split0.stdout()));
        assertFalse(split0.stdout().contains("forged-on-fd3"), "child must not be able to write to fd 3");
        assertTrue(split0.stderr().contains("stderr-line-1\n"), "stderr line 1 captured: " + split0.stderr());
        assertTrue(split0.stderr().contains("stderr-line-2\n"), "stderr line 2 captured: " + split0.stderr());
        assertTrue(split0.stderr().contains("3: Bad file descriptor"), "fd 3 is closed in child: " + split0.stderr());

        Process p7 = new ProcessBuilder("/bin/bash", launcherFile.toString(), "/bin/bash", childScript.toString(), "7")
                .redirectErrorStream(true)
                .start();
        byte[] raw7 = p7.getInputStream().readAllBytes();
        int rc7 = p7.waitFor();
        assertEquals(7, rc7, "launcher must exit with the child script's own non-zero exit code");
        ScriptRunLog.Split split7 = ScriptRunLog.split(raw7, false);
        assertTrue(split7.stderr().contains("stderr-line-1\n"));
    }

    @Test
    void jobNameIncludesLeaseEpochRetryWindowCoversTerminationAndMissingJobUidFailsClosed() {
        // NEW-P2-3: distinct lease epochs get distinct Job names, 409 retry window exceeds 5 seconds, and resolveJobUid
        // never fabricates a synthetic UID when Kubernetes omits metadata.uid.
        String e1 = ScriptRunPodSpec.jobName(JOB_ID, 1L);
        String e2 = ScriptRunPodSpec.jobName(JOB_ID, 2L);
        assertNotEquals(e1, e2);
        assertTrue(e1.endsWith("-e1"), e1);
        assertTrue(e2.endsWith("-e2"), e2);

        long totalRetryMs = 0L;
        for (int attempt = 0; attempt < InClusterKubernetesApi.CONFLICT_MAX_ATTEMPTS - 1; attempt++) {
            totalRetryMs += InClusterKubernetesApi.retryDelayMillis(attempt);
        }
        assertEquals(50L, InClusterKubernetesApi.retryDelayMillis(0));
        assertTrue(totalRetryMs >= 5_000L, "409 retry window must cover Kubernetes Job termination (>= 5s): " + totalRetryMs);

        // Kubernetes double that never populates metadata.uid on Job create or get:
        KubernetesApi uidless = new KubernetesApi() {
            @Override
            public void create(Kind kind, Map<String, Object> object) {
                if (kind == Kind.SECRET) {
                    throw new AssertionError("must never create a seed Secret with a fabricated Job UID");
                }
            }

            @Override
            public Optional<Map<String, Object>> get(Kind kind, String name) {
                return Optional.of(Map.of("kind", "Job", "metadata", Map.of("name", name)));
            }

            @Override
            public List<Map<String, Object>> list(Kind kind, String labelSelector) {
                return List.of();
            }

            @Override
            public void delete(Kind kind, String name) {
            }

            @Override
            public LogRead podLog(String podName, String container, long limitBytes) {
                return new LogRead(new byte[0], false);
            }
        };
        service.bundle = bundle(JOB_ID, "sh", "echo hi\n".getBytes(StandardCharsets.UTF_8), List.of(), List.of(), 30);
        ScriptRunJobExecutor exec = new ScriptRunJobExecutor(leases, attempts, service, new ScriptRunKubernetes(uidless),
                host -> List.of(), IMAGE, null, Clock.systemUTC(), Duration.ofMillis(10), d -> { });
        exec.execute(new ClaimedJob(JOB_ID, 3L), () -> false);
        assertEquals(1, service.finishes.size());
        assertEquals("FAILURE", service.finishes.get(0).outcome());
        assertEquals("EXECUTION_ERROR:IOException", service.finishes.get(0).failureReason());
    }
}
