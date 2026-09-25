package com.securityexpert.nexus.ui2.worker.backup.https;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.artefact.ArtefactStore;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;
import com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext;
import com.securityexpert.nexus.ui2.worker.backup.BackupResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.DownloadResult;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * Confirm and backup for vendors reached over HTTPS: Infoblox Grid Manager (WAPI) and Radware DefensePro
 * (VENDOR_BACKUP_CONTRACTS_2026_09_22.md §1 and §4, measurements addendum 2026-09-24). Nothing the device answered is
 * persisted outside the artefact; credentials and the Radware export passphrase come from the credential store.
 */
public final class HttpsVendorExecutor {

    private static final System.Logger LOG = System.getLogger(HttpsVendorExecutor.class.getName());
    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration LONG = Duration.ofSeconds(600);
    private static final int TEXT_MAX = 1024 * 1024;
    private static final long MAX_ARCHIVE = 20L * 1024 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    /** The identity a confirm read returns; every field optional. */
    /** {@code members}: the grid members an Infoblox Grid Manager lists (facts, ordered); empty for every other vendor. */
    public record Identity(Optional<String> name, Optional<String> model, Optional<String> version, List<GridMember> members) {
        public Identity {
            members = members == null ? List.of() : List.copyOf(members);
        }

        public Identity(Optional<String> name, Optional<String> model, Optional<String> version) {
            this(name, model, version, List.of());
        }

    }

    public sealed interface ConfirmOutcome {
        record Confirmed(Identity identity) implements ConfirmOutcome {
        }

        record AuthenticationFailed(String reason) implements ConfirmOutcome {
        }

        record Failed(String reason) implements ConfirmOutcome {
        }
    }

    private final com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls client;
    private final ArtefactStore artefactStore;
    private final Function<String, Credentials> credentials;

    public HttpsVendorExecutor(com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls client, ArtefactStore artefactStore, Function<String, Credentials> credentials) {
        this.client = Objects.requireNonNull(client, "client");
        this.artefactStore = Objects.requireNonNull(artefactStore, "artefactStore");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
    }

    /** Cisco ASA runs over SSH, but through the same confirm / inventory / backup jobs (docs/design/CISCO_ASA_CONTRACT.md). */
    private com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor ciscoAsa;

    public HttpsVendorExecutor withCiscoAsa(com.securityexpert.nexus.ui2.worker.backup.asa.CiscoAsaExecutor executor) {
        this.ciscoAsa = executor;
        return this;
    }

    private static final String CISCO = "cisco_asa";

    /** Fortinet (FORTINET_CONTRACT.md): FortiGate over SSH, FortiManager over JSON-RPC -- the same vendor jobs. */
    private com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor fortiGate;
    private com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor fortiManager;

    public HttpsVendorExecutor withFortinet(com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiGateExecutor gate,
            com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor manager) {
        this.fortiGate = gate;
        this.fortiManager = manager;
        return this;
    }

    private static final String FORTIGATE = "fortinet";
    private static final String FORTIMANAGER = "fortimanager";

    /** The FortiManager executor over this executor's own HTTPS client and credential resolver. */
    public com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor newFortiManagerExecutor() {
        return new com.securityexpert.nexus.ui2.worker.backup.fortinet.FortiManagerExecutor(client, credentials);
    }


    // ------------------------------------------------------------------------------------------------ confirm

    public ConfirmOutcome confirm(String vendor, Target target, String credentialRef) {
        if (CISCO.equals(vendor)) {
            return ciscoAsa == null ? new ConfirmOutcome.Failed("no Cisco ASA executor in this worker") : ciscoAsa.confirm(target, credentialRef);
        }
        if (FORTIGATE.equals(vendor)) {
            return fortiGate == null ? new ConfirmOutcome.Failed("no FortiGate executor in this worker") : fortiGate.confirm(target, credentialRef);
        }
        if (FORTIMANAGER.equals(vendor)) {
            return fortiManager == null ? new ConfirmOutcome.Failed("no FortiManager executor in this worker") : fortiManager.confirm(target, credentialRef);
        }
        Credentials creds;
        try {
            creds = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new ConfirmOutcome.Failed("credential reference not resolvable");
        }
        try {
            return switch (vendor) {
                case "infoblox" -> confirmInfoblox(target, creds);
                case "radware" -> confirmRadware(target, creds);
                case "radware_cyber_controller" -> confirmCyberController(target, creds);
                case "bluecoat_mc" -> confirmManagementCenter(target, creds);
                default -> new ConfirmOutcome.Failed("no HTTPS confirm for vendor " + vendor);
            };
        } catch (IOException e) {
            return new ConfirmOutcome.Failed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ConfirmOutcome.Failed("interrupted");
        }
    }

