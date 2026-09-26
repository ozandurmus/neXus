package com.securityexpert.nexus.ui2.worker.backup.fortinet;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.device.inventory.GridMember;
import com.securityexpert.nexus.ui2.worker.backup.https.HttpsVendorExecutor;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceCalls;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Credentials;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.Target;
import com.securityexpert.nexus.ui2.worker.transport.https.HttpsDeviceClient.TextResponse;

/**
 * FortiManager over its JSON-RPC API (POST /jsonrpc; docs/design/FORTINET_CONTRACT.md, gate rows V80): login, the
 * system status, the managed device list, logout. Reads only. The password travels in the login body and is never
 * logged; the session token is kept in memory for this run only.
 */
public final class FortiManagerExecutor {

    private static final System.Logger LOG = System.getLogger(FortiManagerExecutor.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX = 16 * 1024 * 1024;
    public static final String PATH = "/jsonrpc";

    private final HttpsDeviceCalls client;
    private final Function<String, Credentials> credentials;
    /** Its SSH CLI (PO 2026-09-26: "ssh imkanın var, interface niye unknown?"): interface link states come from there. */
    private com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport ssh;

    public FortiManagerExecutor(HttpsDeviceCalls client, Function<String, Credentials> credentials) {
        this.client = client;
        this.credentials = credentials;
    }

    public FortiManagerExecutor withSsh(com.securityexpert.nexus.ui2.jobs.transport.DeviceTransport transport) {
        this.ssh = transport;
        return this;
    }

    public static final String SSH_GET_SYSTEM_INTERFACE = "get system interface";
    private static final java.util.regex.Pattern IFACE_BLOCK = java.util.regex.Pattern.compile("(?m)^==\\s*\\[\\s*([A-Za-z0-9_.-]+)\\s*\\]");
    private static final java.util.regex.Pattern IFACE_STATUS = java.util.regex.Pattern.compile("(?im)(?:^|\\s)status:\\s*(up|down|enable|disable)\\b");

    /**
     * FortiManager CLI "get system interface": "== [ port1 ]" blocks; "status:" is the interface's configured state
     * (measured 2026-09-26: enable), shown as up/down like a FortiGate's configured state. Physical link remains a
     * separate, unproven observation after the V87-V89 diagnostics.
     */
    public static Map<String, String> parseInterfaceStates(String out) {
        Map<String, String> states = new java.util.LinkedHashMap<>();
        if (out == null) {
            return states;
        }
        java.util.regex.Matcher b = IFACE_BLOCK.matcher(out);
        java.util.List<int[]> spans = new ArrayList<>();
        java.util.List<String> names = new ArrayList<>();
        while (b.find()) {
            spans.add(new int[] {b.end()});
            names.add(b.group(1));
        }
        for (int i = 0; i < names.size(); i++) {
            int from = spans.get(i)[0];
            int to = i + 1 < names.size() ? spans.get(i + 1)[0] : out.length();
            java.util.regex.Matcher st = IFACE_STATUS.matcher(out.substring(from, to));
            if (st.find()) {
                String v = st.group(1).toLowerCase(java.util.Locale.ROOT);
                states.put(names.get(i), v.equals("up") || v.equals("enable") ? "up" : "down");
            }
        }
        return states;
    }

    /** The link states over SSH, or empty when SSH is not wired, refused or unreadable (logged, never guessed). */
    private Map<String, String> interfaceStatesOverSsh(Target target, String credentialRef) {
        if (ssh == null) {
            return Map.of();
        }
        var ct = new com.securityexpert.nexus.ui2.jobs.transport.ConnectionTarget(UUID.randomUUID().toString(), target.host(), 22);
        var spec = new com.securityexpert.nexus.ui2.jobs.transport.ConnectSpec(credentialRef,
                com.securityexpert.nexus.ui2.worker.transport.ssh.PersistedManagementEndpointTrustResolver.scopeRef(target.host(), 22), Optional.empty());
        var r = ssh.connect(ct, spec, Duration.ofSeconds(30));
        if (!(r instanceof com.securityexpert.nexus.ui2.jobs.transport.ConnectResult.Authenticated a)) {
            LOG.log(System.Logger.Level.INFO, "[FMG] ssh interface states not read: {0}", r.getClass().getSimpleName());
            return Map.of();
        }
        try {
            var out = ssh.execInteractive(a.session(), new com.securityexpert.nexus.ui2.jobs.transport.ExecSpec(SSH_GET_SYSTEM_INTERFACE, true),
                    Duration.ofSeconds(60));
            if (out instanceof com.securityexpert.nexus.ui2.jobs.transport.ExecResult.Completed c) {
                Map<String, String> states = parseInterfaceStates(c.output());
                // MEASURE (2026-09-26: the first parser matched a value that was not up/down): the distinct words after
                // every "status:" and the shape of the first lines (letters a, digits 9) -- categorical, never an address.
                java.util.Set<String> words = new java.util.TreeSet<>();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("status:\\s*(\\S+)").matcher(c.output());
                while (m.find()) {
                    words.add(m.group(1));
                }
                LOG.log(System.Logger.Level.INFO, "[FMG] ssh interface states: {0} read; status words {1}; shape {2}", states.size(), words,
                        com.securityexpert.nexus.ui2.worker.backup.https.ProxySgOutputs.shape(c.output(), 8));
                return states;
            }
            LOG.log(System.Logger.Level.INFO, "[FMG] ssh get system interface: {0}", out.getClass().getSimpleName());
            return Map.of();
        } finally {
            ssh.disconnect(a.session());
        }
    }

    private record Session(String token, Credentials creds) {
    }

    private sealed interface Login {
        record Ok(Session session) implements Login {
        }

        record Refused(String reason, boolean auth) implements Login {
        }
    }

    private Login login(Target target, String credentialRef) throws IOException, InterruptedException {
        Credentials creds;
        try {
            creds = credentials.apply(credentialRef);
        } catch (RuntimeException e) {
            return new Login.Refused("credential reference not resolvable", false);
        }
        String body = JSON.writeValueAsString(Map.of("id", 1, "method", "exec", "params",
                List.of(Map.of("url", "/sys/login/user", "data", Map.of("user", creds.username(), "passwd", new String(creds.password()))))));
        TextResponse r = client.postJson(target, PATH, body, creds, TIMEOUT, 64 * 1024);
        if (!r.ok()) {
            return new Login.Refused("login answered HTTP " + r.status(), r.status() == 401 || r.status() == 403);
        }
        JsonNode node = JSON.readTree(r.body());
        int code = node.path("result").path(0).path("status").path("code").asInt(-1);
        String token = node.path("session").asText("");
        if (code != 0 || token.isEmpty()) {
            return new Login.Refused("login refused (status code " + code + ")", true);
        }
        return new Login.Ok(new Session(token, creds));
    }

    private JsonNode call(Target target, Session s, String method, Map<String, Object> param) throws IOException, InterruptedException {
        String body = JSON.writeValueAsString(Map.of("id", 2, "method", method, "params", List.of(param), "session", s.token()));
        TextResponse r = client.postJson(target, PATH, body, s.creds(), TIMEOUT, MAX);
        if (!r.ok()) {
            throw new IOException("HTTP " + r.status());
        }
        JsonNode result = JSON.readTree(r.body()).path("result").path(0);
        int code = result.path("status").path("code").asInt(-1);
        if (code != 0) {
            throw new IOException(param.get("url") + " status code " + code);
        }
        return result.path("data");
    }

    private void logout(Target target, Session s) {
        try {
            call(target, s, "exec", Map.of("url", "/sys/logout"));
        } catch (IOException | InterruptedException | RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "[FMG] logout: {0}", e.getClass().getSimpleName());
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    static HttpsVendorExecutor.Identity identity(JsonNode status) {
        Optional<String> host = text(status, "Hostname");
        Optional<String> platform = text(status, "Platform Type");
        Optional<String> version = text(status, "Version").map(v -> v.split("\\s+")[0]);
        return new HttpsVendorExecutor.Identity(host, platform, version);
    }

    /** Managed devices as member facts: platform = model, hypervisor slot = FortiOS version, node status = connection. */
    static List<GridMember> devices(JsonNode list) {
        List<GridMember> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode d : list) {
            Optional<String> name = text(d, "name");
            if (name.isEmpty()) {
                continue;
            }
            Optional<String> version = d.hasNonNull("os_ver") ? Optional.of(osVersion(d)) : Optional.empty();
            String conn = switch (d.path("conn_status").asInt(-1)) {
                case 1 -> "UP";
                case 2 -> "DOWN";
                default -> "UNKNOWN";
            };
            List<GridMember.ServiceStatus> services = new ArrayList<>();
            services.add(new GridMember.ServiceStatus("TYPE_FORTIGATE", "WORKING"));
            int ha = d.path("ha_mode").asInt(0);
            out.add(new GridMember(UUID.randomUUID().toString(), name.get(), text(d, "ip"), text(d, "platform_str"),
                    text(d, "platform_str"), version, false, false, ha != 0, Optional.empty(), Optional.of(conn), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), services));
        }
        out.sort(Comparator.comparing(GridMember::hostName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** An address as FortiManager writes it: ["a.b.c.d","m.m.m.m"] or "a.b.c.d m.m.m.m" -> "a.b.c.d/len"; 0.0.0.0 -> empty. */
    static Optional<String> cidr(JsonNode v) {
        String addr;
        String mask;
        if (v != null && v.isArray() && v.size() >= 2) {
            addr = v.get(0).asText();
            mask = v.get(1).asText();
        } else if (v != null && v.isTextual() && v.asText().trim().split("\\s+").length == 2) {
            String[] p = v.asText().trim().split("\\s+");
            addr = p[0];
            mask = p[1];
        } else {
            return Optional.empty();
        }
        if (!addr.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") || !mask.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            return Optional.empty();
        }
        int len = 0;
        for (String part : mask.split("\\.")) {
            len += Integer.bitCount(Integer.parseInt(part) & 0xff);
        }
        return addr.equals("0.0.0.0") && len != 0 ? Optional.empty() : Optional.of(addr + "/" + len);
    }

    /** /cli/global/system/interface: name, ip, status (up/down or 1/0), type -> inventory interfaces. */
    static List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface> interfaces(JsonNode list) {
        List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode i : list) {
            Optional<String> name = text(i, "name");
            if (name.isEmpty()) {
                continue;
            }
            List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress> addresses = new ArrayList<>();
            Optional<String> ip = cidr(i.get("ip"));
            if (ip.isPresent() && !ip.get().startsWith("0.0.0.0")) {
                addresses.add(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress(UUID.randomUUID().toString(),
                        ip.get(), com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress.FAMILY_IPV4,
                        com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryAddress.ROLE_MEMBER));
            }
            String st = i.path("status").asText("").toLowerCase(java.util.Locale.ROOT);
            String state = st.equals("up") || st.equals("1") || st.equals("enable") ? "up"
                    : st.equals("down") || st.equals("0") || st.equals("disable") ? "down" : "unknown";
            out.add(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface(UUID.randomUUID().toString(),
                    name.get(), Optional.empty(), "physical", state, addresses));
        }
        return out;
    }

    /** /cli/global/system/route: static routes, dst / gateway / device. */
    static List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute> routes(JsonNode list) {
        List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute> out = new ArrayList<>();
        if (list == null || !list.isArray()) {
            return out;
        }
        for (JsonNode r : list) {
            Optional<String> dst = cidr(r.get("dst"));
            if (dst.isEmpty()) {
                continue;
            }
            Optional<String> gw = text(r, "gateway").filter(g -> !g.equals("0.0.0.0"));
            out.add(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute(UUID.randomUUID().toString(), dst.get(),
                    gw, text(r, "device"), dst.get().equals("0.0.0.0/0") ? "default" : "static", Optional.empty()));
        }
        return out;
    }

    /** os_ver (major), mr (minor), patch -> "v7.2.8"; build appended when present. */
    static String osVersion(JsonNode d) {
        String v = "v" + d.path("os_ver").asText("?") + "." + d.path("mr").asText("?") + "." + d.path("patch").asText("?");
        return d.hasNonNull("build") ? v + " build" + d.path("build").asText() : v;
    }

    public HttpsVendorExecutor.ConfirmOutcome confirm(Target target, String credentialRef) {
        try {
            Login l = login(target, credentialRef);
            if (l instanceof Login.Refused r) {
                return r.auth() ? new HttpsVendorExecutor.ConfirmOutcome.AuthenticationFailed(r.reason())
                        : new HttpsVendorExecutor.ConfirmOutcome.Failed(r.reason());
            }
            Session s = ((Login.Ok) l).session();
            try {
                HttpsVendorExecutor.Identity id = identity(call(target, s, "get", Map.of("url", "/sys/status")));
                if (id.version().isEmpty()) {
                    return new HttpsVendorExecutor.ConfirmOutcome.Failed("not a FortiManager: /sys/status names no version");
                }
                return new HttpsVendorExecutor.ConfirmOutcome.Confirmed(id);
            } finally {
                logout(target, s);
            }
        } catch (IOException e) {
            return new HttpsVendorExecutor.ConfirmOutcome.Failed("https: " + e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpsVendorExecutor.ConfirmOutcome.Failed("interrupted");
        }
    }

    public HttpsVendorExecutor.InventoryOutcome inventory(Target target, String credentialRef) {
        try {
            Login l = login(target, credentialRef);
            if (l instanceof Login.Refused r) {
                return r.auth() ? new HttpsVendorExecutor.InventoryOutcome.AuthenticationFailed(r.reason())
                        : new HttpsVendorExecutor.InventoryOutcome.Failed(r.reason());
            }
            Session s = ((Login.Ok) l).session();
            try {
                HttpsVendorExecutor.Identity id = identity(call(target, s, "get", Map.of("url", "/sys/status")));
                List<GridMember> managed = devices(call(target, s, "get", Map.of("url", "/dvmdb/device", "loadsub", 0,
                        "fields", List.of("name", "ip", "sn", "platform_str", "os_ver", "mr", "patch", "build", "conn_status", "ha_mode"))));
                // The FortiManager's own interfaces and static routes (PO 2026-09-25: "hani IP hani route").
                List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface> interfaces = List.of();
                List<com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryRoute> routes = List.of();
                try {
                    JsonNode rawInterfaces = call(target, s, "get", Map.of("url", "/cli/global/system/interface"));
                    interfaces = interfaces(rawInterfaces);
                    // MEASURE FIRST (2026-09-25: every state read "unknown"): the status values and the field names only.
                    java.util.Set<String> statuses = new java.util.TreeSet<>();
                    java.util.Set<String> fields = new java.util.TreeSet<>();
                    if (rawInterfaces.isArray()) {
                        for (JsonNode i : rawInterfaces) {
                            statuses.add(i.path("status").toString());
                            i.fieldNames().forEachRemaining(fields::add);
                        }
                    }
                    LOG.log(System.Logger.Level.INFO, "[FMG] interface status values={0} fields={1}", statuses, fields);
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.INFO, "[FMG] interfaces not read: {0}", String.valueOf(e.getMessage()));
                }
                try {
                    routes = routes(call(target, s, "get", Map.of("url", "/cli/global/system/route")));
                } catch (IOException e) {
                    LOG.log(System.Logger.Level.INFO, "[FMG] routes not read: {0}", String.valueOf(e.getMessage()));
                }
                Map<String, String> states = interfaceStatesOverSsh(target, credentialRef);
                if (!states.isEmpty()) {
                    interfaces = interfaces.stream().map(i -> states.containsKey(i.name())
                            ? new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryInterface(i.interfaceId(), i.name(), i.parent(), i.kind(),
                                    states.get(i.name()), i.addresses(), i.vlanId())
                            : i).toList();
                }
                LOG.log(System.Logger.Level.INFO, "[FMG] inventory: managed devices={0} interfaces={1} routes={2} states={3}", managed.size(),
                        interfaces.size(), routes.size(), states.size());
                return new HttpsVendorExecutor.InventoryOutcome.Completed(
                        List.of(new com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext(
                                com.securityexpert.nexus.ui2.persistence.device.inventory.InventoryContext.PHYSICAL, interfaces, routes)),
                        managed, Optional.empty(), new HttpsVendorExecutor.Identity(id.name(), id.model(), id.version(), managed));
            } finally {
                logout(target, s);
            }
        } catch (IOException e) {
            return new HttpsVendorExecutor.InventoryOutcome.Failed("https: " + String.valueOf(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new HttpsVendorExecutor.InventoryOutcome.Failed("interrupted");
        }
    }

    /** One FortiGate as FortiManager lists it, with the ADOM it belongs to (discovery). */
    public record Discovered(String adom, JsonNode device) {
    }

    public sealed interface Discovery {
        record Listed(List<Discovered> devices) implements Discovery {
        }

        record Failed(String reasonClass) implements Discovery {
        }
    }

    private static final java.util.regex.Pattern ADOM_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    /**
     * Discovery (PO 2026-09-25): every ADOM, then each ADOM's devices with their HA members; a FortiManager without
     * ADOMs answers the plain device list, labelled "root". ADOM names from the device are checked before use in a URL.
     */
    public Discovery discover(Target target, String credentialRef) {
        try {
            Login l = login(target, credentialRef);
            if (l instanceof Login.Refused r) {
                return new Discovery.Failed(r.auth() ? "AUTH_FAILED" : "REFUSED");
            }
            Session s = ((Login.Ok) l).session();
            try {
                List<Discovered> out = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                List<String> adoms = new ArrayList<>();
                try {
                    for (JsonNode a : call(target, s, "get", Map.of("url", "/dvmdb/adom", "fields", List.of("name")))) {
                        text(a, "name").filter(n -> ADOM_NAME.matcher(n).matches()).ifPresent(adoms::add);
                    }
                } catch (IOException noAdoms) {
                    LOG.log(System.Logger.Level.INFO, "[FMG] discovery: no ADOM list ({0})", String.valueOf(noAdoms.getMessage()));
                }
                if (adoms.isEmpty()) {
                    for (JsonNode d : call(target, s, "get", Map.of("url", "/dvmdb/device", "loadsub", 1))) {
                        out.add(new Discovered("root", d));
                    }
                } else {
                    for (String adom : adoms) {
                        JsonNode list;
                        try {
                            list = call(target, s, "get", Map.of("url", "/dvmdb/adom/" + adom + "/device", "loadsub", 1));
                        } catch (IOException e) {
                            continue; // an ADOM for other products (FortiAnalyzer, FortiMail) may refuse the query
                        }
                        for (JsonNode d : list) {
                            String sn = d.path("sn").asText("");
                            if (!sn.isEmpty() && seen.add(sn)) {
                                out.add(new Discovered(adom, d));
                            }
                        }
                    }
                }
                LOG.log(System.Logger.Level.INFO, "[FMG] discovery: adoms={0} devices={1}", adoms.size(), out.size());
                return new Discovery.Listed(out);
            } finally {
                logout(target, s);
            }
        } catch (IOException e) {
            return new Discovery.Failed("UNREACHABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Discovery.Failed("INTERRUPTED");
        }
    }

    private static Optional<String> text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asText().isBlank() ? Optional.of(n.get(f).asText()) : Optional.of("").filter(x -> false);
    }
}
