package com.securityexpert.nexus.ui2.worker.backup.https;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.securityexpert.nexus.ui2.persistence.artefact.FileArtefactStore;
import com.securityexpert.nexus.ui2.platform.ArtefactStoreCipher;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

class HttpsVendorExecutorTest {

    private static final Target T = new Target("192.0.2.30", 443);

    @TempDir
    Path tempDir;

    private Scripted calls;
    private HttpsVendorExecutor executor;

    @BeforeEach
    void setUp() {
        calls = new Scripted();
        executor = new HttpsVendorExecutor(calls,
                new FileArtefactStore(tempDir, ArtefactStoreCipher.fromBase64Key(Base64.getEncoder().encodeToString(new byte[32]))),
                ref -> new Credentials("user-" + ref, ("pw-" + ref).toCharArray()));
    }

    @Test
    void theWapiVersionIsTheSingleQuotedVersionLine() {
        assertEquals(Optional.of("2.13.5"), HttpsVendorPlan.parseWapiVersion("<script>var DOCUMENTATION_OPTIONS = {\n VERSION: '2.13.5',\n"));
        assertEquals(Optional.empty(), HttpsVendorPlan.parseWapiVersion("<html>no version</html>"));
    }

    @Test
    void theWapiVersionFallsBackToThePageTitleWhenTheVersionLineMoved() {
        // NIOS with WAPI 2.13.7 (2026-09-25): VERSION lives in _static/documentation_options.js, the title still names it.
        assertEquals(Optional.of("2.13.7"), HttpsVendorPlan.parseWapiVersion(
                "<html><head><title>Infoblox WAPI documentation &#8212; Infoblox WAPI 2.13.7 documentation</title></head>"));
        assertEquals(Optional.of("2.13.5"), HttpsVendorPlan.parseWapiVersion(
                "<title>Infoblox WAPI 2.13.7 documentation</title><script>VERSION: '2.13.5',</script>"), "the VERSION line wins");
    }

    @Test
    void infobloxBackupReadsTheVersionFetchesSameHostAndAlwaysSignalsDownloadComplete() {
        BackupResult r = executor.backup("infoblox", T, "cred", Optional.empty(), "dev", "job-1");
        assertTrue(r instanceof BackupResult.Completed, String.valueOf(r));
        assertTrue(calls.log.contains("POST /wapi/v2.13.5/fileop?_function=getgriddata {\"type\": \"BACKUP\"}"));
        assertTrue(calls.log.contains("GET-DL /http_direct_file_io/req_id-DOWNLOAD-1/database.bak content-type=application/force-download"),
                "the download names application/force-download (415 without it): " + calls.log);
        assertTrue(calls.log.stream().anyMatch(l -> l.startsWith("POST /wapi/v2.13.5/fileop?_function=downloadcomplete") && l.contains("tok-1")),
                "cleanup with the version read (Backbox sends /wapi/v/ and fails): " + calls.log);
    }

    @Test
    void infobloxRefusesAnOffHostDownloadUrlAndStillCleansUp() {
        calls.downloadHost = "198.51.100.9";
        BackupResult r = executor.backup("infoblox", T, "cred", Optional.empty(), "dev", "job-1");
        assertTrue(r instanceof BackupResult.SubmitRefused, String.valueOf(r));
        assertFalse(calls.log.stream().anyMatch(l -> l.startsWith("GET-DL")));
        assertTrue(calls.log.stream().anyMatch(l -> l.contains("downloadcomplete")));
    }

    @Test
    void radwareSendsTheMeasuredFormWithThePassphraseFromTheStoreAndRefusesWithoutOne() {
        BackupResult none = executor.backup("radware", T, "cred", Optional.empty(), "dev", "job-2");
        assertTrue(none instanceof BackupResult.CredentialUnresolvable, String.valueOf(none));
        assertTrue(calls.log.isEmpty(), "nothing sent without a passphrase");
        BackupResult r = executor.backup("radware", T, "cred", Optional.of("pass"), "dev", "job-2");
        assertTrue(r instanceof BackupResult.Completed, String.valueOf(r));
        assertEquals("POST-DL /dynamic/File/Configuration/ReceivefromDevice {DownloadFormat=cli, IncludePKeys=on, passphrase=pw-pass}",
                calls.log.get(0));
    }

    @Test
    void infobloxConfirmNamesTheGridAndTheWapiVersion() {
        HttpsVendorExecutor.ConfirmOutcome c = executor.confirm("infoblox", T, "cred");
        assertTrue(c instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed, String.valueOf(c));
        HttpsVendorExecutor.Identity id = ((HttpsVendorExecutor.ConfirmOutcome.Confirmed) c).identity();
        assertEquals(Optional.of("GRID-A"), id.name());
        assertEquals(Optional.of("WAPI 2.13.5"), id.version());
    }

    @Test
    void sameHostPathRefusesAnotherHostOrPort() {
        assertEquals(Optional.of("/a/b?x=1"), HttpsDeviceClient.sameHostPath(T, "https://192.0.2.30/a/b?x=1"));
        assertEquals(Optional.empty(), HttpsDeviceClient.sameHostPath(T, "https://192.0.2.31/a"));
        assertEquals(Optional.empty(), HttpsDeviceClient.sameHostPath(T, "https://192.0.2.30:8443/a"));
        assertEquals(Optional.empty(), HttpsDeviceClient.sameHostPath(T, "http://192.0.2.30/a"));
    }

