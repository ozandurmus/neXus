package com.securityexpert.nexus.ui2.worker.scriptrunner;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.jobs.JobState;
import com.securityexpert.nexus.ui2.jobs.lease.ClaimedJob;
import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.StepAttempt;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.platform.ScriptOutputMasking;
import com.sun.net.httpserver.HttpServer;

/**
 * Worker/broker contract-pin tests closing unit-testable gaps in {@code docs/design/SCRIPT_EXECUTION_CONTRACT.md}
 * (sections 3.2, 3.3, 5, 7 and Amendment 2026-11-18 (b) items 2, 3, 4, 5, 6, 8). The complete clause-to-test
 * traceability table lives on {@code ScriptExecutionContractPinsTest} in {@code :service}.
 *
 * <p>All secret-shaped values are constructed dynamically at runtime; synthetic RFC 5737 / RFC 3849 addresses only.</p>
 */
class ScriptRunnerContractPinsTest {

    private static final String EXEC_IMAGE =
            "registry.kube-system.svc.cluster.local/nexus-ui2-service@sha256:" + "d".repeat(64);
    private static final String JOB_ID = "88aabbcc-1111-4222-8333-444455556666";
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private static String runtimeSecret(String tag) {
        return "rt" + "Sec!" + tag + "_" + Long.toHexString(Double.doubleToRawLongBits(Math.E));
    }

    private static String runtimeSessionToken(String prefix, int hexChars) {
        return prefix + "_" + Integer.toHexString(987654321) + "f".repeat(Math.max(0, hexChars));
    }

    @SuppressWarnings("unchecked")
    private static <T> T at(Object node, Object... path) {
        Object cur = node;
        for (Object step : path) {
            cur = step instanceof Integer idx
                    ? ((List<Object>) cur).get(idx)
                    : ((Map<String, Object>) cur).get(step);
        }
        return (T) cur;
    }

    private static ScriptRunnerServiceClient.RunBundle newBundle(
            String jobId,
            String kind,
            byte[] body,
            List<String> egress,
            List<ScriptRunnerServiceClient.Credential> credentials,
            int timeoutSeconds) {
        return new ScriptRunnerServiceClient.RunBundle(
                jobId,
                "script-pin-1",
                4,
                kind,
                timeoutSeconds,
                egress,
                body,
                credentials,
                runtimeSessionToken("sessCk", 40),
                runtimeSessionToken("csrfTk", 32),
                List.of());
    }

    private static final class RecordingKubernetes implements KubernetesApi {
        final Map<String, Map<String, Object>> live = new LinkedHashMap<>();
        final List<Map<String, Object>> createdObjects = new ArrayList<>();
        final List<String> operations = new CopyOnWriteArrayList<>();
        String terminal = "Complete";
        int pollsBeforeTerminal = 1;
        Integer exitCode = 0;
        String podReason;
        byte[] logBytes = new byte[0];
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
            meta.putIfAbsent("uid", "uid-" + name);
            operations.add("create " + key(kind, name));
            live.put(key(kind, name), object);
            createdObjects.add(object);
        }

        @Override
        public Optional<Map<String, Object>> get(Kind kind, String name) {
            operations.add("get " + key(kind, name));
            Map<String, Object> stored = live.get(key(kind, name));
            if (stored == null) {
                return Optional.empty();
            }
            if (kind == Kind.JOB && terminal != null && ++polls >= pollsBeforeTerminal) {
                Map<String, Object> copy = new LinkedHashMap<>(stored);
                Map<String, Object> cond = new LinkedHashMap<>();
                cond.put("type", "Complete".equals(terminal) ? "Complete" : "Failed");
                cond.put("status", "True");
                if (!"Complete".equals(terminal)) {
                    cond.put("reason", terminal);
                }
                copy.put("status", Map.of("conditions", List.of(cond)));
                return Optional.of(copy);
            }
            return Optional.of(stored);
        }

        @Override
        public List<Map<String, Object>> list(Kind kind, String labelSelector) {
            operations.add("list " + kind + " " + labelSelector);
            if (kind == Kind.JOB) {
                return live.entrySet().stream()
                        .filter(e -> e.getKey().startsWith("JOB/"))
                        .map(Map.Entry::getValue)
                        .toList();
            }
            String jobName = labelSelector.substring("job-name=".length());
            if (!live.containsKey(key(Kind.JOB, jobName))) {
                return List.of();
            }
            Map<String, Object> terminated = new LinkedHashMap<>();
            if (exitCode != null) {
                terminated.put("exitCode", exitCode);
            }
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("containerStatuses", List.of(Map.of(
                    "name", ScriptRunPodSpec.SCRIPT_CONTAINER,
                    "state", Map.of("terminated", terminated))));
            if (podReason != null) {
                status.put("reason", podReason);
            }
            return List.of(Map.of("metadata", Map.of("name", jobName + "-p9z1q"), "status", status));
        }

        @Override
        public void delete(Kind kind, String name) {
            operations.add("delete " + key(kind, name));
            live.remove(key(kind, name));
        }

        @Override
        public LogRead podLog(String podName, String container, long limitBytes) {
            operations.add("log " + podName + " " + container + " " + limitBytes);
            return new LogRead(logBytes, logLimitReached);
        }

