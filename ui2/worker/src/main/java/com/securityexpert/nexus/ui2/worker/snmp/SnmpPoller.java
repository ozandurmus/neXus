package com.securityexpert.nexus.ui2.worker.snmp;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.persistence.runtime.EndpointAddress;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPollingRepository;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Metrics;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Outcome;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.PollResult;
import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;

/**
 * The SNMP device metrics poller (design section 5). {@link #tick} runs every few seconds: when the fleet switch is on it
 * claims each in-scope device whose poll is due and hands it to the bounded poll pool (at most {@link #MAX_IN_FLIGHT}
 * devices at a time, one request in flight per device). It opens no SSH or XML API session and takes no device
 * collection claim. {@link #maintain} writes hourly rows and prunes by the retention settings whether or not polling is on.
 * Values, addresses and secrets are never logged.
 */
public final class SnmpPoller {
    public static final int MAX_IN_FLIGHT = 16;
    /**
     * Database connections the poll threads may hold at once (credential read and result write). The general worker's
     * pool has 10 connections shared with its 10 claim loops; the wire wait happens outside this bound, so 16 polls in
     * flight never ask for more than 2 connections (plus the scheduling pass, which runs on one thread).
     */
    public static final int MAX_DB_CONCURRENCY = 2;
    private static final System.Logger LOG = System.getLogger(SnmpPoller.class.getName());

    /** Reads the store-backed credential by opaque reference; throws when it cannot be resolved. */
    public interface CredentialSource extends Function<String, Material> {}

    private final SnmpPollingRepository repository;
    private final GateRegistryPort gates;
    private final CredentialSource credentials;
    private final SnmpClient client;
    private final ExecutorService pool;
    private final Clock clock;
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.Semaphore database = new java.util.concurrent.Semaphore(MAX_DB_CONCURRENCY, true);

    public SnmpPoller(SnmpPollingRepository repository, GateRegistryPort gates, CredentialSource credentials,
            SnmpClient client, ExecutorService pool, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.gates = gates;
        this.credentials = Objects.requireNonNull(credentials);
        this.client = Objects.requireNonNull(client);
        this.pool = Objects.requireNonNull(pool);
        this.clock = Objects.requireNonNull(clock);
    }