    @Test
    void aListedDefenseProIsFetchedThroughTheCyberControllerWithKeysAndNothingSavedThere() {
        Optional<BackupResult> r = executor.backupViaCyberController(T, "cc", "192.0.2.41", Optional.of("pp"), "dev", "job-cc");
        assertTrue(r.isPresent() && r.get() instanceof BackupResult.Completed, String.valueOf(r));
        assertTrue(calls.log.get(0).startsWith("LOGIN /mgmt/system/user/login {"), calls.log.toString());
        assertTrue(calls.log.contains("GET /mgmt/system/config/itemlist/alldevices"));
        assertTrue(calls.log.contains("GET-DL /mgmt/device/byip/192.0.2.41/config/getcfg?saveToDb=false&includePrivateKeys=true&passphrase=pw-pp"),
                calls.log.toString());
        assertEquals("POST /mgmt/system/user/logout {}", calls.log.get(calls.log.size() - 1));
    }

    @Test
    void aDefenseProTheCyberControllerDoesNotListFallsBackAndTheSessionIsStillClosed() {
        Optional<BackupResult> r = executor.backupViaCyberController(T, "cc", "192.0.2.99", Optional.of("pp"), "dev", "job-cc");
        assertTrue(r.isEmpty());
        assertTrue(calls.log.stream().noneMatch(l -> l.contains("getcfg")));
        assertTrue(calls.log.get(calls.log.size() - 1).startsWith("POST /mgmt/system/user/logout"));
    }

    @Test
    void aDefenseProTheCyberControllerListsIsConfirmedAndLabelledAsManagementPlaneEvidence() {
        Optional<HttpsVendorExecutor.ConfirmOutcome> listed = executor.confirmViaCyberController(T, "cc", "192.0.2.41");
        assertTrue(listed.isPresent() && listed.get() instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed c
                && c.identity().model().orElse("").contains("listed by Cyber Controller"), String.valueOf(listed));
        assertTrue(executor.confirmViaCyberController(T, "cc", "192.0.2.99").isEmpty());
        calls.loginStatus = 401;
        assertTrue(executor.confirmViaCyberController(T, "cc", "192.0.2.41").isEmpty(), "an unusable Cyber Controller falls back to the device");
    }

    @Test
    void aRefusedCyberControllerLoginCallsNothingElse() {
        calls.loginStatus = 401;
        assertTrue(executor.confirm("radware_cyber_controller", T, "cc") instanceof HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed);
        assertEquals(1, calls.log.size());
    }

    @Test
    void theDeviceListMatchIsAWholeValueNeverAPrefix() throws Exception {
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree("[{\"ip\": \"192.0.2.410\"}]");
        assertFalse(HttpsVendorPlan.listsAddress(node, "192.0.2.41"));
        assertTrue(HttpsVendorPlan.listsAddress(node, "192.0.2.410"));
    }

    private static final class Scripted implements HttpsDeviceCalls {
        final List<String> log = new ArrayList<>();
        String downloadHost = "192.0.2.30";
        int loginStatus = 200;
        String deviceList = "{\"DefensePro Devices\": [{\"name\": \"DP-A\", \"managementIp\": \"192.0.2.41\"}]}";

        @Override
        public HttpsDeviceClient.SessionLogin login(Target target, String path, String json, Duration timeout) {
            log.add("LOGIN " + path + " " + json);
            return new HttpsDeviceClient.SessionLogin(loginStatus, loginStatus == 200 ? Optional.of("JSESSIONID=s1") : Optional.empty());
        }

        @Override
        public TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) {
            log.add("GET " + path);
            if (path.equals("/wapidoc/")) {
                return new TextResponse(200, Optional.of("text/html"), "var DOCUMENTATION_OPTIONS = {\n VERSION: '2.13.5',\n", false);
            }
            if (path.equals(HttpsVendorPlan.CC_ALLDEVICES)) {
                return new TextResponse(200, Optional.of("application/json"), deviceList, false);
            }
            if (path.startsWith("/wapi/v2.13.5/grid")) {
                return new TextResponse(200, Optional.of("application/json"), "[{\"_ref\": \"grid/x\", \"name\": \"GRID-A\"}]", false);
            }
            return new TextResponse(200, Optional.of("text/html"), "<html/>", false);
        }

        @Override
        public TextResponse postJson(Target target, String path, String json, Credentials creds, Duration timeout, int maxBytes) {
            log.add("POST " + path + " " + json);
            if (path.contains("getgriddata")) {
                return new TextResponse(201, Optional.of("application/json"), "{\"token\": \"tok-1\", \"url\": \"https://" + downloadHost
                        + "/http_direct_file_io/req_id-DOWNLOAD-1/database.bak\"}", false);
            }
            return new TextResponse(200, Optional.of("application/json"), "{}", false);
        }

        @Override
        public DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
                OutputStream sink, long maxBytes, Duration timeout) {
            return download(target, method, path, form, creds, null, sink, maxBytes, timeout);
        }

        @Override
        public DownloadResult download(Target target, String method, String path, Map<String, String> form, Credentials creds,
                String requestContentType, OutputStream sink, long maxBytes, Duration timeout) {
            log.add(method + "-DL " + path + (form == null ? "" : " " + form)
                    + (requestContentType == null ? "" : " content-type=" + requestContentType));
            try {
                byte[] body = new byte[2048];
                body[0] = 0x1f;
                body[1] = (byte) 0x8b;
                sink.write(body);
                return new DownloadResult.Downloaded(200, body.length, Optional.of("application/octet-stream"));
            } catch (IOException e) {
                return new DownloadResult.Failed(e.getMessage());
            }
        }
    }
}
