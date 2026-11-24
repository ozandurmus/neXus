package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.capability.GateRegistryPort;
import com.securityexpert.nexus.ui2.capability.GateRow;
import com.securityexpert.nexus.ui2.capability.SignOffState;
import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;
import com.securityexpert.nexus.ui2.platform.ActionClass;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * The Management Center managed-device health read in the inventory run (PO 2026-11-24): never sent while the switch is off
 * (the default) or the gate row is absent; absent gate = UNKNOWN GATE_NOT_APPROVED and an unchanged, completed run; a present
 * gate sends the one request ({@code SHORT}, once) after the device list and parses it. No gate row is seeded anywhere: the
 * gate in the "present" tests is a synthetic row of this test.
 */
class McDeviceHealthInventoryTest {

    private static final Target T = new Target("192.0.2.30", 8082);
    private static final String DEVICE = "dev-1";
    private static final String UUID_RPT = "a1055376-3448-4a94-82db-d9bd30bbb421";
    private static final String UUID_SG = "b2055376-3448-4a94-82db-d9bd30bbb422";
    private static final String STATUS_PATH = "/api/devices/status";

    @TempDir
    Path tempDir;

    private Fake calls;
    private HttpsVendorExecutor executor;

    private static final class Fake implements HttpsDeviceCalls {
        final List<String> log = new ArrayList<>();
        final Map<String, TextResponse> replies = new HashMap<>();
        final Map<String, IOException> failures = new HashMap<>();
        final String devices = "[{\"uuid\":\"" + UUID_RPT + "\",\"name\":\"MD-BRAVO-02\",\"type\":\"rptr\",\"host\":\"192.0.2.15\",\"managementStatus\":\"FULLY_MANAGED\"},"
                + "{\"uuid\":\"" + UUID_SG + "\",\"name\":\"FW-TANGO-04\",\"type\":\"sgos6x\",\"host\":\"192.0.2.20\",\"managementStatus\":\"FULLY_MANAGED\"}]";

        @Override
        public TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) throws IOException {
            log.add("GET " + path + " " + timeout.toSeconds() + " " + maxBytes);
            if (failures.containsKey(path)) {
                throw failures.get(path);
            }
            if (replies.containsKey(path)) {
                return replies.get(path);
            }
            if (path.equals(HttpsVendorPlan.MC_DEVICES)) {
                return new TextResponse(200, Optional.of("application/json"), devices, false, Optional.of("mc.example"));
            }
            if (path.equals(HttpsVendorPlan.MC_SYSTEM_VERSION)) {
                return new TextResponse(200, Optional.of("application/json"), "{\"name\":\"mc\",\"version\":\"4.1\",\"buildNumber\":7}", false);
            }
            return new TextResponse(404, Optional.empty(), "", false);
        }

        @Override
        public TextResponse postJson(Target t, String p, String j, Credentials c, Duration d, int m) {
            throw new AssertionError("unexpected POST " + p);
        }

        @Override
        public HttpsDeviceClient.SessionLogin login(Target t, String p, String j, Duration d) {
            throw new AssertionError("unexpected login");
        }

