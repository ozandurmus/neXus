package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.inventory.LifecycleReadSwitch;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * Lifecycle reads, group B, HTTPS vendors (PO 2026-11-10): ProxySG {@code show licenses} through the Management Center and the
 * Infoblox {@code upgradestatus} grid read. Switch off: the request sequence is unchanged. Switch on: exactly the gate literals,
 * last, and the inventory outcome is the same whatever the new read does.
 */
class LifecycleReadsHttpsGroupBTest {

    private static final Target T = new Target("192.0.2.30", 443);
    private static final String DEVICE = "dev-1";
    private static final String UUID_A = "A1055376-3448-4A94-82DB-D9BD30BBB421";
    private static final String UUID_B = "B2055376-3448-4A94-82DB-D9BD30BBB422";
    private static final String UUID_CLOUD = "C3055376-3448-4A94-82DB-D9BD30BBB423";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String UPGRADE = "/wapi/v2.13.5/upgradestatus?type=GRID";
    private static final String SHOW_LICENSES_A = "PUT /api/devices/" + UUID_A + "/command show licenses 120";
    private static final String SHOW_LICENSES_B = "PUT /api/devices/" + UUID_B + "/command show licenses 120";

    @TempDir
    Path tempDir;

    private Fake calls;
    private HttpsVendorExecutor executor;
    private List<String> lines;

    private static final class Fake implements HttpsDeviceCalls {
        final List<String> log = new ArrayList<>();
        final Map<String, TextResponse> replies = new HashMap<>();
        final Map<String, IOException> failures = new HashMap<>();
        final Map<String, TextResponse> puts = new HashMap<>();
        final Map<String, IOException> putFailures = new HashMap<>();
        boolean commandAnswersAreTruncated;
        String devices = "[{\"uuid\":\"not-a-uuid\",\"name\":\"X\",\"type\":\"sgos6x\"},{\"uuid\":\"" + UUID_CLOUD
                + "\",\"name\":\"WSS\",\"type\":\"cp\"},{\"uuid\":\"" + UUID_A
                + "\",\"name\":\"SG-A\",\"type\":\"sgos6x\"},{\"uuid\":\"" + UUID_B + "\",\"name\":\"SG-B\",\"type\":\"sgos6x\"}]";

        @Override
        public TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) throws IOException {
            log.add("GET " + path + " " + timeout.toSeconds());
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
            if (path.equals("/wapidoc/")) {
                return new TextResponse(200, Optional.of("text/html"), "VERSION: '2.13.5',", false);
            }
            if (path.startsWith("/wapi/v2.13.5/grid")) {
                return new TextResponse(200, Optional.of("application/json"), "[{\"name\":\"GRID-A\"}]", false);
            }
            if (path.startsWith("/wapi/v2.13.5/member?")) {
                return new TextResponse(200, Optional.of("application/json"), "[]", false);
            }
            if (path.startsWith("/wapi/v2.13.5/")) {
                return new TextResponse(200, Optional.of("application/json"), "{\"result\":[]}", false);
            }
            return new TextResponse(404, Optional.empty(), "", false);
        }

