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
 * Lifecycle reads, group A, HTTPS vendors (PO 2026-11-10): Management Center license/version and Infoblox grid-wide
 * licenses. Switch off: the request sequence is unchanged. Switch on: exactly the gate literals, last, and the
 * inventory outcome is the same whatever the new read does.
 */
class LifecycleReadsHttpsTest {

    private static final Target T = new Target("192.0.2.30", 443);
    private static final String DEVICE = "dev-1";
    private static final String UUID_A = "A1055376-3448-4A94-82DB-D9BD30BBB421";
    private static final String UUID_B = "B2055376-3448-4A94-82DB-D9BD30BBB422";
    private static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String GRIDWIDE = "/wapi/v2.13.5/license:gridwide?_return_fields=type,expiry_date,expiration_status,limit";

    @TempDir
    Path tempDir;

    private Fake calls;
    private HttpsVendorExecutor executor;
    private List<String> lines;

    private static final class Fake implements HttpsDeviceCalls {
        final List<String> log = new ArrayList<>();
        final Map<String, TextResponse> replies = new HashMap<>();
        final Map<String, IOException> failures = new HashMap<>();
        String devices = "[{\"uuid\":\"not-a-uuid\",\"name\":\"X\",\"type\":\"sgos6x\"},{\"uuid\":\"" + UUID_A
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
    void managementCenterSwitchOffSendsOnlyTheExistingRequests() {
        inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30"), calls.log);
        assertTrue(lines.isEmpty());
        inventory("bluecoat_mc", LifecycleReadSwitch.from(k -> null));
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30"), calls.log);
        inventory("bluecoat_mc", LifecycleReadSwitch.from(Map.of(
                "UI2_LIFECYCLE_READS", "mc_device_license", "UI2_LIFECYCLE_READ_DEVICES", "dev-9")::get));
        assertEquals(2, calls.log.size(), "another device's switch sends nothing here");
    }

    @Test
    void managementCenterSendsTheVersionShapeAndOneLicenseByDefaultWithAValidatedUuid() {
        String off = inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        calls.replies.put("/api/devices/" + UUID_A + "/license", new TextResponse(200, Optional.of("application/json"),
                "{\"components\":[{\"componentName\":\"Acme" + "Proxy\",\"expirationDate\":\"2030-01-01\",\"serialNumber\":\"FTX" + "1234\"}]}", false));
        String onOutcome = inventory("bluecoat_mc", on("mc_device_license,mc_system_version", null));
        assertEquals(off, onOutcome);
        assertEquals(List.of("GET /api/devices 30", "GET /api/system/version 30", "GET /api/devices/" + UUID_A + "/license 60"), calls.log,
                "the invalid uuid is never put in a path; the version is measured from the existing read, not requested twice");
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).startsWith("[LIFECYCLE_MEASURE] kind=mc_system_version device=dev-1 outcome=OK "), lines.get(0));
        assertTrue(lines.get(0).endsWith("shape={name:string,version:string,buildNumber:number}"), lines.get(0));
        assertTrue(lines.get(1).contains("kind=mc_device_license") && lines.get(1).contains("shape={components[1]{componentName:string,expirationDate:string,serialNumber:string}}"), lines.get(1));
        String joined = String.join("\n", lines);
        assertFalse(joined.contains("Acme") || joined.contains("FTX") || joined.contains("mc.example") || joined.contains(UUID_A), joined);
    }

    @Test
    void managementCenterHonoursTheLicenseMaximum() {
        inventory("bluecoat_mc", on("mc_device_license", "2"));
        assertEquals(List.of("GET /api/devices/" + UUID_A + "/license 60", "GET /api/devices/" + UUID_B + "/license 60"),
                calls.log.subList(2, calls.log.size()));
        inventory("bluecoat_mc", on("mc_device_license", "0"));
        assertEquals(2, calls.log.size());
    }

    @Test
    void aFailingManagementCenterLicenseOrVersionReadNeverChangesTheOutcome() {
        String off = inventory("bluecoat_mc", LifecycleReadSwitch.OFF);
        String licensePath = "/api/devices/" + UUID_A + "/license";
        calls.failures.put(licensePath, new IOException("synthetic"));
        assertEquals(off, inventory("bluecoat_mc", on("mc_device_license", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:IOException"), lines.get(0));
        calls.failures.clear();
        calls.replies.put(licensePath, new TextResponse(500, Optional.empty(), "boom", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_device_license", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:Http500"), lines.get(0));
        calls.replies.put(licensePath, new TextResponse(200, Optional.empty(), "", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_device_license", null)));
        assertTrue(lines.get(0).contains("outcome=EMPTY"), lines.get(0));
        calls.replies.put(licensePath, new TextResponse(200, Optional.empty(), "<html>", false));
        assertEquals(off, inventory("bluecoat_mc", on("mc_device_license", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:JsonParseException"), lines.get(0));
        calls.replies.put(HttpsVendorPlan.MC_SYSTEM_VERSION, new TextResponse(503, Optional.empty(), "", false));
        inventory("bluecoat_mc", on("mc_system_version", null));
        assertTrue(lines.get(0).contains("kind=mc_system_version") && lines.get(0).contains("outcome=ERROR:Http503"), lines.get(0));
    }

    @Test
    void infobloxSwitchOffSendsNothingNewAndSwitchOnSendsTheGridwideLiteralLast() {
        String off = inventory("infoblox", LifecycleReadSwitch.OFF);
        List<String> offLog = new ArrayList<>(calls.log);
        assertTrue(offLog.stream().noneMatch(l -> l.contains("license:gridwide")));
        calls.replies.put(GRIDWIDE, new TextResponse(200, Optional.of("application/json"),
                "[{\"type\":\"DNS\",\"expiry_date\":1798761600,\"expiration_status\":\"NOT_EXPIRED\",\"limit\":\"100\"}]", false));
        String onOutcome = inventory("infoblox", on("infoblox_license_gridwide", null));
        assertEquals(off, onOutcome);
        assertEquals(offLog, calls.log.subList(0, offLog.size()));
        assertEquals(List.of("GET " + GRIDWIDE + " 60"), calls.log.subList(offLog.size(), calls.log.size()));
        assertFalse(calls.log.get(calls.log.size() - 1).contains("key"));
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("kind=infoblox_license_gridwide") && lines.get(0).contains("shape=[1]{type:string,expiry_date:number,expiration_status:string,limit:string}"), lines.get(0));
    }

    @Test
    void aFailingInfobloxGridwideReadNeverChangesTheOutcome() {
        String off = inventory("infoblox", LifecycleReadSwitch.OFF);
        calls.failures.put(GRIDWIDE, new IOException("synthetic"));
        assertEquals(off, inventory("infoblox", on("infoblox_license_gridwide", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:IOException"), lines.get(0));
        calls.failures.clear();
        calls.replies.put(GRIDWIDE, new TextResponse(400, Optional.empty(), "", false));
        assertEquals(off, inventory("infoblox", on("infoblox_license_gridwide", null)));
        assertTrue(lines.get(0).contains("outcome=ERROR:Http400"), lines.get(0));
    }

    @Test
    void theMcDeviceLicensePathRefusesAnythingButAnIdentifier() {
        assertEquals("/api/devices/" + UUID_A + "/license", HttpsVendorPlan.mcDeviceLicense(UUID_A));
        assertThrows(IllegalArgumentException.class, () -> HttpsVendorPlan.mcDeviceLicense("../../x"));
        assertThrows(IllegalArgumentException.class, () -> HttpsVendorPlan.mcDeviceLicense(null));
    }
}
