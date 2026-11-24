package com.securityexpert.nexus.ui2.worker.snmp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.securityexpert.nexus.ui2.capability.GateRegistryFixtureLoader;
import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Configuration;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Device;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.HealthFigures;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Outcome;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Override;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.PollResult;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.Settings;
import com.securityexpert.nexus.ui2.persistence.snmp.SnmpPollingRepository;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.CredentialKind;
import com.securityexpert.nexus.ui2.platform.CredentialStorePort.SnmpSettings;
import com.securityexpert.nexus.ui2.worker.credential.StoreBackedSnmpCredentialResolver.Material;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpAnswer;
import com.securityexpert.nexus.ui2.worker.snmp.SnmpClient.SnmpValue;

/** The poller against an in-memory store, a scripted SNMP client and the real gate fixture. */
class SnmpPollerTest {
    private static final Instant NOW = Instant.parse("2026-11-24T10:00:00Z");
    private static final List<GateRow> FIXTURE = GateRegistryFixtureLoader.loadFromStream(
            SnmpPollerTest.class.getClassLoader().getResourceAsStream("capabilities/gate_registry_fixture.yaml"));
    private static final GateRegistryPort GATES = key -> FIXTURE.stream().filter(r -> r.key().equals(key)).toList();

    private FakeStore store;
    private ScriptedClient client;
    private Map<String, SnmpSettings> credentials;
    private List<char[]> issuedSecrets;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        client = new ScriptedClient();
        credentials = new HashMap<>();
        credentials.put("cred-good", new SnmpSettings("authPriv", "SHA-256", "AES-128"));
        credentials.put("cred-md5", new SnmpSettings("authPriv", "MD5", "AES-128"));
        credentials.put("cred-des", new SnmpSettings("authPriv", "SHA-256", "DES"));
        credentials.put("cred-nopriv", new SnmpSettings("authNoPriv", "SHA-256", null));
        issuedSecrets = new ArrayList<>();
    }

    private SnmpPoller poller(GateRegistryPort gates) {
        SnmpPoller.CredentialSource source = ref -> {
            SnmpSettings s = credentials.get(ref);
            if (s == null) throw new IllegalStateException("SNMP credential reference not resolvable");
            char[] auth = ("synthetic-" + "auth").toCharArray();
            char[] priv = s.privProtocol() == null ? new char[0] : ("synthetic-" + "priv").toCharArray();
            issuedSecrets.add(auth);
            issuedSecrets.add(priv);
            return new Material(CredentialKind.SNMP_V3, "synthetic-user", s, auth, priv);
        };
        return new SnmpPoller(store, gates, source, client, new DirectExecutor(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void enable(String fleetCredential) {
        store.config = new Configuration(new Settings(true, 300, fleetCredential, "VENDORS", 35, 400),
                List.of(Override.vendor("check_point", true, null), Override.vendor("palo_alto", true, null)), null, null);
    }

    @Test
    void theFleetSwitchOffPollsNothing() {
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 0, NOW.minusSeconds(1), null));
        assertEquals(0, poller(GATES).tick());
        assertTrue(store.recorded.isEmpty());
        assertEquals(0, client.calls);
    }

    @Test
    void aNewDeviceIsScheduledInsideTheIntervalNotPolledAtOnce() {
        enable("cred-good");
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 0, null, null));
        assertEquals(0, poller(GATES).tick());
        Instant first = store.scheduled.get("dev-cp-1");
        assertFalse(first.isBefore(NOW));
        assertTrue(first.isBefore(NOW.plusSeconds(300)));
        assertEquals(0, client.calls);
    }

    @Test
    void aDueCheckPointPollIsClaimedParsedAndRecordedAndTheSecretIsCleared() {
        enable("cred-good");
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10:22", 0, NOW.minusSeconds(5), 100L));
        client.next = new SnmpAnswer.Answered(Map.of(SnmpOids.SYS_UPTIME, SnmpValue.number(200),
                SnmpOids.CP_PROC_USAGE, SnmpValue.number(40), SnmpOids.CP_MEM_TOTAL_REAL64, SnmpValue.number(100),
                SnmpOids.CP_MEM_ACTIVE_REAL64, SnmpValue.number(50), SnmpOids.CP_FW_NUM_CONN, SnmpValue.number(7)));
        assertEquals(1, poller(GATES).tick());
        assertEquals(List.of("dev-cp-1"), store.claimed);
        assertEquals("192.0.2.10", client.lastHost, "the SNMP port is 161, not the endpoint's port");
        assertEquals(SnmpVendorProfile.CHECK_POINT, client.lastProfile);
        PollResult r = store.recorded.get(0);
        assertEquals(Outcome.OK, r.outcome());
        assertEquals(new BigDecimal("40.00"), r.metrics().cpuPct());
        assertEquals(new BigDecimal("50.00"), r.metrics().memPct());
        assertEquals(7L, r.metrics().sessions());
        assertFalse(r.rebooted());
        assertEquals(0, r.consecutiveMisses());
        assertEquals(NOW.plusSeconds(300), r.nextPollAt());
        for (char[] secret : issuedSecrets) for (char c : secret) assertEquals('\0', c);
    }

    @Test
    void aLowerUptimeIsRecordedAsAReboot() {
        enable("cred-good");
        store.devices.add(new Device("dev-pan-1", "palo_alto", "192.0.2.20", 0, NOW, 9_000_000L));
        client.next = new SnmpAnswer.Answered(Map.of(SnmpOids.SYS_UPTIME, SnmpValue.number(3_000)));
        poller(GATES).tick();
        PollResult r = store.recorded.get(0);
        assertTrue(r.rebooted());
        assertEquals(Outcome.PARTIAL, r.outcome());
        assertNull(r.metrics().cpuPct());
    }

    @Test
    void threeSilentPollsBackOffToThirtyMinutesAndAuthRefusalCounts() {
        enable("cred-good");
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 2, NOW, null));
        client.next = new SnmpAnswer.NoAnswer();
        poller(GATES).tick();
        PollResult r = store.recorded.get(0);
        assertEquals(Outcome.SNMP_NO_ANSWER, r.outcome());
        assertEquals(3, r.consecutiveMisses());
        assertEquals(NOW.plus(Duration.ofMinutes(30)), r.nextPollAt());

        store.recorded.clear();
        store.devices.set(0, new Device("dev-cp-1", "check_point", "192.0.2.10", 0, NOW, null));
        client.next = new SnmpAnswer.AuthRefused();
        poller(GATES).tick();
        assertEquals(Outcome.SNMP_AUTH_REFUSED, store.recorded.get(0).outcome());
        assertEquals(1, store.recorded.get(0).consecutiveMisses());
    }

    @Test
    void nothingIsSentWithoutGatesCredentialOrAddressOrWithAWeakCredential() {
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 1, NOW, null));
        enable(null);
        poller(GATES).tick();
        assertEquals(Outcome.SNMP_NO_CREDENTIAL, store.recorded.get(0).outcome());
        assertEquals(1, store.recorded.get(0).consecutiveMisses(), "no request: misses unchanged");

        for (String weak : List.of("cred-md5", "cred-des", "cred-nopriv")) {
            store.recorded.clear();
            enable(weak);
            poller(GATES).tick();
            assertEquals(Outcome.SNMP_CREDENTIAL_UNSUITABLE, store.recorded.get(0).outcome(), weak);
        }

        store.recorded.clear();
        enable("cred-missing");
        poller(GATES).tick();
        assertEquals(Outcome.SNMP_NO_CREDENTIAL, store.recorded.get(0).outcome());

        store.recorded.clear();
        enable("cred-good");
        store.devices.set(0, new Device("dev-cp-1", "check_point", null, 0, NOW, null));
        poller(GATES).tick();
        assertEquals(Outcome.SNMP_NO_ADDRESS, store.recorded.get(0).outcome());

        store.recorded.clear();
        store.devices.set(0, new Device("dev-cp-1", "check_point", "192.0.2.10", 0, NOW, null));
        GateRegistryPort withoutCpu = key -> FIXTURE.stream().filter(r -> r.key().equals(key)
                && !r.gateId().equals("cp_snmp_proc_usage")).toList();
        poller(withoutCpu).tick();
        assertEquals(Outcome.SNMP_GATE_MISSING, store.recorded.get(0).outcome());
        assertEquals(0, client.calls, "no device request in any of these cases");
    }

    @Test
    void aClaimLostToAnotherWorkerIsSkippedAndOutOfScopeDevicesAreIgnored() {
        enable("cred-good");
        store.claimWins = false;
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 0, NOW, null));
        assertEquals(0, poller(GATES).tick());
        store.claimWins = true;
        store.config = new Configuration(store.config.settings(), List.of(Override.vendor("palo_alto", true, null),
                Override.device("dev-pan-2", false, null)), null, null);
        store.devices.add(new Device("dev-pan-2", "palo_alto", "192.0.2.21", 0, NOW, null));
        assertEquals(0, poller(GATES).tick());
        assertEquals(0, client.calls);
    }

    @Test
    void aDeviceOverdueByMoreThanAnIntervalIsSpreadAgainNotPolledInABurst() {
        enable("cred-good");
        store.devices.add(new Device("dev-cp-1", "check_point", "192.0.2.10", 0, NOW.minus(Duration.ofHours(6)), null));
        assertEquals(0, poller(GATES).tick());
        assertEquals(List.of("dev-cp-1"), store.claimed, "re-scheduled through a claim");
        assertEquals(0, client.calls);
        assertTrue(store.recorded.isEmpty());
    }

    @Test
    void neverMoreThanSixteenPollsAreInFlight() {
        enable("cred-good");
        for (int i = 0; i < 20; i++) {
            store.devices.add(new Device("dev-cp-" + i, "check_point", "192.0.2." + (10 + i), 0, NOW, null));
        }
        List<Runnable> held = new ArrayList<>();
        SnmpPoller.CredentialSource none = ref -> { throw new IllegalStateException("unused"); };
        var holding = new DirectExecutor() {
            @java.lang.Override public void execute(Runnable r) { held.add(r); }
        };
        var poller = new SnmpPoller(store, GATES, none, client, holding, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(SnmpPoller.MAX_IN_FLIGHT, poller.tick());
        assertEquals(SnmpPoller.MAX_IN_FLIGHT, poller.inFlight());
        assertEquals(0, poller.tick(), "nothing more while sixteen are in flight");
        held.forEach(Runnable::run);
        assertEquals(0, poller.inFlight());
        assertEquals(16, store.claimed.size());
    }

    @Test
    void maintenanceRollsUpThreeCompleteHoursAndPrunesByRetention() {
        enable("cred-good");
        poller(GATES).maintain();
        assertEquals(List.of(NOW.minus(Duration.ofHours(3)), NOW), store.rollups);
        assertEquals(List.of(NOW.minus(Duration.ofDays(35)), NOW.minus(Duration.ofDays(400))), store.prunes);
    }

    @Test
    void aFailingStoreNeverEscapesTheTick() {
        store.failRead = true;
        assertEquals(0, poller(GATES).tick());
        poller(GATES).maintain();
    }

    // ------------------------------------------------------------------ fakes

    private static final class ScriptedClient implements SnmpClient {
        SnmpAnswer next = new SnmpAnswer.NoAnswer();
        int calls;
        String lastHost;
        SnmpVendorProfile lastProfile;

        @java.lang.Override
        public SnmpAnswer poll(String host, Material credential, SnmpVendorProfile profile) {
            calls++;
            lastHost = host;
            lastProfile = profile;
            return next;
        }
    }

    private static final class FakeStore implements SnmpPollingRepository {
        Configuration config = new Configuration(Settings.DEFAULTS, List.of(), null, null);
        final List<Device> devices = new ArrayList<>();
        final Map<String, Instant> scheduled = new HashMap<>();
        final List<String> claimed = new ArrayList<>();
        final List<PollResult> recorded = new ArrayList<>();
        final List<Instant> rollups = new ArrayList<>();
        final List<Instant> prunes = new ArrayList<>();
        boolean claimWins = true;
        boolean failRead;

        @java.lang.Override public Configuration read() {
            if (failRead) throw new IllegalStateException("db down");
            return config;
        }
        @java.lang.Override public Configuration write(String actor, Settings s, List<Override> o) { throw new UnsupportedOperationException(); }
        @java.lang.Override public List<Device> eligibleDevices() { return List.copyOf(devices); }
        @java.lang.Override public void schedule(String id, Instant first) { scheduled.put(id, first); }
        @java.lang.Override public boolean claim(String id, Instant expected, Instant lease) {
            if (claimWins) claimed.add(id);
            return claimWins;
        }
        @java.lang.Override public void record(PollResult r) { recorded.add(r); }
        @java.lang.Override public int rollup(Instant from, Instant to) { rollups.add(from); rollups.add(to); return 0; }
        @java.lang.Override public int[] prune(Instant raw, Instant hourly) { prunes.add(raw); prunes.add(hourly); return new int[2]; }
        @java.lang.Override public Map<String, HealthFigures> health(Instant since) { return Map.of(); }
        @java.lang.Override public Map<String, List<com.securityexpert.nexus.ui2.persistence.snmp.SnmpPolling.DailyFigures>> daily(
                Instant since, String zoneId) { return Map.of(); }
        @java.lang.Override public Map<String, String> lastOutcomes() { return Map.of(); }
        @java.lang.Override public Map<String, Integer> lastOutcomeCounts(Instant since) { return Map.of(); }
    }

    private static class DirectExecutor extends AbstractExecutorService {
        @java.lang.Override public void execute(Runnable r) { r.run(); }
        @java.lang.Override public void shutdown() {}
        @java.lang.Override public List<Runnable> shutdownNow() { return List.of(); }
        @java.lang.Override public boolean isShutdown() { return false; }
        @java.lang.Override public boolean isTerminated() { return false; }
        @java.lang.Override public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }
}
