package com.securityexpert.nexus.ui2.service.system;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.jooq.Record;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securityexpert.nexus.ui2.persistence.TransactionBoundary;

/**
 * Administration › System (Product Owner, 2026-09-22: "a service view -- which pods, their states, what they
 * consume, like top").
 * <ul>
 * <li>Pods: the Kubernetes API of the pod's own namespace, read with the pod's service account (a namespaced,
 * read-only Role: pods list, metrics list) -- phase, readiness, restarts, age, image digest, CPU and memory
 * use and limits. No write verb exists on that Role.</li>
 * <li>Storage: the artefact store (bytes from the manifests, and the volume's own free space), the
 * configuration evidence, and the database size.</li>
 * </ul>
 * When the service account or the metrics API is unavailable the answer says so; nothing is estimated.
 */
@Service
public class SystemStatusService {

    private static final Path SA_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TransactionBoundary transactionBoundary;
    private final com.securityexpert.nexus.ui2.persistence.device.DeviceRepository deviceRepository;

    public SystemStatusService(TransactionBoundary transactionBoundary,
            com.securityexpert.nexus.ui2.persistence.device.DeviceRepository deviceRepository) {
        this.transactionBoundary = Objects.requireNonNull(transactionBoundary, "transactionBoundary");
        this.deviceRepository = Objects.requireNonNull(deviceRepository, "deviceRepository");
    }

    public Map<String, Object> pods() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("read_at", Instant.now().toString());
        if (!Files.exists(SA_DIR.resolve("token"))) {
            body.put("available", false);
            body.put("reason", "the service runs without a Kubernetes service account token; pod status cannot be read");
            body.put("pods", List.of());
            return body;
        }
        try {
            String namespace = Files.readString(SA_DIR.resolve("namespace")).strip();
            HttpClient client = client();
            JsonNode pods = get(client, "/api/v1/namespaces/" + namespace + "/pods");
            Map<String, JsonNode> usage = new HashMap<>();
            String metricsNote = null;
            try {
                JsonNode metrics = get(client, "/apis/metrics.k8s.io/v1beta1/namespaces/" + namespace + "/pods");
                for (JsonNode item : metrics.path("items")) {
                    usage.put(item.path("metadata").path("name").asText(), item);
                }
            } catch (IOException e) {
                metricsNote = "CPU and memory use are unavailable: " + e.getMessage();
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (JsonNode pod : pods.path("items")) {
                rows.add(podRow(pod, usage.get(pod.path("metadata").path("name").asText())));
            }
            rows.sort((a, b) -> String.valueOf(a.get("name")).compareTo(String.valueOf(b.get("name"))));
            body.put("available", true);
            body.put("namespace", namespace);
            body.put("metrics_note", metricsNote);
            body.put("pods", rows);
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            body.put("available", false);
            body.put("reason", "the Kubernetes API could not be read: " + e.getMessage());
            body.put("pods", List.of());
        }
        return body;
    }

