package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.securityexpert.nexus.ui2.platform.ScriptOutputMasking;

/**
 * The Kubernetes objects of one script run ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b) items 2, 3):
 * the seed Secrets, the per-run NetworkPolicy and the Job. Pure functions over the run's bundle, so every property the
 * contract requires of the execution pod is asserted by unit tests on exactly what the broker sends.
 *
 * <p>The execution pod: uid/gid {@link #RUN_UID}, no service-account token, no service links, no {@code env} from any
 * Secret and no {@code envFrom}, read-only root, no capabilities, RuntimeDefault seccomp, {@code activeDeadlineSeconds =
 * timeout_s}, {@code backoffLimit 0}, one scratch {@code emptyDir} (plus a small one for the JVM's temporary directory
 * when {@code nexus-cli} runs). Only the init container mounts the seed Secrets; it copies them into scratch with mode
 * {@code 0400} ({@code 0500} for the launcher and a {@code bin} body).</p>
 */
public final class ScriptRunPodSpec {

    public static final long RUN_UID = 20185L;
    public static final String SCRIPT_CONTAINER = "script";
    public static final String SEED_CONTAINER = "seed";
    public static final String RUN_LABEL = "nexus.securityexpert.com/script-run";
    public static final String SERVICE_ACCOUNT = "ui2-script-run";
    /** One Secret holds at most 1 MiB of data; body chunks stay well below it. */
    public static final int BODY_CHUNK_BYTES = 512 * 1024;
    public static final String SCRATCH = "/scratch";
    public static final List<String> ALLOWED_ENV = List.of("NEXUS_SCRIPT_ID", "NEXUS_RUN_ID", "NEXUS_CLI",
            "NEXUS_CLI_HOME", "NEXUS_CREDENTIALS_FILE", "HOME", "TMPDIR", "PATH", "LANG", "LC_ALL", "JDK_JAVA_OPTIONS");
    static final String MINIMAL_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    /**
     * The launcher: loads the declared credentials as {@code NEXUS_CRED_<ALIAS>_*}, runs the interpreter, tags every
     * stdout line {@code O|} and every stderr line {@code E|} (so the broker can split one pod log into the two streams;
     * a script cannot forge the other stream's tag because every line of its stdout gets {@code O|} first) and exits with
     * the script's own exit code. Written by the broker, never by the script's author.
     */
    static final String LAUNCHER = """
            #!/bin/bash
            # neXus script-run launcher (SCRIPT_EXECUTION_CONTRACT.md Amendment 2026-11-18 (b)).
            set -u
            if [ -r /scratch/credentials.env ]; then set -a; . /scratch/credentials.env; set +a; fi
            exec 3>&1
            "$@" 2> >(sed -u -e 's/^/E|/' -e '$a\\' >&3) 3>&- < /dev/null | sed -u -e 's/^/O|/' -e '$a\\'
            rc=${PIPESTATUS[0]}
            exec 3>&-
            wait
            exit "$rc"
            """;

    /** Init step: copies the seed into scratch with owner-only modes; the main container never mounts the seed. */
    static String seedScript(String scriptFileName, boolean executable) {
        return "set -eu\n"
                + "umask 077\n"
                + "mkdir -p /scratch/work /scratch/home /scratch/tmp /scratch/.nexus-cli\n"
                + "cat /seed/body.* > /scratch/work/" + scriptFileName + "\n"
                + "chmod " + (executable ? "0500" : "0400") + " /scratch/work/" + scriptFileName + "\n"
                + "for f in credentials.json credentials.env; do cp /seed/$f /scratch/$f; chmod 0400 /scratch/$f; done\n"
                + "cp /seed/session.json /scratch/.nexus-cli/session.json\n"
                + "chmod 0400 /scratch/.nexus-cli/session.json\n"
                + "cp /seed/run.sh /scratch/run.sh\n"
                + "chmod 0500 /scratch/run.sh\n";
    }

    /** One declared egress entry and the non-protected addresses it resolved to at run start. */
    public record ResolvedEgress(String host, int port, List<String> addresses, boolean blockedProtectedAddress) {
        public ResolvedEgress {
            addresses = List.copyOf(addresses);
        }