        @Override
        public TextResponse putRaw(Target target, String path, String body, Credentials creds, Duration timeout, int maxBytes) throws IOException {
            log.add("PUT " + path + " " + body + " " + timeout.toSeconds());
            if (putFailures.containsKey(path)) {
                throw putFailures.get(path);
            }
            TextResponse scripted = puts.get(path);
            if (scripted != null) {
                return scripted;
            }
            return new TextResponse(200, Optional.of("application/json"), "{\"reply\":\"License: Valid\\nExpiration: 12 Nov 2030\","
                    + "\"messages\":[],\"status\":\"SUCCESS\"}", commandAnswersAreTruncated);
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
        lines = new ArrayList<>();
        executor = new HttpsVendorExecutor(calls,
                new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))),
                ref -> new Credentials("user", "pw".toCharArray())).withLifecycleSink(lines::add);
    }

    private static LifecycleReadSwitch on(String kinds, String max) {
        Map<String, String> env = new HashMap<>(Map.of("UI2_LIFECYCLE_READS", kinds, "UI2_LIFECYCLE_READ_DEVICES", DEVICE));
        if (max != null) {
            env.put("UI2_LIFECYCLE_MC_LICENSE_MAX", max);
        }
        return LifecycleReadSwitch.from(env::get);
    }

    private String inventory(String vendor, LifecycleReadSwitch sw) {
        calls.log.clear();
        lines.clear();
        executor.withLifecycleReads(sw);
        var outcome = executor.inventory(vendor, T, "cred", DEVICE);
        assertTrue(outcome instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(outcome));
        return outcome.toString().replaceAll("(?i)" + UUID_RE, "ID");
    }

    @Test
    void theLiteralsAreTheGateKeys() {
        assertEquals("show licenses", HttpsVendorPlan.MC_CMD_SHOW_LICENSES);
        assertEquals("/wapi/v%s/upgradestatus?type=GRID", HttpsVendorPlan.INFOBLOX_UPGRADESTATUS_GRID);
        assertTrue(HttpsVendorPlan.INFOBLOX_GATE_KEYS.contains("GET /wapi/v<ver>/upgradestatus?type=GRID"));
    }

    @Test
    void managementCenterSwitchOffSendsOnlyTheExistingRequests() {
        inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30"), calls.log);
        assertTrue(lines.isEmpty());
        inventory("bluecoat_mc", LifecycleReadSwitch.from(k -> null));
        assertEquals(2, calls.log.size());
        inventory("bluecoat_mc", LifecycleReadSwitch.from(Map.of(
                "UI2_LIFECYCLE_READS", "mc_show_licenses", "UI2_LIFECYCLE_READ_DEVICES", "dev-9")::get));
        assertEquals(2, calls.log.size(), "another device's switch sends nothing here");
        assertTrue(lines.isEmpty());
    }

    @Test
    void managementCenterSendsShowLicensesToOneProxySgByDefaultWithAValidatedUuidAfterEveryOtherRead() {
        String off = inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        String onOutcome = inventory("bluecoat_mc", on("mc_show_licenses", null));
        assertEquals(off, onOutcome);
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30", SHOW_LICENSES_A), calls.log,
                "only ProxySGs (sgos6x), only a valid uuid, the body is exactly the literal, 120 s");
        assertEquals(1, lines.size());
        String line = lines.get(0);
        assertTrue(line.startsWith("[LIFECYCLE_MEASURE] kind=mc_show_licenses device=dev-1 outcome=OK "), line);
        assertTrue(line.contains("shape=status=SUCCESS envelope={reply:string,messages[0],status:string} reply=License: Valid // Expiration: 9 Nov 9"), line);
        for (String secret : List.of("mc.example", UUID_A, "SG-A", "192.0.2", "pw")) {
            assertFalse(line.contains(secret), secret + " leaked: " + line);
        }
    }

    @Test
    void managementCenterHonoursTheLicenseMaximumAndNeverReadsAFailedOrNonProxySgTwice() {
        inventory("bluecoat_mc", on("mc_show_licenses", "2"));
        assertEquals(List.of(SHOW_LICENSES_A, SHOW_LICENSES_B), calls.log.subList(2, calls.log.size()));
        assertEquals(2, lines.size());
        inventory("bluecoat_mc", on("mc_show_licenses", "9"));
        assertEquals(2, calls.log.stream().filter(l -> l.startsWith("PUT ")).count(), "never the Cloud (cp) device nor the bad uuid");
        inventory("bluecoat_mc", on("mc_show_licenses", "0"));
        assertEquals(2, calls.log.size());
        assertTrue(lines.isEmpty());
    }

    @Test
    void bothMcKindsTogetherSendTheGroupALicenseGetFirstAndTheCommandLast() {
        inventory("bluecoat_mc", on("mc_device_license,mc_show_licenses", null));
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30", "GET /api/devices/" + UUID_CLOUD + "/license 60", SHOW_LICENSES_A),
                calls.log);
    }

    @Test
    void aFailingShowLicensesReadNeverChangesTheOutcome() {
        String off = inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        String path = "/api/devices/" + UUID_A + "/command";
        calls.putFailures.put(path, new IOException("synthetic"));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:IOException"), lines.get(0));
        calls.putFailures.put(path, new java.net.http.HttpTimeoutException("synthetic"));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:HttpTimeoutException"), lines.get(0));
        calls.putFailures.clear();
        calls.puts.put(path, new TextResponse(403, Optional.empty(), "denied", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:Http403"), lines.get(0));
        calls.puts.put(path, new TextResponse(200, Optional.empty(), "", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=EMPTY"), lines.get(0));
        calls.puts.put(path, new TextResponse(200, Optional.empty(), "<html>", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:JsonParseException"), lines.get(0));
        calls.puts.put(path, new TextResponse(200, Optional.empty(), "{\"reply\":\"\",\"messages\":[\"% Invalid input\"],\"status\":\"FAILURE\"}", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("status=FAILURE ") && lines.get(0).endsWith("reply=-"), lines.get(0));
        calls.puts.clear();
        calls.commandAnswersAreTruncated = true;
        assertEquals(off, inventory("bluecoat_mc", on("mc_show_licenses", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:Truncated"), lines.get(0));
    }

    @Test
    void infobloxSwitchOffSendsNothingNewAndSwitchOnSendsTheUpgradeStatusLiteralLast() {
        String off = inventory("infoblox", LifecycleReadSwitch.OFF);
        List<String> offLog = new ArrayList<>(calls.log);
        assertTrue(offLog.stream().noneMatch(l -> l.contains("upgradestatus")));
        calls.replies.put(UPGRADE, new TextResponse(200, Optional.of("application/json"),
                "[{\"current_version\":\"9.0.4\",\"type\":\"GRID\",\"upgrade_state\":\"NONE\",\"member\":\"gm.corp.example\"}]", false));
        String onOutcome = inventory("infoblox", on("infoblox_upgradestatus_grid", null));
        assertEquals(off, onOutcome);
        assertEquals(offLog, calls.log.subList(0, offLog.size()));
        assertEquals(List.of("GET " + UPGRADE + " 60"), calls.log.subList(offLog.size(), calls.log.size()));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("kind=infoblox_upgradestatus_grid")
                && lines.get(0).contains("shape=[1]{current_version:string,type:string,upgrade_state:string,member:string}"), lines.get(0));
        assertFalse(lines.get(0).contains("gm.corp") || lines.get(0).contains("9.0.4"), lines.get(0));
    }

    @Test
    void bothInfobloxLicenseAndUpgradeStatusAreSentLastInThatOrder() {
        inventory("infoblox", on("infoblox_license_gridwide,infoblox_upgradestatus_grid", null));
        int n = calls.log.size();
        assertTrue(calls.log.get(n - 2).contains("license:gridwide") && calls.log.get(n - 1).equals("GET " + UPGRADE + " 60"), calls.log.toString());
    }

    @Test
    void aFailingInfobloxUpgradeStatusReadNeverChangesTheOutcome() {
        String off = inventory("infoblox", LifecycleReadSwitch.OFF);
        calls.failures.put(UPGRADE, new IOException("synthetic"));
        assertEquals(off, inventory("infoblox", on("infoblox_upgradestatus_grid", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:IOException"), lines.get(0));
        calls.failures.clear();
        calls.replies.put(UPGRADE, new TextResponse(400, Optional.empty(), "", false));
        assertEquals(off, inventory("infoblox", on("infoblox_upgradestatus_grid", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:Http400"), lines.get(0));
        calls.replies.put(UPGRADE, new TextResponse(200, Optional.empty(), "", false));
        assertEquals(off, inventory("infoblox", on("infoblox_upgradestatus_grid", null)));
        assertTrue(lines.get(0).contains("outcome=EMPTY"), lines.get(0));
    }
}