    /** One scheduling pass; returns how many polls it handed to the pool. Never throws. */
    public int tick() {
        try {
            Configuration c = repository.read();
            if (!c.settings().enabled()) return 0;
            Instant now = clock.instant();
            int interval = c.settings().intervalS();
            Map<SnmpVendorProfile, Boolean> gated = new EnumMap<>(SnmpVendorProfile.class);
            for (SnmpVendorProfile p : SnmpVendorProfile.values()) gated.put(p, p.gated(gates));
            int submitted = 0;
            for (Device d : repository.eligibleDevices()) {
                Optional<SnmpPollPlan.Decision> decision = SnmpPollPlan.decide(c, d);
                Optional<SnmpVendorProfile> profile = SnmpVendorProfile.of(d.vendor());
                if (decision.isEmpty() || profile.isEmpty()) continue;
                if (d.nextPollAt() == null) {
                    repository.schedule(d.deviceId(), SnmpPollPlan.firstPoll(now, d.deviceId(), interval));
                    continue;
                }
                if (d.nextPollAt().isAfter(now) || inFlight.contains(d.deviceId())) continue;
                // Overdue by more than one interval (the switch was off, or the worker was down): spread again rather
                // than poll the whole fleet in one burst.
                if (d.nextPollAt().isBefore(now.minusSeconds(interval))) {
                    repository.claim(d.deviceId(), d.nextPollAt(), SnmpPollPlan.firstPoll(now, d.deviceId(), interval));
                    continue;
                }
                if (inFlight.size() >= MAX_IN_FLIGHT) break;
                if (!repository.claim(d.deviceId(), d.nextPollAt(), now.plus(SnmpPollPlan.BACKOFF))) continue;
                inFlight.add(d.deviceId());
                boolean vendorGated = gated.get(profile.get());
                try {
                    pool.execute(() -> {
                        try {
                            PollResult result = pollOne(d, profile.get(), vendorGated, decision.get(), interval);
                            withDatabase(() -> { repository.record(result); return null; });
                        } catch (RuntimeException failed) {
                            LOG.log(System.Logger.Level.WARNING, "SNMP_POLL_RECORD_FAILED " + failed.getClass().getSimpleName());
                        } finally {
                            inFlight.remove(d.deviceId());
                        }
                    });
                    submitted++;
                } catch (RejectedExecutionException shuttingDown) {
                    inFlight.remove(d.deviceId());
                    break;
                }
            }
            return submitted;
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "SNMP_POLL_TICK_FAILED " + unavailable.getClass().getSimpleName());
            return 0;
        }
    }

    /** One device: every refusal before the request (gate, credential, address) is recorded and sends nothing. */
    PollResult pollOne(Device d, SnmpVendorProfile profile, boolean vendorGated, SnmpPollPlan.Decision decision,
            int intervalS) {
        if (!vendorGated) return configurationOutcome(d, Outcome.SNMP_GATE_MISSING, intervalS);
        if (decision.credentialReferenceId().isEmpty()) return configurationOutcome(d, Outcome.SNMP_NO_CREDENTIAL, intervalS);
        String host;
        try {
            host = EndpointAddress.host(d.addressRef());
        } catch (RuntimeException missing) {
            return configurationOutcome(d, Outcome.SNMP_NO_ADDRESS, intervalS);
        }
        SnmpAnswer answer;
        try (Material material = withDatabase(() -> credentials.apply(decision.credentialReferenceId().get()))) {
            if (!Snmp4jClient.suitable(material)) return configurationOutcome(d, Outcome.SNMP_CREDENTIAL_UNSUITABLE, intervalS);
            answer = client.poll(host, material, profile);
        } catch (IllegalStateException unresolvable) {
            return configurationOutcome(d, Outcome.SNMP_NO_CREDENTIAL, intervalS);
        }
        Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Outcome outcome;
        Metrics metrics = Metrics.NONE;
        if (answer instanceof SnmpAnswer.Answered answered) {
            metrics = SnmpMetricParser.parse(profile, answered);
            outcome = metrics.complete() ? Outcome.OK : Outcome.PARTIAL;
        } else if (answer instanceof SnmpAnswer.AuthRefused) {
            outcome = Outcome.SNMP_AUTH_REFUSED;
        } else {
            outcome = Outcome.SNMP_NO_ANSWER;
        }
        boolean rebooted = SnmpPollPlan.rebooted(d.lastSysUptimeCs(), metrics.sysUptimeCs(),
                Duration.ofSeconds(Math.max(intervalS, SnmpPollPlan.BACKOFF.toSeconds())));
        int misses = SnmpPollPlan.misses(d.consecutiveMisses(), outcome);
        return new PollResult(d.deviceId(), at, outcome, metrics, rebooted, misses,
                SnmpPollPlan.nextPoll(at, intervalS, misses));
    }

    /** Runs one short database step under {@link #MAX_DB_CONCURRENCY}. */
    private <T> T withDatabase(java.util.function.Supplier<T> step) {
        database.acquireUninterruptibly();
        try {
            return step.get();
        } finally {
            database.release();
        }
    }

    /** Polls handed to the pool and not yet recorded (at most {@link #MAX_IN_FLIGHT}). */
    int inFlight() {
        return inFlight.size();
    }

    private PollResult configurationOutcome(Device d, Outcome outcome, int intervalS) {
        Instant at = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return new PollResult(d.deviceId(), at, outcome, Metrics.NONE, false, d.consecutiveMisses(),
                SnmpPollPlan.nextPoll(at, intervalS, 0));
    }

    /** Hourly rows for the last three complete hours, then retention. Never throws. */
    public void maintain() {
        try {
            Configuration c = repository.read();
            Instant hour = clock.instant().truncatedTo(ChronoUnit.HOURS);
            repository.rollup(hour.minus(Duration.ofHours(3)), hour);
            repository.prune(clock.instant().minus(Duration.ofDays(c.settings().rawRetentionDays())),
                    clock.instant().minus(Duration.ofDays(c.settings().hourlyRetentionDays())));
        } catch (RuntimeException unavailable) {
            LOG.log(System.Logger.Level.WARNING, "SNMP_METRIC_MAINTENANCE_FAILED " + unavailable.getClass().getSimpleName());
        }
    }
}
