package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.sql.DataSource;

import com.securityexpert.nexus.ui2.jobs.lease.JobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.lease.PersistenceJobLeaseRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.JobStepAttemptRepository;
import com.securityexpert.nexus.ui2.jobs.stepattempt.PersistenceJobStepAttemptRepository;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundaryFactory;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobLeaseDao;
import com.securityexpert.nexus.ui2.persistence.jobrecords.JooqJobStepAttemptDao;
import com.securityexpert.nexus.ui2.persistence.runtime.ModuleRuntimeRepository;
import com.securityexpert.nexus.ui2.platform.JobWindowPolicy;
import com.securityexpert.nexus.ui2.platform.ScriptExecutionJob;
import com.securityexpert.nexus.ui2.platform.logging.MaskingFormatter;

/**
 * Entry point of the {@code ui2-script-runner} broker ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b)).
 *
 * <p>Claims {@link ScriptExecutionJob#CAPABILITY} ({@code script_run}) jobs only, heartbeats its module row in
 * {@code module_runtime_control} ({@code module = 'script_runner'}) and drains cleanly on rollout. Each run executes in
 * its own short-lived Kubernetes Job ({@link ScriptRunJobExecutor}); the broker holds its own database login, its
 * internal-port token and its namespaced service-account token, and no artefact-store or credential-store key.</p>
 */
public final class Ui2ScriptRunnerMain {

    private static final Logger LOG = Logger.getLogger(Ui2ScriptRunnerMain.class.getName());

    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);
    private static final Duration DEFAULT_IDLE_POLL = Duration.ofSeconds(2);
    static final String DEFAULT_INTERNAL_URL = "http://ui2-service-internal.ui2.svc.cluster.local:8086";

    private Ui2ScriptRunnerMain() {
    }

    public static void main(String[] args) {
        MaskingFormatter.install();
        Map<String, String> env = System.getenv();
        String role = ScriptExecutionJob.MODULE;
        String dbUrl = requiredEnv(env, "UI2_DB_URL");
        // Its own least-privilege login (V258 ui2_apply_script_runner_role_grants), never the shared ui2-db login.
        String dbUsername = secretFromEnvOrFile(env, "UI2_DB_USERNAME", "UI2_DB_APP_USER_FILE");
        String dbPassword = secretFromEnvOrFile(env, "UI2_DB_PASSWORD", "UI2_DB_APP_PASSWORD_FILE");
        String token = secretFromEnvOrFile(env, "UI2_SCRIPT_RUNNER_TOKEN", "UI2_SCRIPT_RUNNER_TOKEN_FILE");
        String execImage = requiredEnv(env, "UI2_SCRIPT_EXEC_IMAGE");
        if (!execImage.matches(".+@sha256:[a-f0-9]{64}")) {
            throw new IllegalStateException("UI2_SCRIPT_EXEC_IMAGE must be an image by digest");
        }
        String internalUrl = env.getOrDefault("UI2_SCRIPT_RUNNER_SERVICE_URL", DEFAULT_INTERNAL_URL);
        String cliServiceUrl = env.getOrDefault("UI2_SERVICE_URL", ScriptRunJobExecutor.DEFAULT_SERVICE_URL);

        DataSource dataSource = TransactionBoundaryFactory.jdbcDataSource(dbUrl, dbUsername, dbPassword);
        TransactionBoundary boundary = TransactionBoundaryFactory.fromDataSource(dataSource);
        ModuleRuntimeRepository runtime = new ModuleRuntimeRepository(boundary);
        String workerId = role + "-" + UUID.randomUUID();
        awaitOwnership(role, () -> runtime.heartbeat(role, workerId));

        // script_run executes whenever scheduled or manually triggered (all 360 minutes of every 6-hour slot).
        JobWindowPolicy allDayWindow = new JobWindowPolicy(Clock.systemUTC(), 360);
        JobLeaseRepository leases = new PersistenceJobLeaseRepository(new JooqJobLeaseDao(boundary, allDayWindow));
        JobStepAttemptRepository attempts = new PersistenceJobStepAttemptRepository(new JooqJobStepAttemptDao(boundary));

        ScriptRunJobExecutor executor = new ScriptRunJobExecutor(
                leases,
                attempts,
                ScriptRunnerServiceClient.http(internalUrl, token),
                new ScriptRunKubernetes(kubernetesApi(env)),
                null,
                execImage,
                cliServiceUrl,
                Clock.systemUTC(),
                Duration.ofSeconds(2),
                null);
        executor.sweepOrphans();

        final boolean[] draining = {false};
        ScriptRunnerClaimLoop loop = new ScriptRunnerClaimLoop(
                workerId,
                leases,
                executor,
                DEFAULT_LEASE_DURATION,
                DEFAULT_LEASE_DURATION.dividedBy(3),
                DEFAULT_IDLE_POLL,
                () -> draining[0]);

        var claims = Executors.newSingleThreadExecutor();
        claims.submit(loop::runLoop);

        var scheduled = Executors.newSingleThreadScheduledExecutor();
        scheduled.scheduleWithFixedDelay(() -> {
            try {
                if (!runtime.heartbeat(role, workerId)) {
                    draining[0] = true;
                    loop.requestStop();
                    claims.shutdown();
                }
            } catch (RuntimeException unavailable) {
                LOG.warning("MODULE_HEARTBEAT_UNAVAILABLE");
            }
        }, 10, 10, TimeUnit.SECONDS);

        long drainSeconds = Long.parseLong(env.getOrDefault("UI2_WORKER_DRAIN_SECONDS", "840"));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            draining[0] = true;
            loop.requestStop();
            claims.shutdown();
            try {
                claims.awaitTermination(drainSeconds, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                scheduled.shutdownNow();
                runtime.releaseOwnership(role, workerId);
            }
        }, "script-runner-drain"));

        LOG.info(() -> "ui2_script_runner_started: worker_id=" + workerId);
    }

    private static KubernetesApi kubernetesApi(Map<String, String> env) {
        try {
            return InClusterKubernetesApi.fromEnvironment(env);
        } catch (IOException e) {
            throw new IllegalStateException("the broker's service-account token or cluster CA is unreadable", e);
        }
    }

    private static void awaitOwnership(String role, java.util.function.BooleanSupplier heartbeat) {
        while (!heartbeat.getAsBoolean()) {
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for " + role + " ownership", e);
            }
        }
    }

    static String secretFromEnvOrFile(Map<String, String> env, String directVar, String fileVar) {
        String direct = env.get(directVar);
        if (direct != null && !direct.isBlank()) {
            return direct.strip();
        }
        String file = env.get(fileVar);
        if (file != null && !file.isBlank()) {
            try {
                String value = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8).strip();
                if (!value.isEmpty()) {
                    return value;
                }
            } catch (IOException e) {
                throw new IllegalStateException("could not read secret file from " + fileVar, e);
            }
        }
        throw new IllegalStateException("missing required secret configuration: set " + directVar + " or " + fileVar);
    }

    private static String requiredEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing required environment variable: " + name);
        }
        return value.strip();
    }
}
