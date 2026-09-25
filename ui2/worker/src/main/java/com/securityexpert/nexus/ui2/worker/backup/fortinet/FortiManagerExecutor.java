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

    public FortiManagerExecutor(HttpsDeviceCalls client, Function<String, Credentials> credentials) {
        this.client = client;
        this.credentials = credentials;
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
                LOG.log(System.Logger.Level.INFO, "[FMG] inventory: managed devices={0}", managed.size());
                return new HttpsVendorExecutor.InventoryOutcome.Completed(List.of(), managed, Optional.empty(),
                        new HttpsVendorExecutor.Identity(id.name(), id.model(), id.version(), managed));
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

    private static Optional<String> text(JsonNode n, String f) {
        return n != null && n.hasNonNull(f) && !n.get(f).asText().isBlank() ? Optional.of(n.get(f).asText()) : Optional.of("").filter(x -> false);
    }
}