    private static Map<String, Object> podRow(JsonNode pod, JsonNode usage) {
        Map<String, Object> row = new LinkedHashMap<>();
        JsonNode meta = pod.path("metadata");
        JsonNode status = pod.path("status");
        String name = meta.path("name").asText();
        row.put("name", name);
        row.put("component", meta.path("labels").path("app.kubernetes.io/component").asText(componentOf(name)));
        row.put("phase", status.path("phase").asText());
        int ready = 0;
        int total = 0;
        int restarts = 0;
        String waiting = null;
        String image = null;
        for (JsonNode cs : status.path("containerStatuses")) {
            total++;
            if (cs.path("ready").asBoolean()) {
                ready++;
            }
            restarts += cs.path("restartCount").asInt();
            if (cs.path("state").has("waiting")) {
                waiting = cs.path("state").path("waiting").path("reason").asText();
            }
            image = cs.path("imageID").asText(cs.path("image").asText());
        }
        row.put("ready", ready + "/" + total);
        row.put("restarts", restarts);
        row.put("state", waiting != null ? waiting : status.path("phase").asText());
        String started = status.path("startTime").asText(null);
        row.put("started_at", started);
        row.put("image_digest", image == null ? null : image.contains("sha256:")
                ? image.substring(image.indexOf("sha256:"), Math.min(image.length(), image.indexOf("sha256:") + 19)) : image);
        long cpuLimit = 0;
        long memLimit = 0;
        for (JsonNode c : pod.path("spec").path("containers")) {
            cpuLimit += milliCpu(c.path("resources").path("limits").path("cpu").asText(""));
            memLimit += bytes(c.path("resources").path("limits").path("memory").asText(""));
        }
        row.put("cpu_limit_millicores", cpuLimit == 0 ? null : cpuLimit);
        row.put("memory_limit_bytes", memLimit == 0 ? null : memLimit);
        if (usage != null) {
            long cpu = 0;
            long mem = 0;
            for (JsonNode c : usage.path("containers")) {
                cpu += milliCpu(c.path("usage").path("cpu").asText(""));
                mem += bytes(c.path("usage").path("memory").asText(""));
            }
            row.put("cpu_millicores", cpu);
            row.put("memory_bytes", mem);
        } else {
            row.put("cpu_millicores", null);
            row.put("memory_bytes", null);
        }
        return row;
    }

    private static String componentOf(String podName) {
        String[] parts = podName.split("-");
        return parts.length > 1 ? parts[1] : podName;
    }