        @Override
        public DownloadResult download(Target t, String m, String p, Map<String, String> f, Credentials c, OutputStream s, long x, Duration d) {
            throw new AssertionError("unexpected download");
        }
    }

    @BeforeEach
    void setUp() {
        calls = new Fake();
        executor = new HttpsVendorExecutor(calls,
                new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))),
                ref -> new Credentials("user", "pw".toCharArray()));
    }

    private static GateRegistryPort gateRows(SignOffState state, ActionClass actionClass) {
        GateRow row = new GateRow("synthetic_mc_devices_status", "bluecoat", "bluecoat_management_center", "not_applicable", "HTTPS",
                "GET /api/devices/status", actionClass, state, 30, "none", "once per inventory run", "one client per run",
                "HTTP error: health UNKNOWN", "message text scrubbed", List.of("outcome", "http_status"), "synthetic-test-gate");
        return key -> row.key().equals(key) ? List.of(row) : List.of();
    }

    private static final GateRegistryPort NO_GATE = key -> List.of();

    private static McDeviceHealthRead enabled(GateRegistryPort gates) {
        return McDeviceHealthRead.from(Map.of("UI2_MC_DEVICE_HEALTH_READ", "true")::get, gates);
    }

    private HttpsVendorExecutor.InventoryOutcome.Completed inventory() {
        calls.log.clear();
        var outcome = executor.inventory("bluecoat_mc", T, "cred", DEVICE);
        assertTrue(outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(outcome));
        return (HttpsVendorExecutor.InventoryOutcome.Completed) outcome;
    }

    private static Map<String, GridMember.Health> byName(HttpsVendorExecutor.InventoryOutcome.Completed done) {
        Map<String, GridMember.Health> out = new HashMap<>();
        done.members().forEach(m -> out.put(m.hostName(), m.health().orElse(null)));
        return out;
    }

    private static final String BODY = "[{\"uuid\":\"" + UUID_RPT + "\",\"name\":\"MD-BRAVO-02\",\"monitorState\":\"ERROR\",\"checkDate\":\"2026-11-24T10:12:23\","
            + "\"errors\":[{\"messageId\":\"/BC/MC/CORE/GENERAL\",\"message\":\"Unknown error contacting 192.0.2.15\"}],\"warnings\":[]},"
            + "{\"uuid\":\"" + UUID_SG + "\",\"name\":\"FW-TANGO-04\",\"monitorState\":\"OK\",\"checkDate\":\"2026-11-24T10:12:23\",\"errors\":[],\"warnings\":[]}]";

    @Test
    void theSwitchDefaultsToOffAndNothingExtraIsSentOrRecorded() {
        // never wired, wired off, wired with the variable unset or false -- even with an approved gate present
        for (McDeviceHealthRead read : List.of(McDeviceHealthRead.OFF, McDeviceHealthRead.from(k -> null, gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_0_READ)),
                McDeviceHealthRead.from(Map.of("UI2_MC_DEVICE_HEALTH_READ", "false")::get, gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_0_READ)))) {
            executor.withMcDeviceHealth(read);
            var done = inventory();
            assertEquals(List.of("GET /api/devices 30 8388608", "GET /api/system/version 30 65536"), calls.log);
            assertTrue(done.members().stream().allMatch(m -> m.health().isEmpty()), "no health is recorded when the read is off");
        }
        assertFalse(McDeviceHealthRead.OFF.enabled());
    }

    @Test
    void aMissingGateRowSkipsTheReadAndRecordsUnknownGateNotApprovedWithoutFailingTheRun() {
        for (GateRegistryPort gates : java.util.Arrays.asList(NO_GATE, gateRows(SignOffState.DRAFTED, ActionClass.CLASS_0_READ),
                gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_2_OPERATIONAL_STATE_CHANGE), (GateRegistryPort) null,
                (GateRegistryPort) key -> { throw new IllegalStateException("registry down"); })) {
            executor.withMcDeviceHealth(enabled(gates));
            var done = inventory();
            assertEquals(List.of("GET /api/devices 30 8388608", "GET /api/system/version 30 65536"), calls.log, "the status request is never sent");
            assertEquals(2, done.members().size());
            for (GridMember member : done.members()) {
                var health = member.health().orElseThrow();
                assertEquals("UNKNOWN", health.state());
                assertEquals(List.of("GATE_NOT_APPROVED"), health.messages());
                assertEquals(Optional.of("FULLY_MANAGED"), member.nodeStatus(), "the management state is untouched");
            }
        }
    }

    @Test
    void anApprovedGateSendsOneShortRequestRightAfterTheDeviceListAndParsesIt() {
        calls.replies.put(STATUS_PATH, new TextResponse(200, Optional.of("application/json"), BODY, false));
        executor.withMcDeviceHealth(enabled(gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_0_READ)));
        var done = inventory();
        assertEquals(List.of("GET /api/devices 30 8388608", "GET /api/devices/status 30 1048576", "GET /api/system/version 30 65536"), calls.log,
                "once, SHORT (30 s), TEXT_MAX (1 MiB), directly after the device list");
        var health = byName(done);
        assertEquals("ERROR", health.get("MD-BRAVO-02").state());
        assertEquals(1, health.get("MD-BRAVO-02").errors());
        assertEquals(List.of("Error: Unknown error contacting [device]"), health.get("MD-BRAVO-02").messages());
        assertEquals("OK", health.get("FW-TANGO-04").state());
    }

    @Test
    void aFailingStatusReadIsUnknownHealthReadFailedAndTheRunStillCompletes() {
        executor.withMcDeviceHealth(enabled(gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_0_READ)));
        List<TextResponse> bad = List.of(new TextResponse(403, Optional.empty(), "", false), new TextResponse(404, Optional.empty(), "", false),
                new TextResponse(500, Optional.empty(), "boom", false), new TextResponse(200, Optional.empty(), BODY, true),
                new TextResponse(200, Optional.empty(), "<html>", false), new TextResponse(200, Optional.empty(), "{\"x\":1}", false));
        for (TextResponse reply : bad) {
            calls.replies.put(STATUS_PATH, reply);
            var done = inventory();
            assertEquals(2, done.members().size());
            done.members().forEach(m -> assertEquals(List.of("UNKNOWN", "HEALTH_READ_FAILED"), List.of(m.health().orElseThrow().state(), m.health().get().messages().get(0)), reply.toString()));
        }
        calls.replies.remove(STATUS_PATH);
        calls.failures.put(STATUS_PATH, new IOException("synthetic"));
        inventory().members().forEach(m -> assertEquals(List.of("HEALTH_READ_FAILED"), m.health().orElseThrow().messages()));
    }

    @Test
    void aDeviceAbsentFromTheAnswerIsUnknownNotInStatusAnswer() {
        calls.replies.put(STATUS_PATH, new TextResponse(200, Optional.empty(),
                "[{\"uuid\":\"" + UUID_SG + "\",\"monitorState\":\"OK\"}]", false));
        executor.withMcDeviceHealth(enabled(gateRows(SignOffState.SIGNED_OFF, ActionClass.CLASS_0_READ)));
        var health = byName(inventory());
        assertEquals(List.of("NOT_IN_STATUS_ANSWER"), health.get("MD-BRAVO-02").messages());
        assertEquals("UNKNOWN", health.get("MD-BRAVO-02").state());
        assertEquals("OK", health.get("FW-TANGO-04").state());
    }

    @Test
    void theSwitchAcceptsOnlyAffirmativeWords() {
        for (String on : List.of("1", "true", "TRUE", " on ", "yes")) {
            assertTrue(McDeviceHealthRead.from(Map.of("UI2_MC_DEVICE_HEALTH_READ", on)::get, NO_GATE).enabled(), on);
        }
        for (String off : List.of("0", "false", "off", "", "enabled-maybe")) {
            assertFalse(McDeviceHealthRead.from(Map.of("UI2_MC_DEVICE_HEALTH_READ", off)::get, NO_GATE).enabled(), off);
        }
        assertFalse(McDeviceHealthRead.from(k -> null, NO_GATE).enabled());
    }
}