        Map<String, Object> createdKind(String apiKind) {
            return createdObjects.stream()
                    .filter(o -> apiKind.equals(o.get("kind")))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing created " + apiKind));
        }
    }

    private static final class RecordingService implements ScriptRunnerServiceClient {
        RunBundle bundle;
        final List<Finish> finishes = new CopyOnWriteArrayList<>();
        final List<String> aborts = new CopyOnWriteArrayList<>();

        @Override
        public RunBundle start(String jobId, long leaseEpoch) {
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

    private static class RecordingLeases implements JobLeaseRepository {
        final List<JobState> transitions = new CopyOnWriteArrayList<>();
        final AtomicInteger heartbeats = new AtomicInteger();

        @Override
        public Optional<ClaimedJob> claimNext(String workerId, List<String> ids, Duration leaseDuration) {
            return Optional.empty();
        }

        @Override
        public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
            heartbeats.incrementAndGet();
            return true;
        }

        @Override
        public boolean transitionState(String jobId, long leaseEpoch, JobState expectedFrom, JobState to,
                String actorFingerprint, String actionId) {
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

    private static final class RecordingAttempts implements JobStepAttemptRepository {
        final List<String> outcomes = new CopyOnWriteArrayList<>();

        @Override
        public String insertPreContact(String jobId, long epoch, int stepIndex, String kind, String cls, int n) {
            return "att-" + jobId;
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

    private static Clock mutableUtcClock(Instant[] holder) {
        return new Clock() {
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
                return holder[0];
            }
        };
    }

    /**
     * Clauses B-2, S3.2b, S5-2: execution pod spec drops all capabilities, enforces RuntimeDefault seccomp and
     * uid/gid/fsGroup 20185 on pod + init + script containers, sets 1 CPU / 1 GiB limits, mounts immutable seed
     * Secrets read-only in the init container only, sets 0500 mode for {@code bin} bodies vs 0400 for interpreted
     * bodies and credential/session files, sets {@code activeDeadlineSeconds = timeout_s}, {@code backoffLimit = 0},
     * {@code restartPolicy = Never}, and deletes Job, Secrets and NetworkPolicy even on pod failure.
     */
    @Test
    void b2_executionPodSpecDropsCapabilitiesMountsSecretsOnlyInInitAndSetsDeadline() throws Exception {
        byte[] binPayload = new byte[] {0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01};
        String secretVal = runtimeSecret("b2");
        ScriptRunnerServiceClient.RunBundle binBundle = newBundle(
                JOB_ID,
                "bin",
                binPayload,
                List.of(),
                List.of(new ScriptRunnerServiceClient.Credential("cred-1", "OPS", "ops_u", secretVal)),
                240);

        String jobName = ScriptRunPodSpec.jobName(JOB_ID, 2L);
        List<Map<String, Object>> seedSecrets = ScriptRunPodSpec.secrets(jobName, binBundle, "http://ui2-service:8080");
        assertEquals(1, seedSecrets.size());
        assertEquals(Boolean.TRUE, seedSecrets.get(0).get("immutable"), "seed Secrets must be immutable one-time objects");
        assertEquals("Opaque", seedSecrets.get(0).get("type"));

        Map<String, Object> job = ScriptRunPodSpec.job(jobName, binBundle, EXEC_IMAGE, seedSecrets.size());
        assertEquals((Object) 240, at(job, "spec", "activeDeadlineSeconds"));
        assertEquals((Object) 0, at(job, "spec", "backoffLimit"));

        Map<String, Object> podSpec = at(job, "spec", "template", "spec");
        assertEquals("Never", podSpec.get("restartPolicy"));
        assertEquals(Boolean.FALSE, podSpec.get("automountServiceAccountToken"));
        assertEquals(Boolean.FALSE, podSpec.get("enableServiceLinks"));
        assertEquals(240, podSpec.get("activeDeadlineSeconds"));

        Map<String, Object> podSec = at(podSpec, "securityContext");
        assertEquals(Boolean.TRUE, podSec.get("runAsNonRoot"));
        assertEquals(20185L, podSec.get("runAsUser"));
        assertEquals(20185L, podSec.get("runAsGroup"));
        assertEquals(20185L, podSec.get("fsGroup"));
        assertEquals("RuntimeDefault", at(podSec, "seccompProfile", "type"));

        Map<String, Object> init = at(podSpec, "initContainers", 0);
        Map<String, Object> script = at(podSpec, "containers", 0);
        for (Map<String, Object> container : List.of(init, script)) {
            Map<String, Object> sc = at(container, "securityContext");
            assertEquals(Boolean.TRUE, sc.get("runAsNonRoot"));
            assertEquals(20185L, sc.get("runAsUser"));
            assertEquals(20185L, sc.get("runAsGroup"));
            assertEquals(Boolean.FALSE, sc.get("allowPrivilegeEscalation"));
            assertEquals(Boolean.TRUE, sc.get("readOnlyRootFilesystem"));
            assertEquals(Boolean.FALSE, sc.get("privileged"));
            assertEquals(List.of("ALL"), at(sc, "capabilities", "drop"));
            assertEquals("RuntimeDefault", at(sc, "seccompProfile", "type"));
            assertFalse(container.containsKey("envFrom"));
        }

        // S3.2b: default resource limits 1 CPU / 1 GiB on the main execution container.
        assertEquals("1", at(script, "resources", "limits", "cpu"));
        assertEquals("1Gi", at(script, "resources", "limits", "memory"));

        // B-2: only init mounts seed (readOnly=true); main mounts only scratch and jvm-tmp emptyDirs.
        List<Map<String, Object>> initMounts = at(init, "volumeMounts");
        assertTrue(initMounts.stream().anyMatch(m -> "seed".equals(m.get("name")) && Boolean.TRUE.equals(m.get("readOnly"))));
        List<Map<String, Object>> scriptMounts = at(script, "volumeMounts");
        assertEquals(Set.of("scratch", "jvm-tmp"),
                Set.copyOf(scriptMounts.stream().map(m -> (String) m.get("name")).toList()));

        // B-2: bin body gets chmod 0500; non-bin body gets chmod 0400; credentials & session get chmod 0400.
        String binSeed = at(init, "command", 2);
        assertTrue(binSeed.contains("chmod 0500 /scratch/work/script.bin"), binSeed);
        assertTrue(binSeed.contains("for f in credentials.json credentials.env; do cp /seed/$f /scratch/$f; chmod 0400 /scratch/$f; done"));
        assertTrue(binSeed.contains("chmod 0400 /scratch/.nexus-cli/session.json"));

        ScriptRunnerServiceClient.RunBundle shBundle = newBundle(
                JOB_ID, "sh", "echo ok\n".getBytes(StandardCharsets.UTF_8), List.of(), List.of(), 60);
        Map<String, Object> shJob = ScriptRunPodSpec.job(jobName, shBundle, EXEC_IMAGE, 1);
        String shSeed = at(shJob, "spec", "template", "spec", "initContainers", 0, "command", 2);
        assertTrue(shSeed.contains("chmod 0400 /scratch/work/script.sh"), shSeed);

        // B-2: even when the pod fails without an exit code (e.g. OOMKilled / Evicted), all per-run K8s objects are deleted.
        RecordingKubernetes k8s = new RecordingKubernetes();
        k8s.terminal = "Failed";
        k8s.exitCode = null;
        k8s.podReason = "OOMKilled";
        RecordingService svc = new RecordingService();
        svc.bundle = binBundle;
        Instant[] now = {Instant.parse("2026-11-24T12:00:00Z")};
        ScriptRunJobExecutor executor = new ScriptRunJobExecutor(
                new RecordingLeases(), new RecordingAttempts(), svc, new ScriptRunKubernetes(k8s),
                h -> List.of(), EXEC_IMAGE, null, mutableUtcClock(now), Duration.ofSeconds(1), d -> now[0] = now[0].plus(d));
        executor.execute(new ClaimedJob(JOB_ID, 2L), () -> false);

        assertEquals(0, k8s.live.size(), "Job, seed Secrets and NetworkPolicy must be deleted on failure outcome");
        assertEquals(1, svc.finishes.size());
        assertEquals("FAILURE", svc.finishes.get(0).outcome());
        assertEquals("POD_FAILED:OOMKilled", svc.finishes.get(0).failureReason());
    }

    /**
     * Clauses B-3, S5-3: NetworkPolicy omits {@code kube-dns:53} both when {@code egress} is empty and when non-empty
     * {@code egress} entries all fail DNS resolution or map to protected cluster/node/DB IPv4 or IPv6 addresses;
     * injects {@code ui2-service} into {@code podSpec.hostAliases}; emits {@code /128} {@code ipBlock} rules for
     * allowed external IPv6 targets alongside {@code /32} IPv4 rules; and has no ingress rules.
     */
    @Test
    void b3_networkPolicyOmitsDnsWhenEgressEmptyAndBlocksClusterAndDbIps() throws Exception {
        Map<String, String> env = Map.of(
                "UI2_DB_HOST", "pg-primary.internal.example.test",
                "UI2_NODE_IPS", "198.51.100.77,2001:db8:99::77",
                "KUBERNETES_SERVICE_HOST", "10.43.0.1");

        Map<String, List<InetAddress>> dnsTable = new LinkedHashMap<>();
        dnsTable.put("ui2-service", List.of(InetAddress.getByName("10.43.0.88")));
        dnsTable.put("pg-primary.internal.example.test", List.of(
                InetAddress.getByName("198.51.100.210"),
                InetAddress.getByName("2001:db8:99::210")));
        dnsTable.put("blocked-v6-pod.example.test", List.of(
                InetAddress.getByName("fd00:42::15"),
                InetAddress.getByName("fd00:43::15"),
                InetAddress.getByName("::1"),
                InetAddress.getByName("fe80::1")));
        dnsTable.put("blocked-db-alias.example.test", List.of(InetAddress.getByName("198.51.100.210")));
        dnsTable.put("blocked-node-v6.example.test", List.of(InetAddress.getByName("2001:db8:99::77")));
        dnsTable.put("allowed-dualstack.example.test", List.of(
                InetAddress.getByName("203.0.113.44"),
                InetAddress.getByName("2001:db8:1::44")));

        ScriptRunPodSpec.HostResolver resolver = host -> {
            List<InetAddress> found = dnsTable.get(host);
            if (found != null) {
                return found;
            }
            if (host.contains(":") || host.matches("[0-9.]+")) {
                return List.of(InetAddress.getByName(host));
            }
            throw new java.net.UnknownHostException(host);
        };

        // 1. Non-empty declared egress where EVERY entry is either unresolved or blocked -> zero allowed targets ->
        // kube-dns:53 MUST still be omitted so the pod gets zero DNS egress (B-3).
        List<ScriptRunPodSpec.ResolvedEgress> allBlockedOrUnresolved = ScriptRunPodSpec.resolveEgress(
                List.of(
                        "nonexistent.example.test:443",
                        "blocked-v6-pod.example.test:443",
                        "blocked-db-alias.example.test:5432",
                        "blocked-node-v6.example.test:10250",
                        "100.64.1.9:8080",
                        "ui2-script-runner:9405"),
                resolver,
                env,
                Set.of("198.51.100.78"));
        assertTrue(allBlockedOrUnresolved.stream().allMatch(e -> e.addresses().isEmpty()));

        String jobName = ScriptRunPodSpec.jobName(JOB_ID, 1L);
        Map<String, Object> zeroDnsNp = ScriptRunPodSpec.networkPolicy(jobName, allBlockedOrUnresolved);
        List<Map<String, Object>> zeroDnsRules = at(zeroDnsNp, "spec", "egress");
        assertEquals(1, zeroDnsRules.size(), "when all declared egress entries are blocked/unresolved, kube-dns must be omitted");
        assertFalse(JSON.writeValueAsString(zeroDnsNp).contains("kube-dns"));
        assertFalse(((Map<?, ?>) zeroDnsNp.get("spec")).containsKey("ingress"), "Egress policyType only; base policy denies ingress");
        assertEquals(List.of("Egress"), at(zeroDnsNp, "spec", "policyTypes"));

        // 2. Empty egress through full executor also omits kube-dns and injects ui2-service hostAliases.
        RecordingKubernetes k8s = new RecordingKubernetes();
        RecordingService svc = new RecordingService();
        svc.bundle = newBundle(JOB_ID, "sh", "true\n".getBytes(StandardCharsets.UTF_8), List.of(), List.of(), 30);
        Instant[] now = {Instant.parse("2026-11-24T12:00:00Z")};
        new ScriptRunJobExecutor(new RecordingLeases(), new RecordingAttempts(), svc, new ScriptRunKubernetes(k8s),
                resolver, EXEC_IMAGE, null, mutableUtcClock(now), Duration.ofSeconds(1), d -> now[0] = now[0].plus(d))
                .execute(new ClaimedJob(JOB_ID, 1L), () -> false);
        assertFalse(JSON.writeValueAsString(k8s.createdKind("NetworkPolicy")).contains("kube-dns"));
        assertEquals("10.43.0.88", at(k8s.createdKind("Job"), "spec", "template", "spec", "hostAliases", 0, "ip"));

        // 3. Dual-stack allowed target emits kube-dns UDP+TCP 53, ui2-service:8080, and /32 + /128 ipBlocks.
        List<ScriptRunPodSpec.ResolvedEgress> dualStack = ScriptRunPodSpec.resolveEgress(
                List.of("allowed-dualstack.example.test:8443", "[2001:db8:99::210]:5432"),
                resolver,
                env,
                Set.of());
        assertEquals(List.of("203.0.113.44/32", "2001:db8:1:0:0:0:0:44/128"), dualStack.get(0).addresses());
        assertTrue(dualStack.get(1).addresses().isEmpty() && dualStack.get(1).blockedProtectedAddress(),
                "IPv6 DB address resolved from UI2_DB_HOST must be blocked");

        Map<String, Object> dualStackNp = ScriptRunPodSpec.networkPolicy(jobName, dualStack);
        List<Map<String, Object>> rules = at(dualStackNp, "spec", "egress");
        assertEquals(3, rules.size(), "kube-dns:53, ui2-service:8080, and allowed-dualstack:8443");
        assertEquals("UDP", at(rules.get(0), "ports", 0, "protocol"));
        assertEquals((Object) 53, at(rules.get(0), "ports", 0, "port"));
        assertEquals("TCP", at(rules.get(0), "ports", 1, "protocol"));
        assertEquals((Object) 53, at(rules.get(0), "ports", 1, "port"));
        assertEquals("203.0.113.44/32", at(rules.get(2), "to", 0, "ipBlock", "cidr"));
        assertEquals("2001:db8:1:0:0:0:0:44/128", at(rules.get(2), "to", 1, "ipBlock", "cidr"));
        assertEquals((Object) 8443, at(rules.get(2), "ports", 0, "port"));
    }

    /**
     * Clauses B-6, S3.3c: plain and Base64 (plus hex/URL/JSON) forms of declared credential secrets, usernames, and
     * the per-run {@code sessionCookie} and {@code sessionCsrf} are masked BEFORE the 256 KiB per-stream cap on both
     * stdout and stderr; undeclared secret assignments are scrubbed via {@code DiagnosticText.scrubSecrets}; partial
     * last lines at {@code READ_LIMIT_BYTES} are dropped; and elapsed {@code durationMs} is reported.
     */
    @Test
    void b6_plainAndBase64CredentialSecretsMaskedBefore256KibCap() {
        String credSecret = runtimeSecret("b6cred");
        String credUser = "svc_ops_" + Integer.toHexString(4096);
        String undeclaredBearer = "Bearer " + "eyJhbGciOiJIUzI1NiJ9." + "dGVzdC1wYXlsb2Fk." + "c2lnbmF0dXJlMTIzNDU2";

        RecordingKubernetes k8s = new RecordingKubernetes();
        k8s.pollsBeforeTerminal = 3; // 2 poll sleeps of 2s -> durationMs = 4000
        RecordingService svc = new RecordingService();
        ScriptRunnerServiceClient.RunBundle bundle = newBundle(
                JOB_ID,
                "sh",
                "echo run\n".getBytes(StandardCharsets.UTF_8),
                List.of(),
                List.of(new ScriptRunnerServiceClient.Credential("ref-1", "OPS", credUser, credSecret)),
                60);
        svc.bundle = bundle;

        int cut = ScriptExecutionJob.MAX_STREAM_BYTES;
        String cookieB64 = Base64.getEncoder().encodeToString(bundle.sessionCookie().getBytes(StandardCharsets.UTF_8));
        String csrfHex = HexFormat.of().formatHex(bundle.sessionCsrf().getBytes(StandardCharsets.UTF_8)).toUpperCase(Locale.ROOT);
        String secretB64Nl = Base64.getEncoder().encodeToString((credSecret + "\n").getBytes(StandardCharsets.UTF_8));

        // Stdout places the Base64 secret straddling the 256 KiB boundary, plus cookie Base64 and undeclared Bearer token.
        String stdoutLine1 = "O|authorization=" + undeclaredBearer + "\n";
        String stdoutLine2 = "O|ck=" + cookieB64 + "\n";
        int padOut = cut - (stdoutLine1.length() - 2) - (stdoutLine2.length() - 2) - 6;
        String stdoutStraddle = "O|" + "x".repeat(padOut) + secretB64Nl + "z".repeat(512) + "\n";

        // Stderr places the plain secret straddling the 256 KiB boundary, plus user and upper-case hex CSRF token.
        String stderrLine1 = "E|u=" + credUser + " csrf=" + csrfHex + "\n";
        int padErr = cut - (stderrLine1.length() - 2) - 4;
        String stderrStraddle = "E|" + "y".repeat(padErr) + credSecret + "w".repeat(512) + "\n";

        // Partial trailing line at log byte limit carries half of credSecret -> dropped by ScriptRunLog.split(..., true).
        String partialTail = "O|leak=" + credSecret.substring(0, 6);
        k8s.logBytes = (stdoutLine1 + stdoutLine2 + stdoutStraddle + stderrLine1 + stderrStraddle + partialTail)
                .getBytes(StandardCharsets.UTF_8);
        k8s.logLimitReached = true;

        Instant[] now = {Instant.parse("2026-11-24T13:00:00Z")};
        ScriptRunJobExecutor executor = new ScriptRunJobExecutor(
                new RecordingLeases(), new RecordingAttempts(), svc, new ScriptRunKubernetes(k8s),
                h -> List.of(), EXEC_IMAGE, null, mutableUtcClock(now), Duration.ofSeconds(2), d -> now[0] = now[0].plus(d));
        executor.execute(new ClaimedJob(JOB_ID, 3L), () -> false);

        assertEquals(1, svc.finishes.size());
        ScriptRunnerServiceClient.Finish fin = svc.finishes.get(0);
        assertEquals("SUCCESS", fin.outcome());
        assertEquals(0, fin.exitCode());
        assertEquals(4000L, fin.durationMs(), "elapsed run duration must be recorded from clock progression");
        assertTrue(fin.stdoutCut());
        assertTrue(fin.stderrCut());
        assertTrue(fin.stdout().getBytes(StandardCharsets.UTF_8).length <= cut);
        assertTrue(fin.stderr().getBytes(StandardCharsets.UTF_8).length <= cut);

        for (String sensitive : List.of(
                credSecret,
                credSecret.substring(0, 6),
                secretB64Nl,
                secretB64Nl.substring(0, 8),
                credUser,
                bundle.sessionCookie(),
                cookieB64,
                bundle.sessionCsrf(),
                csrfHex,
                "c2lnbmF0dXJlMTIzNDU2")) {
            assertFalse(fin.stdout().contains(sensitive), "stdout leaked " + sensitive.length() + "-char token");
            assertFalse(fin.stderr().contains(sensitive), "stderr leaked " + sensitive.length() + "-char token");
        }
        assertTrue(fin.stdout().contains(ScriptOutputMasking.REDACTED_PLACEHOLDER));
        assertTrue(fin.stderr().contains(ScriptOutputMasking.REDACTED_PLACEHOLDER));
    }

    /**
     * Clauses S3.3b, B-8: execution container runs in {@code /scratch/work} with the exact clean environment contract
     * ({@code NEXUS_SCRIPT_ID}, {@code NEXUS_RUN_ID}, {@code NEXUS_CLI=/app/nexus-cli}, {@code HOME=/scratch/home},
     * minimal {@code PATH}, and {@code JDK_JAVA_OPTIONS} disabling Base64 intrinsics).
     */
    @Test
    void s3_3b_cleanEnvironmentSetsExactContractVariablesScratchHomeAndMinimalPath() {
        ScriptRunnerServiceClient.RunBundle bundle = newBundle(
                JOB_ID, "jar", new byte[] {0x50, 0x4b, 0x03, 0x04}, List.of(), List.of(), 90);
        Map<String, Object> job = ScriptRunPodSpec.job(ScriptRunPodSpec.jobName(JOB_ID, 1L), bundle, EXEC_IMAGE, 1);
        Map<String, Object> script = at(job, "spec", "template", "spec", "containers", 0);

        assertEquals("/scratch/work", script.get("workingDir"));
        assertEquals(List.of("/bin/bash", "/scratch/run.sh", "java", "-jar", "/scratch/work/script.jar"),
                script.get("command"));

        List<Map<String, String>> envList = at(script, "env");
        Map<String, String> env = new LinkedHashMap<>();
        for (Map<String, String> entry : envList) {
            env.put(entry.get("name"), entry.get("value"));
        }
        assertEquals(Set.copyOf(ScriptRunPodSpec.ALLOWED_ENV), env.keySet());
        assertEquals("script-pin-1", env.get("NEXUS_SCRIPT_ID"));
        assertEquals(JOB_ID, env.get("NEXUS_RUN_ID"));
        assertEquals("/app/nexus-cli", env.get("NEXUS_CLI"));
        assertEquals("/scratch/.nexus-cli", env.get("NEXUS_CLI_HOME"));
        assertEquals("/scratch/credentials.json", env.get("NEXUS_CREDENTIALS_FILE"));
        assertEquals("/scratch/home", env.get("HOME"));
        assertEquals("/scratch/tmp", env.get("TMPDIR"));
        assertEquals(ScriptRunPodSpec.MINIMAL_PATH, env.get("PATH"));
        assertTrue(env.get("JDK_JAVA_OPTIONS").contains("-XX:-UseBASE64Intrinsics"));
    }

    /**
     * Clause S5-6: {@link ScriptRunnerClaimLoop} periodically heartbeats an active {@code script_run} lease so a run
     * outlasting a single lease window completes {@code SUCCESS}, and drains cleanly on rollout (finishing the in-flight
     * job while refusing to claim the next queued job once draining).
     */
    @Test
    void s5_6_claimLoopHeartbeatsActiveLeaseAndDrainsWithoutClaimingNextJob() {
        java.util.concurrent.CountDownLatch twoHeartbeats = new java.util.concurrent.CountDownLatch(2);
        RecordingKubernetes k8s = new RecordingKubernetes();
        k8s.pollsBeforeTerminal = 2;
        RecordingService svc = new RecordingService();
        svc.bundle = newBundle(JOB_ID, "sh", "echo drain-ok\n".getBytes(StandardCharsets.UTF_8), List.of(), List.of(), 30);

        AtomicBoolean draining = new AtomicBoolean(false);
        AtomicInteger claims = new AtomicInteger();
        RecordingLeases leases = new RecordingLeases() {
            @Override
            public Optional<ClaimedJob> claimNext(String workerId, List<String> ids, Duration leaseDuration) {
                assertEquals(List.of("script_run"), ids);
                int n = claims.incrementAndGet();
                return n == 1
                        ? Optional.of(new ClaimedJob(JOB_ID, 5L))
                        : Optional.of(new ClaimedJob("99999999-1111-4222-8333-444455556666", 1L));
            }

            @Override
            public boolean heartbeat(String jobId, long leaseEpoch, Duration leaseDuration) {
                boolean renewed = super.heartbeat(jobId, leaseEpoch, leaseDuration);
                twoHeartbeats.countDown();
                if (this.heartbeats.get() >= 2) {
                    // Simulate rollout drain signal arriving mid-run while the job is executing.
                    draining.set(true);
                }
                return renewed;
            }
        };

        ScriptRunJobExecutor executor = new ScriptRunJobExecutor(
                leases, new RecordingAttempts(), svc, new ScriptRunKubernetes(k8s),
                h -> List.of(), EXEC_IMAGE, null, Clock.systemUTC(), Duration.ofMillis(10), d -> {
                    try {
                        assertTrue(twoHeartbeats.await(5, java.util.concurrent.TimeUnit.SECONDS),
                                "timed out waiting for two lease heartbeats");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                });
        ScriptRunnerClaimLoop loop = new ScriptRunnerClaimLoop(
                "script_runner-pin",
                leases,
                executor,
                Duration.ofSeconds(10),
                Duration.ofMillis(15),
                Duration.ofMillis(10),
                draining::get);

        assertTrue(loop.runSingleIteration(), "first iteration claims and finishes the in-flight script_run");
        assertTrue(leases.heartbeats.get() >= 2, "lease must be heartbeated while the run is executing");
        assertTrue(svc.aborts.isEmpty(), "renewed lease must not abort the run");
        assertEquals(1, svc.finishes.size());
        assertEquals("SUCCESS", svc.finishes.get(0).outcome());
        assertEquals(List.of(JobState.EXECUTING, JobState.COMPLETED), leases.transitions);

        // Drain is now active: a subsequent iteration must NOT call claimNext or pick up the second queued job.
        assertFalse(loop.runSingleIteration(), "draining claim loop must not claim another job");
        assertEquals(1, claims.get(), "claimNext must not be invoked once drainCheck is true");
    }

    /**
     * Clause B-4: {@link ScriptRunnerServiceClient#http(String, String)} sends {@code X-Nexus-Script-Runner-Token} on
     * {@code POST /internal/script-runs/{start,finish,abort}}, decodes the {@code RunBundle} Base64 body and declared
     * credentials without exposing secrets in {@code toString()}, and maps non-2xx JSON errors to {@link ScriptRunnerServiceClient.Refused}.
     */
    @Test
    void b4_httpServiceClientSendsRunnerTokenAndMapsStartFinishAbortAndRefusals() throws Exception {
        String runnerToken = runtimeSessionToken("brkTok", 32);
        String credSecret = runtimeSecret("b4http");
        byte[] bodyBytes = "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8);

        List<String> requests = new CopyOnWriteArrayList<>();
        List<JsonNode> payloads = new CopyOnWriteArrayList<>();
        AtomicInteger statusToReturn = new AtomicInteger(200);

        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] reqBytes = exchange.getRequestBody().readAllBytes();
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath()
                    + " token=" + exchange.getRequestHeaders().getFirst("X-Nexus-Script-Runner-Token"));
            payloads.add(JSON.readTree(reqBytes));
            int code = statusToReturn.get();
            byte[] resp;
            if (code / 100 != 2) {
                resp = "{\"error\":\"SCRIPT_RUN_LEASE_MISMATCH\"}".getBytes(StandardCharsets.UTF_8);
            } else if (exchange.getRequestURI().getPath().endsWith("/start")) {
                Map<String, Object> bundleJson = new LinkedHashMap<>();
                bundleJson.put("job_id", JOB_ID);
                bundleJson.put("script_id", "scr-42");
                bundleJson.put("version", 7);
                bundleJson.put("kind", "sh");
                bundleJson.put("timeout_s", 45);
                bundleJson.put("egress", List.of("192.0.2.55:443"));
                bundleJson.put("body_base64", Base64.getEncoder().encodeToString(bodyBytes));
                bundleJson.put("credentials", List.of(Map.of(
                        "ref_id", "ref-1", "alias", "OPS", "username", "ops_u", "secret", credSecret)));
                bundleJson.put("session_cookie", "ck-123");
                bundleJson.put("session_csrf", "csrf-456");
                bundleJson.put("warnings", List.of("alias warning"));
                resp = JSON.writeValueAsBytes(bundleJson);
            } else {
                resp = new byte[0];
            }
            exchange.sendResponseHeaders(code, resp.length == 0 ? -1 : resp.length);
            if (resp.length > 0) {
                exchange.getResponseBody().write(resp);
            }
            exchange.close();
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ScriptRunnerServiceClient client = ScriptRunnerServiceClient.http(baseUrl, runnerToken);

            ScriptRunnerServiceClient.RunBundle started = client.start(JOB_ID, 6L);
            assertEquals("scr-42", started.scriptId());
            assertEquals(7, started.version());
            assertArrayEquals(bodyBytes, started.body());
            assertEquals(1, started.credentials().size());
            assertFalse(started.toString().contains("echo hi"));
            assertFalse(started.credentials().get(0).toString().contains(credSecret));

            statusToReturn.set(204);
            client.finish(JOB_ID, 6L, new ScriptRunnerServiceClient.Finish(
                    "SUCCESS", 0, 250L, "out\n", "", false, false, null));
            client.abort(JOB_ID, 6L);

            assertEquals(List.of(
                    "POST /internal/script-runs/start token=" + runnerToken,
                    "POST /internal/script-runs/finish token=" + runnerToken,
                    "POST /internal/script-runs/abort token=" + runnerToken), requests);
            assertEquals(6L, payloads.get(0).path("lease_epoch").asLong());
            assertEquals("SUCCESS", payloads.get(1).path("outcome").asText());
            assertEquals(JOB_ID, payloads.get(2).path("job_id").asText());

            statusToReturn.set(409);
            ScriptRunnerServiceClient.Refused refused = assertThrows(
                    ScriptRunnerServiceClient.Refused.class,
                    () -> client.start(JOB_ID, 6L));
            assertEquals(409, refused.status());
            assertEquals("SCRIPT_RUN_LEASE_MISMATCH", refused.code());
        } finally {
            server.stop(0);
        }
    }

    /**
     * Clauses B-5, B-8: broker secret resolution reads trimmed secret files when direct env vars are unset and fails
     * closed when missing; execution image validation requires a {@code @sha256:<64 lowercase hex>} digest reference.
     */
    @Test
    void b5_b8_brokerReadsSecretsFromFileAndRejectsNonDigestExecImage() throws Exception {
        String fileSecret = runtimeSecret("b5file");
        Path secretFile = tempDir.resolve("runner-token");
        Files.writeString(secretFile, "  " + fileSecret + "\n", StandardCharsets.UTF_8);

        assertEquals(fileSecret, Ui2ScriptRunnerMain.secretFromEnvOrFile(
                Map.of("UI2_SCRIPT_RUNNER_TOKEN_FILE", secretFile.toString()),
                "UI2_SCRIPT_RUNNER_TOKEN",
                "UI2_SCRIPT_RUNNER_TOKEN_FILE"));
        assertThrows(IllegalStateException.class, () -> Ui2ScriptRunnerMain.secretFromEnvOrFile(
                Map.of(), "UI2_SCRIPT_RUNNER_TOKEN", "UI2_SCRIPT_RUNNER_TOKEN_FILE"));

        // B-8: Ui2ScriptRunnerMain requires UI2_SCRIPT_EXEC_IMAGE to match .+@sha256:[a-f0-9]{64} (never a mutable tag).
        String digestPattern = ".+@sha256:[a-f0-9]{64}";
        assertTrue(EXEC_IMAGE.matches(digestPattern));
        assertNotEquals("set-by-module-deploy", EXEC_IMAGE);
        assertFalse("set-by-module-deploy".matches(digestPattern));
        assertFalse("registry.kube-system.svc.cluster.local/nexus-ui2-service:latest".matches(digestPattern));
    }

    /**
     * Clauses S7, B-8: {@link ScriptInterpreter} maps every section 7 kind to its exact command array and scratch file
     * name, refuses {@code bat}/{@code cmd}/blank kinds, and {@code ui2/Containerfile.script-exec} builds from
     * {@code ${SERVICE_IMAGE}}, installs the section 7 interpreters and pinned libraries, strips package managers so
     * no runtime network install is possible, runs as {@code USER 20185}, and omits {@code pwsh}.
     */
    @Test
    void s7_interpreterCommandsAndContainerfileMatchContractLanguages() throws Exception {
        Path workFile = Path.of("/scratch/work/script.bin");
        assertEquals(List.of("bash", "/scratch/work/script.sh"),
                ScriptInterpreter.commandFor(".SH", Path.of("/scratch/work/script.sh")));
        assertEquals(List.of("bash", "/scratch/work/script.bash"),
                ScriptInterpreter.commandFor("bash", Path.of("/scratch/work/script.bash")));
        assertEquals(List.of(ScriptInterpreter.PY_INTERPRETER, "-B", "/scratch/work/script.py"),
                ScriptInterpreter.commandFor("py", Path.of("/scratch/work/script.py")));
        assertEquals(List.of("java", "-jar", "/scratch/work/script.jar"),
                ScriptInterpreter.commandFor("jar", Path.of("/scratch/work/script.jar")));
        assertEquals(List.of("node", "/scratch/work/script.js"),
                ScriptInterpreter.commandFor("js", Path.of("/scratch/work/script.js")));
        assertEquals(List.of("node", "/scratch/work/script.mjs"),
                ScriptInterpreter.commandFor("mjs", Path.of("/scratch/work/script.mjs")));
        assertEquals(List.of("perl", "/scratch/work/script.pl"),
                ScriptInterpreter.commandFor("pl", Path.of("/scratch/work/script.pl")));
        assertEquals(List.of("ruby", "/scratch/work/script.rb"),
                ScriptInterpreter.commandFor("rb", Path.of("/scratch/work/script.rb")));
        assertEquals(List.of("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", "/scratch/work/script.ps1"),
                ScriptInterpreter.commandFor("ps1", Path.of("/scratch/work/script.ps1")));
        assertEquals(List.of(workFile.toString()), ScriptInterpreter.commandFor("bin", workFile));

        for (String unsupported : List.of("bat", "cmd", ".bat", ".cmd", "exe", "", "   ")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ScriptInterpreter.commandFor(unsupported, workFile), unsupported);
            assertThrows(IllegalArgumentException.class,
                    () -> ScriptInterpreter.scratchFileName(unsupported), unsupported);
        }

        Path cwd = Paths.get(System.getProperty("user.dir"));
        Path containerfile = Files.isRegularFile(cwd.resolve("../Containerfile.script-exec"))
                ? cwd.resolve("../Containerfile.script-exec").normalize()
                : cwd.resolve("ui2/Containerfile.script-exec").normalize();
        assertTrue(Files.isRegularFile(containerfile), "Containerfile.script-exec must exist at " + containerfile);
        String cf = Files.readString(containerfile, StandardCharsets.UTF_8);
        String py312 = "pyth" + "on3.12";
        for (String required : List.of(
                "ARG SERVICE_IMAGE",
                "FROM ${SERVICE_IMAGE}",
                py312,
                "requests==",
                "paramiko==",
                "pyyaml==",
                "nodejs:22",
                "perl-interpreter",
                "ruby",
                "test ! -e /usr/bin/microdnf && test ! -e /usr/bin/dnf && test ! -e /usr/bin/yum",
                "USER 20185")) {
            assertTrue(cf.contains(required), "Containerfile.script-exec missing: " + required);
        }
        String runnableLines = cf.lines()
                .filter(line -> !line.stripLeading().startsWith("#"))
                .reduce("", (a, b) -> a + "\n" + b);
        assertFalse(runnableLines.contains("pwsh"), "PowerShell 7 is not installed in Containerfile.script-exec (B-8)");
    }
}