    static long milliCpu(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return 0;
        }
        if (quantity.endsWith("n")) {
            return Long.parseLong(quantity.substring(0, quantity.length() - 1)) / 1_000_000;
        }
        if (quantity.endsWith("u")) {
            return Long.parseLong(quantity.substring(0, quantity.length() - 1)) / 1_000;
        }
        if (quantity.endsWith("m")) {
            return Long.parseLong(quantity.substring(0, quantity.length() - 1));
        }
        return Math.round(Double.parseDouble(quantity) * 1000);
    }

    static long bytes(String quantity) {
        if (quantity == null || quantity.isBlank()) {
            return 0;
        }
        String[][] units = {{"Ki", "1024"}, {"Mi", "1048576"}, {"Gi", "1073741824"}, {"Ti", "1099511627776"},
                {"k", "1000"}, {"M", "1000000"}, {"G", "1000000000"}};
        for (String[] u : units) {
            if (quantity.endsWith(u[0])) {
                return Math.round(Double.parseDouble(quantity.substring(0, quantity.length() - u[0].length())) * Long.parseLong(u[1]));
            }
        }
        return Math.round(Double.parseDouble(quantity));
    }

    private static HttpClient client() throws IOException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            try (var in = Files.newInputStream(SA_DIR.resolve("ca.crt"))) {
                int i = 0;
                for (var cert : cf.generateCertificates(in)) {
                    trust.setCertificateEntry("k8s-ca-" + i++, (X509Certificate) cert);
                }
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trust);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, tmf.getTrustManagers(), null);
            return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(5)).build();
        } catch (java.security.GeneralSecurityException e) {
            throw new IOException("the cluster CA could not be loaded", e);
        }
    }

    private static JsonNode get(HttpClient client, String path) throws IOException, InterruptedException {
        String token = Files.readString(SA_DIR.resolve("token")).strip();
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://kubernetes.default.svc" + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(8)).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " for " + path.replaceAll("/namespaces/[^/]+", "/namespaces/*"));
        }
        return JSON.readTree(response.body());
    }

    public Map<String, Object> storage() {
        // Names as the device list serves them (observed hostname, else the discovery name) -- F21, 2026-09-23.
        Map<String, com.securityexpert.nexus.ui2.persistence.device.DeviceSummaryRecord> summaries = new HashMap<>();
        deviceRepository.listAll().forEach(d -> summaries.put(d.deviceId(), d));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("read_at", Instant.now().toString());
        transactionBoundary.inTransaction(dsl -> {
            List<Map<String, Object>> backups = new ArrayList<>();
            for (Record r : dsl.fetch("select vendor, artefact_class, count(*) as artefacts, count(distinct device_id) as devices, "
                    + "coalesce(sum(ciphertext_bytes), 0) as stored_bytes, coalesce(sum(plaintext_bytes), 0) as original_bytes, "
                    + "min(created_at) as oldest, max(created_at) as newest from backup_artefact "
                    + "group by vendor, artefact_class order by vendor, artefact_class")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("vendor", r.get("vendor", String.class));
                row.put("class", r.get("artefact_class", String.class));
                row.put("artefacts", r.get("artefacts", Long.class));
                row.put("devices", r.get("devices", Long.class));
                row.put("stored_bytes", r.get("stored_bytes", Long.class));
                row.put("original_bytes", r.get("original_bytes", Long.class));
                row.put("oldest", ts(r.get("oldest", Timestamp.class)));
                row.put("newest", ts(r.get("newest", Timestamp.class)));
                backups.add(row);
            }
            body.put("backups", backups);
            List<Map<String, Object>> top = new ArrayList<>();
            for (Record r : dsl.fetch("select b.device_id, d.observed_hostname as hostname, d.cluster_member_ref, count(*) as artefacts, "
                    + "sum(b.ciphertext_bytes) as stored_bytes from backup_artefact b left join devices d on d.device_id = b.device_id "
                    + "group by b.device_id, d.observed_hostname, d.cluster_member_ref order by stored_bytes desc limit 10")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("device_id", r.get("device_id", String.class));
                var summary = summaries.get(r.get("device_id", String.class));
                row.put("hostname", summary == null ? r.get("hostname", String.class) : summary.observedHostname().orElse(null));
                row.put("cluster_member_ref", summary == null ? r.get("cluster_member_ref", String.class) : summary.clusterMemberRef().orElse(null));
                row.put("artefacts", r.get("artefacts", Long.class));
                row.put("stored_bytes", r.get("stored_bytes", Long.class));
                top.add(row);
            }
            body.put("top_devices", top);
            Record c = dsl.fetchOne("select count(*) as artefacts, coalesce(sum(ciphertext_bytes), 0) as stored_bytes "
                    + "from configuration_artefact");
            Map<String, Object> configuration = new LinkedHashMap<>();
            configuration.put("artefacts", c.get("artefacts", Long.class));
            configuration.put("stored_bytes", c.get("stored_bytes", Long.class));
            configuration.put("text_in_database_bytes", dsl.fetchOne(
                    "select coalesce(sum(octet_length(sanitized_text)), 0) from device_configuration_run").get(0, Long.class));
            body.put("configuration", configuration);
            body.put("database_bytes", dsl.fetchOne("select pg_database_size(current_database())").get(0, Long.class));
            return null;
        });
        String root = System.getenv().getOrDefault("UI2_ARTEFACT_STORE_ROOT", "");
        Map<String, Object> volume = new LinkedHashMap<>();
        if (!root.isBlank() && Files.isDirectory(Path.of(root))) {
            try {
                FileStore store = Files.getFileStore(Path.of(root));
                volume.put("total_bytes", store.getTotalSpace());
                volume.put("usable_bytes", store.getUsableSpace());
                volume.put("note", "the artefact volume is a host-path volume: its size is the host disk's, not the 5 Gi the claim declares");
            } catch (IOException e) {
                volume.put("note", "the artefact volume could not be measured: " + e.getMessage());
            }
        } else {
            volume.put("note", "this pod has no artefact store mounted");
        }
        body.put("artefact_volume", volume);
        return body;
    }

    private static String ts(Timestamp t) {
        return t == null ? null : t.toInstant().toString();
    }
}
