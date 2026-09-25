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
        assertTrue(calls.log.stream().noneMatch(l -> l.contains("/member?")), "PO 2026-09-25: a backup only backs up: " + calls.log);
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

    /** Shape measured on the production grid 2026-09-25 (WAPI 2.13.7), values fictional. */
    static final String MEMBERS_JSON = "["
            + "{\"_ref\": \"member/a\", \"host_name\": \"ns2.example\", \"platform\": \"VNIOS\", \"master_candidate\": false, \"enable_ha\": false,"
            + " \"vip_setting\": {\"address\": \"192.0.2.12\", \"subnet_mask\": \"255.255.255.0\", \"gateway\": \"192.0.2.1\"},"
            + " \"lan2_enabled\": true, \"lan2_port_setting\": {\"enabled\": true, \"network_setting\": {\"address\": \"10.1.0.12\", \"subnet_mask\": \"255.255.255.0\"}},"
            + " \"additional_ip_list\": [{\"interface\": \"LOOPBACK\", \"ipv4_network_setting\": {\"address\": \"192.0.2.200\", \"subnet_mask\": \"255.255.255.255\"}}],"
            + " \"static_routes\": [{\"address\": \"10.9.0.0\", \"subnet_mask\": \"255.255.0.0\", \"gateway\": \"10.0.0.1\"}],"
            + " \"node_info\": [{\"ha_status\": \"NOT_CONFIGURED\", \"hwtype\": \"IB-V2326\", \"hwmodel\": \"\", \"hypervisor\": \"VMware\","
            + "   \"mgmt_network_setting\": {\"address\": \"10.0.0.12\", \"subnet_mask\": \"255.255.255.0\", \"gateway\": \"10.0.0.1\"}, \"service_status\": ["
            + "   {\"service\": \"NODE_STATUS\", \"status\": \"WORKING\", \"description\": \"Running\"},"
            + "   {\"service\": \"DISK_USAGE\", \"status\": \"WORKING\", \"description\": \"24% - Primary drive usage is OK.\"},"
            + "   {\"service\": \"MEMORY\", \"status\": \"WORKING\", \"description\": \"38% - System memory usage is OK.\"},"
            + "   {\"service\": \"CPU_USAGE\", \"status\": \"WORKING\", \"description\": \"CPU Usage: 37%\"},"
            + "   {\"service\": \"DB_OBJECT\", \"status\": \"WORKING\", \"description\": \"0% - Database capacity usage is OK.\"},"
            + "   {\"service\": \"REPLICATION\", \"status\": \"WORKING\", \"description\": \"Online\"},"
            + "   {\"service\": \"ENET_LAN\", \"status\": \"WORKING\", \"description\": \"192.0.2.12\"}]}],"
            + " \"service_status\": [{\"service\": \"DNS\", \"status\": \"WORKING\", \"description\": \"DNS Service is working\"},"
            + "   {\"service\": \"DHCP\", \"status\": \"INACTIVE\", \"description\": \"DHCP Service is inactive\"},"
            + "   {\"service\": \"ATP\", \"status\": \"WARNING\", \"description\": \"monitoring mode\"}]},"
            + "{\"_ref\": \"member/b\", \"host_name\": \"gm.example\", \"platform\": \"VNIOS\", \"master_candidate\": true, \"enable_ha\": false,"
            + " \"vip_setting\": {\"address\": \"192.0.2.30\"}, \"node_info\": [{\"ha_status\": \"NOT_CONFIGURED\", \"hwtype\": \"IB-V1516\", \"hypervisor\": \"VMware\"}],"
            + " \"service_status\": [{\"service\": \"DNS\", \"status\": \"WORKING\"}]},"
            + "{\"_ref\": \"member/c\", \"platform\": \"VNIOS\"}]";

    @Test
    void infobloxConfirmNamesTheGridAndTheWapiVersion() {
        HttpsVendorExecutor.ConfirmOutcome c = executor.confirm("infoblox", T, "cred");
        assertTrue(c instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed, String.valueOf(c));
        HttpsVendorExecutor.Identity id = ((HttpsVendorExecutor.ConfirmOutcome.Confirmed) c).identity();
        assertEquals(Optional.of("GRID-A"), id.name());
        assertEquals(Optional.of("WAPI 2.13.5"), id.version());
        assertTrue(id.members().isEmpty(), "PO 2026-09-25: the confirm establishes identity only; members are the inventory job's");
        assertTrue(calls.log.stream().noneMatch(l -> l.contains("/member?")), "no member read on confirm: " + calls.log);
    }

    @Test
    void infobloxInventoryListsMembersWithFactsInterfacesAndStaticRoutes() {
        HttpsVendorExecutor.InventoryOutcome o = executor.inventory("infoblox", T, "cred");
        assertTrue(o instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(o));
        var done = (HttpsVendorExecutor.InventoryOutcome.Completed) o;
        assertEquals(List.of("gm.example", "ns2.example"), done.members().stream().map(m -> m.hostName()).toList(),
                "the Grid Master first, then candidates, then by name; a nameless entry skipped");
        var gm = done.members().get(0);
        assertTrue(gm.gridMaster(), "the member whose VIP is the dialled address is the Grid Master");
        assertTrue(gm.masterCandidate());
        assertEquals(Optional.of("IB-V1516"), gm.hardwareType());
        var ns2 = done.members().get(1);
        assertEquals(Optional.of(24), ns2.diskPercent());
        assertEquals(Optional.of(38), ns2.memoryPercent());
        assertEquals(Optional.of(37), ns2.cpuPercent());
        assertEquals(Optional.of(0), ns2.dbPercent());
        assertEquals(Optional.of("Online"), ns2.replication());
        assertEquals(Optional.of("WORKING"), ns2.nodeStatus());
        assertEquals(Optional.of("VMware"), ns2.hypervisor());
        assertEquals(Optional.of("NOT_CONFIGURED"), ns2.haStatus());
        assertEquals(List.of("DNS=WORKING", "DHCP=INACTIVE", "ATP=WARNING"),
                ns2.services().stream().map(sv -> sv.service() + "=" + sv.status()).toList());
        assertEquals(Optional.of("gm.example, ns2.example"), done.virtualSystems());
        assertEquals(Optional.of("GRID-A"), done.identity().name(), "the grid's own name, never the Grid Master's host name");
        var ns2Ctx = done.contexts().stream().filter(c -> c.context().equals("ns2.example")).findFirst().orElseThrow();
        assertEquals(List.of("LAN1", "MGMT", "LAN2", "LOOPBACK-1"), ns2Ctx.interfaces().stream().map(i -> i.name()).toList());
        assertEquals("192.0.2.12/24", ns2Ctx.interfaces().get(0).addresses().get(0).address());
        assertEquals("10.0.0.12/24", ns2Ctx.interfaces().get(1).addresses().get(0).address());
        assertEquals("10.1.0.12/24", ns2Ctx.interfaces().get(2).addresses().get(0).address());
        assertEquals("192.0.2.200/32", ns2Ctx.interfaces().get(3).addresses().get(0).address());
        assertEquals(1, ns2Ctx.routes().size());
        assertEquals("10.9.0.0/16", ns2Ctx.routes().get(0).destination());
        assertEquals(Optional.of("10.0.0.1"), ns2Ctx.routes().get(0).nextHop());
        assertEquals("static", ns2Ctx.routes().get(0).protocol());
        var gmCtx = done.contexts().stream().filter(c -> c.context().equals("gm.example")).findFirst().orElseThrow();
        assertEquals(List.of("LAN1"), gmCtx.interfaces().stream().map(i -> i.name()).toList(), "no MGMT/LAN2 on this member");
        assertTrue(calls.log.stream().anyMatch(l -> l.startsWith("GET /wapi/v2.13.5/member?_return_fields=host_name,platform,master_candidate")
                && l.contains("static_routes")), String.valueOf(calls.log));
    }

    @Test
    void cyberControllerInventoryNamesTheControllerAndItsManagedDevices() {
        calls.deviceList = "[{\"name\": \"CC-MAIN\", \"managementIp\": \"192.0.2.30\", \"type\": \"CyberController\", \"treeType\": \"ROOT\","
                + " \"deviceVersion\": \"10.13.0\", \"ormId\": \"1\", \"parentOrmId\": null, \"children\": ["
                + "  {\"name\": \"Site A\", \"type\": \"Site\", \"treeType\": \"SITE\", \"ormId\": \"2\", \"parentOrmId\": \"1\", \"children\": ["
                + "    {\"name\": \"DP-B\", \"managementIp\": \"192.0.2.42\", \"type\": \"DefensePro\", \"treeType\": \"DEVICE\", \"deviceVersion\": \"8.32\"},"
                + "    {\"name\": \"DP-A\", \"managementIp\": \"192.0.2.41\", \"type\": \"DefensePro\", \"treeType\": \"DEVICE\", \"deviceVersion\": \"8.32\"},"
                + "    {\"name\": \"DP-OLD\", \"managementIp\": \"192.0.2.43\", \"type\": \"DefensePro\", \"treeType\": \"DEVICE\", \"deleted\": true}]}]}]";
        HttpsVendorExecutor.InventoryOutcome o = executor.inventory("radware_cyber_controller", T, "cred");
        assertTrue(o instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(o));
        var done = (HttpsVendorExecutor.InventoryOutcome.Completed) o;
        assertEquals(Optional.of("CC-MAIN"), done.identity().name(), "the node whose address is the one dialled is the controller");
        assertEquals(Optional.of("10.13.0"), done.identity().version());
        assertEquals(Optional.of("DP-A, DP-B"), done.virtualSystems(), "managed devices by name, sorted; sites and deleted entries skipped");
        assertTrue(calls.log.stream().anyMatch(l -> l.startsWith("POST " + HttpsVendorPlan.CC_LOGOUT)), "always logs out: " + calls.log);
    }

    @Test
    void cyberControllerNotInItsOwnListIsNamedByItsCertificate() {
        // Measured 2026-09-25: alldevices lists the DefensePros only; the controller's TLS certificate carries its name.
        calls.deviceList = "[{\"name\": \"DP-A\", \"managementIp\": \"192.0.2.41\", \"type\": \"DefensePro\"}]";
        HttpsVendorExecutor.InventoryOutcome o = executor.inventory("radware_cyber_controller", T, "cred");
        var done = (HttpsVendorExecutor.InventoryOutcome.Completed) o;
        assertEquals(Optional.of("cc.example"), done.identity().name());
        assertEquals(Optional.of("DP-A"), done.virtualSystems());
    }

    @Test
    void aDefenseProIsInventoriedFromItsCyberControllerEntry() {
        calls.deviceList = "[{\"name\": \"DP-A\", \"managementIp\": \"192.0.2.41\", \"type\": \"DefensePro\", \"deviceVersion\": \"8.32.1\","
                + " \"formFactor\": \"x4420\", \"status\": \"UP\"}]";
        HttpsVendorExecutor.InventoryOutcome o = executor.inventoryDefenseProViaCyberController(T, "cred", "192.0.2.41");
        assertTrue(o instanceof HttpsVendorExecutor.InventoryOutcome.Completed, String.valueOf(o));
        var id = ((HttpsVendorExecutor.InventoryOutcome.Completed) o).identity();
        assertEquals(Optional.of("DP-A"), id.name());
        assertEquals(Optional.of("8.32.1"), id.version());
        assertEquals(Optional.of("Radware DefensePro x4420"), id.model());
        assertTrue(executor.inventoryDefenseProViaCyberController(T, "cred", "192.0.2.99") instanceof HttpsVendorExecutor.InventoryOutcome.Failed,
                "an address the controller does not list fails honestly");
    }

    @Test
    void aManagementCenterIsConfirmedAndInventoriedFromItsDeviceList() {
        assertTrue(executor.confirm("bluecoat_mc", T, "cred") instanceof HttpsVendorExecutor.ConfirmOutcome.Confirmed);
        var o = (HttpsVendorExecutor.InventoryOutcome.Completed) executor.inventory("bluecoat_mc", T, "cred");
        assertEquals(Optional.of("Reporter, SG-A, SG-B"), o.virtualSystems());
        assertEquals(Optional.of("Symantec Management Center"), o.identity().model());
    }

    @Test
    void cidrFromAddressAndDottedMask() {
        assertEquals(Optional.of("10.0.0.12/24"), InfobloxMembers.cidr(Optional.of("10.0.0.12"), Optional.of("255.255.255.0")));
        assertEquals(Optional.of("10.9.0.0/16"), InfobloxMembers.cidr(Optional.of("10.9.0.0"), Optional.of("255.255.0.0")));
        assertEquals(Optional.of("192.0.2.200/32"), InfobloxMembers.cidr(Optional.of("192.0.2.200"), Optional.empty()));
        assertEquals(Optional.empty(), InfobloxMembers.cidr(Optional.of("10.0.0.1"), Optional.of("bad")));
        assertEquals(Optional.empty(), InfobloxMembers.cidr(Optional.empty(), Optional.of("255.255.255.0")));
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
            return new HttpsDeviceClient.SessionLogin(loginStatus, loginStatus == 200 ? Optional.of("JSESSIONID=s1") : Optional.empty(),
                    Optional.of("cc.example"));
        }

        @Override
        public TextResponse get(Target target, String path, Credentials creds, Duration timeout, int maxBytes) {
            log.add("GET " + path);
            if (path.equals("/wapidoc/")) {
                return new TextResponse(200, Optional.of("text/html"), "var DOCUMENTATION_OPTIONS = {\n VERSION: '2.13.5',\n", false);
            }
            if (path.equals(HttpsVendorPlan.MC_DEVICES)) {
                return new TextResponse(200, Optional.of("application/json"),
                        "[{\"uuid\": \"u1\", \"name\": \"SG-B\", \"type\": \"SG\", \"osVersion\": \"7.4.15.1\"},"
                        + " {\"uuid\": \"u2\", \"name\": \"SG-A\", \"type\": \"SG\"}, {\"uuid\": \"u3\", \"name\": \"Reporter\", \"type\": \"REPORTER\"}]", false);
            }
            if (path.equals(HttpsVendorPlan.CC_ALLDEVICES)) {
                return new TextResponse(200, Optional.of("application/json"), deviceList, false);
            }
            if (path.startsWith("/wapi/v2.13.5/grid")) {
                return new TextResponse(200, Optional.of("application/json"), "[{\"_ref\": \"grid/x\", \"name\": \"GRID-A\"}]", false);
            }
            if (path.startsWith("/wapi/v2.13.5/member")) {
                return new TextResponse(200, Optional.of("application/json"), MEMBERS_JSON, false);
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
