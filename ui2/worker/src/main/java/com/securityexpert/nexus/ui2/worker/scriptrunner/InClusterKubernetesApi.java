package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@link KubernetesApi} over the in-cluster API server with the pod's projected service-account token (re-read on every
 * call, so a rotated token is picked up) and the cluster CA from the same projected volume. TLS verification stays on.
 * No client library: five REST calls do not justify a new dependency.
 */
public final class InClusterKubernetesApi implements KubernetesApi {

    static final Path SA_DIR = Path.of("/var/run/secrets/kubernetes.io/serviceaccount");

    private final String baseUrl;
    private final String namespace;
    private final Path tokenFile;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public InClusterKubernetesApi(String baseUrl, String namespace, Path tokenFile, SSLContext tls) {
        this.baseUrl = baseUrl;
        this.namespace = namespace;
        this.tokenFile = tokenFile;
        this.http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).build();
    }

    /** From {@code KUBERNETES_SERVICE_HOST} / {@code KUBERNETES_SERVICE_PORT} and the projected service-account files. */
    public static InClusterKubernetesApi fromEnvironment(Map<String, String> env) throws IOException {
        String host = env.get("KUBERNETES_SERVICE_HOST");
        String port = env.getOrDefault("KUBERNETES_SERVICE_PORT", "443");
        if (host == null || host.isBlank()) {
            throw new IllegalStateException("KUBERNETES_SERVICE_HOST is not set; the broker must run in the cluster");
        }
        String authority = host.contains(":") ? "[" + host + "]" : host;
        return new InClusterKubernetesApi("https://" + authority + ":" + port, ScriptRunKubernetes.NAMESPACE,
                SA_DIR.resolve("token"), tlsFor(Files.readAllBytes(SA_DIR.resolve("ca.crt"))));
    }

    static SSLContext tlsFor(byte[] caPem) throws IOException {
        try {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            store.load(null, null);
            int i = 0;
            for (Certificate cert : CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(caPem))) {
                store.setCertificateEntry("cluster-ca-" + i++, cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(store);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, tmf.getTrustManagers(), null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IOException("cluster CA unusable", e);
        }
    }

    private String collection(Kind kind) {
        return baseUrl + "/" + kind.apiPath + "/namespaces/" + namespace + "/" + kind.plural;
    }

    private HttpRequest.Builder request(String url) throws IOException {
        String token = Files.readString(tokenFile, StandardCharsets.UTF_8).strip();
        return HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static IOException failure(String what, HttpResponse<?> response) {
        // The response body may echo object content; only the status is reported.
        return new IOException("kubernetes " + what + " failed: HTTP " + response.statusCode());
    }

    static final int CONFLICT_MAX_ATTEMPTS = 15;

    @Override
    @SuppressWarnings("unchecked")
    public void create(Kind kind, Map<String, Object> object) throws IOException {
        byte[] payload = json.writeValueAsBytes(object);
        String name = objectName(object);
        HttpResponse<byte[]> response = null;
        for (int attempt = 0; attempt < CONFLICT_MAX_ATTEMPTS; attempt++) {
            response = send(request(collection(kind))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build());
            if (response.statusCode() / 100 == 2) {
                copyCreatedUid(object, response.body());
                return;
            }
            if (response.statusCode() == 409 && name != null && !name.isBlank()) {
                delete(kind, name);
                if (attempt + 1 < CONFLICT_MAX_ATTEMPTS) {
                    pauseBeforeRetry(attempt);
                }
                continue;
            }
            throw failure("create " + kind.plural, response);
        }
        throw failure("create " + kind.plural, response);
    }

    @SuppressWarnings("unchecked")
    private static String objectName(Map<String, Object> object) {
        if (object == null || !(object.get("metadata") instanceof Map<?, ?> meta)) {
            return null;
        }
        Object name = ((Map<String, Object>) meta).get("name");
        return name instanceof String s ? s : null;
    }

    @SuppressWarnings("unchecked")
    private void copyCreatedUid(Map<String, Object> target, byte[] responseBody) {
        if (responseBody == null || responseBody.length == 0
                || !(target.get("metadata") instanceof Map<?, ?> targetMeta)) {
            return;
        }
        try {
            Map<String, Object> created = json.readValue(responseBody, new TypeReference<Map<String, Object>>() { });
            if (created.get("metadata") instanceof Map<?, ?> createdMeta
                    && createdMeta.get("uid") instanceof String uid
                    && !uid.isBlank()) {
                ((Map<String, Object>) targetMeta).put("uid", uid);
            }
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    static long retryDelayMillis(int attempt) {
        // 50, 100, 200, 400, then 500 ms across 14 waits = 5,750 ms total (>5s Job/pod termination window).
        return Math.min(500L, 50L << Math.min(attempt, 4));
    }

    private static void pauseBeforeRetry(int attempt) throws IOException {
        try {
            Thread.sleep(retryDelayMillis(attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    @Override
    public Optional<Map<String, Object>> get(Kind kind, String name) throws IOException {
        HttpResponse<byte[]> response = send(request(collection(kind) + "/" + name).GET().build());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() / 100 != 2) {
            throw failure("get " + kind.plural, response);
        }
        return Optional.of(json.readValue(response.body(), new TypeReference<Map<String, Object>>() { }));
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> list(Kind kind, String labelSelector) throws IOException {
        HttpResponse<byte[]> response = send(request(collection(kind) + "?labelSelector="
                + URLEncoder.encode(labelSelector, StandardCharsets.UTF_8)).GET().build());
        if (response.statusCode() / 100 != 2) {
            throw failure("list " + kind.plural, response);
        }
        Map<String, Object> body = json.readValue(response.body(), new TypeReference<Map<String, Object>>() { });
        Object items = body.get("items");
        return items instanceof List<?> l ? new ArrayList<>((List<Map<String, Object>>) l) : List.of();
    }

    @Override
    public void delete(Kind kind, String name) throws IOException {
        String body = "{\"kind\":\"DeleteOptions\",\"apiVersion\":\"v1\",\"propagationPolicy\":\"Background\","
                + "\"gracePeriodSeconds\":0}";
        HttpResponse<byte[]> response = send(request(collection(kind) + "/" + name)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build());
        if (response.statusCode() != 404 && response.statusCode() / 100 != 2) {
            throw failure("delete " + kind.plural, response);
        }
    }

    @Override
    public LogRead podLog(String podName, String container, long limitBytes) throws IOException {
        String url = collection(Kind.POD) + "/" + podName + "/log?container="
                + URLEncoder.encode(container, StandardCharsets.UTF_8) + "&limitBytes=" + limitBytes;
        HttpRequest request = request(url).header("Accept", "text/plain").GET().build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("kubernetes pod log failed: HTTP " + response.statusCode());
            }
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, limitBytes));
            return new LogRead(bytes, bytes.length >= limitBytes);
        }
    }
}
