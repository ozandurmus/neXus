package com.securityexpert.nexus.ui2.worker.scriptrunner;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The broker's calls to the service's internal port ({@code SCRIPT_EXECUTION_CONTRACT.md} Amendment 2026-11-18 (b)
 * item 4): {@code start} (body, declared credentials, per-run session), {@code finish} (masked output and outcome) and
 * {@code abort} (revoke the session after a lost lease). Authenticated by the broker's own token.
 */
public interface ScriptRunnerServiceClient {

    /** What the service releases for one run. {@link #toString()} never prints the body or a secret. */
    record RunBundle(
            String jobId,
            String scriptId,
            int version,
            String kind,
            int timeoutSeconds,
            List<String> egress,
            byte[] body,
            List<Credential> credentials,
            String sessionCookie,
            String sessionCsrf,
            List<String> warnings) {
        public RunBundle {
            body = body == null ? new byte[0] : body.clone();
            egress = List.copyOf(egress);
            credentials = List.copyOf(credentials);
            warnings = List.copyOf(warnings);
        }

        @Override
        public byte[] body() {
            return body.clone();
        }

        @Override
        public String toString() {
            return "RunBundle[jobId=" + jobId + ", scriptId=" + scriptId + ", version=" + version + ", body=[REDACTED]]";
        }
    }

    record Credential(String refId, String alias, String username, String secret) {
        @Override
        public String toString() {
            return "Credential[refId=" + refId + ", alias=" + alias + ", secret=[REDACTED]]";
        }
    }

    /** The run's end as the broker reports it; stdout/stderr already masked and truncated. */
    record Finish(
            String outcome,
            Integer exitCode,
            long durationMs,
            String stdout,
            String stderr,
            boolean stdoutCut,
            boolean stderrCut,
            String failureReason) {
    }

    /** A refusal from the service: status and code word only. */
    final class Refused extends IOException {
        private final int status;
        private final String code;

        public Refused(int status, String code) {
            super("service refused: HTTP " + status + " " + code);
            this.status = status;
            this.code = code;
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }
    }

    RunBundle start(String jobId, long leaseEpoch) throws IOException;

    void finish(String jobId, long leaseEpoch, Finish finish) throws IOException;

    void abort(String jobId, long leaseEpoch) throws IOException;

    /** Over HTTP to {@code ui2-service-internal:8086}. */
    static ScriptRunnerServiceClient http(String baseUrl, String token) {
        Objects.requireNonNull(token, "token");
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ObjectMapper json = new ObjectMapper();
        return new ScriptRunnerServiceClient() {
            private HttpResponse<byte[]> post(String path, Map<String, Object> body) throws IOException {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .timeout(Duration.ofSeconds(120))
                        .header("Content-Type", "application/json")
                        .header("X-Nexus-Script-Runner-Token", token)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                        .build();
                try {
                    HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    if (response.statusCode() / 100 != 2) {
                        String code = "UNKNOWN";
                        try {
                            code = json.readTree(response.body()).path("error").asText("UNKNOWN");
                        } catch (IOException ignored) {
                            // non-JSON refusal body
                        }
                        throw new Refused(response.statusCode(), code);
                    }
                    return response;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
            }

            @Override
            public RunBundle start(String jobId, long leaseEpoch) throws IOException {
                JsonNode node = json.readTree(post("/internal/script-runs/start",
                        Map.of("job_id", jobId, "lease_epoch", leaseEpoch)).body());
                List<Credential> credentials = new ArrayList<>();
                for (JsonNode c : node.path("credentials")) {
                    credentials.add(new Credential(c.path("ref_id").asText(), c.path("alias").asText(),
                            c.path("username").asText(""), c.path("secret").asText("")));
                }
                List<String> egress = new ArrayList<>();
                node.path("egress").forEach(e -> egress.add(e.asText()));
                List<String> warnings = new ArrayList<>();
                node.path("warnings").forEach(w -> warnings.add(w.asText()));
                return new RunBundle(node.path("job_id").asText(), node.path("script_id").asText(),
                        node.path("version").asInt(), node.path("kind").asText(), node.path("timeout_s").asInt(),
                        egress, Base64.getDecoder().decode(node.path("body_base64").asText("")), credentials,
                        node.path("session_cookie").asText(), node.path("session_csrf").asText(), warnings);
            }

            @Override
            public void finish(String jobId, long leaseEpoch, Finish finish) throws IOException {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("job_id", jobId);
                body.put("lease_epoch", leaseEpoch);
                body.put("outcome", finish.outcome());
                body.put("exit_code", finish.exitCode());
                body.put("duration_ms", finish.durationMs());
                body.put("stdout", finish.stdout());
                body.put("stderr", finish.stderr());
                body.put("stdout_cut", finish.stdoutCut());
                body.put("stderr_cut", finish.stderrCut());
                body.put("failure_reason", finish.failureReason());
                post("/internal/script-runs/finish", body);
            }

            @Override
            public void abort(String jobId, long leaseEpoch) throws IOException {
                post("/internal/script-runs/abort", Map.of("job_id", jobId, "lease_epoch", leaseEpoch));
            }
        };
    }
}