    private ConfirmOutcome confirmInfoblox(Target target, Credentials creds) throws IOException, InterruptedException {
        TextResponse doc = client.get(target, HttpsVendorPlan.INFOBLOX_WAPIDOC, creds, SHORT, TEXT_MAX);
        Optional<String> version = HttpsVendorPlan.parseWapiVersion(doc.body());
        if (version.isEmpty()) {
            return new ConfirmOutcome.Failed("the WAPI version could not be read from /wapidoc/ (HTTP " + doc.status() + ")");
        }
        TextResponse grid = client.get(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_GRID, version.get()), creds, SHORT, TEXT_MAX);
        if (grid.status() == 401 || grid.status() == 403) {
            return new ConfirmOutcome.AuthenticationFailed("HTTP " + grid.status());
        }
        if (!grid.ok()) {
            return new ConfirmOutcome.Failed("grid read answered HTTP " + grid.status());
        }
        Optional<String> name = Optional.empty();
        JsonNode node = JSON.readTree(grid.body());
        if (node.isArray() && node.size() > 0 && node.get(0).hasNonNull("name")) {
            name = Optional.of(node.get(0).get("name").asText());
        }
        return new ConfirmOutcome.Confirmed(new Identity(name, Optional.of("Infoblox Grid Manager"), Optional.of("WAPI " + version.get())));
    }

    // ------------------------------------------------------------------------------------------------ inventory

    /** What an HTTPS inventory read produced (PO 2026-09-25: inventory is its own job for these vendors). */
    public sealed interface InventoryOutcome {
        /**
         * {@code contexts}: one per grid member (Infoblox) carrying its interfaces and static routes; {@code members}: the
         * member facts; {@code virtualSystems}: the member (or managed device) names the Devices tree lists under the device.
         */
        record Completed(List<InventoryContext> contexts, List<GridMember> members, Optional<String> virtualSystems, Identity identity)
                implements InventoryOutcome {
        }
        record AuthenticationFailed(String reason) implements InventoryOutcome {
        }
        record Failed(String reason) implements InventoryOutcome {
        }
    }

    public InventoryOutcome inventory(String vendor, Target target, String credentialRef) {
        if (CISCO.equals(vendor)) {
            return ciscoAsa == null ? new InventoryOutcome.Failed("no Cisco ASA executor in this worker") : ciscoAsa.inventory(target, credentialRef);
        }
        if (FORTIGATE.equals(vendor)) {
            return fortiGate == null ? new InventoryOutcome.Failed("no FortiGate executor in this worker") : fortiGate.inventory(target, credentialRef);
        }
        if (FORTIMANAGER.equals(vendor)) {
            return fortiManager == null ? new InventoryOutcome.Failed("no FortiManager executor in this worker") : fortiManager.inventory(target, credentialRef);
        }
        try {
            Credentials creds = credentials.apply(credentialRef);
            return switch (vendor) {
                case "infoblox" -> inventoryInfoblox(target, creds);
                case "radware_cyber_controller" -> inventoryCyberController(target, creds);
                case "bluecoat_mc" -> inventoryManagementCenter(target, creds);
                default -> new InventoryOutcome.Failed("no HTTPS inventory read for vendor " + vendor);
            };
        } catch (RuntimeException e) {
            return new InventoryOutcome.Failed("credential_unresolvable: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            return new InventoryOutcome.Failed(e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InventoryOutcome.Failed("interrupted");
        }
    }

    /** Infoblox: the grid's members with interfaces, static routes and facts -- {@code GET /wapi/v<ver>/member} (gate infoblox_member_list). */
    private InventoryOutcome inventoryInfoblox(Target target, Credentials creds) throws IOException, InterruptedException {
        TextResponse doc = client.get(target, HttpsVendorPlan.INFOBLOX_WAPIDOC, creds, SHORT, TEXT_MAX);
        Optional<String> version = HttpsVendorPlan.parseWapiVersion(doc.body());
        if (version.isEmpty()) {
            return new InventoryOutcome.Failed("the WAPI version could not be read from /wapidoc/ (HTTP " + doc.status() + ")");
        }
        TextResponse r = client.get(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_MEMBERS, version.get()), creds, SHORT, TEXT_MAX);
        if (r.status() == 401 || r.status() == 403) {
            return new InventoryOutcome.AuthenticationFailed("HTTP " + r.status());
        }
        if (!r.ok()) {
            return new InventoryOutcome.Failed("member list answered HTTP " + r.status());
        }
        JsonNode json = JSON.readTree(r.body());
        List<GridMember> members = InfobloxMembers.parse(json, target.host());
        List<InventoryContext> contexts = InfobloxMembers.contexts(json);
        LOG.log(System.Logger.Level.INFO, "[HTTPS_INVENTORY] infoblox grid lists {0} member(s), {1} interface(s), {2} route(s); member field names {3}",
                members.size(), contexts.stream().mapToInt(c -> c.interfaces().size()).sum(),
                contexts.stream().mapToInt(c -> c.routes().size()).sum(), HttpsVendorPlan.fieldNames(json));
        Optional<String> names = members.isEmpty() ? Optional.empty()
                : Optional.of(members.stream().map(GridMember::hostName).sorted().collect(java.util.stream.Collectors.joining(", ")));
        // The grid's own name (GET /grid, gate infoblox_grid_identity) is the device's name -- re-read here so it follows
        // every read too, and never the Grid Master's host name.
        Optional<String> gridName = Optional.empty();
        TextResponse grid = client.get(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_GRID, version.get()), creds, SHORT, TEXT_MAX);
        if (grid.ok()) {
            JsonNode g = JSON.readTree(grid.body());
            if (g.isArray() && g.size() > 0 && g.get(0).hasNonNull("name")) {
                gridName = Optional.of(g.get(0).get("name").asText()).filter(n -> !n.isBlank());
            }
        }
        return new InventoryOutcome.Completed(contexts, members, names,
                new Identity(gridName, Optional.of("Infoblox Grid Manager"), Optional.of("WAPI " + version.get())));
    }

    /**
     * A DefensePro through the Cyber Controller that manages it (PO 2026-09-25): its entry in the controller's device
     * list (gate radware_cc_alldevices, no new request) gives its name, version, form factor, status and HA priority.
     * Management-plane evidence, not a read from the DefensePro itself; interfaces and routes are not in that list.
     */
    public InventoryOutcome inventoryDefenseProViaCyberController(Target cc, String ccCredentialRef, String deviceAddress) {
        try {
            CcLogin login = ccLogin(cc, credentials.apply(ccCredentialRef));
            if (login.session().isEmpty()) {
                return login.reason().startsWith("authentication_failed")
                        ? new InventoryOutcome.AuthenticationFailed("Cyber Controller " + login.reason())
                        : new InventoryOutcome.Failed("Cyber Controller " + login.reason());
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> devices = ccDevices(s);
                if (devices.isEmpty()) {
                    return new InventoryOutcome.Failed("the Cyber Controller device list could not be read");
                }
                Optional<JsonNode> entry = CyberControllerTree.walk(devices.get(), cc.host()).deviceAt(deviceAddress);
                if (entry.isEmpty()) {
                    return new InventoryOutcome.Failed("the Cyber Controller does not list this DefensePro's address");
                }
                JsonNode e = entry.get();
                String model = "Radware DefensePro" + CyberControllerTree.field(e, "formFactor").map(f -> " " + f).orElse("");
                LOG.log(System.Logger.Level.INFO, "[HTTPS_INVENTORY] defensepro via cyber controller: status present={0}, HA priority present={1}",
                        CyberControllerTree.field(e, "status").isPresent(), CyberControllerTree.field(e, "highAvailabilityPriorityEnum").isPresent());
                return new InventoryOutcome.Completed(List.of(), List.of(), Optional.empty(),
                        new Identity(CyberControllerTree.field(e, "name"), Optional.of(model), CyberControllerTree.field(e, "deviceVersion")));
            }
        } catch (RuntimeException e) {
            return new InventoryOutcome.Failed("credential_unresolvable: " + e.getClass().getSimpleName());
        } catch (IOException e) {
            return new InventoryOutcome.Failed(e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new InventoryOutcome.Failed("interrupted");
        }
    }

    /**
     * Radware Cyber Controller: the managed device tree (V67 calls 1, 2, 4) as the controller's inventory. The tree
     * (measured 2026-09-25: children, deleted, deviceVersion, formFactor, highAvailabilityPriorityEnum, managementIp,
     * name, ormId, parentOrmId, status, supportTemplate, treeType, type) is walked whole; every node with a
     * management address that is not the controller's own is a managed device; the controller's own name is the node
     * whose address is the one dialled, else the node whose type names a controller, else the single root node. Node
     * types and tree types are logged as counts (they are enumerations, never names).
     */
    private InventoryOutcome inventoryCyberController(Target target, Credentials creds) throws IOException, InterruptedException {
        CcLogin login = ccLogin(target, creds);
        if (login.session().isEmpty()) {
            return login.reason().startsWith("authentication_failed")
                    ? new InventoryOutcome.AuthenticationFailed(login.reason().substring("authentication_failed: ".length()))
                    : new InventoryOutcome.Failed(login.reason());
        }
        try (CcSession s = login.session().get()) {
            Optional<JsonNode> devices = ccDevices(s);
            if (devices.isEmpty()) {
                return new InventoryOutcome.Failed("the Cyber Controller device list could not be read");
            }
            CyberControllerTree tree = CyberControllerTree.walk(devices.get(), target.host());
            // Measured 2026-09-25: the controller is not in its own device list; its TLS certificate names it (SAN / CN).
            Optional<String> ownName = tree.ownName().or(login::peerName);
            String nameRule = tree.ownName().isPresent() ? tree.ownNameRule() : login.peerName().isPresent() ? "the TLS certificate name" : "none";
            LOG.log(System.Logger.Level.INFO, "[HTTPS_INVENTORY] cyber controller tree: {0} node(s), types {1}, tree types {2}, "
                    + "managed devices {3}, own name found by {4}", tree.nodeCount(), tree.typeCounts(), tree.treeTypeCounts(),
                    tree.managedNames().size(), nameRule);
            List<String> names = tree.managedNames();
            return new InventoryOutcome.Completed(List.of(), List.of(), names.isEmpty() ? Optional.empty() : Optional.of(String.join(", ", names)),
                    new Identity(ownName, Optional.of("Radware Cyber Controller"), tree.ownVersion()));
        }
    }

    // ------------------------------------------------------------------------------------------------ Management Center

    /**
     * Symantec (Blue Coat) Management Center (PO 2026-09-25): {@code GET /api/devices} with basic auth (gate
     * bluecoat_mc_devices). MEASURE FIRST: the list's field names and size are logged, never values; the name comes
     * from the MC's TLS certificate, as for the Cyber Controller.
     */
    private ConfirmOutcome confirmManagementCenter(Target target, Credentials creds) throws IOException, InterruptedException {
        TextResponse r = client.get(target, HttpsVendorPlan.MC_DEVICES, creds, SHORT, 8 * 1024 * 1024);
        if (r.status() == 401 || r.status() == 403) {
            return new ConfirmOutcome.AuthenticationFailed("HTTP " + r.status());
        }
        if (!r.ok()) {
            return new ConfirmOutcome.Failed("the Management Center device list answered HTTP " + r.status());
        }
        JsonNode list = JSON.readTree(r.body());
        LOG.log(System.Logger.Level.INFO, "[MANAGEMENT_CENTER] devices HTTP {0}, {1} bytes, {2} entries, field names {3}", r.status(),
                r.body().length(), list.isArray() ? list.size() : -1, HttpsVendorPlan.fieldNames(list));
        // The MC is not in its own list (measured 2026-09-25: types cp, rptr, sgos6x): its certificate names it.
        return new ConfirmOutcome.Confirmed(new Identity(r.peerName(), Optional.of("Symantec Management Center"), Optional.empty()));
    }

    /**
     * The Management Center's inventory: its managed devices by name (ProxySG, Reporter, WSS entries), with the
     * type and OS version counts logged for measurement. Field names are read defensively (name / deviceName /
     * displayName; osVersion / version / softwareVersion) until the first run names them.
     */
    private InventoryOutcome inventoryManagementCenter(Target target, Credentials creds) throws IOException, InterruptedException {
        TextResponse r = client.get(target, HttpsVendorPlan.MC_DEVICES, creds, SHORT, 8 * 1024 * 1024);
        if (r.status() == 401 || r.status() == 403) {
            return new InventoryOutcome.AuthenticationFailed("HTTP " + r.status());
        }
        if (!r.ok()) {
            return new InventoryOutcome.Failed("the Management Center device list answered HTTP " + r.status());
        }
        JsonNode list = JSON.readTree(r.body());
        List<GridMember> managed = ManagementCenterDevices.parse(list);
        LOG.log(System.Logger.Level.INFO, "[HTTPS_INVENTORY] management center lists {0} device(s), types {1}, field names {2}",
                managed.size(), ManagementCenterDevices.typeCounts(list), HttpsVendorPlan.fieldNames(list));
        Optional<String> names = managed.isEmpty() ? Optional.empty()
                : Optional.of(managed.stream().map(GridMember::hostName).sorted(String.CASE_INSENSITIVE_ORDER)
                        .collect(java.util.stream.Collectors.joining(", ")));
        return new InventoryOutcome.Completed(List.of(), managed, names,
                new Identity(r.peerName(), Optional.of("Symantec Management Center"), Optional.empty()));
    }

    /**
     * ProxySG backup through the Symantec Management Center (PO 2026-09-25; VENDOR_BACKUP_CONTRACTS §8a, measured on
     * the test proxy): for every ProxySG the MC lists, {@code show version} and {@code show configuration} through
     * {@code PUT /api/devices/{uuid}/command} -- exactly these literals, never another (the MC's session is at
     * #(config)). One gzip tar bundle: {@code <uuid>/show-version.txt}, {@code <uuid>/configuration.txt} per ProxySG and
     * {@code manifest.txt} (uuid, name, type) inside the encrypted artefact; member paths carry opaque ids only.
     * A ProxySG whose read fails is named in the manifest as failed; the run fails only when none succeeded.
     */
    private BackupResult backupManagementCenter(Target target, Credentials creds, String deviceId, String jobId)
            throws IOException, InterruptedException {
        TextResponse list = client.get(target, HttpsVendorPlan.MC_DEVICES, creds, SHORT, 8 * 1024 * 1024);
        if (list.status() == 401 || list.status() == 403) {
            return new BackupResult.ConnectFailed("authentication_failed: HTTP " + list.status());
        }
        if (!list.ok()) {
            return new BackupResult.ConnectFailed("the Management Center device list answered HTTP " + list.status());
        }
        List<JsonNode> proxies = new java.util.ArrayList<>();
        for (JsonNode d : JSON.readTree(list.body())) {
            if (HttpsVendorPlan.MC_TYPE_PROXYSG.equals(d.path("type").asText()) && d.hasNonNull("uuid")) {
                proxies.add(d);
            }
        }
        if (proxies.isEmpty()) {
            return new BackupResult.SubmitRefused("the Management Center lists no ProxySG (type " + HttpsVendorPlan.MC_TYPE_PROXYSG + ")");
        }
        StringBuilder manifest = new StringBuilder("# ProxySG configuration backup through the Management Center\n# uuid\tname\tresult\n");
        java.util.LinkedHashMap<String, byte[]> members = new java.util.LinkedHashMap<>();
        int ok = 0;
        for (JsonNode p : proxies) {
            String uuid = p.get("uuid").asText();
            String name = p.path("name").asText("");
            String result;
            try {
                String version = mcCommand(target, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_VERSION);
                String config = mcCommand(target, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_CONFIGURATION);
                if (!config.contains("!- BEGIN") || !config.contains("!- END")) {
                    result = "configuration without BEGIN/END section markers -- not stored";
                } else {
                    members.put(uuid + "/show-version.txt", version.getBytes(StandardCharsets.UTF_8));
                    members.put(uuid + "/configuration.txt", config.getBytes(StandardCharsets.UTF_8));
                    result = "ok " + config.length() + " bytes";
                    ok++;
                }
            } catch (McCommandFailed e) {
                result = "failed: " + e.getMessage();
            }
            manifest.append(uuid).append('\t').append(name).append('\t').append(result).append('\n');
            LOG.log(System.Logger.Level.INFO, "[MC_BACKUP] proxysg {0}: {1}", uuid, result.startsWith("ok") ? "ok" : result);
        }
        if (ok == 0) {
            return new BackupResult.SubmitRefused("no ProxySG configuration could be read through the Management Center ("
                    + proxies.size() + " tried)");
        }
        ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "bluecoat", false);
        try {
            try (com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter tar =
                    new com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                tar.file("manifest.txt", manifest.toString().getBytes(StandardCharsets.UTF_8));
                for (var e : members.entrySet()) {
                    tar.file(e.getKey(), e.getValue());
                }
            }
            ArtefactStore.ArtefactMetadata metadata = handle.finish();
            LOG.log(System.Logger.Level.INFO, "[MC_BACKUP] stored {0} of {1} ProxySG configuration(s), {2} bytes", ok, proxies.size(),
                    metadata.plaintextBytes());
            return new BackupResult.Completed(metadata, "proxysg-configurations.tgz", Optional.empty(), Optional.empty());
        } catch (IOException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("bundle could not be stored: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------------------------------------ ProxySG via MC

    /** The Management Center's device list, for discovery; empty with a failure class when it cannot be read. */
    public CcDeviceList mcDeviceList(Target mc, String mcCredentialRef) {
        try {
            Credentials creds = credentials.apply(mcCredentialRef);
            TextResponse r = client.get(mc, HttpsVendorPlan.MC_DEVICES, creds, SHORT, 8 * 1024 * 1024);
            if (r.status() == 401 || r.status() == 403) {
                return new CcDeviceList(Optional.empty(), "AUTH_FAILED");
            }
            if (!r.ok()) {
                return new CcDeviceList(Optional.empty(), "REFUSED");
            }
            return new CcDeviceList(Optional.of(JSON.readTree(r.body())), "");
        } catch (RuntimeException e) {
            return new CcDeviceList(Optional.empty(), "CREDENTIAL_UNRESOLVABLE");
        } catch (IOException e) {
            return new CcDeviceList(Optional.empty(), "UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CcDeviceList(Optional.empty(), "INTERRUPTED");
        }
    }

    /** The MC's entry for the ProxySG at {@code host} (its "host" field, port ignored), if the MC lists it. */
    private Optional<JsonNode> mcEntry(Target mc, Credentials creds, String host) throws IOException, InterruptedException {
        TextResponse r = client.get(mc, HttpsVendorPlan.MC_DEVICES, creds, SHORT, 8 * 1024 * 1024);
        if (!r.ok()) {
            return Optional.empty();
        }
        for (JsonNode d : JSON.readTree(r.body())) {
            String h = d.path("host").asText("");
            int colon = h.lastIndexOf(':');
            String bare = colon > 0 && h.indexOf(':') == colon ? h.substring(0, colon) : h;
            if (!h.isEmpty() && (h.equalsIgnoreCase(host) || bare.equalsIgnoreCase(host)) && d.hasNonNull("uuid")) {
                return Optional.of(d);
            }
        }
        return Optional.empty();
    }

    private static Identity proxySgIdentity(JsonNode d) {
        Optional<String> name = Optional.ofNullable(d.path("name").asText(null)).filter(v -> !v.isBlank());
        Optional<String> model = Optional.ofNullable(d.path("model").asText(null)).filter(v -> !v.isBlank());
        Optional<String> version = Optional.ofNullable(d.path("osVersion").asText(null)).filter(v -> !v.isBlank());
        return new Identity(name, model.map(m -> "ProxySG " + m).or(() -> Optional.of("ProxySG")), version);
    }

    /** Confirm a ProxySG by the enrolled Management Center that lists it (management-plane evidence, labelled as such). */
    public Optional<ConfirmOutcome> confirmProxySgViaManagementCenter(Target mc, String mcCredentialRef, String host) {
        try {
            Credentials creds = credentials.apply(mcCredentialRef);
            return mcEntry(mc, creds, host).map(d -> new ConfirmOutcome.Confirmed(proxySgIdentity(d)));
        } catch (RuntimeException | IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** A ProxySG's interfaces and routes through the MC's command API (V82); shapes logged for the first-run record. */
    public Optional<InventoryOutcome> inventoryProxySgViaManagementCenter(Target mc, String mcCredentialRef, String host) {
        try {
            Credentials creds = credentials.apply(mcCredentialRef);
            Optional<JsonNode> entry = mcEntry(mc, creds, host);
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            String uuid = entry.get().get("uuid").asText();
            String ifs;
            String rt;
            try {
                ifs = mcCommand(mc, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_INTERFACE_ALL);
                rt = mcCommand(mc, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_IP_ROUTE_TABLE);
            } catch (McCommandFailed f) {
                return Optional.of(new InventoryOutcome.Failed("management center command: " + f.getMessage()));
            }
            var interfaces = ProxySgOutputs.interfaces(ifs);
            var routes = ProxySgOutputs.routes(rt);
            LOG.log(System.Logger.Level.INFO, "[PROXYSG] inventory via MC: interfaces={0} routes={1}; MEASURE interface shape: {2}; route shape: {3}",
                    interfaces.size(), routes.size(), ProxySgOutputs.shape(ifs, 14), ProxySgOutputs.shape(rt, 10));
            return Optional.of(new InventoryOutcome.Completed(
                    List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext(
                            com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext.PHYSICAL, interfaces, routes)),
                    List.of(), Optional.empty(), proxySgIdentity(entry.get())));
        } catch (RuntimeException | IOException e) {
            return Optional.of(new InventoryOutcome.Failed("management center: " + e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new InventoryOutcome.Failed("interrupted"));
        }
    }

    /** One ProxySG's backup through the MC: show version and show configuration of that device only. */
    public Optional<BackupResult> backupProxySgViaManagementCenter(Target mc, String mcCredentialRef, String host, String deviceId,
            String jobId) {
        try {
            Credentials creds = credentials.apply(mcCredentialRef);
            Optional<JsonNode> entry = mcEntry(mc, creds, host);
            if (entry.isEmpty()) {
                return Optional.empty();
            }
            String uuid = entry.get().get("uuid").asText();
            String version;
            String config;
            try {
                version = mcCommand(mc, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_VERSION);
                config = mcCommand(mc, creds, uuid, HttpsVendorPlan.MC_CMD_SHOW_CONFIGURATION);
            } catch (McCommandFailed f) {
                return Optional.of(new BackupResult.SubmitRefused("management center command: " + f.getMessage()));
            }
            if (!config.contains("!- BEGIN") || !config.contains("!- END")) {
                return Optional.of(new BackupResult.SubmitOutputUnparseable("configuration without BEGIN/END section markers"));
            }
            ArtefactStore.ArtefactHandle handle = artefactStore.open(deviceId, jobId, "bluecoat", false);
            try {
                try (com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter tar =
                        new com.securityexpert.nexus.ui2.persistence.artefact.content.TarWriter(new java.util.zip.GZIPOutputStream(handle.sink()))) {
                    tar.file("manifest.txt", ("# ProxySG configuration backup through the Management Center\nshow-version.txt\t"
                            + version.length() + " chars\nconfiguration.txt\t" + config.length() + " chars\n").getBytes(StandardCharsets.UTF_8));
                    tar.file("show-version.txt", version.getBytes(StandardCharsets.UTF_8));
                    tar.file("configuration.txt", config.getBytes(StandardCharsets.UTF_8));
                }
                return Optional.of(new BackupResult.Completed(handle.finish(), "proxysg-configuration.tgz", Optional.empty(), Optional.empty()));
            } catch (IOException e) {
                closeQuietly(handle);
                return Optional.of(new BackupResult.ArtefactStoreFailed("bundle could not be stored: " + e.getClass().getSimpleName()));
            }
        } catch (RuntimeException | IOException e) {
            return Optional.of(new BackupResult.ConnectFailed("management center: " + e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new BackupResult.ConnectFailed("interrupted"));
        }
    }

    private static final class McCommandFailed extends Exception {
        McCommandFailed(String message) {
            super(message, null, false, false);
        }
    }

    /** One exact read literal through the MC; the device's reply text, or why there is none. */
    private String mcCommand(Target target, Credentials creds, String uuid, String command) throws IOException, InterruptedException,
            McCommandFailed {
        if (!java.util.Set.of(HttpsVendorPlan.MC_CMD_SHOW_VERSION, HttpsVendorPlan.MC_CMD_SHOW_CONFIGURATION,
                HttpsVendorPlan.MC_CMD_SHOW_INTERFACE_ALL, HttpsVendorPlan.MC_CMD_SHOW_IP_ROUTE_TABLE).contains(command)) {
            throw new IllegalArgumentException("not a gated Management Center command");
        }
        TextResponse r = client.putRaw(target, HttpsVendorPlan.mcDeviceCommand(uuid), command, creds, Duration.ofSeconds(120),
                64 * 1024 * 1024);
        if (!r.ok()) {
            throw new McCommandFailed("HTTP " + r.status());
        }
        if (r.truncated()) {
            throw new McCommandFailed("reply larger than 64 MB");
        }
        JsonNode j = JSON.readTree(r.body());
        if (!"SUCCESS".equals(j.path("status").asText())) {
            throw new McCommandFailed("status " + j.path("status").asText("?") + ", " + j.path("messages").size() + " message(s)");
        }
        return j.path("reply").asText("");
    }

    // ------------------------------------------------------------------------------------------------ Cyber Controller

    /** A logged-in Cyber Controller session; {@link #close} always logs out. */
    private final class CcSession implements AutoCloseable {
        final Target target;
        final Credentials session;

        CcSession(Target target, Credentials session) {
            this.target = target;
            this.session = session;
        }

        @Override
        public void close() {
            try {
                TextResponse r = client.postJson(target, HttpsVendorPlan.CC_LOGOUT, "{}", session, SHORT, 64 * 1024);
                LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] logout HTTP {0}", r.status());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] logout failed: {0}", e.getClass().getSimpleName());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Either a session, or why there is none ("authentication_failed: ..." when the Cyber Controller refused the login). */
    private record CcLogin(Optional<CcSession> session, String reason, Optional<String> peerName) {
        CcLogin(Optional<CcSession> session, String reason) {
            this(session, reason, Optional.empty());
        }
    }

    private CcLogin ccLogin(Target target, Credentials creds) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of("username", creds.username(), "password", new String(creds.password())));
        HttpsDeviceClient.SessionLogin login = client.login(target, HttpsVendorPlan.CC_LOGIN, body, SHORT);
        LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] login HTTP {0}, session={1}", login.status(), login.cookie().isPresent());
        if (login.status() == 401 || login.status() == 403) {
            return new CcLogin(Optional.empty(), "authentication_failed: HTTP " + login.status());
        }
        if (login.status() < 200 || login.status() >= 300 || login.cookie().isEmpty()) {
            return new CcLogin(Optional.empty(), "the Cyber Controller login answered HTTP " + login.status()
                    + (login.cookie().isEmpty() ? " without a session cookie" : ""));
        }
        return new CcLogin(Optional.of(new CcSession(target, Credentials.session(login.cookie().get()))), "", login.peerName());
    }

    /** The device list, or empty when it could not be read; logs field names and size only (MEASURE FIRST). */
    private Optional<JsonNode> ccDevices(CcSession s) throws IOException, InterruptedException {
        TextResponse list = client.get(s.target, HttpsVendorPlan.CC_ALLDEVICES, s.session, Duration.ofSeconds(60), 8 * 1024 * 1024);
        if (!list.ok()) {
            LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] alldevices HTTP {0}", list.status());
            return Optional.empty();
        }
        JsonNode node = JSON.readTree(list.body());
        LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] alldevices HTTP {0}, {1} bytes, field names {2}", list.status(),
                list.body().length(), HttpsVendorPlan.fieldNames(node));
        return Optional.of(node);
    }

    private ConfirmOutcome confirmCyberController(Target target, Credentials creds) throws IOException, InterruptedException {
        CcLogin login = ccLogin(target, creds);
        if (login.session().isEmpty()) {
            return login.reason().startsWith("authentication_failed")
                    ? new ConfirmOutcome.AuthenticationFailed(login.reason().substring("authentication_failed: ".length()))
                    : new ConfirmOutcome.Failed(login.reason());
        }
        try (CcSession s = login.session().get()) {
            if (ccDevices(s).isEmpty()) {
                return new ConfirmOutcome.Failed("the Cyber Controller device list could not be read");
            }
            return new ConfirmOutcome.Confirmed(new Identity(Optional.empty(), Optional.of("Radware Cyber Controller"), Optional.empty()));
        }
    }

    /** A Cyber Controller's device list, or why there is none ({@code failureClass}: AUTH_FAILED, UNREACHABLE, LIST_FAILED). */
    public record CcDeviceList(Optional<JsonNode> list, String failureClass) {
    }

    /** Login, the device list, logout (V67 calls 1, 2, 4) -- for discovery. */
    public CcDeviceList ccDeviceList(Target cc, String ccCredentialRef) {
        try {
            CcLogin login = ccLogin(cc, credentials.apply(ccCredentialRef));
            if (login.session().isEmpty()) {
                return new CcDeviceList(Optional.empty(), login.reason().startsWith("authentication_failed") ? "AUTH_FAILED" : "LOGIN_FAILED");
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> list = ccDevices(s);
                return new CcDeviceList(list, list.isPresent() ? "" : "LIST_FAILED");
            }
        } catch (RuntimeException e) {
            return new CcDeviceList(Optional.empty(), "CREDENTIAL_UNRESOLVABLE");
        } catch (IOException e) {
            return new CcDeviceList(Optional.empty(), "UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new CcDeviceList(Optional.empty(), "INTERRUPTED");
        }
    }

    /**
     * A DefensePro confirmed by the Cyber Controller that lists its address -- management-plane evidence, not a read
     * from the device itself, and the identity says so. Empty when that Cyber Controller does not list it or cannot be
     * read (the caller then reads the device directly).
     */
    public Optional<ConfirmOutcome> confirmViaCyberController(Target cc, String ccCredentialRef, String deviceAddress) {
        try {
            CcLogin login = ccLogin(cc, credentials.apply(ccCredentialRef));
            if (login.session().isEmpty()) {
                LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] confirm via Cyber Controller not possible: {0}", login.reason());
                return Optional.empty();
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> devices = ccDevices(s);
                if (devices.isEmpty() || !HttpsVendorPlan.listsAddress(devices.get(), deviceAddress)) {
                    return Optional.empty();
                }
                return Optional.of(new ConfirmOutcome.Confirmed(new Identity(Optional.empty(),
                        Optional.of("Radware DefensePro (listed by Cyber Controller)"), Optional.empty())));
            }
        } catch (RuntimeException | IOException e) {
            LOG.log(System.Logger.Level.WARNING, "[CYBER_CONTROLLER] confirm via Cyber Controller failed: {0}", e.getClass().getSimpleName());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * A DefensePro backup through the Cyber Controller that manages it (PO, 2026-09-24): its {@code getcfg}, keys
     * included and encrypted with the device's export passphrase, streamed here ({@code saveToDb=false}: nothing is
     * written to the Cyber Controller's own backup slots). Empty when the Cyber Controller does not list the device --
     * the caller then takes the direct path.
     */
    public Optional<BackupResult> backupViaCyberController(Target cc, String ccCredentialRef, String deviceAddress,
            Optional<String> passphraseRef, String deviceId, String jobId) {
        if (passphraseRef.isEmpty()) {
            return Optional.of(new BackupResult.CredentialUnresolvable("no export passphrase credential is set for this Radware device: "
                    + "the export carries the private keys encrypted with it -- refused rather than a backup without keys"));
        }
        Credentials creds;
        String passphrase;
        try {
            creds = credentials.apply(ccCredentialRef);
            passphrase = new String(credentials.apply(passphraseRef.get()).password());
        } catch (RuntimeException e) {
            return Optional.of(new BackupResult.CredentialUnresolvable("Cyber Controller or passphrase credential not resolvable"));
        }
        try {
            CcLogin login = ccLogin(cc, creds);
            if (login.session().isEmpty()) {
                return Optional.of(new BackupResult.ConnectFailed("cyber controller: " + login.reason()));
            }
            try (CcSession s = login.session().get()) {
                Optional<JsonNode> devices = ccDevices(s);
                if (devices.isEmpty()) {
                    return Optional.of(new BackupResult.ConnectFailed("cyber controller: the device list could not be read"));
                }
                if (!HttpsVendorPlan.listsAddress(devices.get(), deviceAddress)) {
                    LOG.log(System.Logger.Level.INFO, "[CYBER_CONTROLLER] device {0} not listed; direct path", deviceId);
                    return Optional.empty();
                }
                BackupResult r = fetch(cc, "GET", HttpsVendorPlan.ccGetcfg(deviceAddress, passphrase), null, s.session, null, deviceId, jobId,
                        "radware", "DefensePro_configuration_via_cyber_controller", 256);
                return Optional.of(r);
            }
        } catch (IOException e) {
            return Optional.of(new BackupResult.ConnectFailed("cyber controller https: " + e.getClass().getSimpleName()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of(new BackupResult.ConnectFailed("interrupted"));
        }
    }

    private ConfirmOutcome confirmRadware(Target target, Credentials creds) throws IOException, InterruptedException {
        // MEASURE FIRST (contract §1): no read is measured for model/version; the confirm proves reachability and the
        // credential only. Identity stays UNKNOWN.
        TextResponse root = client.get(target, HttpsVendorPlan.RADWARE_ROOT, creds, SHORT, 64 * 1024);
        if (root.status() == 401 || root.status() == 403) {
            return new ConfirmOutcome.AuthenticationFailed("HTTP " + root.status());
        }
        if (root.status() >= 500 || root.status() == 0) {
            return new ConfirmOutcome.Failed("the device answered HTTP " + root.status());
        }
        return new ConfirmOutcome.Confirmed(new Identity(Optional.empty(), Optional.of("Radware DefensePro"), Optional.empty()));
    }

    // ------------------------------------------------------------------------------------------------ backup

    /** @param passphraseRef Radware only: the credential whose password encrypts the private keys in the export */
    public BackupResult backup(String vendor, Target target, String credentialRef, Optional<String> passphraseRef,
            String deviceId, String jobId) {
        if (CISCO.equals(vendor)) {
            return ciscoAsa == null ? new BackupResult.ConnectFailed("no Cisco ASA executor in this worker")
                    : ciscoAsa.backup(target, credentialRef, deviceId, jobId);
        }
        if (FORTIGATE.equals(vendor)) {
            return fortiGate == null ? new BackupResult.ConnectFailed("no FortiGate executor in this worker")
                    : fortiGate.backup(target, credentialRef, deviceId, jobId);
        }
        Credentials creds;
        try {
            creds = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("credential reference not resolvable -- refused before any device contact");
        }
        try {
            return switch (vendor) {
                case "infoblox" -> backupInfoblox(target, creds, deviceId, jobId);
                case "radware" -> backupRadware(target, creds, passphraseRef, deviceId, jobId);
                case "bluecoat" -> backupManagementCenter(target, creds, deviceId, jobId);
                default -> new BackupResult.ConnectFailed("no HTTPS backup for vendor " + vendor);
            };
        } catch (IOException e) {
            return new BackupResult.ConnectFailed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new BackupResult.ConnectFailed("interrupted");
        }
    }

    private BackupResult backupInfoblox(Target target, Credentials creds, String deviceId, String jobId)
            throws IOException, InterruptedException {
        TextResponse doc = client.get(target, HttpsVendorPlan.INFOBLOX_WAPIDOC, creds, SHORT, TEXT_MAX);
        Optional<String> version = HttpsVendorPlan.parseWapiVersion(doc.body());
        if (version.isEmpty()) {
            return new BackupResult.ConnectFailed("the WAPI version could not be read from /wapidoc/ (HTTP " + doc.status() + ")");
        }
        String ver = version.get();
        TextResponse start = client.postJson(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_GETGRIDDATA, ver),
                HttpsVendorPlan.INFOBLOX_GETGRIDDATA_BODY, creds, LONG, TEXT_MAX);
        if (start.status() == 401 || start.status() == 403) {
            return new BackupResult.ConnectFailed("authentication_failed: HTTP " + start.status());
        }
        if (!start.ok()) {
            return new BackupResult.SubmitRefused("getgriddata answered HTTP " + start.status());
        }
        JsonNode node = JSON.readTree(start.body());
        String token = node.path("token").asText("");
        String url = node.path("url").asText("");
        if (token.isEmpty() || url.isEmpty()) {
            return new BackupResult.SubmitOutputUnparseable("getgriddata named no token or url");
        }
        try {
            Optional<String> path = HttpsDeviceClient.sameHostPath(target, url);
            if (path.isEmpty()) {
                return new BackupResult.SubmitRefused("the download url is not on the same appliance; refused");
            }
            // PO 2026-09-25: a backup only backs up; the grid members are the inventory job's business.
            return fetch(target, "GET", path.get(), null, creds, HttpsVendorPlan.INFOBLOX_DOWNLOAD_CONTENT_TYPE, deviceId, jobId,
                    "infoblox", "database.bak", 1);
        } finally {
            // Always -- the appliance holds the file until told (Backbox sends this without a version and fails).
            try {
                TextResponse done = client.postJson(target, HttpsVendorPlan.withVersion(HttpsVendorPlan.INFOBLOX_DOWNLOADCOMPLETE, ver),
                        JSON.writeValueAsString(Map.of("token", token)), creds, SHORT, 64 * 1024);
                LOG.log(System.Logger.Level.INFO, "[HTTPS_BACKUP] infoblox downloadcomplete HTTP {0}", done.status());
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING, "[HTTPS_BACKUP] infoblox downloadcomplete failed: {0}", e.getClass().getSimpleName());
            }
        }
    }

    private BackupResult backupRadware(Target target, Credentials creds, Optional<String> passphraseRef, String deviceId, String jobId) {
        if (passphraseRef.isEmpty()) {
            return new BackupResult.CredentialUnresolvable("no export passphrase credential is set for this Radware device: the "
                    + "export carries the private keys encrypted with it (IncludePKeys=on) -- refused rather than a backup without keys");
        }
        String passphrase;
        try {
            passphrase = new String(credentials.apply(passphraseRef.get()).password());
        } catch (RuntimeException e) {
            return new BackupResult.CredentialUnresolvable("export passphrase credential not resolvable");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("DownloadFormat", "cli");
        form.put("IncludePKeys", "on");
        form.put("passphrase", passphrase);
        return fetch(target, "POST", HttpsVendorPlan.RADWARE_RECEIVE_CONFIGURATION, form, creds, null, deviceId, jobId, "radware",
                "DefensePro_backup_configuration.txt", 1024);
    }

    private BackupResult fetch(Target target, String method, String path, Map<String, String> form, Credentials creds,
            String requestContentType, String deviceId, String jobId, String vendor, String memberName, long minBytes) {
        ArtefactStore.ArtefactHandle handle;
        try {
            handle = artefactStore.open(deviceId, jobId, vendor, false);
        } catch (IOException e) {
            return new BackupResult.ArtefactStoreFailed("artefact store open failed: " + e.getMessage());
        }
        HeadCapture head = new HeadCapture(handle.sink());
        DownloadResult r = client.download(target, method, path, form, creds, requestContentType, head, MAX_ARCHIVE, LONG);
        if (!(r instanceof DownloadResult.Downloaded d)) {
            closeQuietly(handle);
            return r instanceof DownloadResult.Refused refused && (refused.status() == 401 || refused.status() == 403)
                    ? new BackupResult.ConnectFailed("authentication_failed: HTTP " + refused.status())
                    : new BackupResult.SubmitRefused("download: " + (r instanceof DownloadResult.Refused x ? x.reason()
                            // an exception's text may echo the request (the getcfg query holds the passphrase): class only
                            : ((DownloadResult.Failed) r).reason().split(":", 2)[0]));
        }
        if (d.bytes() < minBytes) {
            closeQuietly(handle);
            return new BackupResult.SubmitOutputUnparseable("the export was " + d.bytes() + " bytes; not a backup");
        }
        ArtefactStore.ArtefactMetadata metadata;
        try {
            metadata = handle.finish();
        } catch (IOException e) {
            closeQuietly(handle);
            return new BackupResult.ArtefactStoreFailed("artefact store finish failed: " + e.getMessage());
        }
        LOG.log(System.Logger.Level.INFO, "[HTTPS_BACKUP] {0} {1} bytes, content-type={2}, gzip_magic={3}", vendor, d.bytes(),
                d.contentType().orElse("-"), head.gzip());
        return new BackupResult.Completed(metadata, memberName, Optional.empty(), Optional.empty());
    }

    /** Passes bytes through, remembering the first two for a format check (gzip magic) -- nothing else is kept. */
    private static final class HeadCapture extends OutputStream {
        private final OutputStream out;
        private final ByteArrayOutputStream head = new ByteArrayOutputStream(2);

        HeadCapture(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            if (head.size() < 2) {
                head.write(b);
            }
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len && head.size() < 2; i++) {
                head.write(b[off + i]);
            }
            out.write(b, off, len);
        }

        boolean gzip() {
            byte[] h = head.toByteArray();
            return h.length == 2 && (h[0] & 0xff) == 0x1f && (h[1] & 0xff) == 0x8b;
        }
    }

    private static void closeQuietly(ArtefactStore.ArtefactHandle h) {
        try {
            h.close();
        } catch (IOException ignored) {
            // best effort
        }
    }
}