        public ResolvedEgress(String host, int port, List<String> addresses) {
            this(host, port, addresses, false);
        }
    }

    @FunctionalInterface
    public interface HostResolver {
        List<InetAddress> resolve(String host) throws java.io.IOException;
    }

    private static final Set<String> PROTECTED_HOST_NAMES = Set.of(
            "localhost",
            "kubernetes",
            "kubernetes.default",
            "kubernetes.default.svc",
            "registry",
            "kube-dns",
            "ui2-db",
            "ui2-db-0",
            "ui2-service",
            "ui2-service-internal",
            "ui2-worker",
            "ui2-policy",
            "ui2-backup",
            "ui2-configuration",
            "ui2-compliance",
            "ui2-packet-analysis",
            "ui2-script-runner");

    private static final List<String> PROTECTED_HOST_SUFFIXES = List.of(
            ".cluster.local",
            ".svc",
            ".pod",
            ".localdomain");

    /**
     * Returns {@code true} when {@code host} names an in-cluster service, registry, database, node or loopback target
     * that must never be opened through a script's declared egress.
     */
    public static boolean isProtectedEgressHost(String host) {
        return isProtectedEgressHost(host, System.getenv());
    }

    static boolean isProtectedEgressHost(String host, Map<String, String> env) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String lower = host.strip().toLowerCase(Locale.ROOT);
        if (lower.startsWith("[") && lower.endsWith("]")) {
            lower = lower.substring(1, lower.length() - 1);
        }
        while (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (PROTECTED_HOST_NAMES.contains(lower)) {
            return true;
        }
        for (String suffix : PROTECTED_HOST_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        if (env != null) {
            for (String key : List.of("UI2_DB_HOST", "KUBERNETES_SERVICE_HOST")) {
                String val = env.get(key);
                if (val != null && !val.isBlank() && lower.equalsIgnoreCase(val.strip())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code address} is in a cluster pod/service CIDR, loopback, link-local, multicast,
     * unspecified, node IP, Kubernetes API or database address that must never be allowed in a per-run NetworkPolicy.
     */
    public static boolean isProtectedEgressAddress(InetAddress address) {
        return isProtectedEgressAddress(address, System.getenv(), localInterfaceAddresses());
    }

    static boolean isProtectedEgressAddress(InetAddress address, Map<String, String> env, Set<String> nodeIps) {
        if (address == null) {
            return true;
        }
        InetAddress norm = normalizeInetAddress(address);
        if (norm.isLoopbackAddress() || norm.isLinkLocalAddress() || norm.isAnyLocalAddress()
                || norm.isMulticastAddress()) {
            return true;
        }
        String hostAddr = norm.getHostAddress().toLowerCase(Locale.ROOT);
        int pct = hostAddr.indexOf('%');
        if (pct > 0) {
            hostAddr = hostAddr.substring(0, pct);
        }
        if (nodeIps != null && nodeIps.contains(hostAddr)) {
            return true;
        }
        if (env != null) {
            for (String key : List.of("KUBERNETES_SERVICE_HOST", "UI2_DB_HOST", "UI2_NODE_IPS", "UI2_PROTECTED_EGRESS_IPS")) {
                String raw = env.get(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                for (String token : raw.split(",")) {
                    String candidate = token.strip().toLowerCase(Locale.ROOT);
                    if (!candidate.isEmpty() && candidate.equals(hostAddr)) {
                        return true;
                    }
                }
            }
        }
        byte[] b = norm.getAddress();
        if (b.length == 4) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            // 0.0.0.0/8, 127.0.0.0/8, 169.254.0.0/16, 224.0.0.0/4, 240.0.0.0/4
            if (b0 == 0 || b0 == 127 || (b0 == 169 && b1 == 254) || b0 >= 224) {
                return true;
            }
            // Cluster pod & service CIDRs:
            // 10.42.0.0/16 (k3s pod CIDR), 10.43.0.0/16 (k3s service CIDR: registry, ui2-db, kubernetes.default),
            // 10.96.0.0/12 (Kubernetes default service CIDR), 10.244.0.0/16 (flannel pod CIDR),
            // 100.64.0.0/10 (CNI / CGNAT range).
            if (b0 == 10 && (b1 == 42 || b1 == 43 || (b1 & 0xf0) == 96 || b1 == 244)) {
                return true;
            }
            if (b0 == 100 && (b1 & 0xc0) == 64) {
                return true;
            }
        } else if (b.length == 16) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            int b2 = b[2] & 0xff;
            int b3 = b[3] & 0xff;
            // k3s IPv6 pod/service ULA ranges fd00:42::/32 and fd00:43::/32
            if (b0 == 0xfd && b1 == 0x00 && b2 == 0x00 && (b3 == 0x42 || b3 == 0x43)) {
                return true;
            }
        }
        return false;
    }

    private static InetAddress normalizeInetAddress(InetAddress address) {
        try {
            return InetAddress.getByAddress(address.getAddress());
        } catch (java.io.IOException e) {
            return address;
        }
    }

    private static Set<String> localInterfaceAddresses() {
        Set<String> ips = new LinkedHashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    InetAddress norm = normalizeInetAddress(addr);
                    if (!norm.isLoopbackAddress()) {
                        String s = norm.getHostAddress().toLowerCase(Locale.ROOT);
                        int pct = s.indexOf('%');
                        ips.add(pct > 0 ? s.substring(0, pct) : s);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return ips;
    }

    /** Resolves every declared {@code host:port} now (hostnames are re-resolved for every run), blocking protected IPs. */
    public static List<ResolvedEgress> resolveEgress(List<String> declared, HostResolver resolver) {
        return resolveEgress(declared, resolver, System.getenv(), localInterfaceAddresses());
    }

    static List<ResolvedEgress> resolveEgress(List<String> declared, HostResolver resolver,
            Map<String, String> env, Set<String> nodeIps) {
        Set<String> protectedIps = resolveProtectedIps(resolver, env, nodeIps);
        List<ResolvedEgress> out = new ArrayList<>();
        for (String entry : declared == null ? List.<String>of() : declared) {
            int colon = entry.lastIndexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                continue;
            }
            String host = entry.substring(0, colon);
            if (host.startsWith("[") && host.endsWith("]")) {
                host = host.substring(1, host.length() - 1);
            }
            int port;
            try {
                port = Integer.parseInt(entry.substring(colon + 1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (port < 1 || port > 65535) {
                continue;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if (isProtectedEgressHost(normalizedHost, env)) {
                out.add(new ResolvedEgress(normalizedHost, port, List.of(), true));
                continue;
            }
            Set<String> addresses = new LinkedHashSet<>();
            boolean blocked = false;
            try {
                for (InetAddress a : resolver.resolve(host)) {
                    InetAddress norm = normalizeInetAddress(a);
                    if (isProtectedEgressAddress(norm, env, protectedIps)) {
                        blocked = true;
                        continue;
                    }
                    addresses.add(norm instanceof Inet6Address
                            ? norm.getHostAddress() + "/128"
                            : norm.getHostAddress() + "/32");
                }
            } catch (java.io.IOException | RuntimeException e) {
                // unresolved: allows nothing, reported on the run
            }
            out.add(new ResolvedEgress(normalizedHost, port, new ArrayList<>(addresses), blocked));
        }
        return out;
    }

    private static Set<String> resolveProtectedIps(HostResolver resolver, Map<String, String> env, Set<String> nodeIps) {
        Set<String> protectedIps = new LinkedHashSet<>();
        if (nodeIps != null) {
            for (String ip : nodeIps) {
                if (ip != null && !ip.isBlank()) {
                    protectedIps.add(stripZoneIndex(ip.strip().toLowerCase(Locale.ROOT)));
                }
            }
        }
        if (env != null) {
            for (String key : List.of("KUBERNETES_SERVICE_HOST", "UI2_DB_HOST", "UI2_NODE_IPS", "UI2_PROTECTED_EGRESS_IPS")) {
                String raw = env.get(key);
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                for (String token : raw.split(",")) {
                    String candidate = token.strip();
                    if (candidate.startsWith("[") && candidate.endsWith("]")) {
                        candidate = candidate.substring(1, candidate.length() - 1);
                    }
                    if (candidate.isEmpty()) {
                        continue;
                    }
                    protectedIps.add(stripZoneIndex(candidate.toLowerCase(Locale.ROOT)));
                    if ("UI2_DB_HOST".equals(key) && resolver != null) {
                        try {
                            for (InetAddress resolved : resolver.resolve(candidate)) {
                                InetAddress norm = normalizeInetAddress(resolved);
                                protectedIps.add(stripZoneIndex(norm.getHostAddress().toLowerCase(Locale.ROOT)));
                            }
                        } catch (java.io.IOException | RuntimeException ignored) {
                            // Unresolvable DB host in environment does not abort egress resolution.
                        }
                    }
                }
            }
        }
        return protectedIps;
    }

    private static String stripZoneIndex(String hostAddr) {
        int pct = hostAddr.indexOf('%');
        return pct > 0 ? hostAddr.substring(0, pct) : hostAddr;
    }

    public static String jobName(String jobId) {
        return jobName(jobId, 1L);
    }

    public static String jobName(String jobId, long leaseEpoch) {
        String suffix = "-e" + Math.max(0L, leaseEpoch);
        String base = ScriptRunKubernetes.PREFIX + jobId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        int maxBase = 55 - suffix.length();
        if (base.length() > maxBase) {
            base = base.substring(0, maxBase);
        }
        base = base.replaceAll("-+$", "");
        return ScriptRunKubernetes.requireName(base + suffix);
    }

    public static String secretName(String jobName, int index) {
        return ScriptRunKubernetes.requireName(jobName + "-s" + index);
    }

    private static Map<String, Object> metadata(String name, String jobName) {
        Map<String, Object> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/part-of", "nexus-ui2");
        labels.put("app.kubernetes.io/component", ScriptRunKubernetes.COMPONENT);
        labels.put(RUN_LABEL, jobName);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("name", name);
        meta.put("namespace", ScriptRunKubernetes.NAMESPACE);
        meta.put("labels", labels);
        return meta;
    }

    /** Single-quoted for a bash {@code .} file: {@code '} becomes {@code '\''}. */
    static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    static String credentialsJson(List<ScriptRunnerServiceClient.Credential> credentials) {
        StringBuilder json = new StringBuilder("{\n  \"credentials\": {\n");
        for (int i = 0; i < credentials.size(); i++) {
            ScriptRunnerServiceClient.Credential c = credentials.get(i);
            if (i > 0) {
                json.append(",\n");
            }
            json.append("    \"").append(ScriptOutputMasking.jsonEscape(c.refId())).append("\": {")
                    .append("\"id\":\"").append(ScriptOutputMasking.jsonEscape(c.refId())).append("\",")
                    .append("\"alias\":\"").append(ScriptOutputMasking.jsonEscape(c.alias())).append("\",")
                    .append("\"username\":\"").append(ScriptOutputMasking.jsonEscape(c.username())).append("\",")
                    .append("\"secret\":\"").append(ScriptOutputMasking.jsonEscape(c.secret())).append("\"}");
        }
        return json.append("\n  }\n}\n").toString();
    }

    static String credentialsEnv(List<ScriptRunnerServiceClient.Credential> credentials) {
        StringBuilder env = new StringBuilder();
        for (ScriptRunnerServiceClient.Credential c : credentials) {
            env.append("NEXUS_CRED_").append(c.alias()).append("_USERNAME=").append(shellQuote(c.username())).append('\n');
            env.append("NEXUS_CRED_").append(c.alias()).append("_SECRET=").append(shellQuote(c.secret())).append('\n');
        }
        return env.toString();
    }

    static String sessionJson(String serviceUrl, String cookie, String csrf) {
        return "{\"base_url\":\"" + ScriptOutputMasking.jsonEscape(serviceUrl) + "\",\"cookie\":\""
                + ScriptOutputMasking.jsonEscape(cookie) + "\",\"csrf\":\"" + ScriptOutputMasking.jsonEscape(csrf) + "\"}\n";
    }

    /** The seed Secrets: index 0 carries the launcher, credentials, session and the first body chunk. */
    public static List<Map<String, Object>> secrets(String jobName, ScriptRunnerServiceClient.RunBundle bundle,
            String serviceUrl) {
        byte[] body = bundle.body();
        int chunks = Math.max(1, (body.length + BODY_CHUNK_BYTES - 1) / BODY_CHUNK_BYTES);
        List<Map<String, Object>> out = new ArrayList<>();
        Base64.Encoder b64 = Base64.getEncoder();
        for (int i = 0; i < chunks; i++) {
            Map<String, Object> data = new LinkedHashMap<>();
            if (i == 0) {
                data.put("run.sh", b64.encodeToString(LAUNCHER.getBytes(StandardCharsets.UTF_8)));
                data.put("credentials.json", b64.encodeToString(
                        credentialsJson(bundle.credentials()).getBytes(StandardCharsets.UTF_8)));
                data.put("credentials.env", b64.encodeToString(
                        credentialsEnv(bundle.credentials()).getBytes(StandardCharsets.UTF_8)));
                data.put("session.json", b64.encodeToString(
                        sessionJson(serviceUrl, bundle.sessionCookie(), bundle.sessionCsrf()).getBytes(StandardCharsets.UTF_8)));
            }
            byte[] chunk = Arrays.copyOfRange(body, i * BODY_CHUNK_BYTES, Math.min(body.length, (i + 1) * BODY_CHUNK_BYTES));
            data.put(String.format("body.%03d", i), b64.encodeToString(chunk));
            Map<String, Object> secret = new LinkedHashMap<>();
            secret.put("apiVersion", "v1");
            secret.put("kind", "Secret");
            secret.put("metadata", metadata(secretName(jobName, i), jobName));
            secret.put("type", "Opaque");
            secret.put("immutable", true);
            secret.put("data", data);
            out.add(secret);
        }
        return out;
    }

    /**
     * Attaches a {@code batch/v1 Job} {@code ownerReference} to a per-run {@code Secret} (or {@code NetworkPolicy}) so
     * Kubernetes garbage-collects it automatically whenever the {@code Job} is deleted or TTL-reaped.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withJobOwner(Map<String, Object> object, String jobName, String jobUid) {
        if (jobUid == null || jobUid.isBlank()) {
            return object;
        }
        Map<String, Object> meta = (Map<String, Object>) object.get("metadata");
        if (meta != null) {
            Map<String, Object> ownerRef = new LinkedHashMap<>();
            ownerRef.put("apiVersion", "batch/v1");
            ownerRef.put("kind", "Job");
            ownerRef.put("name", jobName);
            ownerRef.put("uid", jobUid);
            ownerRef.put("controller", true);
            ownerRef.put("blockOwnerDeletion", false);
            meta.put("ownerReferences", List.of(ownerRef));
        }
        return object;
    }

    /**
     * The run's NetworkPolicy: egress to {@code ui2-service:8080} (for {@code nexus-cli}), and, when declared egress
     * resolves to at least one allowed external target, to cluster DNS ({@code kube-dns} UDP/TCP 53) and each declared
     * {@code host:port} as the addresses it resolved to; when {@code egress} has no allowed targets, no DNS rule is
     * emitted (the broker injects {@code ui2-service} into {@code podSpec.hostAliases}); no ingress rule (the static
     * base policy denies all).
     */
    public static Map<String, Object> networkPolicy(String jobName, List<ResolvedEgress> egress) {
        List<Object> rules = new ArrayList<>();
        boolean hasAllowedEgress = egress != null && egress.stream().anyMatch(e -> !e.addresses().isEmpty());
        if (hasAllowedEgress) {
            rules.add(Map.of(
                    "to", List.of(Map.of(
                            "namespaceSelector", Map.of("matchLabels", Map.of("kubernetes.io/metadata.name", "kube-system")),
                            "podSelector", Map.of("matchLabels", Map.of("k8s-app", "kube-dns")))),
                    "ports", List.of(Map.of("protocol", "UDP", "port", 53), Map.of("protocol", "TCP", "port", 53))));
        }
        rules.add(Map.of(
                "to", List.of(Map.of("podSelector", Map.of("matchLabels", Map.of(
                        "app.kubernetes.io/part-of", "nexus-ui2", "app.kubernetes.io/component", "service")))),
                "ports", List.of(Map.of("protocol", "TCP", "port", 8080))));
        for (ResolvedEgress e : egress == null ? List.<ResolvedEgress>of() : egress) {
            if (e.addresses().isEmpty()) {
                continue;
            }
            List<Object> to = new ArrayList<>();
            for (String cidr : e.addresses()) {
                to.add(Map.of("ipBlock", Map.of("cidr", cidr)));
            }
            rules.add(Map.of("to", to, "ports", List.of(Map.of("protocol", "TCP", "port", e.port()))));
        }
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("podSelector", Map.of("matchLabels", Map.of(RUN_LABEL, jobName)));
        spec.put("policyTypes", List.of("Egress"));
        spec.put("egress", rules);
        Map<String, Object> np = new LinkedHashMap<>();
        np.put("apiVersion", "networking.k8s.io/v1");
        np.put("kind", "NetworkPolicy");
        np.put("metadata", metadata(jobName, jobName));
        np.put("spec", spec);
        return np;
    }

    private static Map<String, Object> hardenedContainerSecurity() {
        Map<String, Object> sc = new LinkedHashMap<>();
        sc.put("runAsNonRoot", true);
        sc.put("runAsUser", RUN_UID);
        sc.put("runAsGroup", RUN_UID);
        sc.put("allowPrivilegeEscalation", false);
        sc.put("readOnlyRootFilesystem", true);
        sc.put("privileged", false);
        sc.put("capabilities", Map.of("drop", List.of("ALL")));
        sc.put("seccompProfile", Map.of("type", "RuntimeDefault"));
        return sc;
    }

    private static List<Object> env(String scriptId, String jobId, List<String> command) {
        String path = MINIMAL_PATH;
        if (!command.isEmpty()) {
            Path exe = Path.of(command.get(0));
            if (exe.isAbsolute() && exe.getParent() != null && !MINIMAL_PATH.contains(exe.getParent().toString())) {
                path = exe.getParent() + ":" + MINIMAL_PATH;
            }
        }
        List<Object> env = new ArrayList<>();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("NEXUS_SCRIPT_ID", scriptId);
        values.put("NEXUS_RUN_ID", jobId);
        values.put("NEXUS_CLI", "/app/nexus-cli");
        values.put("NEXUS_CLI_HOME", SCRATCH + "/.nexus-cli");
        values.put("NEXUS_CREDENTIALS_FILE", SCRATCH + "/credentials.json");
        values.put("HOME", SCRATCH + "/home");
        values.put("TMPDIR", SCRATCH + "/tmp");
        values.put("PATH", path);
        values.put("LANG", "C.UTF-8");
        values.put("LC_ALL", "C.UTF-8");
        // CVE-2026-114938: every java launched in the pod (nexus-cli, a jar script) runs without the Base64 intrinsic,
        // as the image entrypoint does for the service (ui2/Containerfile on main), and allows the Origin header on
        // state-changing nexus-cli calls.
        values.put("JDK_JAVA_OPTIONS", "-XX:-UseBASE64Intrinsics -Djdk.httpclient.allowRestrictedHeaders=origin");
        values.forEach((k, v) -> env.add(Map.of("name", k, "value", v)));
        return env;
    }

    /** The Job that runs the script once. */
    public static Map<String, Object> job(String jobName, ScriptRunnerServiceClient.RunBundle bundle, String image,
            int secretCount) {
        return job(jobName, bundle, image, secretCount, Map.of());
    }

    /** The Job that runs the script once, with optional {@code hostAliases} for zero-DNS {@code ui2-service} resolution. */
    public static Map<String, Object> job(String jobName, ScriptRunnerServiceClient.RunBundle bundle, String image,
            int secretCount, Map<String, List<String>> hostAliases) {
        Objects.requireNonNull(image, "image");
        String fileName = ScriptInterpreter.scratchFileName(bundle.kind());
        List<String> interpreter = ScriptInterpreter.commandFor(bundle.kind(), Path.of(SCRATCH, "work", fileName));
        List<String> command = new ArrayList<>(List.of("/bin/bash", SCRATCH + "/run.sh"));
        command.addAll(interpreter);

        Map<String, Object> seed = new LinkedHashMap<>();
        seed.put("name", SEED_CONTAINER);
        seed.put("image", image);
        seed.put("imagePullPolicy", "IfNotPresent");
        seed.put("command", List.of("/bin/bash", "-c",
                seedScript(fileName, ScriptInterpreter.requiresExecutableBit(bundle.kind()))));
        seed.put("securityContext", hardenedContainerSecurity());
        seed.put("resources", Map.of("requests", Map.of("cpu", "10m", "memory", "16Mi"),
                "limits", Map.of("cpu", "200m", "memory", "64Mi")));
        seed.put("volumeMounts", List.of(
                Map.of("name", "seed", "mountPath", "/seed", "readOnly", true),
                Map.of("name", "scratch", "mountPath", SCRATCH)));

        Map<String, Object> script = new LinkedHashMap<>();
        script.put("name", SCRIPT_CONTAINER);
        script.put("image", image);
        script.put("imagePullPolicy", "IfNotPresent");
        script.put("command", command);
        script.put("workingDir", SCRATCH + "/work");
        script.put("env", env(bundle.scriptId(), bundle.jobId(), interpreter));
        script.put("securityContext", hardenedContainerSecurity());
        script.put("resources", Map.of("requests", Map.of("cpu", "100m", "memory", "128Mi"),
                "limits", Map.of("cpu", "1", "memory", "1Gi")));
        script.put("volumeMounts", List.of(
                Map.of("name", "scratch", "mountPath", SCRATCH),
                Map.of("name", "jvm-tmp", "mountPath", "/app/tmp")));

        List<Object> sources = new ArrayList<>();
        for (int i = 0; i < secretCount; i++) {
            sources.add(Map.of("secret", Map.of("name", secretName(jobName, i))));
        }
        Map<String, Object> podSecurity = new LinkedHashMap<>();
        podSecurity.put("runAsNonRoot", true);
        podSecurity.put("runAsUser", RUN_UID);
        podSecurity.put("runAsGroup", RUN_UID);
        podSecurity.put("fsGroup", RUN_UID);
        podSecurity.put("seccompProfile", Map.of("type", "RuntimeDefault"));

        Map<String, Object> podSpec = new LinkedHashMap<>();
        podSpec.put("restartPolicy", "Never");
        podSpec.put("serviceAccountName", SERVICE_ACCOUNT);
        podSpec.put("automountServiceAccountToken", false);
        podSpec.put("enableServiceLinks", false);
        podSpec.put("terminationGracePeriodSeconds", 5);
        podSpec.put("activeDeadlineSeconds", bundle.timeoutSeconds());
        podSpec.put("securityContext", podSecurity);
        if (hostAliases != null && !hostAliases.isEmpty()) {
            List<Object> aliases = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : hostAliases.entrySet()) {
                String hostname = entry.getKey();
                if (hostname == null || hostname.isBlank() || entry.getValue() == null) {
                    continue;
                }
                for (String ip : entry.getValue()) {
                    if (ip != null && !ip.isBlank()) {
                        aliases.add(Map.of("ip", ip, "hostnames", List.of(hostname)));
                    }
                }
            }
            if (!aliases.isEmpty()) {
                podSpec.put("hostAliases", aliases);
            }
        }
        podSpec.put("initContainers", List.of(seed));
        podSpec.put("containers", List.of(script));
        podSpec.put("volumes", List.of(
                Map.of("name", "scratch", "emptyDir", Map.of("sizeLimit", "512Mi")),
                Map.of("name", "jvm-tmp", "emptyDir", Map.of("sizeLimit", "64Mi")),
                Map.of("name", "seed", "projected", Map.of("defaultMode", 0440, "sources", sources))));

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("metadata", Map.of("labels", ((Map<?, ?>) metadata(jobName, jobName).get("labels"))));
        template.put("spec", podSpec);

        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("activeDeadlineSeconds", bundle.timeoutSeconds());
        spec.put("backoffLimit", 0);
        // Safety net only: the broker deletes the Job itself at the end of every run.
        spec.put("ttlSecondsAfterFinished", 900);
        spec.put("template", template);

        Map<String, Object> job = new LinkedHashMap<>();
        job.put("apiVersion", "batch/v1");
        job.put("kind", "Job");
        job.put("metadata", metadata(jobName, jobName));
        job.put("spec", spec);
        return job;
    }
}
